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
 * to `/?returnUrl=<the route>`, and `/` renders the login form IN PLACE (`AuthRedirectComponent`), so
 * the path is `/`, not `/login`: these tests assert what the visitor sees. A login there follows the
 * session at once (RPS-1278) and returns the visitor to the requested route. `/login` for a logged-in
 * user bounces to `/` (`AuthRedirectGuard`), and `adminGuard` bounces a non-admin from `/users` and
 * `/security` to `/`.
 */
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { documentIsMarked, markDocument } from '../../../src/ui/document-marker.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { NO_SESSION, storedSession } from './stored-session.js';

test.describe('AUTH-05 anonymous visitor', () => {
  const guardedRoutes = ['/repositories', '/users', '/security', '/profile'];

  for (const route of guardedRoutes) {
    test(`${route} shows the login form`, async ({ page }) => {
      await page.goto(route);

      await expectLoginInPlace(page, route);
      await expect(page).toHaveURL(returnsTo(route));
    });
  }

  test('a repository page shows the login form', async ({ page, seeder }) => {
    // A repository that exists, so it is the guard that stops the visitor, not a 404.
    const repo = await seeder.createRepo(RepoType.MAVEN);

    await page.goto(`/${repo.name}`);

    await expectLoginInPlace(page, `/${repo.name}`);
    await expect(page).toHaveURL(returnsTo(`/${repo.name}`));
  });

  test('logging in from the redirected form returns to the requested page', async ({
    page,
    seededUser,
  }) => {
    await page.goto('/repositories');
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();
    // A marker that a document load (a reload, a full navigation) would wipe: RPS-1278 needed one.
    await markDocument(page);

    await login.login(seededUser.username, seededUser.password);

    await expect(page).toHaveURL('/repositories');
    await expect(new RepositoriesPage(page).title).toBeVisible();
    await expect(login.form).toHaveCount(0);
    expect((await storedSession(page)).username).toBe(seededUser.username);
    expect(await documentIsMarked(page)).toBe(true);
  });

  test('logging in from the redirected form of a repository returns to that repository', async ({
    page,
    seededUser,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await page.goto(`/${repo.name}`);
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();

    await login.login(seededUser.username, seededUser.password);

    await expect(page).toHaveURL(`/${repo.name}`);
    await expect(login.form).toHaveCount(0);
    await expect(new Shell(page).sidebar.root).toBeVisible();
  });

  test('logging in from the bare form at "/" opens the dashboard without a reload', async ({
    page,
    seededUser,
  }) => {
    await page.goto('/');
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();
    await markDocument(page);

    await login.login(seededUser.username, seededUser.password);

    // The router is already at "/", so nothing navigates: the form has to follow the session itself.
    const dashboard = new DashboardPage(page);
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(seededUser.username);
    await expect(login.form).toHaveCount(0);
    await expect(page).toHaveURL('/');
    expect(await documentIsMarked(page)).toBe(true);
  });

  // The value is read from the address bar, so it is attacker-controlled: only an in-app path may
  // be followed, anything else is ignored and the visitor lands on the dashboard.
  const unsafeReturnUrls = [
    'https://evil.example/phish',
    '//evil.example/phish',
    '/\\evil.example/phish',
    'javascript:alert(1)',
  ];
  for (const returnUrl of unsafeReturnUrls) {
    test(`a returnUrl of ${returnUrl} is ignored (no open redirect)`, async ({
      page,
      seededUser,
      baseURL,
    }) => {
      await page.goto(`/?returnUrl=${encodeURIComponent(returnUrl)}`);
      const login = new LoginPage(page);
      await expect(login.submit).toBeVisible();

      await login.login(seededUser.username, seededUser.password);

      const dashboard = new DashboardPage(page);
      await dashboard.expectLoaded();
      // Still this app, on its root.
      await expect(page).toHaveURL(
        (url) => url.origin === new URL(baseURL!).origin && url.pathname === '/',
      );
      await expect(login.form).toHaveCount(0);
    });
  }

  test('a returnUrl to an admin-only page is still guarded for a USER', async ({
    page,
    seededUser,
  }) => {
    await page.goto('/users');
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();

    await login.login(seededUser.username, seededUser.password);

    // adminGuard bounces the USER from /users to "/".
    await expect(page).toHaveURL('/');
    await new DashboardPage(page).expectLoaded();
    await expect(login.form).toHaveCount(0);
  });
});

/** The address of the in-place login form: "/", with the requested `route` remembered by `AuthGuard`. */
const returnsTo = (route: string) => (url: URL) =>
  url.pathname === '/' && url.searchParams.get('returnUrl') === route;

/** The visitor sees the login form (and nothing of the panel), whatever the address. */
async function expectLoginInPlace(page: Page, route: string): Promise<void> {
  await expect(new LoginPage(page).submit).toBeVisible();
  await expect(page).toHaveURL(returnsTo(route));
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

  // RPS-1456: "/" renders the dashboard inside the OnPush AuthRedirectComponent, and the sidebar takes
  // its role from its own GET /api/profile. The dashboard asks for the profile too, so two answers come
  // back; whichever arrives last on a slow host used to leave the sidebar without Users until the next
  // change detection (the nightly run on a CI runner). Holding back one answer at a time, after
  // everything else has arrived, pins both orders.
  for (const heldBack of [1, 2]) {
    test(`the admin sidebar shows Users when profile answer ${heldBack} of 2 arrives last`, async ({
      adminPage,
    }) => {
      let asked = 0;
      await adminPage.route('**/api/profile', async (route) => {
        asked += 1;
        if (asked === heldBack) {
          await new Promise((resolve) => setTimeout(resolve, 1_500));
        }
        await route.fallback();
      });
      const admin = new Shell(adminPage);

      await new DashboardPage(adminPage).goto();

      await expect(admin.sidebar.users).toBeVisible();
      await expect(admin.sidebar.repositories).toBeVisible();
      expect(asked).toBe(2);
    });
  }
});
