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
ARG CRANE_VERSION=v0.22.1
FROM gcr.io/go-containerregistry/crane:${CRANE_VERSION} AS crane

# The docker runner: the harness itself (see base.Dockerfile) plus the `crane` binary copied in from
# the stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason maven.Dockerfile's/npm.Dockerfile's/
# cargo.Dockerfile's/nuget.Dockerfile's headers give: Docker Compose has no way to guarantee a sibling
# service's image is built first, and duplicating these cheap, well-cached layers here keeps the
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

# --- docker-specific layers ---

COPY --from=crane /ko-app/crane /usr/local/bin/crane

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN chmod a+rx /usr/local/bin/crane

RUN crane version

CMD ["./entrypoint.sh", "docker"]
