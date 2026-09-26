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

# 3.14.7: current stable CPython (3.15 is rc-only as of writing). `twine upload`/`pip download` are
# pure `requests`/`urllib3` HTTP clients -- there is no daemon/socket of any kind here, the same
# "copy the toolchain, not the whole image" approach as cargo.Dockerfile's Rust toolchain /
# nuget.Dockerfile's .NET SDK. The final image must stay `node:24-bookworm-slim` (the harness itself
# needs Node), so CPython is copied in from the official `python` image rather than installed via
# apt (Debian bookworm's own `python3` package is 3.11, not a pinned-to-us 3.14.7).
# A named build stage (not the final image), only used below as a `COPY --from` source for the
# interpreter/stdlib -- a normal build-time reference, not a Docker Compose sibling-service
# dependency (see maven.Dockerfile's/nuget.Dockerfile's headers for why that distinction matters
# here).
ARG PYTHON_VERSION=3.14.7
ARG PIP_VERSION=26.2.1
ARG TWINE_VERSION=7.0.0
# uv (RPS-1486): the second PyPI client, a static binary (`uv publish`, `uv pip`, `uv lock`) copied out
# of Astral's own image the way the toolchains above are, a named stage used only as a `COPY --from`
# source. It is never given a Python of its own to download (UV_PYTHON_DOWNLOADS=never below): it
# uses the CPython copied in from `python-toolchain`.
ARG UV_VERSION=0.12.19
FROM ghcr.io/astral-sh/uv:${UV_VERSION} AS uv-binary
FROM python:${PYTHON_VERSION}-slim-bookworm AS python-toolchain
ARG PIP_VERSION
ARG TWINE_VERSION
RUN python3 -m pip install --no-cache-dir "pip==${PIP_VERSION}" "twine==${TWINE_VERSION}"

# The pypi runner: the harness itself (see base.Dockerfile) plus the toolchain copied in from the
# stage above, nothing else. Its first layers intentionally repeat base.Dockerfile's rather than
# `FROM` a separately built tag, for the same reason every other protocol runner's header gives:
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

# --- pypi-specific layers ---

# CPython's own runtime .so deps that node:24-bookworm-slim's base image does not already carry
# (libbz2/libdb/libffi/liblzma/libuuid/zlib1g/libtinfo are already present via glibc's own closure --
# verified live with `ldd` against every extension module under lib-dynload/ and cross-checked
# against `dpkg -l`/`ldconfig -p` on a bare node:24-bookworm-slim container, not assumed from a
# generic runtime-deps list): libssl3 (ssl, hashlib's OpenSSL-backed algorithms), libsqlite3-0
# (sqlite3, imported by pip's own internals), libreadline8 + libncursesw6 (readline/curses, imported
# transitively by some stdlib modules at interpreter start), libgdbm6 (dbm.gnu), ca-certificates
# (TLS root store -- unused by this harness's own http:// calls, but `requests`/`urllib3` still
# import `ssl`/certifi at load time and a missing trust store would break any accidental https://
# fallback). No -dev/build packages: nothing here ever compiles anything (twine/pip are pure-Python
# wheels themselves).
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libssl3 libsqlite3-0 libreadline8 libncursesw6 libgdbm6 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=python-toolchain /usr/local/bin/python3.14 /usr/local/bin/python3.14
COPY --from=python-toolchain /usr/local/lib/python3.14 /usr/local/lib/python3.14
COPY --from=python-toolchain /usr/local/lib/libpython3.14.so.1.0 /usr/local/lib/libpython3.14.so.1.0

RUN ln -s python3.14 /usr/local/bin/python3 \
    && ln -s python3 /usr/local/bin/python \
    && ldconfig

# Readable/executable (not necessarily owned) by whatever uid the container runs as
# (docker-compose.runners.yml's "user:", the host user) -- installed as root during the build, run as
# that other user at runtime, same reasoning as /app itself above.
RUN chmod -R a+rX /usr/local/lib/python3.14 && chmod a+rx /usr/local/bin/python3.14

# uv and uvx, the two static binaries of Astral's image; UV_PYTHON pins the interpreter the harness
# copied in above and UV_PYTHON_DOWNLOADS=never forbids fetching one from the network. Every uv
# invocation of the harness still gets its own allow-listed environment (clients/uv.ts), so these two
# only make a manual `docker run` of the image behave.
COPY --from=uv-binary /uv /uvx /usr/local/bin/
ENV UV_PYTHON=/usr/local/bin/python3.14 \
    UV_PYTHON_DOWNLOADS=never

RUN python3 -c "import ssl, hashlib, zlib, bz2, lzma, sqlite3, ctypes, uuid" \
    && python3 -m pip --version \
    && python3 -m twine --version \
    && uv --version

CMD ["./entrypoint.sh", "pypi"]
