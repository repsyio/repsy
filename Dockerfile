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

ARG MAVEN_OPTS_VALUE="--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"

# ─────────────────────────────────────────
# Stage 1: Build Core
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS core-build

ARG MAVEN_OPTS_VALUE
ENV MAVEN_OPTS="${MAVEN_OPTS_VALUE}"

RUN apk add --no-cache maven

WORKDIR /app

COPY core/config/ ./config/
COPY core/ ./core/

WORKDIR /app/core

RUN mvn install -DskipTests -B

# ─────────────────────────────────────────
# Stage 2: Build libs
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS libs-build

ARG MAVEN_OPTS_VALUE
ENV MAVEN_OPTS="${MAVEN_OPTS_VALUE}"

RUN apk add --no-cache maven

WORKDIR /app

COPY --from=core-build /root/.m2 /root/.m2

COPY core/config/ ./config/
COPY core/core-parent/pom.xml ./core/core-parent/pom.xml
COPY pom.xml ./pom.xml
COPY libs/pom.xml ./libs/pom.xml
COPY libs/protocol-router/ ./libs/protocol-router/
COPY libs/multiport/ ./libs/multiport/
COPY libs/storage/ ./libs/storage/

RUN mvn -f ./pom.xml install -N -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B && \
    mvn -f ./libs/pom.xml install -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 3: Build repsy-protocols
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS protocols-build

ARG MAVEN_OPTS_VALUE
ENV MAVEN_OPTS="${MAVEN_OPTS_VALUE}"

RUN apk add --no-cache maven

WORKDIR /app

COPY --from=libs-build /root/.m2 /root/.m2

COPY core/config/ ./config/
COPY core/core-parent/pom.xml ./core/core-parent/pom.xml
COPY pom.xml ./pom.xml
COPY repsy-protocols/ ./repsy-protocols/

RUN mvn -f ./repsy-protocols/pom.xml install -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 4: Build Angular Frontend
# ─────────────────────────────────────────
FROM node:24-alpine AS frontend-build

WORKDIR /app
ENV CI=true

RUN corepack enable && corepack prepare pnpm@latest --activate

COPY repsy-frontend/package.json repsy-frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY repsy-frontend/ .
RUN pnpm run build:prod

# ─────────────────────────────────────────
# Stage 5: Build Spring Boot Backend
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS backend-build

ARG MAVEN_OPTS_VALUE
ENV MAVEN_OPTS="${MAVEN_OPTS_VALUE}"

RUN apk add --no-cache maven

WORKDIR /app

COPY --from=protocols-build /root/.m2 /root/.m2

COPY core/core-parent/pom.xml ./core/core-parent/pom.xml
COPY pom.xml ./pom.xml
COPY repsy-backend/pom.xml ./repsy-backend/pom.xml

RUN mvn -f ./repsy-backend/pom.xml dependency:go-offline -Dcheckstyle.skip=true -Dfmt.skip=true -B

COPY repsy-backend/src ./repsy-backend/src

RUN mvn -f ./repsy-backend/pom.xml package -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 6: Runtime
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/data /app/certs && \
    chown -R appuser:appgroup /app/data /app/certs

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER appuser

COPY --from=backend-build \
     /app/repsy-backend/target/repsy-backend.jar \
     app.jar

COPY --chown=appuser:appgroup --from=frontend-build /app/dist/panel-frontend/browser ./static/

VOLUME /app/data

EXPOSE 8080 8443 9090 9443

ENTRYPOINT ["/app/entrypoint.sh"]
