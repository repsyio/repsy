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
 * The shared scenario catalog (13 auth/override scenarios) run with pnpm as the client (RPS-1330):
 * `registerPublishConsumeLoop(npmFamilyAdapter(pnpmClient))`, titles `npm[pnpm] > <scenario>`. Same
 * catalog, same expectations as the npm baseline beside it (`../npm/publish-consume.spec.ts`), so a
 * cell that differs is a pnpm difference, pinned in the loop's per-client expectations or here.
 */
import { npmFamilyAdapter } from '../../../src/clients/npm-family/adapter.js';
import { pnpmClient } from '../../../src/clients/npm-family/pnpm-client.js';
import { registerPublishConsumeLoop } from '../../../src/scenarios/loop.js';

registerPublishConsumeLoop(npmFamilyAdapter(pnpmClient));
