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
 * The shared scenario catalog (13 auth/override scenarios) run through the npm-family harness with the
 * npm CLI as the client (RPS-1330): `registerPublishConsumeLoop(npmFamilyAdapter(npmClient))`, titles
 * `npm[npm] > <scenario>`. It is the same catalog `tests/npm` runs through `clients/npm.ts`, so a
 * result that differs is a bug in the new adapter, sealed environment or configuration renderer, not
 * in the registry -- that is what makes npm the baseline column for the clients added later (each of
 * those PRs adds its own `tests/npm-clients/<client>/publish-consume.spec.ts` beside this one).
 *
 * The scenarios tagged `@smoke` in the catalog (`password-admin`) are this suite's publish + install
 * round trip smoke.
 */
import { npmFamilyAdapter } from '../../../src/clients/npm-family/adapter.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { registerPublishConsumeLoop } from '../../../src/scenarios/loop.js';

registerPublishConsumeLoop(npmFamilyAdapter(npmClient));
