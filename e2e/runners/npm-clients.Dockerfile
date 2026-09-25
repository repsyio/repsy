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

# bun, current stable on the 1.3 line (confirmed live as `oven/bun:1.3.14-debian`). Copied in from the
# official image the same "copy the toolchain, not the whole image" approach as cargo.Dockerfile's
# Rust toolchain / golang.Dockerfile's Go toolchain / ruby.Dockerfile's interpreter: bun ships as one
# glibc binary, which runs on bookworm-slim unchanged. The final image must stay
# `node:24-bookworm-slim` (the harness itself needs Node). A named build stage (not the final
# image), only used below as a `COPY --from` source -- a normal build-time reference, not a Docker
# Compose sibling-service dependency (see maven.Dockerfile's/nuget.Dockerfile's headers for why that
# distinction matters here).
ARG BUN_VERSION=1.3.14
FROM oven/bun:${BUN_VERSION}-debian AS bun-toolchain

# The npm-clients runner: the harness itself (see base.Dockerfile) plus the npm-family package
# managers that talk to a Repsy npm repository -- npm (the node:24 base's own, the baseline), pnpm,
# yarn classic (1.x), yarn berry (4.x) and bun. Its first layers intentionally repeat
# base.Dockerfile's rather than `FROM` a separately built tag, for the same reason every other
# protocol runner's header gives: Docker Compose has no way to guarantee a sibling service's image
# is built first, and duplicating these cheap, well-cached layers here keeps the already-proven
# "skeleton" and "npm" services untouched.
FROM node:24-bookworm-slim

RUN npm install -g --ignore-scripts pnpm@12.5.1

WORKDIR /app
RUN chmod 777 /app

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

# --- npm-clients-specific layers ---
#
# Before `COPY src`/`tests` on purpose: those two are bind-mounted over the image's copy at run time
# (docker-compose.runners.yml), so editing a test never invalidates the client layers below.
#
# Every client lives under its own /opt/clients/<name> and the harness always calls it by ABSOLUTE
# path (`src/clients/npm-family/*-client.ts`), never through PATH: the node image carries its own
# `yarn` (1.22.x) and the harness's own global `pnpm` (above, 12.5.1) is a different thing from the
# pnpm under test. No corepack either: it would download at run time and write a `packageManager`
# field into a fixture's package.json, changing the very tarball a test packs. Each install is
# `--ignore-scripts`, root-owned and world-readable, run as the host uid at run time.
ARG PNPM_CLIENT_VERSION
ARG YARN_CLASSIC_VERSION
ARG YARN_BERRY_VERSION
ARG BUN_VERSION

RUN test -n "$PNPM_CLIENT_VERSION" && test -n "$YARN_CLASSIC_VERSION" \
    && test -n "$YARN_BERRY_VERSION" && test -n "$BUN_VERSION"

RUN npm install -g --ignore-scripts --prefix /opt/clients/pnpm "pnpm@${PNPM_CLIENT_VERSION}" \
    && npm install -g --ignore-scripts --prefix /opt/clients/yarn1 "yarn@${YARN_CLASSIC_VERSION}" \
    && npm install -g --ignore-scripts --prefix /opt/clients/yarn4 "@yarnpkg/cli-dist@${YARN_BERRY_VERSION}"

COPY --from=bun-toolchain /usr/local/bin/bun /opt/clients/bun/bin/bun

RUN chmod -R a+rX /opt/clients && chmod a+rx /opt/clients/bun/bin/bun

# What the specs (tests/npm-clients/versions.spec.ts) compare each client's own `--version` with.
ENV NPM_CLIENTS_PNPM_VERSION=${PNPM_CLIENT_VERSION} \
    NPM_CLIENTS_YARN_CLASSIC_VERSION=${YARN_CLASSIC_VERSION} \
    NPM_CLIENTS_YARN_BERRY_VERSION=${YARN_BERRY_VERSION} \
    NPM_CLIENTS_BUN_VERSION=${BUN_VERSION}

# Printed in the build log (and checked to match what was asked for), so a bump is visible in it.
RUN echo "node $(node --version)" && echo "npm $(npm --version)" \
    && echo "pnpm $(/opt/clients/pnpm/bin/pnpm --version)" \
    && echo "yarn classic $(/opt/clients/yarn1/bin/yarn --version)" \
    && echo "yarn berry $(/opt/clients/yarn4/bin/yarn --version)" \
    && echo "bun $(/opt/clients/bun/bin/bun --version)" \
    && test "$(/opt/clients/pnpm/bin/pnpm --version)" = "$PNPM_CLIENT_VERSION" \
    && test "$(/opt/clients/yarn1/bin/yarn --version)" = "$YARN_CLASSIC_VERSION" \
    && test "$(/opt/clients/yarn4/bin/yarn --version)" = "$YARN_BERRY_VERSION" \
    && test "$(/opt/clients/bun/bin/bun --version)" = "$BUN_VERSION"

COPY tsconfig.json playwright.config.ts ./
COPY src ./src
COPY tests ./tests
COPY runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

CMD ["./entrypoint.sh", "npm-clients"]
