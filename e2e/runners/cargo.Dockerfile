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

# 1.98.1: latest stable per the Rust blog (2026-09-03), `-slim-bookworm` (no gcc/build-essential; the
# final stage installs just `gcc` + `libc6-dev`, see below) -- this harness's crates are dependency-free
# and every publish/fetch runs with `--no-verify` (see clients/cargo.ts); only `cargo install` of a
# binary crate (tests/cargo/install-add.spec.ts) compiles Rust code. bookworm matches the final stage's own node:24-bookworm-slim base libc.
# A named build stage (not the final image), only used below as a `COPY --from` source for the
# toolchain directories -- a normal build-time reference, not a Docker Compose sibling-service
# dependency (see the comment right before the final `FROM` for why that distinction matters here).
# The digest has no default on purpose (RPS-1597): docker-compose.runners.yml is its single source and
# runners/bump-pins.sh keeps it (README.md "Runner images and pins"), so a build without it fails loudly.
ARG RUST_VERSION=1.98.1
ARG RUST_IMAGE_DIGEST
FROM rust:${RUST_VERSION}-slim-bookworm@${RUST_IMAGE_DIGEST} AS rust-toolchain

# The cargo runner: the harness itself (see base.Dockerfile) plus the toolchain copied in from the
# stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason maven.Dockerfile's/npm.Dockerfile's headers give:
# Docker Compose has no way to guarantee a sibling service's image is built first, and duplicating
# these cheap, well-cached layers here keeps the already-proven "skeleton" service untouched.
FROM node:24-bookworm-slim

# The harness's directory in the build context (RPS-1500): `.` when the context is e2e/ (docker-compose.runners.yml),
# a path such as `repsy-os/e2e` when another repository builds from a parent directory. Every COPY of the
# harness's own files below is relative to it. Declared after FROM, so it is in scope for this stage only.
ARG HARNESS_DIR=.

RUN npm install -g --ignore-scripts pnpm@12.5.1

WORKDIR /app
RUN chmod 777 /app

COPY ${HARNESS_DIR}/package.json ${HARNESS_DIR}/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY ${HARNESS_DIR}/tsconfig.json ${HARNESS_DIR}/playwright.config.ts ./
COPY ${HARNESS_DIR}/src ./src
COPY ${HARNESS_DIR}/tests ./tests
COPY ${HARNESS_DIR}/runners/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

# --- cargo-specific layers ---

# A C compiler driver and libc headers: rustc links every binary through `cc`. Only `cargo install`
# of a binary crate (tests/cargo/install-add.spec.ts, RPS-1486) builds anything: everything else here
# is dependency-free and runs with `--no-verify`, so nothing else needs it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

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
