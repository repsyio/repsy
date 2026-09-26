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

# 10.0.401: current .NET 10 (LTS) SDK per dotnet/dotnet-docker's README.sdk.md. There is NO Debian
# image for the 10.0 SDK at all (noble/resolute/alpine/azurelinux only -- 9.0 was the last SDK with a
# bookworm-slim tag), so the toolchain is copied in from the Ubuntu-noble SDK image instead of
# installing it by hand: that image itself only untars the portable linux-x64 SDK build into
# /usr/share/dotnet, and Debian 12 (bookworm) is a supported .NET 10 OS, so the copy works unmodified.
# A named build stage (not the final image), only used below as a `COPY --from` source for the
# toolchain directory -- a normal build-time reference, not a Docker Compose sibling-service
# dependency (see the comment right before the final `FROM` for why that distinction matters here).
ARG DOTNET_SDK_VERSION=10.0.401
FROM mcr.microsoft.com/dotnet/sdk:${DOTNET_SDK_VERSION}-noble AS dotnet-sdk

# The nuget runner: the harness itself (see base.Dockerfile) plus the toolchain copied in from the
# stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason maven.Dockerfile's/npm.Dockerfile's/
# cargo.Dockerfile's headers give: Docker Compose has no way to guarantee a sibling service's image is
# built first, and duplicating these cheap, well-cached layers here keeps the already-proven
# "skeleton" service untouched.
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

# --- nuget-specific layers ---

# .NET's Linux runtime dependencies (the official runtime-deps image's own bookworm package list),
# plus zlib1g for nupkg (de)compression. ICU is replaced by invariant globalization
# (DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1, clients/nuget.ts's nugetEnv) instead of pinning an ICU
# package version to this image's glibc, the same approach the official Alpine .NET images take; this
# harness's rendered packages/projects carry no culture-sensitive data, so invariant mode costs
# nothing here. libicu72 is the bookworm fallback if that is ever judged undesirable.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libgcc-s1 libssl3 libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/*

ENV DOTNET_ROOT=/usr/share/dotnet
ENV PATH="/usr/share/dotnet:${PATH}"
ENV DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1 \
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    DOTNET_GENERATE_ASPNET_CERTIFICATE=false \
    NUGET_XMLDOC_MODE=skip \
    DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE=1

COPY --from=dotnet-sdk /usr/share/dotnet /usr/share/dotnet

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN chmod -R a+rX /usr/share/dotnet

RUN dotnet --version

# .NET keeps its named mutexes (NuGet takes one, "NuGet-Migrations", in every dotnet command) under
# /tmp/.dotnet/shm/session<sid>, and removes each directory again once it is empty, the shm directory
# itself included. Two dotnet processes that start together (the two Playwright workers do, on their
# first tests) race that create/remove and the loser exits 1 with "System.IO.IOException: The system
# cannot open the device or file specified. : 'NuGet-Migrations' ... mkdir(/tmp/.dotnet/shm/...) == -1;
# errno == ENOENT|EEXIST" before it did anything (RPS-1455). A placeholder keeps shm from ever being
# empty, so it is never removed (fresh containers with 6 concurrent pushes each: 0 failing pushes in
# 300, against 25 in 240 without it). World-writable like the ones .NET creates itself, as the
# runner runs as the host's uid, not the root that built the image.
RUN mkdir -p /tmp/.dotnet/shm/keep /tmp/.dotnet/lockfiles && chmod -R 777 /tmp/.dotnet

CMD ["./entrypoint.sh", "nuget"]
