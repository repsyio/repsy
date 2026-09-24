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
 * AUTH-08 .. AUTH-10 and AUTH-12: the session. Access tokens live 30 minutes and are not configurable, and the
 * backend only answers `sessionExpired` (the one answer that makes `RefreshTokenInterceptor` refresh)
 * for a really expired token, so a test cannot wait for, or forge, an expiry. The 401 is therefore the
 * one thing that is stubbed (`expireAccessToken`); the refresh call, the token rotation and the
 * logout on a refused refresh token all run against the real backend. Every other 401 (a tampered
 * token: `accessNotAllowed`) logs out without a refresh, and needs no stub.
 *
 * Refresh tokens are single-use with family revocation: a token consumed twice logs the whole family
 * out. Every test here has its own login (`adminPage`/`userPage` log in per test) and mutates only
 * its own page's `localStorage`. The SPA reads `localStorage` once at boot (`AuthService`), so a
 * change takes effect on the next load: `seedSession()` writes once per tab, so a reload keeps it.
 */
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { loginSession } from '../../../src/ui/session.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';

const REFRESH_PATH = '/api/auth/tokens/refresh';

function isRefreshResponse(response: { url(): string }): boolean {
  return new URL(response.url()).pathname === REFRESH_PATH;
}

/**
 * From now on the page's API calls carrying `Bearer <token>` are answered with the 401
 * `sessionExpired` the backend gives an expired access token. Calls with any other token (the one
 * a refresh hands out) and the `/api/auth/` calls themselves (the refresh) go to the real backend.
 */
async function expireAccessToken(page: Page, token: string): Promise<void> {
  await page.route(/\/api\/(?!auth\/)/, async (route) => {
    if (route.request().headers()['authorization'] !== `Bearer ${token}`) {
      await route.continue();
      return;
    }
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        msgId: 'sessionExpired',
        type: 'ERROR',
        data: 'sessionExpired',
        text: 'Session expired.',
      }),
    });
  });
}

test.describe('AUTH-08 expired access token (stubbed 401)', () => {
  test('is swapped through the refresh token without the user noticing', async ({ adminPage }) => {
    const dashboard = new DashboardPage(adminPage);

    await dashboard.goto();
    const before = await storedSession(adminPage);
    expect(before.token).toMatch(JWT_SHAPE);
    // A token the SPA holds at the next boot and the stubbed backend calls expired. It is a changed
    // value on purpose, so the storage assertion below proves the SPA stored the NEW access token
    // (two tokens minted in the same second are otherwise identical).
    const stale = `${before.token}-stale`;
    await adminPage.evaluate((token) => window.localStorage.setItem('token', token), stale);
    await expireAccessToken(adminPage, stale);

    // A new boot with the same storage: the dashboard's first API calls now hit the "expiry".
    const refreshed = adminPage.waitForResponse(isRefreshResponse);
    await adminPage.reload();
    const refreshResponse = await refreshed;
    expect(refreshResponse.status()).toBe(200);

    // The page is the dashboard, not a login form, and it has its data.
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(env.adminUsername);
    await expect(adminPage).toHaveURL('/');
    await expect(new LoginPage(adminPage).form).toHaveCount(0);

    // Storage holds a NEW pair (the refresh token rotates on every use) for the same user.
    const after = await storedSession(adminPage);
    expect(after.username).toBe(env.adminUsername);
    expect(after.token).toMatch(JWT_SHAPE);
    expect(after.refreshToken).toMatch(JWT_SHAPE);
    expect(after.token).not.toBe(stale);
    expect(after.refreshToken).not.toBe(before.refreshToken);
  });
});

