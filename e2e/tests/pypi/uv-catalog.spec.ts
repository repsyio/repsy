///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

/**
 * The scenario catalog through uv (RPS-1486): `registerPublishConsumeLoop(uvAdapter)` runs every
 * scenario `pypiAdapter` runs (password, USER role, deploy tokens read-write/read-only,
 * anonymous on a public and a private repo, expired/revoked/rotated tokens, override / no-override,
 * ...) with `uv publish` as the publisher and `uv lock` + `uv sync --locked` as the consumer, each in
 * its own private HOME with the credential in uv's own variables (`src/clients/uv.ts`). The
 * expectations are pypi's: `expectationFor(scenario, 'pypi')`. Titles read `pypi[uv] > <id>`; tag
 * `@uv` selects them (`--grep @uv`).
 */
import { uvAdapter } from '../../src/clients/uv.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(uvAdapter);
