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

// Read from process.env on purpose, NOT via src/env.ts: env.ts throws at import time without
// REPSY_ADMIN_PASSWORD, which would break `playwright test --list` (and every other project) on a host
// without a .env. Only the ui runner sets REPSY_UI_WORKERS (docker-compose.runners.yml), so the
// protocol runners keep Playwright's default worker count; `workers` is config-wide, not per project.
const uiWorkers = process.env.REPSY_UI_WORKERS ? Number(process.env.REPSY_UI_WORKERS) : undefined;
// The SPA is served on the API port (8080), not the protocol port (9090).
const uiBaseUrl =
  process.env.REPSY_UI_BASE_URL || process.env.REPSY_API_BASE_URL || 'http://localhost:8080';

// One project per protocol is added from step 2 onward; `skeleton` proves the harness itself
// (seeding, cleanup, the raw-HTTP auth probe) and needs no protocol client or browser. `ui` is the
// odd one out: it drives the panel in a browser rather than a package client.
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
  workers: uiWorkers,
  projects: [
    {
      name: 'skeleton',
      testMatch: 'skeleton/**/*.spec.ts',
    },
    {
      name: 'maven',
      testMatch: 'maven/**/*.spec.ts',
    },
    {
      name: 'npm',
      testMatch: 'npm/**/*.spec.ts',
    },
    {
      // The npm registry under the other npm-family clients too -- pnpm, yarn classic, yarn berry and
      // bun, with npm as the baseline (README.md "npm-family clients", RPS-1330). Its own project and
      // runner so `--protocol npm` stays exactly as small and fast as it is.
      name: 'npm-clients',
      testMatch: 'npm-clients/**/*.spec.ts',
    },
    {
      name: 'cargo',
      testMatch: 'cargo/**/*.spec.ts',
    },
    {
      name: 'nuget',
      testMatch: 'nuget/**/*.spec.ts',
    },
    {
      name: 'pypi',
      testMatch: 'pypi/**/*.spec.ts',
    },
    {
      name: 'docker',
      testMatch: 'docker/**/*.spec.ts',
    },
    {
      // Both Helm protocols (OCI and classic/ChartMuseum, `clients/helm.ts`/`clients/helm-classic
      // .ts`) run inside this one project/runner -- they share one client toolchain (the `helm`
      // binary plus the build-time-installed `cm-push` plugin), so there is no reason to split
      // them into two runner services.
      name: 'helm',
      testMatch: 'helm/**/*.spec.ts',
    },
    {
      name: 'golang',
      testMatch: 'golang/**/*.spec.ts',
    },
    {
      name: 'ruby',
      testMatch: 'ruby/**/*.spec.ts',
    },
    {
      // Cases that `docker exec` into the Repsy container itself (the password reset marker file in
      // the image, README.md "Stack runner"). Runs in the "stack" runner, the only one with the
      // host's Docker socket; specs under tests/stack/ skip themselves on a remote target.
      name: 'stack',
      testMatch: 'stack/**/*.spec.ts',
    },
    {
      // The panel UI, driven in headless Chromium (README.md "UI suite"). Runs in the "ui" runner,
      // which is the only one with a browser installed; specs live under tests/ui/.
      name: 'ui',
      testMatch: 'ui/**/*.spec.ts',
      // A UI test waits on renders and network round trips, but never on a real package client.
      timeout: 60_000,
      use: {
        browserName: 'chromium',
        headless: true,
        baseURL: uiBaseUrl,
        viewport: { width: 1440, height: 900 },
        testIdAttribute: 'data-testid',
        actionTimeout: 10_000,
        navigationTimeout: 20_000,
        // `on-first-retry` never fires outside CI (retries is 0 there), so a local failure would leave
        // no trace behind; keep one on failure instead.
        trace: isCI ? 'on-first-retry' : 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
        // The app itself ignores prefers-reduced-motion; src/ui/defaults.ts injects the CSS that does
        // the work. This only makes the media query true for anything that does honour it.
        contextOptions: { reducedMotion: 'reduce' },
        launchOptions: {
          // Playwright's own default is chromiumSandbox:false; this suite wants the sandbox ON unless
          // opted out (kernels without unprivileged user namespaces). Never a bare --no-sandbox arg.
          chromiumSandbox: process.env.REPSY_UI_NO_SANDBOX !== '1',
        },
      },
    },
  ],
});
