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

# Regenerates the API client and runs Playwright for one project. Calls the installed binaries
# directly (node_modules/.bin/...) rather than through "pnpm run"/"pnpm exec": the container runs
# as the host's uid:gid (docker-compose.runners.yml), not the root that built node_modules, and
# pnpm's own script runner re-verifies the modules directory against its store on every invocation,
# which fails under that uid mismatch even though the packages themselves only need to be read.
set -eu

project="$1"
shift

rm -rf src/api/generated
./node_modules/.bin/openapi \
  --input ../repsy-backend/src/main/resources/openapi/openapi-spec.yaml \
  --client axios \
  --name PanelClient \
  --output src/api/generated \
  --useOptions \
  --indent 2

exec ./node_modules/.bin/playwright test --project="$project" "$@"
