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
# pinned sbt (RPS-134), and a pinned Apache Ant with the Apache Ivy jar (RPS-135), all of them clients of
# the same Maven repository. Its first layers
# intentionally repeat base.Dockerfile's rather than `FROM` a separately built tag: Docker Compose
# has no way to guarantee that a sibling service's image
# ("the base runner image") is built before this one, short of an extra explicit prebuild step, and
# duplicating ~15 cheap, well-cached layers here keeps the already-proven "skeleton" service in
# docker-compose.runners.yml untouched. Keep this block equal to base.Dockerfile's up to (and
# including) the entrypoint COPY if that file changes.
FROM node:24-bookworm-slim

# The harness's directory in the build context (RPS-1500): `.` when the context is e2e/ (docker-compose.runners.yml),
# a path such as `repsy-os/e2e` when another repository builds from a parent directory. Every COPY of the
# harness's own files below is relative to it. Declared after FROM, so it is in scope for this stage only.
ARG HARNESS_DIR=.

RUN npm install -g --ignore-scripts pnpm@12.16.1

WORKDIR /app
RUN chmod 777 /app

COPY ${HARNESS_DIR}/package.json ${HARNESS_DIR}/pnpm-lock.yaml ${HARNESS_DIR}/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY ${HARNESS_DIR}/tsconfig.json ${HARNESS_DIR}/playwright.config.ts ./
COPY ${HARNESS_DIR}/src ./src
COPY ${HARNESS_DIR}/tests ./tests
COPY ${HARNESS_DIR}/runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

# --- maven-specific layers ---

# Temurin: the exact release (`21.0.12.1+1`, api.adoptium.net names it jdk-<version>) and the SHA-256 of its
# linux/x64 tarball (the `.sha256.txt` next to it on GitHub = `package.checksum` of api.adoptium.net/v3/assets), not `latest/21/ga`, which moves under a
# green build (RPS-1597). x64 only, as before. Maven: the SHA-512 of the tarball (Maven Central; archive.apache.org is unreachable from the CI runner host, RPS-2530).
# The checksums have no default on purpose: docker-compose.runners.yml is their single source and
# runners/bump-pins.sh keeps them (README.md "Runner images and pins"), so a build without them fails loudly.
ARG TEMURIN_VERSION=21.0.12.1+1
ARG TEMURIN_SHA256
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_SHA512
# The published checksum of gradle-${GRADLE_VERSION}-bin.zip (https://gradle.org/release-checksums/);
# change both together. The build fails on a mismatch.
ARG GRADLE_VERSION=8.14.3
ARG GRADLE_SHA256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531

# The published checksum of sbt-${SBT_VERSION}.tgz (its GitHub release page lists a .sha256 next to it);
# change both together. The build fails on a mismatch. Scala versions are not installed: the sbt
# projects of clients/sbt.ts name them and sbt downloads them, once, into the primed cache below.
ARG SBT_VERSION=1.13.0
ARG SBT_SHA256=06806805ffd26232727326216766ed4793b549f8c1e6ffeef2e610db7245b698

# Ant and Ivy come from Maven Central (archive.apache.org is unreachable from the CI runner host, RPS-2530),
# which has no Ant distribution: ANT_HOME is assembled from ant.jar and ant-launcher.jar (byte-identical to
# the lib/ jars of apache-ant-${ANT_VERSION}-bin.tar.gz) plus a bin/ant wrapper around the launcher, which is
# what the distribution's script ends up running. The SHA-512 of each jar is pinned, change each version and
# its checksums together; the build fails on a mismatch. Ivy is only its jar (ivy-${IVY_VERSION}.jar, the
# same file as in apache-ivy-${IVY_VERSION}-bin.tar.gz), on Ant's classpath (`-lib /opt/ivy/ivy.jar`): the
# plain jar without Apache HttpClient, so Ivy talks HTTP through its own URL handler, the way an Ivy
# dropped into ANT_HOME/lib does.
ARG ANT_VERSION=1.10.15
ARG ANT_SHA512=d5109838090a4463098c4a82c171d07b92d42633010e045388fb8a68744fdcdd2b07a75a9fb56783e83ecbcc80664119ccc44537993125d6724956e114508316
ARG ANT_LAUNCHER_SHA512=6fc2b33cde07493704c201b759e6813ba5f60ffd3925a52bb097a7fffedbc950bcc4fbc93fe0a86ec2a41872b16b92868b6be87632d3e8e0dc89a5b4143ccb9e
ARG IVY_VERSION=2.5.3
ARG IVY_SHA512=be291ddf612703fd3657a72820199430d053a8e06f7631e78477b428ba343631bc36b16bde8c25df544b64b420be84568d3898921f8db63fe676fb590586355b

