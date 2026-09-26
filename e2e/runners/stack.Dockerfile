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
ARG DOCKER_CLI_VERSION=29.8.1
# crane, copied the way docker.Dockerfile does it (a named stage, only a `COPY --from` source).
ARG CRANE_VERSION=v0.22.1
FROM docker:${DOCKER_CLI_VERSION}-cli AS docker-cli

FROM gcr.io/go-containerregistry/crane:${CRANE_VERSION} AS crane

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
# The compose plugin ships in the same image. tests/stack/persistence.spec.ts recreates the Repsy
# container with it (`docker compose up --force-recreate`, clients/stack.ts `recreateRepsy`).
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
RUN chmod a+rx /usr/local/bin/docker /usr/local/libexec/docker/cli-plugins/docker-compose \
    && docker --version && docker compose version

# Real package clients for the persistence spec (tests/stack/persistence.spec.ts, RPS-1476): a pinned
# Temurin JDK and Maven (the same download as maven.Dockerfile; keep the versions equal, runners.yml
# pins both), crane (docker.Dockerfile's copy) and npm, which comes with node. The stack runner drives
# the same adapters as the protocol runners, so a package is published and consumed by the real tool.
ARG TEMURIN_VERSION=21
ARG MAVEN_VERSION=3.9.9
ENV JAVA_HOME="/opt/java/temurin"
ENV MAVEN_HOME="/opt/maven"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/java \
    && curl -fsSL "https://api.adoptium.net/v3/binary/latest/${TEMURIN_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" \
    | tar -xzC /opt/java \
    && mv /opt/java/jdk-* "${JAVA_HOME}"

RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xzC /opt \
    && mv "/opt/apache-maven-${MAVEN_VERSION}" "${MAVEN_HOME}" \
    && chmod -R a+rX "${JAVA_HOME}" "${MAVEN_HOME}"

COPY --from=crane /ko-app/crane /usr/local/bin/crane
RUN chmod a+rx /usr/local/bin/crane

# Maven's shared, third-party-only cache (clients/maven.ts): a named volume is mounted here at runtime,
# owned openly in the image so a fresh volume is writable by the host uid (see maven.Dockerfile).
RUN mkdir -p /app/.maven-shared-m2 && chmod 777 /app/.maven-shared-m2

RUN java --version && mvn --version && crane version && npm --version

CMD ["./entrypoint.sh", "stack"]
