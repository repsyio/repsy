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

# The stub scanner of the opt-in scanner stack (docker-compose.stack-scanner.yml, README.md "Scanner
# stack"): src/stubs/scanner/, run by Node's own type stripping. No dependencies, no build step, no
# pnpm install; the same base image as the runners, so nothing new is pulled. Built with the e2e/
# directory as its context.
FROM node:24-bookworm-slim

WORKDIR /app
COPY src/stubs/scanner ./

ENV SERVER_PORT=8090
EXPOSE 8090
USER node
CMD ["node", "--disable-warning=ExperimentalWarning", "main.ts"]
