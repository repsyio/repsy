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
 * The cargo client's environment is an allow-list.
 * (RPS-1446, modelled on `tests/npm-clients/sealed-env.spec.ts`.) Every client of this suite runs in
 * an allow-list environment (`clientEnv`, `src/clients/client-env.ts`), never the runner's own:
 * `REPSY_ADMIN_PASSWORD` and the rest of the harness's variables must not reach the client, its
 * plugins or the scripts it runs. Each cell runs `env` through `run()` with the environment the
 * suite's own builder produced and reads back the NAMES that arrived (`src/clients/env-probe.ts`).
 * No stack is needed: nothing here talks to Repsy.
 */
import { test } from '@playwright/test';

import { cargoEnv } from '../../src/clients/cargo.js';
import { isolatedWorkDir } from '../../src/clients/exec.js';
import { expectSealed, probeEnv } from '../../src/clients/env-probe.js';

test(
  'cargo > no runner variable reaches cargo, its credential and cache directories do',
  { tag: ['@sealed-env'] },
  async () => {
    const { home, work } = await isolatedWorkDir('cargo-sealed-env');
    const seen = await probeEnv(
      cargoEnv(home, {
        transport: 'basic',
        username: 'token',
        password: 'sealed-env-secret',
        kind: 'token',
      }),
      work,
      'cargo-sealed-env',
    );
    expectSealed(seen, home, ['CARGO_HOME', 'CARGO_REGISTRIES_REPSY_TOKEN']);
  },
);
