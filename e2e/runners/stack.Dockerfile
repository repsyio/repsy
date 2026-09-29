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

# The stack runner: the harness itself (see base.Dockerfile) plus the static `docker` CLI and its compose
# plugin, copied in from the official docker-cli image (a named build stage, only a `COPY --from` source, the same
# "copy the tool, not the whole image" approach as docker.Dockerfile's crane). Its first layers
# repeat base.Dockerfile's on purpose, for the reason every protocol runner's header gives.
#
# Unlike every other runner, this one talks to the HOST's Docker daemon: docker-compose.runners.yml
# mounts /var/run/docker.sock into it. That is deliberate and confined to this one runner. The cases
# under tests/stack/ exist to check the Repsy IMAGE itself (`docker exec` into the running Repsy
# container: file ownership of the runtime user, the log line an operator copies a password from),
# which no HTTP client can see. Read docker.Dockerfile's "Why no daemon" section in README.md for why
# the protocol runners do not do this: a socket is root on the host, so the runner is only run
# against a local stack the harness owns (the specs skip themselves on a remote target).
# RPS-1719/RPS-1720: the upgrade-path and persistence-across-restart legs also publish a Helm chart and a
# Go module against the previous release and pull them again with the real `helm`/`go` clients, so this
# runner needs both -- the same static `helm` binary as helm.Dockerfile (no `cm-push` plugin: only the OCI
# adapter is used here, never the ChartMuseum one) and the same Go toolchain (copied in, not run as a
# daemon) plus the build-time TLS shim certificate as golang.Dockerfile (a credentialed `go` invocation
# refuses a plain-http GOPROXY, see clients/golang-tls-shim.ts's file header). Keep these pins equal to the
# helm/golang services' in docker-compose.runners.yml (runners/bump-pins.sh fails when they differ). This
# grows the image by roughly the size of the Go toolchain plus the helm binary (~250 MB).
ARG DOCKER_CLI_VERSION=29.8.1
# crane, copied the way docker.Dockerfile does it (a named stage, only a `COPY --from` source).
ARG CRANE_VERSION=v0.22.1
ARG HELM_VERSION=v4.3.0
ARG GO_VERSION=1.27.1
# The digests have no default on purpose (RPS-1597): docker-compose.runners.yml is their single source and
# runners/bump-pins.sh keeps them (README.md "Runner images and pins"), so a build without them fails loudly.
ARG DOCKER_CLI_IMAGE_DIGEST
ARG CRANE_IMAGE_DIGEST
ARG GO_IMAGE_DIGEST
ARG HELM_SHA256_AMD64
ARG HELM_SHA256_ARM64
FROM docker:${DOCKER_CLI_VERSION}-cli@${DOCKER_CLI_IMAGE_DIGEST} AS docker-cli

FROM gcr.io/go-containerregistry/crane:${CRANE_VERSION}@${CRANE_IMAGE_DIGEST} AS crane

FROM golang:${GO_VERSION}-bookworm@${GO_IMAGE_DIGEST} AS go-toolchain

