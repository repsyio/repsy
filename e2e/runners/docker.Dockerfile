# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# v0.22.1: current go-containerregistry/crane release (2026-09-04). `crane` is a DAEMONLESS registry
# client -- there is no `dockerd`, no DinD, no host socket anywhere in this runner (see
# clients/docker.ts's file header and this repo's README.md "Docker runner" section for why a host
# socket / Docker-in-Docker are both rejected for this step): `crane push`/`crane pull` speak the
# Registry HTTP API V2 wire protocol directly, the same protocol `clients/docker-raw.ts` probes raw.
# The image is distroless (no shell), so only its one binary is copied out, the same "copy the
# toolchain, not the whole image" approach as cargo.Dockerfile's Rust toolchain.
# A named build stage (not the final image), only used below as a `COPY --from` source -- a normal
# build-time reference, not a Docker Compose sibling-service dependency (see maven.Dockerfile's header
# for why that distinction matters here).
#
# Image pins (RPS-1597): the tag is for humans, the digest (the multi-arch LIST digest, so arm64 keeps
# building) is what is pulled. The digests have no default here on purpose: docker-compose.runners.yml is
# their single source and runners/bump-pins.sh keeps them, so a build without them fails loudly instead
# of pulling whatever the tag points at today (README.md "Runner images and pins").
ARG CRANE_VERSION=v0.22.1
ARG CRANE_IMAGE_DIGEST
ARG GO_VERSION=1.27.1
ARG GO_IMAGE_DIGEST
FROM gcr.io/go-containerregistry/crane:${CRANE_VERSION}@${CRANE_IMAGE_DIGEST} AS crane

# skopeo v1.24.1 (2026-09-16) and regctl v0.11.6 (2026-09-02, regclient) are the second and third
# daemonless clients of this runner (RPS-1478 part B): they speak the same Registry HTTP API V2 wire
# protocol as `crane` but with their own token handling, copy engine and delete scope, so the docker
# catalog runs through each of them (clients/docker-skopeo.ts, clients/docker-regctl.ts).
#
# skopeo publishes no binary and the distro package (bookworm 1.9.3) is years old, so it is built
# statically from the pinned tag in a throwaway Go stage: CGO off, the pure-Go OpenPGP build tag and
# the graph-driver and Docker-daemon backends left out (this runner only ever talks to a registry or
# an OCI layout directory, never to a daemon or to containers-storage). Only the one binary is copied out below, the same
# "copy the tool, not the stage" approach as crane. regctl is one release binary per arch, verified
# against a pinned sha256 (Docker's TARGETARCH picks the file).
#
# A git tag can be moved, so the tag is only a name (RPS-1597): the clone must end on the pinned commit
# (SKOPEO_COMMIT, `git ls-remote <repo> 'refs/tags/<tag>^{}'`), and the build is hermetic: skopeo vendors
# every module, so -mod=vendor with no proxy and no toolchain download compiles exactly the reviewed
# sources (a vendor/ that disagrees with go.mod fails the build instead of fetching anything).
FROM golang:${GO_VERSION}-bookworm@${GO_IMAGE_DIGEST} AS skopeo-build
ARG SKOPEO_VERSION=v1.24.1
ARG SKOPEO_COMMIT
RUN git clone --depth 1 --branch "${SKOPEO_VERSION}" https://github.com/containers/skopeo.git /src/skopeo
WORKDIR /src/skopeo
RUN test -n "${SKOPEO_COMMIT}" && test "$(git rev-parse HEAD)" = "${SKOPEO_COMMIT}" \
 && GOFLAGS=-mod=vendor GOPROXY=off GOTOOLCHAIN=local CGO_ENABLED=0 go build -trimpath -o /out/skopeo \
      -tags "containers_image_openpgp exclude_graphdriver_btrfs exclude_graphdriver_devicemapper containers_image_docker_daemon_stub" \
      ./cmd/skopeo \
 && /out/skopeo --version | tee /out/version \
 && grep -F "${SKOPEO_VERSION#v}" /out/version

