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

# 1.98.1: latest stable per the Rust blog (2026-09-03), `-slim-bookworm` so no gcc/build-essential
# lands in the final image -- this harness's crates are dependency-free and every client command runs
# with `--no-verify` (see clients/cargo.ts), so nothing here ever needs to actually compile Rust code,
# only package/publish/fetch. bookworm matches the final stage's own node:24-bookworm-slim base libc.
# A named build stage (not the final image), only used below as a `COPY --from` source for the
# toolchain directories -- a normal build-time reference, not a Docker Compose sibling-service
# dependency (see the comment right before the final `FROM` for why that distinction matters here).
ARG RUST_VERSION=1.98.1
FROM rust:${RUST_VERSION}-slim-bookworm AS rust-toolchain

# The cargo runner: the harness itself (see base.Dockerfile) plus the toolchain copied in from the
# stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason maven.Dockerfile's/npm.Dockerfile's headers give:
# Docker Compose has no way to guarantee a sibling service's image is built first, and duplicating
# these cheap, well-cached layers here keeps the already-proven "skeleton" service untouched.
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

# --- cargo-specific layers ---

# /usr/local/cargo/bin/{cargo,rustc} in the official image are rustup proxies that resolve the real
# toolchain via RUSTUP_HOME at run time -- copying both directories (not just the proxies) is what
# makes that resolution work. CARGO_HOME here only ever hosts these proxies: clients/cargo.ts
# overrides CARGO_HOME per invocation (an isolated registry cache/credentials directory per publish
# or consume), so the image-level one is never where anything gets cached or configured.
ENV RUSTUP_HOME=/usr/local/rustup
ENV CARGO_HOME=/usr/local/cargo
ENV PATH="/usr/local/cargo/bin:${PATH}"
COPY --from=rust-toolchain /usr/local/rustup /usr/local/rustup
COPY --from=rust-toolchain /usr/local/cargo /usr/local/cargo

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN chmod -R a+rX /usr/local/rustup /usr/local/cargo

RUN cargo --version && rustc --version

CMD ["./entrypoint.sh", "cargo"]
