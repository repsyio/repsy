#!/bin/sh
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

set -eu

: "${API_BASE_URL:=}"
: "${REPO_BASE_URL:=http://localhost:9090}"

STATIC_ENV_FILE="./static/assets/static-env.js"

if [ -f "$STATIC_ENV_FILE" ]; then
  sed -i \
    -e "s|__API_BASE_URL__|${API_BASE_URL}|g" \
    -e "s|__REPO_BASE_URL__|${REPO_BASE_URL}|g" \
    "$STATIC_ENV_FILE"
fi

# RPS-1401: before this release the image stored artifacts in /home/appuser/.repsy (the writable
# layer, unless the operator mounted it). The image default is now /app/data/storage, on the volume.
# When the default is in effect, is still empty and the old directory holds data, keep using the old
# directory, so an upgraded container that mounted it does not start with an empty repository.
LEGACY_STORAGE_DIR="${HOME:-/home/appuser}/.repsy"
DEFAULT_STORAGE_DIR="/app/data/storage"
if [ "${STORAGE_BASE_PATH:-}" = "$DEFAULT_STORAGE_DIR" ] \
   && [ -z "$(ls -A "$DEFAULT_STORAGE_DIR" 2>/dev/null || true)" ] \
   && [ -n "$(ls -A "$LEGACY_STORAGE_DIR" 2>/dev/null || true)" ]; then
  export STORAGE_BASE_PATH="$LEGACY_STORAGE_DIR"
  echo "WARN: $LEGACY_STORAGE_DIR holds artifacts and $DEFAULT_STORAGE_DIR is empty: keeping" \
       "STORAGE_BASE_PATH=$LEGACY_STORAGE_DIR. Move its content into the /app/data volume" \
       "(for example: docker cp the directory to /app/data/storage) or set STORAGE_BASE_PATH" \
       "explicitly; see README 'Upgrading' (RPS-1401)." >&2
fi

exec java -jar app.jar
