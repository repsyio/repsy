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
 * AUTH-13: one browser, two tabs, one session (RPS-1621). The session lives in `localStorage`, shared by
 * every tab, but each tab keeps its own in-memory copy of the tokens, and a refresh token is SINGLE USE:
 * the backend revokes the whole token family when a spent one comes back (`RefreshTokenService.consume`).
 * A tab that refreshed with its stale copy after another tab had refreshed therefore logged BOTH tabs
 * out. `AuthService` now adopts what another tab stores (the `storage` event), refreshes under a
 * cross-tab lock (`navigator.locks`, or a `localStorage` one on a plain-HTTP origin such as this
 * suite's `http://repsy:8080`, which has no Web Locks) and re-reads the storage inside the lock.
 *
 * As in `session.spec.ts` the access-token expiry is the one stubbed thing (`expireAccessToken`); the
 * refresh, the rotation and the family revocation are the real backend's. Every test starts with two
 * pages of ONE context (they share `localStorage`) that were BOTH opened before anything rotated:
 * `seedSession()` seeds a new tab with the original tokens, which would overwrite a rotated pair.
 */
import type { Page } from '@playwright/test';

import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { loginRoute } from '../../../src/ui/routes.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import {
  countRefreshCalls,
  delayRefreshAnswers,
  expireAccessToken,
  isRefreshResponse,
  setStoredSessionValue,
  withoutWebLocks,
} from '../../../src/ui/session.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';

/**
 * Counts the requests of a page; the returned function resolves once none is in flight and none started for
 * `QUIET_MS` (not `networkidle`: the lint rule `playwright/no-networkidle` forbids it).
 */
const QUIET_MS = 500;

function trackRequests(page: Page): () => Promise<void> {
  const state = { inFlight: 0, lastActivity: Date.now() };
  const finished = (): void => {
    state.inFlight = Math.max(0, state.inFlight - 1);
    state.lastActivity = Date.now();
  };
  page.on('request', () => {
    state.inFlight += 1;
    state.lastActivity = Date.now();
  });
  page.on('requestfinished', finished);
  page.on('requestfailed', finished);
  return async () => {
    await expect
      .poll(() => state.inFlight === 0 && Date.now() - state.lastActivity >= QUIET_MS, {
        timeout: 30_000,
      })
      .toBe(true);
  };
}

/** Opens the dashboard in `first` and in a second tab of the same context; both hold the login's tokens. */
async function openTwoTabs(first: Page): Promise<{ tabA: Page; tabB: Page }> {
  const tabB = await first.context().newPage();
  const quietA = trackRequests(first);
  const quietB = trackRequests(tabB);
  await new DashboardPage(first).goto();
  await new DashboardPage(tabB).goto();
  // The dashboard shows its welcome card before its per-repository usage calls are all out. One that fires after the
  // test planted a stale token makes the page that is about to be reloaded refresh with the real refresh token; the
  // reload kills that request after the server spent the token, the reloaded tab spends it again and the server
  // revokes the whole family (a flake on loaded CI runners, RPS-2025). So wait until both tabs are quiet.
  await quietA();
  await quietB();
  return { tabA: first, tabB };
}

