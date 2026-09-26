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

# v4.3.0: current Helm release (2026-09-09, get.helm.sh). Helm ships as a static Go binary
# (helm-vX.Y.Z-linux-<arch>.tar.gz + a detached .sha256sum, verified below) -- exactly like crane
# (docker.Dockerfile), there is NO daemon of any kind here: `helm push`/`pull`/`cm-push`/
# `registry login` are pure HTTP client commands that never contact a Kubernetes API, so none of
# maven.Dockerfile's/docker.Dockerfile's DinD-avoidance reasoning applies beyond "static binary
# copied in, no socket, no privileged mode". `TARGETARCH` is set by BuildKit (the default builder
# for `docker compose build`) to "amd64"/"arm64", which is also get.helm.sh's own arch spelling.
#
# v0.11.1: the chartmuseum/helm-push plugin (the real `helm cm-push` command every "classic"/
# ChartMuseum-protocol test in this step uses, `clients/helm-classic.ts`) -- v0.11.0 added Helm 4
# support, v0.11.1 (2026-02-09) ships `plugin.yaml` in the Helm-3 plugin-manifest format for
# cross-version compatibility. Installed here at BUILD time, once, into a dedicated `HELM_PLUGINS`
# directory that is then copied into the final image and never touched again at test time -- not
# per-invocation the way `clients/helm.ts`/`clients/helm-classic.ts` render a fresh `HOME`/registry
# config per client run: the plugin binary itself carries no per-test state (only the rendered
# `HELM_REGISTRY_CONFIG`/`HELM_REPOSITORY_CONFIG`/credentials do, and those already get a fresh
# `HOME` per invocation, see `clients/helm.ts`'s `helmEnv`), so re-installing it for every test
# would just be slower for no isolation benefit. `helm plugin install` in Helm 4 verifies a
# plugin's signature by default and refuses a source that publishes none (this plugin does not);
# `--verify=false` skips that (confirmed live) -- the install itself still goes through the
# plugin's own `scripts/install_plugin.sh`, which downloads
# https://github.com/chartmuseum/helm-push/releases/download/v0.11.1/helm-push_0.11.1_linux_<arch>.tar.gz
# and is trusted the same way `helm plugin install <git-url>` always is: no checksum file is published
# for this release, so the plugin is the one client of these runners whose bytes are NOT pinned by content
# (RPS-1597 records that exception in README.md "Runner images and pins"; the version is exact).
#
# The `helm` binary itself is pinned by content: the per-arch SHA-256 below lives in this repository (and in
# docker-compose.runners.yml, its single source), not in a `.sha256sum` fetched from the host that serves the
# tarball, which would vouch for whatever that host serves. The checksums have no default on purpose, so a
# build without them fails loudly; runners/bump-pins.sh keeps them.
ARG HELM_VERSION=v4.3.0
ARG HELM_PUSH_VERSION=0.11.1
FROM debian:bookworm-slim AS helm-tools
ARG HELM_VERSION
ARG HELM_PUSH_VERSION
ARG HELM_SHA256_AMD64
ARG HELM_SHA256_ARM64
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl tar gzip git \
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

ENV HELM_PLUGINS=/opt/helm/plugins
# See this file's header for --verify=false and why this runs at build time, once.
RUN helm plugin install https://github.com/chartmuseum/helm-push \
    --version "v${HELM_PUSH_VERSION}" --verify=false \
    && helm version \
    && helm plugin list | grep cm-push

# The helm runner: the harness itself (see base.Dockerfile) plus the `helm` binary and the
# `cm-push` plugin copied in from the stage above, nothing else. Its first layers intentionally
# repeat base.Dockerfile's rather than `FROM` a separately built tag, for the same reason every
# other protocol runner's header gives: Docker Compose has no way to guarantee a sibling service's
# image is built first, and duplicating these cheap, well-cached layers here keeps the
# already-proven "skeleton" service untouched.
FROM node:24-bookworm-slim

RUN npm install -g --ignore-scripts pnpm@12.5.1

WORKDIR /app
RUN chmod 777 /app

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY tsconfig.json playwright.config.ts ./
COPY src ./src
COPY tests ./tests
COPY runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

# --- helm-specific layers ---

ENV HELM_PLUGINS=/opt/helm/plugins
COPY --from=helm-tools /usr/local/bin/helm /usr/local/bin/helm
COPY --from=helm-tools /opt/helm/plugins /opt/helm/plugins

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run
# as that other user at runtime, same reasoning as /app itself above. The plugin directory is a
# whole tree (the plugin's own repo checkout, its compiled binary, scripts), so it needs the
# recursive form crane's single binary (docker.Dockerfile) does not.
RUN chmod a+rx /usr/local/bin/helm && chmod -R a+rX /opt/helm/plugins

RUN helm version && helm plugin list

CMD ["./entrypoint.sh", "helm"]
