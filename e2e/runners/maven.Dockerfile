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

# The maven runner: the harness itself (see base.Dockerfile) plus a pinned Temurin JDK and Maven,
# and the two signing toolchains of the signed-deploy specs (tests/maven/gpg-signed-deploy.spec.ts):
# the `gpg` binary that maven-gpg-plugin and Gradle's `signing` plugin drive, a pinned Gradle and a
# pinned sbt (RPS-134), all of them clients of the same Maven repository. Its first layers
# intentionally repeat base.Dockerfile's rather than `FROM` a separately built tag: Docker Compose
# has no way to guarantee that a sibling service's image
# ("the base runner image") is built before this one, short of an extra explicit prebuild step, and
# duplicating ~15 cheap, well-cached layers here keeps the already-proven "skeleton" service in
# docker-compose.runners.yml untouched. Keep this block equal to base.Dockerfile's up to (and
# including) the entrypoint COPY if that file changes.
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

# --- maven-specific layers ---

ARG TEMURIN_VERSION=21
ARG MAVEN_VERSION=3.9.9
# The published checksum of gradle-${GRADLE_VERSION}-bin.zip (https://gradle.org/release-checksums/);
# change both together. The build fails on a mismatch.
ARG GRADLE_VERSION=8.14.3
ARG GRADLE_SHA256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531

# The published checksum of sbt-${SBT_VERSION}.tgz (its GitHub release page lists a .sha256 next to it);
# change both together. The build fails on a mismatch. Scala versions are not installed: the sbt
# projects of clients/sbt.ts name them and sbt downloads them, once, into the primed cache below.
ARG SBT_VERSION=1.13.0
ARG SBT_SHA256=06806805ffd26232727326216766ed4793b549f8c1e6ffeef2e610db7245b698

ENV JAVA_HOME="/opt/java/temurin"
ENV MAVEN_HOME="/opt/maven"
ENV GRADLE_HOME="/opt/gradle"
ENV SBT_HOME="/opt/sbt"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${GRADLE_HOME}/bin:${SBT_HOME}/bin:${PATH}"

# gpg + gpg-agent (Debian bookworm's GnuPG 2.2, pinned by the base image's release) rather than the
# full `gnupg` metapackage: signing needs no dirmngr/gpgsm/keyserver tooling, and the specs turn the
# key server lookup off or register the key by hand. unzip is for the Gradle distribution below.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    gpg \
    gpg-agent \
    unzip \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/java \
    && curl -fsSL "https://api.adoptium.net/v3/binary/latest/${TEMURIN_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" \
    | tar -xzC /opt/java \
    && mv /opt/java/jdk-* "${JAVA_HOME}"

RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xzC /opt \
    && mv "/opt/apache-maven-${MAVEN_VERSION}" "${MAVEN_HOME}"

RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "${GRADLE_HOME}" \
    && rm /tmp/gradle.zip \
    && chmod -R a+rX "${GRADLE_HOME}"

RUN curl -fsSL -o /tmp/sbt.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    && echo "${SBT_SHA256}  /tmp/sbt.tgz" | sha256sum -c - \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz \
    && chmod -R a+rX "${SBT_HOME}"

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run
# as that other user at runtime, same reasoning as /app itself above.
RUN chmod -R a+rX "${JAVA_HOME}" "${MAVEN_HOME}"

# Maven's own shared, third-party-only local-repository cache (see clients/maven.ts's file-level
# comment): a named volume is mounted here at runtime (docker-compose.runners.yml), but the
# directory has to already exist, owned openly, in the image so Docker's first-mount initialisation
# of a fresh named volume copies that (world-writable) ownership instead of defaulting to root.
RUN mkdir -p /app/.maven-shared-m2 && chmod 777 /app/.maven-shared-m2

# The primed Gradle user home of clients/gradle.ts (a named volume at runtime, like the directory
# above, so it has to exist here, world-writable, for the same reason).
RUN mkdir -p /app/.gradle-shared && chmod 777 /app/.gradle-shared

# The primed sbt caches of clients/sbt.ts (the sbt launcher's boot directory and coursier's cache),
# filled at build time by resolving and compiling a throwaway project (runners/sbt-warmup) for every
# Scala version the sbt templates use, so a run downloads no sbt, Scala compiler or compiler bridge.
# They hold third-party files only: coursier keys an entry by the full URL it came from, and every test
# publishes to a repository of its own, so nothing a test publishes is ever found here. World-writable
# for the same reason as the directories above (lock files), and never a named volume: it is part of
# the image, so a run starts from exactly what the build primed.
COPY runners/sbt-warmup /tmp/sbt-warmup
RUN mkdir -p /opt/sbt-cache/boot /opt/sbt-cache/coursier /opt/sbt-cache/ivy /opt/sbt-cache/global \
    && cd /tmp/sbt-warmup \
    && HOME=/opt/sbt-cache/home COURSIER_CACHE=/opt/sbt-cache/coursier sbt -batch -no-colors \
        -Dsbt.boot.directory=/opt/sbt-cache/boot -Dsbt.ivy.home=/opt/sbt-cache/ivy \
        -Dsbt.global.base=/opt/sbt-cache/global -Dsbt.server.autostart=false \
        +update +compile +package +makePom \
    && rm -rf /tmp/sbt-warmup /tmp/.sbt /opt/sbt-cache/home /opt/sbt-cache/global \
    && mkdir -p /opt/sbt-cache/global && chmod -R a+rwX /opt/sbt-cache

RUN java --version && mvn --version && GRADLE_USER_HOME=/tmp/gradle-check gradle --version && rm -rf /tmp/gradle-check && gpg --version | head -1

CMD ["./entrypoint.sh", "maven"]
