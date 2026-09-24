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
 * The three `@smoke` tests of the UI suite: the shortest path that proves the runner image, the
 * fixtures, the seeded session and the page objects all work against a real stack. Everything else
 * lives in the per-area specs next to this file.
 */
import { env } from '../../src/env.js';
import { expect, test } from '../../src/ui/fixtures.js';
import { DashboardPage } from '../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../src/ui/pages/login.js';
import { Shell } from '../../src/ui/pages/shell.js';

test.describe('UI smoke', () => {
  test('UI login lands on the dashboard', { tag: ['@smoke'] }, async ({ page }) => {
    const login = new LoginPage(page);
    const dashboard = new DashboardPage(page);

    await login.goto();
    // Only typed, never changed: the admin's password must stay what the whole run logs in with.
    await login.login(env.adminUsername, env.adminPassword);

    await expect(page).toHaveURL('/');
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(env.adminUsername);
  });

  test('logout returns to /login', { tag: ['@smoke'] }, async ({ adminPage }) => {
    const dashboard = new DashboardPage(adminPage);
    const shell = new Shell(adminPage);

    // adminPage starts logged in (the seeded session), without touching the login form.
    await dashboard.goto();
    await shell.logoutViaSidebar();

    const storedSession = () =>
      adminPage.evaluate(() => ({
        username: window.localStorage.getItem('username'),
        token: window.localStorage.getItem('token'),
        refreshToken: window.localStorage.getItem('refresh-token'),
      }));
    expect(await storedSession()).toEqual({ username: null, token: null, refreshToken: null });

    // The session is seeded once per tab: a reload must NOT log the seeded admin back in.
    await adminPage.reload();
    await expect(adminPage).toHaveURL(/\/login$/);
    expect(await storedSession()).toEqual({ username: null, token: null, refreshToken: null });
  });

  test('anonymous visit to /repositories lands on login', { tag: ['@smoke'] }, async ({ page }) => {
    await page.goto('/repositories');

    // AuthGuard sends an anonymous visitor to "/?returnUrl=/repositories", and "/" renders the login
    // form IN PLACE (AuthRedirectComponent follows the session): the path is "/", not "/login".
    // Assert what the visitor sees, not a URL that is the dashboard's for everyone else.
    await expect(new LoginPage(page).submit).toBeVisible();
    await expect(page).toHaveURL(
      (url) => url.pathname === '/' && url.searchParams.get('returnUrl') === '/repositories',
    );
    await expect(new DashboardPage(page).welcomeCard).toHaveCount(0);
  });
});
