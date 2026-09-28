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

/**
 * RPS-1482 / RPS-1561: a real `npm publish` of a package over the upload limit
 * (`NPM_MAX_PUBLISH_SIZE`, `repsy.npm.max-publish-size`), on a stack started with the limits overlay
 * (`./run.sh local up --limits`, README.md "Size-limit leg"). Registered by `registerSizeLimitSpecs`
 * (`src/scenarios/size-limits.ts`); the push itself is `clients/oversize.ts`'s `pushNpm`.
 *
 * npm hides the server's answer behind its own error line rather than Repsy's envelope, so the raw
 * replay (`registerSizeLimitSpecs`'s default `expectPayloadTooLarge`) is what actually pins the 413
 * and the `payloadTooLarge` envelope. Probed live against this overlay, a real `npm publish` over the
 * limit prints exactly:
 *
 *   npm error code E413
 *   npm error 413 Payload Too Large - PUT http://.../<repo>/<package>
 *
 * one attempt, no retry (a 413 is not a status npm retries, unlike the old 500 of RPS-1205).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { npmAdapter } from '../../src/clients/npm.js';
import { pushNpm } from '../../src/clients/oversize.js';
import { registerSizeLimitSpecs } from '../../src/scenarios/size-limits.js';

registerSizeLimitSpecs({
  protocol: 'npm',
  client: 'npm publish',
  repoType: RepoType.NPM,
  adapter: npmAdapter,
  push: pushNpm,
  clientMessage: /npm error code E413/,
});
