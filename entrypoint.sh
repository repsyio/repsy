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

exec java -jar app.jar