# The static `helm` binary, downloaded and verified exactly like helm.Dockerfile's `helm-tools` stage
# (see there for why there is no daemon and why the checksum is pinned by content); the `cm-push` plugin
# is deliberately left out, this runner never drives the ChartMuseum protocol.
FROM debian:bookworm-slim AS helm-tools
ARG HELM_VERSION
ARG HELM_SHA256_AMD64
ARG HELM_SHA256_ARM64
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl tar gzip \
    && rm -rf /var/lib/apt/lists/*

RUN case "${TARGETARCH:-amd64}" in \
      amd64) sha="${HELM_SHA256_AMD64}" ;; \
      arm64) sha="${HELM_SHA256_ARM64}" ;; \
      *) echo "helm: no pinned checksum for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSLO "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" \
    && echo "${sha}  helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" | sha256sum -c - \
    && tar -xzf "helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" \
    && install -m 0755 "linux-${TARGETARCH}/helm" /usr/local/bin/helm

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

# --- stack-specific layers ---

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
# The compose plugin ships in the same image. tests/stack/persistence.spec.ts recreates the Repsy
# container with it (`docker compose up --force-recreate`, clients/stack.ts `recreateRepsy`).
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
RUN chmod a+rx /usr/local/bin/docker /usr/local/libexec/docker/cli-plugins/docker-compose \
    && docker --version && docker compose version

# Real package clients for the persistence spec (tests/stack/persistence.spec.ts, RPS-1476): a pinned
# Temurin JDK and Maven (the same download as maven.Dockerfile; keep the versions equal, runners.yml
# pins both), crane (docker.Dockerfile's copy) and npm, which comes with node. The stack runner drives
# the same adapters as the protocol runners, so a package is published and consumed by the real tool.
# The same exact JDK and Maven, verified the same way, as maven.Dockerfile (see there); keep the four values equal
# to the maven service's in docker-compose.runners.yml (runners/bump-pins.sh fails when they differ).
ARG TEMURIN_VERSION=21.0.12.1+1
ARG TEMURIN_SHA256
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_SHA512
ENV JAVA_HOME="/opt/java/temurin"
ENV MAVEN_HOME="/opt/maven"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/java \
    && curl -fsSL -o /tmp/temurin.tgz "https://api.adoptium.net/v3/binary/version/jdk-${TEMURIN_VERSION}/linux/x64/jdk/hotspot/normal/eclipse" \
    && echo "${TEMURIN_SHA256}  /tmp/temurin.tgz" | sha256sum -c - \
    && tar -xzf /tmp/temurin.tgz -C /opt/java \
    && rm /tmp/temurin.tgz \
    && mv /opt/java/jdk-* "${JAVA_HOME}"

RUN curl -fsSL -o /tmp/maven.tgz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA512}  /tmp/maven.tgz" | sha512sum -c - \
    && tar -xzf /tmp/maven.tgz -C /opt \
    && rm /tmp/maven.tgz \
    && mv "/opt/apache-maven-${MAVEN_VERSION}" "${MAVEN_HOME}" \
    && chmod -R a+rX "${JAVA_HOME}" "${MAVEN_HOME}"

COPY --from=crane /ko-app/crane /usr/local/bin/crane
RUN chmod a+rx /usr/local/bin/crane

# Maven's shared, third-party-only cache (clients/maven.ts): a named volume is mounted here at runtime,
# owned openly in the image so a fresh volume is writable by the host uid (see maven.Dockerfile).
RUN mkdir -p /app/.maven-shared-m2 && chmod 777 /app/.maven-shared-m2

# helm (RPS-1719): the binary only, copied from the helm-tools stage above (see it for the checksum and
# why no cm-push plugin).
COPY --from=helm-tools /usr/local/bin/helm /usr/local/bin/helm
RUN chmod a+rx /usr/local/bin/helm

# go (RPS-1720): the toolchain copied from the go-toolchain stage above, the same "copy the toolchain,
# not the whole image" approach as golang.Dockerfile's.
COPY --from=go-toolchain /usr/local/go /usr/local/go
ENV PATH="/usr/local/go/bin:${PATH}" GOTOOLCHAIN=local
RUN chmod -R a+rX /usr/local/go

# The TLS shim's certificate/key (RPS-1720), generated ONCE at build time exactly as golang.Dockerfile
# does (see there): a credentialed `go` invocation against this harness's plain-http stack needs an
# `https://` endpoint in front of it, and `clients/golang-tls-shim.ts` refuses to start without these two
# files. World-readable on purpose: a throwaway, test-only key that signs nothing outside this
# container's own loopback interface.
RUN mkdir -p /opt/e2e-tls && cd /opt/e2e-tls \
    && HOME=/tmp GOPATH=/tmp/gopath GOCACHE=/tmp/gocache CGO_ENABLED=0 \
       go run /usr/local/go/src/crypto/tls/generate_cert.go \
         --host localhost,127.0.0.1 --ecdsa-curve P256 --duration 87600h \
    && chmod a+r /opt/e2e-tls/cert.pem /opt/e2e-tls/key.pem \
    && rm -rf /tmp/gopath /tmp/gocache
ENV REPSY_E2E_TLS_CERT=/opt/e2e-tls/cert.pem REPSY_E2E_TLS_KEY=/opt/e2e-tls/key.pem

RUN java --version && mvn --version && crane version && npm --version && helm version \
    && go version && test -s /opt/e2e-tls/cert.pem && test -s /opt/e2e-tls/key.pem

CMD ["./entrypoint.sh", "stack"]
