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

/**
 * AUTH-05 .. AUTH-07: the route guards. `AuthGuard` sends an anonymous visitor of a protected route
 * to `/`, and `/` renders the login form IN PLACE (`AuthRedirectComponent`), so the URL is `/`, not
 * `/login`: these tests assert what the visitor sees. `/login` for a logged-in user bounces to `/`
 * (`AuthRedirectGuard`), and `adminGuard` bounces a non-admin from `/users` and `/security` to `/`.
 */
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { NO_SESSION, storedSession } from './stored-session.js';

test.describe('AUTH-05 anonymous visitor', () => {
  const guardedRoutes = ['/repositories', '/users', '/security', '/profile'];

  for (const route of guardedRoutes) {
    test(`${route} shows the login form`, async ({ page }) => {
      await page.goto(route);

      await expectLoginInPlace(page);
      await expect(page).toHaveURL('/');
    });
  }

  test('a repository page shows the login form', async ({ page, seeder }) => {
    // A repository that exists, so it is the guard that stops the visitor, not a 404.
    const repo = await seeder.createRepo(RepoType.MAVEN);

    await page.goto(`/${repo.name}`);

    await expectLoginInPlace(page);
    await expect(page).toHaveURL('/');
  });

  test('logging in from the redirected form opens the dashboard', async ({ page, seededUser }) => {
    // Intended behaviour. Today the login succeeds (the session is stored) but the page stays on the
    // form: it sits at "/" inside AuthRedirectComponent and LoginComponent navigates to "/" again,
    // which changes nothing, so the dashboard only appears after a reload. Remove this line with the fix.
    test.fail(
      true,
      'PRODUCT BUG RPS-1278: login from the in-place form at "/" does not render the dashboard',
    );

    await page.goto('/repositories');
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();

    await login.login(seededUser.username, seededUser.password);

    // The login itself worked ...
    await expect.poll(() => storedSession(page).then((s) => s.username)).toBe(seededUser.username);
    // ... so the visitor must now see the dashboard, not the form again.
    const dashboard = new DashboardPage(page);
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(seededUser.username);
    await expect(login.form).toHaveCount(0);
  });
});

async function expectLoginInPlace(page: Page): Promise<void> {
  await expect(new LoginPage(page).submit).toBeVisible();
  await expect(page).toHaveURL('/');
  await expect(new DashboardPage(page).welcomeCard).toHaveCount(0);
  await expect(new Shell(page).sidebar.root).toHaveCount(0);
  expect(await storedSession(page)).toEqual(NO_SESSION);
}

test.describe('AUTH-06 logged-in visitor', () => {
  test('/login redirects to the dashboard', async ({ adminPage }) => {
    await adminPage.goto('/login');

    await expect(adminPage).toHaveURL('/');
    await new DashboardPage(adminPage).expectLoaded();
    await expect(new LoginPage(adminPage).form).toHaveCount(0);
  });
});

test.describe('AUTH-07 admin-only routes for a USER', () => {
  for (const route of ['/users', '/security']) {
    test(
      `${route} redirects a USER to the dashboard`,
      { tag: route === '/users' ? ['@smoke'] : [] },
      async ({ userPage, seededUser }) => {
        await userPage.goto(route);

        // adminGuard answers from GET /api/profile, then navigates to "/".
        await expect(userPage).toHaveURL('/');
        const dashboard = new DashboardPage(userPage);
        await dashboard.expectLoaded();
        await expect(dashboard.welcomeUsername).toContainText(seededUser.username);
        // Still logged in: a refused route is not a logout.
        expect((await storedSession(userPage)).username).toBe(seededUser.username);
      },
    );
  }

  test('the sidebar hides Users and Security from a USER and shows them to an admin', async ({
    adminPage,
    userPage,
  }) => {
    const user = new Shell(userPage);
    const admin = new Shell(adminPage);

    await Promise.all([new DashboardPage(userPage).goto(), new DashboardPage(adminPage).goto()]);

    await expect(user.sidebar.repositories).toBeVisible();
    await expect(user.sidebar.users).toHaveCount(0);
    await expect(user.sidebar.security).toHaveCount(0);
    // The control: the same layout does render the link for an admin (Security also needs a scanner).
    await expect(admin.sidebar.users).toBeVisible();
  });
});