test.describe('AUTH-13 two tabs, one session', () => {
  test('a tab whose access token expired adopts the pair the other tab refreshed', async ({
    adminPage,
  }) => {
    const { tabA, tabB } = await openTwoTabs(adminPage);
    const before = await storedSession(tabA);
    expect(before.token).toMatch(JWT_SHAPE);

    // Tab A's access token expires: it refreshes (the real refresh, the token rotates). The wait keeps the
    // new access token from being minted in the same second as the old one, which would make them equal.
    await tabA.waitForTimeout(1100);
    const stale = `${before.token}-stale`;
    await setStoredSessionValue(tabA, 'token', stale);
    await expireAccessToken(tabA, stale);
    const refreshed = tabA.waitForResponse(isRefreshResponse);
    await tabA.reload();
    expect((await refreshed).status()).toBe(200);
    await new DashboardPage(tabA).expectLoaded();
    const after = await storedSession(tabA);
    expect(after.refreshToken).toMatch(JWT_SHAPE);
    expect(after.refreshToken).not.toBe(before.refreshToken);
    expect(after.token).not.toBe(before.token);

    // Tab B is still the page that booted with the OLD pair, and the old access token is one the backend
    // now calls expired. Before RPS-1621 B refreshed with the spent refresh token: the backend revoked the
    // family and logged both tabs out. B has to use the new pair, not call the refresh at all.
    await expireAccessToken(tabB, before.token!);
    const refreshCallsOfB = countRefreshCalls(tabB);
    const repos = new RepositoriesPage(tabB);
    await repos.afterListResponse(() => new Shell(tabB).sidebar.repositories.click());

    await expect(repos.title).toBeVisible();
    await expect(tabB).toHaveURL('/repositories');
    expect(refreshCallsOfB.count).toBe(0);
    expect(await storedSession(tabB)).toEqual(after);

    // Nobody was logged out: tab A carries on, and its refresh token is alive (the family was not revoked).
    await tabA.reload();
    await new DashboardPage(tabA).expectLoaded();
    expect(await storedSession(tabA)).toEqual(after);
  });

  // Web Locks exist only in a secure context: `localhost`, which the suite's stack is served from, has them, a
  // plain-HTTP install on any other host does not. `AuthService` locks with a `localStorage` entry there.
  //
  // RPS-1672: the Web Locks variant used to be pinned to fail on Firefox. Firefox replicates `localStorage`
  // between tabs asynchronously, so a tab that had just been granted the Web Lock could still read its own
  // spent refresh token for a few milliseconds (a probe found 17 of 20 first reads stale there, none in
  // Chromium or WebKit); the second tab then refreshed with the already-spent token and the backend's
  // single-use rotation revoked the whole family, logging both tabs out. `AuthService` now backs that
  // re-read up with an explicit `BroadcastChannel` hand-over of the rotated pair instead of relying on
  // `localStorage` replication timing.
  for (const { name, hasWebLocks } of [
    { name: 'Web Locks', hasWebLocks: true },
    { name: 'no Web Locks (plain HTTP)', hasWebLocks: false },
  ]) {
    test(`two tabs whose access tokens expire together make one refresh between them (${name})`, async ({
      adminPage,
    }) => {
      await withoutWebLocks(adminPage.context(), !hasWebLocks);
      const { tabA, tabB } = await openTwoTabs(adminPage);
      expect(await tabA.evaluate(() => 'locks' in navigator && !!navigator.locks)).toBe(
        hasWebLocks,
      );
      const before = await storedSession(tabA);
      const stale = `${before.token}-stale`;
      await setStoredSessionValue(tabA, 'token', stale);
      // Both tabs boot with the same expired token and the same refresh token. The refresh answers are held
      // back, so the two refreshes overlap for certain: the second would spend the first one's token.
      const refreshCalls = [countRefreshCalls(tabA), countRefreshCalls(tabB)];
      for (const tab of [tabA, tabB]) {
        await expireAccessToken(tab, stale);
        await delayRefreshAnswers(tab, 500);
      }

      await Promise.all([tabA.reload(), tabB.reload()]);

      await new DashboardPage(tabA).expectLoaded();
      await new DashboardPage(tabB).expectLoaded();
      // The dashboard shows before its data is in; wait for the rotated pair itself.
      await expect
        .poll(async () => (await storedSession(tabA)).refreshToken, { timeout: 15_000 })
        .not.toBe(before.refreshToken);
      expect(refreshCalls[0].count + refreshCalls[1].count).toBe(1);
      const after = await storedSession(tabA);
      expect(await storedSession(tabB)).toEqual(after);
      // The family is alive: the pair in storage still refreshes and both tabs still load their data.
      await tabA.reload();
      await new DashboardPage(tabA).expectLoaded();
      await tabB.reload();
      await new DashboardPage(tabB).expectLoaded();
    });
  }

  test('logging out in one tab logs the other out too, and a new login returns to its page', async ({
    adminPage,
  }) => {
    const { tabA, tabB } = await openTwoTabs(adminPage);
    const repos = new RepositoriesPage(tabB);
    await repos.goto();

    await new Shell(tabA).logoutViaSidebar();

    await expect(new LoginPage(tabA).submit).toBeVisible();
    await expect(new LoginPage(tabB).submit).toBeVisible();
    await expect(tabB).toHaveURL(
      (url) => url.pathname === '/login' && url.searchParams.get('returnUrl') === '/repositories',
    );
    expect(await storedSession(tabB)).toEqual(NO_SESSION);
    await expect(new Shell(tabB).sidebar.root).toHaveCount(0);
  });

  test('logging in in one tab signs the other tab in', async ({ page }) => {
    // The other way round: a visitor with two tabs on the login form signs in in one of them.
    const tabB = await page.context().newPage();
    await page.goto(loginRoute());
    await tabB.goto(loginRoute());
    await expect(new LoginPage(page).submit).toBeVisible();
    await expect(new LoginPage(tabB).submit).toBeVisible();

    await new LoginPage(page).login(env.adminUsername, env.adminPassword);

    // "/" shows the login form or the dashboard by the session, live (AuthRedirectComponent, RPS-1278).
    await new DashboardPage(page).expectLoaded();
    await new DashboardPage(tabB).expectLoaded();
    await expect(new LoginPage(tabB).submit).toBeHidden();
    const signedIn = await storedSession(page);
    expect(signedIn.token).toMatch(JWT_SHAPE);
    expect(await storedSession(tabB)).toEqual(signedIn);
  });
});