FROM debian:bookworm-slim AS regctl-download
ARG TARGETARCH
ARG REGCTL_VERSION=v0.11.6
ARG REGCTL_SHA256_AMD64=8e0e62a497fcdb8048d18aa927a139613176ba0531f412bc541044e28f9856bd
ARG REGCTL_SHA256_ARM64=a9b71a3ee79b2d1dbbd7d51fd5e8fa214722c192864235d3d8764463c751a1ff
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH:-amd64}" in \
      amd64) sha="${REGCTL_SHA256_AMD64}" ;; \
      arm64) sha="${REGCTL_SHA256_ARM64}" ;; \
      *) echo "regctl: no pinned checksum for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && curl -fsSL -o /regctl "https://github.com/regclient/regclient/releases/download/${REGCTL_VERSION}/regctl-linux-${TARGETARCH:-amd64}" \
 && echo "${sha}  /regctl" | sha256sum -c - \
 && chmod a+rx /regctl

# oras v1.3.4 (2026-08-27, oras-project) is the fourth daemonless client (RPS-1478 part C): the OCI
# artifact client (`oras push`/`pull`/`attach`/`discover`, the referrers API and its tag-schema
# fallback). One release tarball per arch, verified against a pinned sha256 (Docker's TARGETARCH picks
# the file), like regctl above.
FROM debian:bookworm-slim AS oras-download
ARG TARGETARCH
ARG ORAS_VERSION=v1.3.4
ARG ORAS_SHA256_AMD64=f27adb935022d94df8dc77719c322dda592c78a0d57a6f7dcdd8d900b248c454
ARG ORAS_SHA256_ARM64=15702c6e3a4a56a8bd8ac5c17efdbcab56d9bada661ccbcf017f5b10c1d89399
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH:-amd64}" in \
      amd64) sha="${ORAS_SHA256_AMD64}" ;; \
      arm64) sha="${ORAS_SHA256_ARM64}" ;; \
      *) echo "oras: no pinned checksum for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && curl -fsSL -o /oras.tar.gz "https://github.com/oras-project/oras/releases/download/${ORAS_VERSION}/oras_${ORAS_VERSION#v}_linux_${TARGETARCH:-amd64}.tar.gz" \
 && echo "${sha}  /oras.tar.gz" | sha256sum -c - \
 && tar -xzf /oras.tar.gz -C / oras \
 && chmod a+rx /oras

# The docker runner: the harness itself (see base.Dockerfile) plus the `crane` binary copied in from
# the stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason maven.Dockerfile's/npm.Dockerfile's/
# cargo.Dockerfile's/nuget.Dockerfile's headers give: Docker Compose has no way to guarantee a sibling
# service's image is built first, and duplicating these cheap, well-cached layers here keeps the
# already-proven "skeleton" service untouched.
FROM node:24-bookworm-slim

# The harness's directory in the build context (RPS-1500): `.` when the context is e2e/ (docker-compose.runners.yml),
# a path such as `repsy-os/e2e` when another repository builds from a parent directory. Every COPY of the
# harness's own files below is relative to it. Declared after FROM, so it is in scope for this stage only.
ARG HARNESS_DIR=.

RUN npm install -g --ignore-scripts pnpm@12.5.1

WORKDIR /app
RUN chmod 777 /app

COPY ${HARNESS_DIR}/package.json ${HARNESS_DIR}/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY ${HARNESS_DIR}/tsconfig.json ${HARNESS_DIR}/playwright.config.ts ./
COPY ${HARNESS_DIR}/src ./src
COPY ${HARNESS_DIR}/tests ./tests
COPY ${HARNESS_DIR}/runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

# --- docker-specific layers ---

COPY --from=crane /ko-app/crane /usr/local/bin/crane

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN chmod a+rx /usr/local/bin/crane

RUN crane version

ARG SKOPEO_VERSION=v1.24.1
ARG REGCTL_VERSION=v0.11.6
COPY --from=skopeo-build /out/skopeo /usr/local/bin/skopeo
COPY --from=regctl-download /regctl /usr/local/bin/regctl
RUN chmod a+rx /usr/local/bin/skopeo /usr/local/bin/regctl

RUN skopeo --version | grep -F "${SKOPEO_VERSION#v}" \
 && regctl version | grep -F "${REGCTL_VERSION}"

ARG ORAS_VERSION=v1.3.4
COPY --from=oras-download /oras /usr/local/bin/oras
RUN chmod a+rx /usr/local/bin/oras \
 && oras version | grep -F "${ORAS_VERSION#v}"

CMD ["./entrypoint.sh", "docker"]
