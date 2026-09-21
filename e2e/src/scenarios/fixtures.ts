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
 * The base Playwright fixture: an admin-authenticated `PanelApi` and a per-test `Seeder` that
 * cleans up after itself. The scenario model (`Scenario`, `catalog`, turning a scenario into a
 * ready-to-test world) is step 2 of the e2e harness plan; this file only carries what step 1
 * needs to prove seeding and cleanup work end to end.
 */
import { test as base } from '@playwright/test';

import { PanelApi } from '../api/panel-api.js';
import { env } from '../env.js';
import { perTestRunId } from '../seed/run-id.js';
import { Seeder } from '../seed/seeder.js';

export interface Fixtures {
  panelApi: PanelApi;
  seeder: Seeder;
}

export const test = base.extend<Fixtures>({
  // eslint-disable-next-line no-empty-pattern
  panelApi: async ({}, use) => {
    const api = new PanelApi(env.apiBaseUrl);
    await api.login(env.adminUsername, env.adminPassword);
    await use(api);
  },

  seeder: async ({ panelApi }, use) => {
    const seeder = new Seeder(panelApi, perTestRunId(env.runId));
    await use(seeder);
    await seeder.cleanup();
  },
});

export { expect } from '@playwright/test';