test.describe('AUTH-08 tampered access token', () => {
  // The backend answers a token with a bad signature 401 `accessNotAllowed`. Such a token can never
  // become valid (and a refresh token signed by the same key would not either), so
  // `RefreshTokenInterceptor` logs out at once, without a refresh call (RPS-1279; before, every
  // call failed and the dashboard sat on "Loading..." with no toast and no logout). Nothing is
  // stubbed: the 401 is the real one.
  test('logs the user out with a toast, without a refresh call', async ({ adminPage }) => {
    const dashboard = new DashboardPage(adminPage);

    await dashboard.goto();
    const before = await storedSession(adminPage);
    const tampered = `${before.token}x`;
    await adminPage.evaluate((token) => window.localStorage.setItem('token', token), tampered);
    let refreshCalls = 0;
    adminPage.on('request', (request) => {
      if (new URL(request.url()).pathname === REFRESH_PATH) {
        refreshCalls += 1;
      }
    });

    await adminPage.reload();

    await expectLoggedOut(adminPage);
    await new Shell(adminPage).toasts.expectError('Session invalid, please log in again.');
    expect(refreshCalls).toBe(0);
  });
});

/** What a logged-out visitor sees: /login, an empty storage and a working login form. */
async function expectLoggedOut(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login$/);
  await expect(new LoginPage(page).submit).toBeVisible();
  expect(await storedSession(page)).toEqual(NO_SESSION);
}

test.describe('AUTH-09 refused refresh token', () => {
  /** Boots the dashboard logged in, then returns the page's stored session. */
  async function bootLoggedIn(page: Page) {
    await new DashboardPage(page).goto();
    const session = await storedSession(page);
    expect(session.token).toMatch(JWT_SHAPE);
    return session;
  }

  test('a refresh token that is not a token logs the user out', async ({ userPage }) => {
    const session = await bootLoggedIn(userPage);
    await userPage.evaluate(() => window.localStorage.setItem('refresh-token', 'not-a-token'));
    await expireAccessToken(userPage, session.token!);

    const refreshed = userPage.waitForResponse(isRefreshResponse);
    await userPage.reload();
    expect((await refreshed).status()).toBe(401);

    await expectLoggedOut(userPage);
  });

  test('a refresh token that was already used logs the user out', async ({
    openUiPage,
    seededUser,
  }) => {
    const page = await openUiPage({
      session: await loginSession(seededUser.username, seededUser.password),
    });
    const session = await bootLoggedIn(page);
    // Somebody else (another tab, a stolen copy) spends the refresh token first: single use.
    const spent = await page.request.post(`${env.apiBaseUrl}${REFRESH_PATH}`, {
      data: { refreshToken: session.refreshToken },
    });
    expect(spent.status()).toBe(200);
    await expireAccessToken(page, session.token!);

    const refreshed = page.waitForResponse(isRefreshResponse);
    await page.reload();
    expect((await refreshed).status()).toBe(401);

    await expectLoggedOut(page);
  });

  test('a refresh token the server reports as expired logs the user out', async ({ userPage }) => {
    const session = await bootLoggedIn(userPage);
    await expireAccessToken(userPage, session.token!);
    // What the backend answers for a refresh token past its lifetime (also 30 days, so not waitable).
    await userPage.route(`**${REFRESH_PATH}`, (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          msgId: 'refreshTokenExpired',
          type: 'ERROR',
          data: 'refreshTokenExpired',
          text: 'Refresh token expired.',
        }),
      }),
    );

    await userPage.reload();

    await expect(userPage).toHaveURL(/\/login$/);
    await expectLoggedOut(userPage);
    await new Shell(userPage).toasts.expectError('Session expired, please log in again.');
  });
});

/**
 * AUTH-12 (RPS-1284): "signed in, not allowed" is a 403 and "not signed in" a 401, and only the 401
 * ends the session. Every repository operation that needs MANAGE (the ADMIN role) answers a USER 403
 * `accessDenied`; it used to be 401 `unAuthorized`, the same status as a lost session, so the SPA
 * could not tell them apart. Nothing is stubbed: both answers are the real ones.
 */
