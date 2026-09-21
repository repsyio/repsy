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

import 'dotenv/config';

import { defineConfig } from '@playwright/test';

const isCI = Boolean(process.env.CI);

// One project per protocol is added from step 2 onward; `skeleton` proves the harness itself
// (seeding, cleanup, the raw-HTTP auth probe) and needs no protocol client or browser.
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  // A real `mvn deploy`/`dependency:get` (network round trips, cold JVM/plugin startup) is slower
  // than the skeleton's plain HTTP calls; 30s (this file's default before maven existed) was too
  // tight for it.
  timeout: 120_000,
  expect: { timeout: 10_000 },
  projects: [
    {
      name: 'skeleton',
      testMatch: 'skeleton/**/*.spec.ts',
    },
    {
      name: 'maven',
      testMatch: 'maven/**/*.spec.ts',
    },
  ],
});
