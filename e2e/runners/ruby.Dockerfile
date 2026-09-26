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

# 4.0.7 (current stable on the "4.0" line, confirmed live as `ruby:4.0.7-slim-bookworm`; ships
# RubyGems 4.0.20 / Bundler 4.0.20 -- unified versioning since 4.0, confirmed live with
# `ruby --version && gem --version && bundle --version`). Only the CONSUME side needs a real
# resolver/installer (`bundle install`); the publish side (`gem push`) is the same toolchain. This
# Dockerfile copies the interpreter/stdlib in from the official image the same "copy the toolchain,
# not the whole image" approach as cargo.Dockerfile's Rust toolchain / nuget.Dockerfile's .NET SDK /
# pypi.Dockerfile's CPython / golang.Dockerfile's Go toolchain. The final image must stay
# `node:24-bookworm-slim` (the harness itself needs Node). A named build stage (not the final
# image), only used below as a `COPY --from` source -- a normal build-time reference, not a Docker
# Compose sibling-service dependency (see maven.Dockerfile's/nuget.Dockerfile's headers for why that
# distinction matters here).
# The digest has no default on purpose (RPS-1597): docker-compose.runners.yml is its single source and
# runners/bump-pins.sh keeps it (README.md "Runner images and pins"), so a build without it fails loudly.
ARG RUBY_VERSION=4.0.7
ARG RUBY_IMAGE_DIGEST
FROM ruby:${RUBY_VERSION}-slim-bookworm@${RUBY_IMAGE_DIGEST} AS ruby-toolchain

# The ruby runner: the harness itself (see base.Dockerfile) plus the Ruby toolchain copied in from
# the stage above. Its first layers intentionally repeat base.Dockerfile's rather than `FROM` a
# separately built tag, for the same reason every other protocol runner's header gives: Docker
# Compose has no way to guarantee a sibling service's image is built first, and duplicating these
# cheap, well-cached layers here keeps the already-proven "skeleton" service untouched.
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

# --- ruby-specific layers ---

# Ruby's own runtime .so deps that node:24-bookworm-slim's base image does not already carry
# (verified live with `ldd` against every extension module under
# /usr/local/lib/ruby/**/x86_64-linux/*.so plus libruby.so itself, and cross-checked against a bare
# `node:24-bookworm-slim` container with the same libs looked up -- not a generic runtime-deps list):
# libssl3/libcrypto (openssl, hashlib-equivalent digests, HTTPS -- unused by this harness's own
# http:// calls but still dlopen'd by the openssl extension at require time), libyaml-0-2 (psych --
# MANDATORY: a gemspec's metadata.gz is YAML, loaded via Psych both client- and server-side).
# libgmp10/libcrypt1/libffi8/zlib1g are already present via node:24-bookworm-slim's own closure
# (glibc/OpenSSL's own dependents) -- confirmed live, not assumed. ca-certificates is not needed for
# this harness's own http:// calls, but is cheap and keeps a stray https:// (a remote target) from
# failing on an unrelated missing trust store.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libssl3 libyaml-0-2 \
    && rm -rf /var/lib/apt/lists/*

# `ruby`/`gem`/`bundle`/`bundler` in /usr/local/bin are `#!/usr/local/bin/ruby`-shebang scripts
# (`gem`/`bundle`/`bundler` resolved from the default-gem specifications under
# /usr/local/lib/ruby/gems/4.0.0/specifications/default/, which is part of the stdlib tree copied
# below) -- copying only the interpreter binary plus the whole stdlib/gems tree is enough; the image's
# own `GEM_HOME` (`/usr/local/bundle`) is deliberately NOT copied: every `gem`/`bundle` invocation in
# this harness overrides `GEM_HOME`/`GEM_PATH`/`BUNDLE_*` itself (`clients/ruby.ts`'s `gemEnv`/
# `bundleEnv`), so that directory is irrelevant here.
COPY --from=ruby-toolchain /usr/local/bin/ruby /usr/local/bin/gem /usr/local/bin/bundle /usr/local/bin/bundler /usr/local/bin/
COPY --from=ruby-toolchain /usr/local/lib/ruby /usr/local/lib/ruby
# The exact three names (a real .so plus its two symlinks) -- confirmed live with
# `ls /usr/local/lib/libruby*` in the toolchain stage rather than guessed.
COPY --from=ruby-toolchain /usr/local/lib/libruby.so /usr/local/lib/libruby.so.4.0 /usr/local/lib/libruby.so.4.0.7 /usr/local/lib/

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN ldconfig \
    && chmod -R a+rX /usr/local/lib/ruby \
    && chmod a+rx /usr/local/bin/ruby /usr/local/bin/gem /usr/local/bin/bundle /usr/local/bin/bundler

RUN ruby --version && gem --version && bundle --version \
    && ruby -e 'require "psych"; require "openssl"; require "zlib"; require "digest"; require "fiddle"; require "bigdecimal"; require "etc"; puts Psych::LIBYAML_VERSION'

CMD ["./entrypoint.sh", "ruby"]
