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

COPY config/ ./config/
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

COPY config/ ./config/
COPY core/core-parent/pom.xml ./core/core-parent/pom.xml
COPY repsy/repsy-os/pom.xml ./repsy/repsy-os/pom.xml
COPY repsy/repsy-os/libs/pom.xml ./repsy/repsy-os/libs/pom.xml
COPY repsy/repsy-os/libs/protocol-router/ ./repsy/repsy-os/libs/protocol-router/
COPY repsy/repsy-os/libs/multiport/ ./repsy/repsy-os/libs/multiport/
COPY repsy/repsy-os/libs/storage/ ./repsy/repsy-os/libs/storage/

RUN mvn -f ./repsy/repsy-os/pom.xml install -N -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B && \
    mvn -f ./repsy/repsy-os/libs/pom.xml install -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 3: Build repsy-protocols
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS protocols-build

ARG MAVEN_OPTS_VALUE
ENV MAVEN_OPTS="${MAVEN_OPTS_VALUE}"

RUN apk add --no-cache maven

WORKDIR /app

COPY --from=libs-build /root/.m2 /root/.m2

COPY config/ ./config/
COPY core/core-parent/pom.xml ./core/core-parent/pom.xml
COPY repsy/repsy-os/pom.xml ./repsy/repsy-os/pom.xml
COPY repsy/repsy-os/repsy-protocols/ ./repsy/repsy-os/repsy-protocols/

RUN mvn -f ./repsy/repsy-os/repsy-protocols/pom.xml install -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 4: Build Angular Frontend
# ─────────────────────────────────────────
FROM node:24-alpine AS frontend-build

WORKDIR /app

RUN corepack enable && corepack prepare pnpm@latest --activate

COPY repsy/repsy-os/repsy-frontend/package.json repsy/repsy-os/repsy-frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY repsy/repsy-os/repsy-frontend/ .
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
COPY repsy/repsy-os/pom.xml ./repsy/repsy-os/pom.xml
COPY repsy/repsy-os/repsy-backend/pom.xml ./repsy/repsy-os/repsy-backend/pom.xml

RUN mvn -f ./repsy/repsy-os/repsy-backend/pom.xml dependency:go-offline -Dcheckstyle.skip=true -Dfmt.skip=true -B

COPY repsy/repsy-os/repsy-backend/src ./repsy/repsy-os/repsy-backend/src

COPY --from=frontend-build /app/dist/panel-frontend/browser \
     ./repsy/repsy-os/repsy-backend/src/main/resources/static

RUN mvn -f ./repsy/repsy-os/repsy-backend/pom.xml package -DskipTests -Dcheckstyle.skip=true -Dfmt.skip=true -B

# ─────────────────────────────────────────
# Stage 6: Runtime
# ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/data && \
    chown -R appuser:appgroup /app/data
USER appuser

COPY --from=backend-build \
     /app/repsy/repsy-os/repsy-backend/target/repsy-backend.jar \
     app.jar

COPY --from=frontend-build /app/dist/panel-frontend/browser ./static/

VOLUME /app/data

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
