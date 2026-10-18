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

ARG TRIVY_VERSION=0.66.0

# ─────────────────────────────────────────
# Stage 1: Trivy binary source (no build step — pure COPY --from source)
# ─────────────────────────────────────────
FROM aquasec/trivy:${TRIVY_VERSION} AS trivy

# ─────────────────────────────────────────
# Stage 2: Build the Spring Boot app
#
# RPS-2170: the build context is the repository root (docker build -f repsy-scanner-trivy/Dockerfile .),
# because the service shares the wire records of libs/scanner-client. The library and its parents
# (the root and libs POM; repsy-core itself comes from repo.repsy.io, RPS-2265) are installed into the local repository first, then the
# service is built against them. Dockerfile.dockerignore next to this file keeps the rest of the
# repository out of the context.
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

RUN apk add --no-cache maven

WORKDIR /app

# Error Prone (the root POM) needs the compiler --add-exports of the root .mvn/jvm.config.
COPY .mvn/ ./.mvn/
COPY pom.xml ./pom.xml
COPY libs/pom.xml ./libs/pom.xml
COPY libs/scanner-client/ ./libs/scanner-client/

RUN mvn -f pom.xml install -N -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B && \
    mvn -f libs/pom.xml install -N -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B && \
    mvn -f libs/scanner-client/pom.xml install -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dfmt.skip=true -B

COPY repsy-scanner-trivy/pom.xml ./repsy-scanner-trivy/pom.xml
RUN mvn -f repsy-scanner-trivy/pom.xml dependency:go-offline -Dcheckstyle.skip=true -Dfmt.skip=true -B

COPY repsy-scanner-trivy/src ./repsy-scanner-trivy/src
RUN mvn -f repsy-scanner-trivy/pom.xml package -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 3: Runtime
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.description="repsy-os Trivy vulnerability scanner adapter"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy

WORKDIR /app

COPY --from=builder /app/repsy-scanner-trivy/target/repsy-scanner-trivy.jar app.jar

# Trivy's default cache dir is $HOME/.cache/trivy (verified via `trivy --help`), which resolves to
# /home/appuser/.cache/trivy for this user — pre-create it with appuser ownership so a mounted
# named volume (docker-compose.yml's trivy-cache) inherits that ownership on first mount instead of
# defaulting to root, which would otherwise leave it unwritable by this non-root user.
RUN mkdir -p /home/appuser/.cache/trivy && chown -R appuser:appgroup /home/appuser/.cache/trivy

USER appuser

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:8090/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
