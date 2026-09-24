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
 * Proof that the UI suite's own plumbing does what README.md "UI suite" promises, so a later story can
 * trust it: the admin guards, the opt-in switch, the flake defaults and the two-role fixtures. None
 * of it is product coverage, and none is `@smoke`.
 */
import { test as plainTest, expect as plainExpect } from '@playwright/test';

import { env } from '../../src/env.js';
import { expect, test } from '../../src/ui/fixtures.js';
import { serveReadsFromNode } from '../../src/ui/defaults.js';
import { DashboardPage } from '../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../src/ui/pages/login.js';
import { Shell } from '../../src/ui/pages/shell.js';
import { assertAdminCredentialsUsableInUi, assertNotAdmin, optedIn } from '../../src/ui/session.js';

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}

// Pure functions: no browser, no stack.
plainTest.describe('UI harness guards', () => {
  plainTest('assertNotAdmin refuses the harness admin, in any case, and only that', () => {
    plainExpect(() => assertNotAdmin(env.adminUsername)).toThrow(/harness admin/);
    plainExpect(() => assertNotAdmin(env.adminUsername.toUpperCase())).toThrow(/harness admin/);
    plainExpect(() => assertNotAdmin('e2e-abc123-user-1')).not.toThrow();
    plainExpect(() => assertNotAdmin(null)).not.toThrow();
  });

  plainTest('the preflight rejects an admin password the login form cannot submit', () => {
    const original = env.adminPassword;
    try {
      plainExpect(() => assertAdminCredentialsUsableInUi()).not.toThrow();
      // Synchronous set/check/restore, so no other test of this worker can observe the value.
      for (const unusable of [
        'Ab1',
        'alllowercase1',
        'ALLUPPERCASE1',
        'NoDigitsHere',
        'Has Space1a',
      ]) {
        env.adminPassword = unusable;
        plainExpect(() => assertAdminCredentialsUsableInUi(), unusable).toThrow(
          /REPSY_ADMIN_PASSWORD must be 6-50 chars/,
        );
      }
      env.adminPassword = `Aa1${'x'.repeat(50)}`;
      plainExpect(() => assertAdminCredentialsUsableInUi()).toThrow(/6-50 chars/);
    } finally {
      env.adminPassword = original;
    }
  });

  plainTest('optedIn reads the comma list REPSY_UI_OPT_IN, case-insensitively', () => {
    const original = process.env.REPSY_UI_OPT_IN;
    try {
      delete process.env.REPSY_UI_OPT_IN;
      plainExpect(optedIn('throttle')).toBe(false);
      process.env.REPSY_UI_OPT_IN = ' Throttle , scanner,,';
      plainExpect(optedIn('throttle')).toBe(true);
      plainExpect(optedIn('SCANNER')).toBe(true);
      plainExpect(optedIn('other')).toBe(false);
    } finally {
      restoreEnv('REPSY_UI_OPT_IN', original);
    }
  });
});

test.describe('UI harness fixtures', () => {
  // The guard is a fixture error, so this "fails" exactly when adminPage refuses a @credentials test
  // (test.fail() at declaration level, because a fixture error happens before the body runs). If the
  // guard ever stops refusing, the test passes unexpectedly and test.fail() turns that red.
  // eslint-disable-next-line playwright/expect-expect
  test.fail(
    'adminPage refuses a test tagged @credentials',
    { tag: ['@credentials'] },
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    async ({ adminPage }) => {
      // Never reached: the fixture throws first.
    },
  );

  test('the flake defaults are applied and third-party hosts are blocked', async ({ page }) => {
    await page.goto('/login');

    const motion = await page.evaluate(() => ({
      reduced: window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      animation: window.getComputedStyle(document.body).animationDuration,
      transition: window.getComputedStyle(document.body).transitionDuration,
    }));
    expect(motion.reduced).toBe(true);
    expect(motion.transition).toBe('0s');
    expect(motion.animation).toBe('0.001s');

    // Same origin is untouched; any other host is aborted before it leaves the browser.
    const outcomes = await page.evaluate(async () => {
      const attempt = (url: string) =>
        fetch(url, { mode: 'no-cors' }).then(
          () => 'reached',
          () => 'blocked',
        );
      return {
        sameOrigin: await attempt('/login'),
        gravatar: await attempt('https://www.gravatar.com/avatar/'),
        tagManager: await attempt('https://www.googletagmanager.com/gtag/js?id=G-TEST'),
      };
    });
    expect(outcomes).toEqual({ sameOrigin: 'reached', gravatar: 'blocked', tagManager: 'blocked' });
  });

  // RPS-1303: Chromium fails in-flight requests with net::ERR_NETWORK_CHANGED when the host's network
  // changes, which leaves the SPA unbooted. Its scripts, styles and API reads are therefore fetched by
  // Playwright (`serveReadsFromNode`); a test cannot make Chromium report the real error, so these two
  // prove the wiring: the reads come through it, and it steps aside when its own fetch fails.
  test('the panel scripts and API reads are served by Playwright, not Chromium', async ({
    page,
    context,
    baseURL,
  }) => {
    const served: string[] = [];
    await serveReadsFromNode(context, new Set([new URL(baseURL ?? env.apiBaseUrl).origin]), {
      onServed: (url) => served.push(new URL(url).pathname),
    });

    await page.goto('/login');

    await expect(new LoginPage(page).form).toBeVisible();
    expect(served.some((path) => /\/main-[^/]+\.js$/.test(path))).toBe(true);
    expect(served.some((path) => path.endsWith('.css'))).toBe(true);
    const status = await page.evaluate(async () => (await fetch('/api/repos/NPM/info')).status);
    expect([200, 401]).toContain(status);
    expect(served).toContain('/api/repos/NPM/info');
    // A write is not among them.
    const write = await page.evaluate(
      async () => (await fetch('/api/nothing', { method: 'POST' })).status,
    );
    expect(write).toBeGreaterThanOrEqual(400);
    expect(served).not.toContain('/api/nothing');
  });

  test('a failing Playwright fetch falls back to Chromium and the page still boots', async ({
    page,
    context,
    baseURL,
  }) => {
    const served: string[] = [];
    await serveReadsFromNode(context, new Set([new URL(baseURL ?? env.apiBaseUrl).origin]), {
      fetch: () => Promise.reject(new Error('the fetch is broken')),
      onServed: (url) => served.push(url),
    });

    await page.goto('/login');

    await expect(new LoginPage(page).form).toBeVisible();
    expect(served).toEqual([]);
  });

  test('adminPage and userPage are two independent sessions', async ({
    adminPage,
    userPage,
    seededUser,
  }) => {
    const admin = { dashboard: new DashboardPage(adminPage), shell: new Shell(adminPage) };
    const user = { dashboard: new DashboardPage(userPage), shell: new Shell(userPage) };

    await Promise.all([admin.dashboard.goto(), user.dashboard.goto()]);

    await expect(admin.dashboard.welcomeUsername).toContainText(env.adminUsername);
    await expect(user.dashboard.welcomeUsername).toContainText(seededUser.username);
    // Role-based navigation: only an admin sees Users.
    await expect(admin.shell.sidebar.users).toBeVisible();
    await expect(user.shell.sidebar.repositories).toBeVisible();
    await expect(user.shell.sidebar.users).toHaveCount(0);
  });
});
