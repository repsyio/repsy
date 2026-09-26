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

# 1.27.1 (current stable, confirmed available live as `golang:1.27.1-bookworm`). Only the CONSUME
# side runs a real toolchain -- Go has no official publisher at all (`clients/golang.ts`'s file
# header: the only documented publisher is a single `curl -T`), so this Dockerfile copies `go` in
# from the official image the same "copy the toolchain, not the whole image" approach as
# cargo.Dockerfile's Rust toolchain / nuget.Dockerfile's .NET SDK / pypi.Dockerfile's CPython. The
# final image must stay `node:24-bookworm-slim` (the harness itself needs Node).
# A named build stage (not the final image), only used below as a `COPY --from` source -- a normal
# build-time reference, not a Docker Compose sibling-service dependency (see maven.Dockerfile's/
# nuget.Dockerfile's headers for why that distinction matters here).
# The digest has no default on purpose (RPS-1597): docker-compose.runners.yml is its single source and
# runners/bump-pins.sh keeps it (README.md "Runner images and pins"), so a build without it fails loudly.
ARG GO_VERSION=1.27.1
ARG GO_IMAGE_DIGEST
FROM golang:${GO_VERSION}-bookworm@${GO_IMAGE_DIGEST} AS go-toolchain

# The golang runner: the harness itself (see base.Dockerfile) plus the Go toolchain copied in from
# the stage above, `curl` (the real publisher, `clients/golang.ts`'s file header) and a build-time-
# generated throwaway TLS certificate/key (`clients/golang-tls-shim.ts`'s file header: a real `go`
# command refuses to pass credentials to a plain-http GOPROXY URL, confirmed live, so a credentialed
# consume needs an in-process HTTPS terminator in front of this stack's own plain-http port). Its
# first layers intentionally repeat base.Dockerfile's rather than `FROM` a separately built tag, for
# the same reason every other protocol runner's header gives: Docker Compose has no way to guarantee
# a sibling service's image is built first, and duplicating these cheap, well-cached layers here
# keeps the already-proven "skeleton" service untouched.
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

# --- golang-specific layers ---

# `curl` is absent from `node:24-bookworm-slim` (confirmed live: `which curl` prints nothing on a
# bare image) -- 7.88.1 on bookworm carries `--fail-with-body` (7.76+), which `clients/golang.ts`
# relies on to get BOTH the response body and the raw HTTP status out of one invocation.
# `ca-certificates` is not needed for this harness's own http:// calls, but is cheap and keeps a
# stray https:// (a remote target) from failing on an unrelated missing trust store.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=go-toolchain /usr/local/go /usr/local/go
ENV PATH="/usr/local/go/bin:${PATH}" GOTOOLCHAIN=local
# Readable/executable by whatever uid the container runs as (docker-compose.runners.yml's "user:",
# the host user) -- installed as root during the build, run as that other user at runtime, same
# reasoning as /app itself above.
RUN chmod -R a+rX /usr/local/go

# The TLS shim's certificate/key, generated ONCE at build time with Go's own official tool
# (`crypto/tls/generate_cert.go`, `//go:build ignore` -- exists on the toolchain we just copied in)
# -- confirmed live/H4: `SSL_CERT_FILE` pointed at the leaf alone is enough for a real `go` command
# to trust it, no `--ca`/intermediate needed. World-readable on purpose: this is a throwaway,
# test-only key that signs nothing outside this container's own loopback interface.
RUN mkdir -p /opt/e2e-tls && cd /opt/e2e-tls \
    && HOME=/tmp GOPATH=/tmp/gopath GOCACHE=/tmp/gocache CGO_ENABLED=0 \
       go run /usr/local/go/src/crypto/tls/generate_cert.go \
         --host localhost,127.0.0.1 --ecdsa-curve P256 --duration 87600h \
    && chmod a+r /opt/e2e-tls/cert.pem /opt/e2e-tls/key.pem \
    && rm -rf /tmp/gopath /tmp/gocache
ENV REPSY_E2E_TLS_CERT=/opt/e2e-tls/cert.pem REPSY_E2E_TLS_KEY=/opt/e2e-tls/key.pem

RUN go version && curl --version | head -1 && test -s /opt/e2e-tls/cert.pem && test -s /opt/e2e-tls/key.pem

CMD ["./entrypoint.sh", "golang"]