ENV JAVA_HOME="/opt/java/temurin"
ENV MAVEN_HOME="/opt/maven"
ENV GRADLE_HOME="/opt/gradle"
ENV SBT_HOME="/opt/sbt"
ENV ANT_HOME="/opt/ant"
ENV IVY_JAR="/opt/ivy/ivy.jar"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${GRADLE_HOME}/bin:${SBT_HOME}/bin:${ANT_HOME}/bin:${PATH}"

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
    && curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o /tmp/temurin.tgz "https://api.adoptium.net/v3/binary/version/jdk-${TEMURIN_VERSION}/linux/x64/jdk/hotspot/normal/eclipse" \
    && echo "${TEMURIN_SHA256}  /tmp/temurin.tgz" | sha256sum -c - \
    && tar -xzf /tmp/temurin.tgz -C /opt/java \
    && rm /tmp/temurin.tgz \
    && mv /opt/java/jdk-* "${JAVA_HOME}"

RUN curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o /tmp/maven.tgz "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA512}  /tmp/maven.tgz" | sha512sum -c - \
    && tar -xzf /tmp/maven.tgz -C /opt \
    && rm /tmp/maven.tgz \
    && mv "/opt/apache-maven-${MAVEN_VERSION}" "${MAVEN_HOME}"

RUN curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "${GRADLE_HOME}" \
    && rm /tmp/gradle.zip \
    && chmod -R a+rX "${GRADLE_HOME}"

RUN curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o /tmp/sbt.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    && echo "${SBT_SHA256}  /tmp/sbt.tgz" | sha256sum -c - \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz \
    && chmod -R a+rX "${SBT_HOME}"

RUN mkdir -p "${ANT_HOME}/bin" "${ANT_HOME}/lib" \
    && curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o "${ANT_HOME}/lib/ant.jar" "https://repo.maven.apache.org/maven2/org/apache/ant/ant/${ANT_VERSION}/ant-${ANT_VERSION}.jar" \
    && curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o "${ANT_HOME}/lib/ant-launcher.jar" "https://repo.maven.apache.org/maven2/org/apache/ant/ant-launcher/${ANT_VERSION}/ant-launcher-${ANT_VERSION}.jar" \
    && echo "${ANT_SHA512}  ${ANT_HOME}/lib/ant.jar" | sha512sum -c - \
    && echo "${ANT_LAUNCHER_SHA512}  ${ANT_HOME}/lib/ant-launcher.jar" | sha512sum -c - \
    && chmod -R a+rX "${ANT_HOME}"

COPY --chmod=755 ${HARNESS_DIR}/runners/ant-wrapper.sh ${ANT_HOME}/bin/ant

RUN mkdir -p /opt/ivy \
    && curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 -o "${IVY_JAR}" "https://repo.maven.apache.org/maven2/org/apache/ivy/ivy/${IVY_VERSION}/ivy-${IVY_VERSION}.jar" \
    && echo "${IVY_SHA512}  ${IVY_JAR}" | sha512sum -c - \
    && chmod -R a+rX /opt/ivy

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
COPY ${HARNESS_DIR}/runners/sbt-warmup /tmp/sbt-warmup
RUN mkdir -p /opt/sbt-cache/boot /opt/sbt-cache/coursier /opt/sbt-cache/ivy /opt/sbt-cache/global \
    && cd /tmp/sbt-warmup \
    && HOME=/opt/sbt-cache/home COURSIER_CACHE=/opt/sbt-cache/coursier sbt -batch -no-colors \
        -Dsbt.boot.directory=/opt/sbt-cache/boot -Dsbt.ivy.home=/opt/sbt-cache/ivy \
        -Dsbt.global.base=/opt/sbt-cache/global -Dsbt.server.autostart=false \
        +update +compile +package +makePom \
    && rm -rf /tmp/sbt-warmup /tmp/.sbt /opt/sbt-cache/home /opt/sbt-cache/global \
    && mkdir -p /opt/sbt-cache/global && chmod -R a+rwX /opt/sbt-cache

RUN java --version && mvn --version && GRADLE_USER_HOME=/tmp/gradle-check gradle --version && rm -rf /tmp/gradle-check && gpg --version | head -1 && ant -version && java -cp "${IVY_JAR}" org.apache.ivy.Main -version

CMD ["./entrypoint.sh", "maven"]
