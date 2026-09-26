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

# The ui runner: the harness itself (see base.Dockerfile) plus Playwright's own headless Chromium
# build, for the panel UI suite (tests/ui). Its first layers intentionally repeat base.Dockerfile's
# rather than `FROM` a separately built tag, for the same reason npm.Dockerfile's header gives.
#
# Why not the mcr.microsoft.com/playwright image: its Playwright/browser revision has to equal the
# one in pnpm-lock.yaml or Playwright refuses to launch ("Executable doesn't exist"), so it would
# drift on every Dependabot bump. Installing the browser with the locked Playwright's own CLI pins it
# to the lockfile automatically. A Playwright bump therefore needs `./run.sh test --protocol ui -b`.
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

# --- ui-specific layers ---

# Chromium (plus its headless shell and ffmpeg for videos) and its system libraries/fonts, installed
# as root at build time into a fixed path the non-root runtime uid (docker-compose.runners.yml runs
# as the host's uid:gid) can read. Never run `playwright install` at test time. This layer sits before
# `COPY src ./src` so editing harness code never re-downloads the browser on `-b`. Calls the binary
# directly, not `pnpm exec`, for the uid reason entrypoint.sh gives.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN ./node_modules/.bin/playwright install --with-deps chromium \
  && rm -rf /var/lib/apt/lists/* \
  && chmod -R a+rX /ms-playwright

COPY ${HARNESS_DIR}/tsconfig.json ${HARNESS_DIR}/playwright.config.ts ./
COPY ${HARNESS_DIR}/src ./src
COPY ${HARNESS_DIR}/tests ./tests
COPY ${HARNESS_DIR}/runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

CMD ["./entrypoint.sh", "ui"]
