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

// Retries are per project, not config-wide (RPS-1595). A retry hides a flake behind a warning nobody is
// obliged to read, so only a project whose tests wait on something outside the stack gets one:
//   - `ui` (a real browser: render and network timing) and `maven` (plugin resolution from Maven
//     Central on a cold maven_m2_shared volume) retry once under CI. The trivy contract spec asks for
//     its own retry in the spec (it downloads the vulnerability databases from ghcr.io).
//   - `stack` NEVER retries: a retried `test.describe.serial` re-enters `beforeAll` on an already
//     upgraded container, and persistence.spec.ts restarts the container mid-retry.
//   - every other project stays at 0, so a protocol failure is a ticket (RPS-1355 was found that way).
// Two ways to change that without editing this file: `REPSY_E2E_RETRIES=<n>` sets the count of every
// project but `stack` (0 turns the ui/maven retry off in CI too), and a `remote` REPSY_TARGET (a shared
// instance, where network flakes are real) makes every project retry once under CI, which is what
// Repsy Cloud's harness against DEV wants (RPS-1512). `CI` also turns `forbidOnly` on for every project.
const retriesOverride = process.env.REPSY_E2E_RETRIES;
const isRemoteTarget = process.env.REPSY_TARGET === 'remote';
function retriesFor(networkBound: boolean): number {
  if (retriesOverride) {
    const n = Number(retriesOverride);
    if (!Number.isInteger(n) || n < 0) {
      throw new Error(`REPSY_E2E_RETRIES must be a non-negative integer, got "${retriesOverride}"`);
    }
    return n;
  }
  return isCI && (networkBound || isRemoteTarget) ? 1 : 0;
}

// Read from process.env on purpose, NOT via src/env.ts: env.ts throws at import time without
// REPSY_ADMIN_PASSWORD, which would break `playwright test --list` (and every other project) on a host
// without a .env. Only the ui runner sets REPSY_UI_WORKERS (docker-compose.runners.yml), so the
// protocol runners keep Playwright's default worker count; `workers` is config-wide, not per project.
const uiWorkers = process.env.REPSY_UI_WORKERS ? Number(process.env.REPSY_UI_WORKERS) : undefined;
// The stack runner sets REPSY_E2E_WORKERS=1 (docker-compose.runners.yml): its persistence spec restarts
// the one Repsy container of the stack, so no other spec may run beside it.
const e2eWorkers = process.env.REPSY_E2E_WORKERS
  ? Number(process.env.REPSY_E2E_WORKERS)
  : undefined;
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
  // Per project, see retriesFor() above.
  retries: 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
    // The one place a retried test is visible for every project: junit.xml marks it passed, and Playwright
    // only keeps a `*-retry1` directory when the retry attempt wrote something (a trace, a screenshot), which
    // a maven or api test does not. The nightly's "Flag the tests that needed a retry" step reads the
    // tests whose status is "flaky" from here (RPS-1595).
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  // A real `mvn deploy`/`dependency:get` (network round trips, cold JVM/plugin startup) is slower
  // than the skeleton's plain HTTP calls; 30s (this file's default before maven existed) was too
  // tight for it.
  timeout: 120_000,
  expect: { timeout: 10_000 },
  workers: uiWorkers ?? e2eWorkers,
  projects: [
    {
      name: 'skeleton',
      testMatch: 'skeleton/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'maven',
      testMatch: 'maven/**/*.spec.ts',
      retries: retriesFor(true),
    },
    {
      name: 'npm',
      testMatch: 'npm/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      // The npm registry under the other npm-family clients too -- pnpm, yarn classic, yarn berry and
      // bun, with npm as the baseline (README.md "npm-family clients", RPS-1330). Its own project and
      // runner so `--protocol npm` stays exactly as small and fast as it is.
      name: 'npm-clients',
      testMatch: 'npm-clients/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'cargo',
      testMatch: 'cargo/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'nuget',
      testMatch: 'nuget/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'pypi',
      testMatch: 'pypi/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'docker',
      testMatch: 'docker/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      // Both Helm protocols (OCI and classic/ChartMuseum, `clients/helm.ts`/`clients/helm-classic
      // .ts`) run inside this one project/runner -- they share one client toolchain (the `helm`
      // binary plus the build-time-installed `cm-push` plugin), so there is no reason to split
      // them into two runner services.
      name: 'helm',
      testMatch: 'helm/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'golang',
      testMatch: 'golang/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      name: 'ruby',
      testMatch: 'ruby/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      // Raw HTTP at the edge of Repsy, no package client: which port serves what, the public URLs
      // behind X-Forwarded-*, the CORS and CSP headers (README.md "API suite", RPS-1480).
      name: 'api',
      testMatch: 'api/**/*.spec.ts',
      retries: retriesFor(false),
    },
    {
      // Cases that `docker exec` into the Repsy container itself (the password reset marker file in
      // the image, README.md "Stack runner"). Runs in the "stack" runner, the only one with the
      // host's Docker socket; specs under tests/stack/ skip themselves on a remote target.
      name: 'stack',
      testMatch: 'stack/**/*.spec.ts',
      // Never retried, not even under REPSY_E2E_RETRIES or a remote target (see the top of this file).
      retries: 0,
    },
    {
      // The panel UI, driven in headless Chromium (README.md "UI suite"). Runs in the "ui" runner,
      // which is the only one with a browser installed; specs live under tests/ui/.
      name: 'ui',
      testMatch: 'ui/**/*.spec.ts',
      retries: retriesFor(true),
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
        // `on-first-retry` never fires without a retry (outside CI, or REPSY_E2E_RETRIES=0), so a
        // failure would leave no trace behind then; keep one on failure instead.
        trace: retriesFor(true) > 0 ? 'on-first-retry' : 'retain-on-failure',
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
