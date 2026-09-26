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
 * The crane (docker) client's environment is an allow-list.
 * (RPS-1446, modelled on `tests/npm-clients/sealed-env.spec.ts`.) Every client of this suite runs in
 * an allow-list environment (`clientEnv`, `src/clients/client-env.ts`), never the runner's own:
 * `REPSY_ADMIN_PASSWORD` and the rest of the harness's variables must not reach the client, its
 * plugins or the scripts it runs. Each cell runs `env` through `run()` with the environment the
 * suite's own builder produced and reads back the NAMES that arrived (`src/clients/env-probe.ts`).
 * No stack is needed: nothing here talks to Repsy.
 */
import { test } from '@playwright/test';

import { craneEnv } from '../../src/clients/docker.js';
import { isolatedWorkDir } from '../../src/clients/exec.js';
import { expectSealed, probeEnv } from '../../src/clients/env-probe.js';

test(
  'docker > no runner variable reaches crane, its DOCKER_CONFIG does',
  { tag: ['@sealed-env'] },
  async () => {
    const { home, work } = await isolatedWorkDir('docker-sealed-env');
    const seen = await probeEnv(craneEnv(home), work, 'docker-sealed-env');
    expectSealed(seen, home, ['DOCKER_CONFIG']);
  },
);
