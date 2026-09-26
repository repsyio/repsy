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
 * The shared scenario catalog (13 auth/override scenarios), consumed through Deno (RPS-1486):
 * `registerPublishConsumeLoop(npmFamilyAdapter(npmClient, denoClient))`, titles `npm[deno] > <scenario>`.
 * Deno cannot publish to npm, so the publish side of every scenario is npm's (its own loop is
 * `tests/npm-clients/npm/publish-consume.spec.ts`) and the consume side is `deno install npm:<pkg>@<version>`
 * with the scenario's credential in `.npmrc`; a failure message that says "the real deno publish" means
 * the publish leg, which npm ran.
 */
import { npmFamilyAdapter } from '../../../src/clients/npm-family/adapter.js';
import { denoClient } from '../../../src/clients/npm-family/deno-client.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { registerPublishConsumeLoop } from '../../../src/scenarios/loop.js';

registerPublishConsumeLoop(npmFamilyAdapter(npmClient, denoClient));
