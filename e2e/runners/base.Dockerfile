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

# The base every protocol runner (added from step 2 onward) builds on: Node + the harness itself,
# no package-manager tooling for any protocol. Tests only ever run inside a runner container (see
# README.md); this one is also used directly for the "skeleton" service, which needs no protocol
# client. Build context is the e2e/ directory (see docker-compose.runners.yml).
FROM node:24-bookworm-slim

# Pinned the same literal way repsy-frontend/.github/actions/setup-frontend pins pnpm for CI: a
# literal version (not package.json's absent "packageManager" field) and --ignore-scripts. Keep
# this version equal to the one used to generate pnpm-lock.yaml, or --frozen-lockfile below fails.
RUN npm install -g --ignore-scripts pnpm@12.5.1

WORKDIR /app

# /app itself (not just what is COPY'd into it, which stays root-owned but world-readable) needs to
# be writable by whatever uid the container runs as (docker-compose.runners.yml's "user:", the host
# user): Playwright's outputDir handling removes and recreates test-results/playwright-report
# outright, which needs write access to their parent, not just to themselves.
RUN chmod 777 /app

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY tsconfig.json playwright.config.ts ./
COPY src ./src
COPY tests ./tests
COPY runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

# entrypoint.sh regenerates src/api/generated (git-ignored) from the openapi spec on every start,
# not at build time: docker-compose.runners.yml bind-mounts both the spec and ./src over this COPY
# in local runs, so editing either needs no rebuild. See that file for the mount paths.
CMD ["./entrypoint.sh", "skeleton"]