test.describe('AUTH-12 permission failure is not a lost session', () => {
  test('a USER is answered 403 by a MANAGE route and stays signed in', async ({
    userPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const dashboard = new DashboardPage(userPage);
    await dashboard.goto();
    const before = await storedSession(userPage);
    expect(before.token).toMatch(JWT_SHAPE);
    const authorization = { Authorization: `Bearer ${before.token}` };
    const repoUrl = `${env.apiBaseUrl}/api/repos/${encodeURIComponent(repo.name)}`;

    // The route families that need MANAGE: usage, settings (read/write), deploy tokens, description,
    // rename and delete. The stack's default USER may read the repo (`/permissions`), not manage it.
    const refused = [
      await userPage.request.get(`${repoUrl}/usage`, { headers: authorization }),
      await userPage.request.get(`${repoUrl}/settings`, { headers: authorization }),
      await userPage.request.get(`${repoUrl}/deploy-tokens`, { headers: authorization }),
      await userPage.request.patch(`${repoUrl}/description`, {
        headers: authorization,
        data: { description: 'not allowed' },
      }),
      await userPage.request.delete(repoUrl, { headers: authorization }),
    ];
    for (const response of refused) {
      expect(response.status(), `${response.url()}`).toBe(403);
      expect(((await response.json()) as { msgId: string }).msgId).toBe('accessDenied');
    }
    expect(
      (await userPage.request.get(`${repoUrl}/permissions`, { headers: authorization })).ok(),
    ).toBe(true);

    // The refusals did not touch the session: same stored pair, and a reload is still the dashboard.
    expect(await storedSession(userPage)).toEqual(before);
    await userPage.reload();
    await dashboard.expectLoaded();
    await expect(userPage).toHaveURL('/');
    expect(await storedSession(userPage)).toEqual(before);
  });

  test('a token that is not valid is still a 401 on the same route, and logs the user out', async ({
    userPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await new DashboardPage(userPage).goto();
    const before = await storedSession(userPage);
    const tampered = `${before.token}x`;

    const response = await userPage.request.get(
      `${env.apiBaseUrl}/api/repos/${encodeURIComponent(repo.name)}/usage`,
      { headers: { Authorization: `Bearer ${tampered}` } },
    );
    expect(response.status()).toBe(401);
    // A msgId of its own, so nothing can mistake it for the permission failure above.
    expect(((await response.json()) as { msgId: string }).msgId).toBe('accessNotAllowed');

    await userPage.evaluate((token) => window.localStorage.setItem('token', token), tampered);
    await userPage.reload();

    await expectLoggedOut(userPage);
    await new Shell(userPage).toasts.expectError('Session invalid, please log in again.');
  });
});

test.describe('AUTH-10 logout', () => {
  const variants = [
    { name: 'the sidebar', logout: (shell: Shell) => shell.logoutViaSidebar() },
    { name: "the header's avatar menu", logout: (shell: Shell) => shell.logoutViaHeader() },
  ];

  for (const { name, logout } of variants) {
    test(`logging out through ${name} clears the session`, async ({ adminPage }) => {
      const shell = new Shell(adminPage);
      await new DashboardPage(adminPage).goto();
      expect((await storedSession(adminPage)).token).toMatch(JWT_SHAPE);

      await logout(shell);

      await expect(new LoginPage(adminPage).submit).toBeVisible();
      await expect(shell.sidebar.root).toHaveCount(0);
      expect(await storedSession(adminPage)).toEqual(NO_SESSION);

      // Logged out for real: a protected page does not come back, in this tab or after a reload.
      await adminPage.goto('/repositories');
      await expect(new LoginPage(adminPage).submit).toBeVisible();
      await expect(adminPage).toHaveURL(
        (url) => url.pathname === '/' && url.searchParams.get('returnUrl') === '/repositories',
      );
      expect(await storedSession(adminPage)).toEqual(NO_SESSION);
    });
  }
});
