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
 * RPS-1482: a real `cargo publish` of a package over the upload limit, on a stack started with the limits overlay
 * (`./run.sh local up --limits`, README.md "Size-limit leg"). Registered by `registerSizeLimitSpecs`
 * (`src/scenarios/size-limits.ts`); the push itself is `clients/oversize.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { cargoAdapter } from '../../src/clients/cargo.js';
import { pushCargo } from '../../src/clients/oversize.js';
import { expect } from '../../src/scenarios/fixtures.js';
import { registerSizeLimitSpecs } from '../../src/scenarios/size-limits.js';

registerSizeLimitSpecs({
  protocol: 'cargo',
  client: 'cargo publish',
  repoType: RepoType.CARGO,
  adapter: cargoAdapter,
  push: pushCargo,
  // Cargo's own error shape (`{"errors":[{"detail":...}]}`), not Repsy's msgId envelope: cargo prints the
  // detail, and the 413 carries no msgId.
  expectReplay: (replay) => {
    expect(replay.status, 'raw replay status').toBe(413);
    expect(JSON.parse(replay.body.toString('utf8'))).toEqual({
      errors: [{ detail: 'the crate exceeds the maximum upload size' }],
    });
  },
  clientMessage: /status 413 Payload Too Large\): the crate exceeds the maximum upload size/,
});
