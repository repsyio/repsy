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
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

RUN apk add --no-cache maven

WORKDIR /app

COPY pom.xml ./
RUN mvn dependency:go-offline -Dcheckstyle.skip=true -Dfmt.skip=true -B

COPY src ./src
RUN mvn package -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 3: Runtime
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.description="repsy-os Trivy vulnerability scanner adapter"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy

WORKDIR /app

COPY --from=builder /app/target/repsy-scanner-trivy.jar app.jar

USER appuser

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:8090/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
