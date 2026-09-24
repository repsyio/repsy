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

# The stack runner: the harness itself (see base.Dockerfile) plus the static `docker` CLI, copied in
# from the official docker-cli image (a named build stage, only a `COPY --from` source, the same
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
ARG DOCKER_CLI_VERSION=29.8.1
FROM docker:${DOCKER_CLI_VERSION}-cli AS docker-cli

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

# --- stack-specific layers ---

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN chmod a+rx /usr/local/bin/docker && docker --version

CMD ["./entrypoint.sh", "stack"]
