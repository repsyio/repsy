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
 * RPS-1622 G04 + G06: an open second session (page A) must not outlive a credential-ending event that
 * happens elsewhere (page B), and a copied refresh token must not outlive a logout.
 *
 * G04. `UserTxService` bumps a user's `token_version` on a password change (own or an admin's reset)
 * and on a username change, never on a role change alone (`updateUserDetails` only calls
 * `revokeRefreshTokens()` inside the `if (!user.getUsername().equals(dto.getUsername()))` branch), and
 * a deletion removes the row `PanelAuthHelper.authenticateSession` looks up. Every panel request reads
 * the CURRENT `token_version` and role from the database (`PanelTokenClaims.issuedTo`,
 * `PanelAuthHelper.requireAdmin`), never from the JWT, so:
 *
 *  - a password change, an admin's reset or a deletion answers page A's very next request 401
 *    (`sessionExpired` for the first two, `unAuthorized` for a deletion: `AuthController`,
 *    `AuthUserService`), which `RefreshTokenInterceptor` turns into a real refresh attempt (itself
 *    refused, `refreshTokenExpired`, since the refresh token carries the OLD `token_version` too) and a
 *    logout, exactly the mechanics `tests/ui/auth/session.spec.ts` (AUTH-09) already pins for a stubbed
 *    401 or a stolen refresh token; here the 401 is real and the trigger is a real credential change
 *    made from a second, independent login. No `expireAccessToken` stub is needed: the access token is
 *    invalid for real from the moment the version moves, not only after its 30-minute lifetime.
 *  - a role-only change (a demotion that keeps the username, what the Users page's edit modal does)
 *    is NOT one of those events: page A's session and its access token stay valid (RPS-1284,
 *    `tests/ui/auth/session.spec.ts` AUTH-12, "a permission failure is not a lost session"). What ends
 *    is the demoted admin's OWN access to admin-only pages, enforced fresh on every request
 *    (`adminGuard` re-reads the profile on every navigation): a session does not have to end for a
 *    demotion to take effect at once. The ticket's premise ("session dies" for all four triggers) does
 *    not hold for a same-username demotion; this file pins the real, current, deliberate behaviour
 *    instead of a false assumption, and documents the difference next to it.
 *
 * G06. `POST /api/auth/logout` (added by this ticket) revokes the whole refresh-token family, so a
 * refresh token copied before logout (a leaked one, or one left in another tab's memory) answers
 * `refreshTokenExpired` afterwards, the same way a replayed one already does
 * (`RefreshTokenService.consume`/`revoke`, `AuthControllerIT$Logout`).
 */
import { UserRole } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { ProfilePage } from '../../../src/ui/pages/profile.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { env } from '../../../src/env.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { REFRESH_PATH, loginSession } from '../../../src/ui/session.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';
import { allowLists } from '../../../src/ui/page-errors.js';
import type { Page } from '@playwright/test';

const SESSION_EXPIRED_TOAST = 'Session expired, please log in again.';
const SESSION_INVALID_TOAST = 'Session invalid, please log in again.';

/** Reloads `page` (its next request carries the pre-change tokens) and asserts it lands on /login. */
async function expectSessionEndedOnNextRequest(page: Page, toast: string | RegExp): Promise<void> {
  await page.reload();
  await expect(page).toHaveURL(/\/login(\?.*)?$/);
  await new Shell(page).toasts.expectError(toast);
  await expect(new LoginPage(page).submit).toBeVisible();
  await expect(new DashboardPage(page).welcomeCard).toHaveCount(0);
  expect(await storedSession(page)).toEqual(NO_SESSION);
}

// @cloud-skip: drives the OS Users page and the OS self-service Profile page (see the file comment).
test.describe(
  'RPS-1622 G04 a second open session ends when a credential-ending event happens elsewhere',
  { tag: ['@cloud-skip'] },
  () => {
    test.use({
      allowedPageErrors: allowLists(
        errorToasts(
          'by design: the acting page reloads with tokens the change just invalidated',
          SESSION_EXPIRED_TOAST,
          SESSION_INVALID_TOAST,
        ),
        {
          entries: [
            {
              pattern: /^Failed to load user role:/,
              reason:
                'by design: the sidebar asks for the profile with the refused token, right before the session ends (same as session.spec.ts AUTH-08/AUTH-12)',
            },
          ],
        },
      ),
    });

    test('password change: a second tab of the SAME user logs the other tab out', async ({
      seededUser,
      openUiPage,
    }) => {
      // Two independent logins of the same account: single-use refresh tokens mean the two pages must
      // never share one session (session.ts).
      const pageA = await openUiPage({
        session: await loginSession(seededUser.username, seededUser.password),
      });
      const pageB = await openUiPage({
        session: await loginSession(seededUser.username, seededUser.password),
      });
      await new DashboardPage(pageA).goto();
      await new DashboardPage(pageB).goto();

      const profileB = new ProfilePage(pageB);
      const newPassword = `E2e-${seededUser.username}-New1`;
      await profileB.goto();
      await profileB.changePassword(newPassword);
      await profileB.shell.toasts.expectSuccess('Your password updated successfully');

      // Page B (which made the change) stayed on the new tokens; page A did not and dies on its next call.
      await expectSessionEndedOnNextRequest(pageA, SESSION_EXPIRED_TOAST);
      await expect(pageB).toHaveURL(/\/profile$/);
      expect((await storedSession(pageB)).token).toMatch(JWT_SHAPE);
    });

    test("an admin password reset ends the user's other open session", async ({
      seededUser,
      openUiPage,
      adminPage,
    }) => {
      const pageA = await openUiPage({
        session: await loginSession(seededUser.username, seededUser.password),
      });
      await new DashboardPage(pageA).goto();

      const usersPage = new UsersPage(adminPage);
      await usersPage.goto();
      await usersPage.search(seededUser.username);
      await usersPage.resetPassword(seededUser.username);
      await usersPage.shell.toasts.expectSuccess('Password reset successfully');

      await expectSessionEndedOnNextRequest(pageA, SESSION_EXPIRED_TOAST);
    });

    test("an admin deleting the user ends the user's other open session", async ({
      seededUser,
      openUiPage,
      adminPage,
    }) => {
      const pageA = await openUiPage({
        session: await loginSession(seededUser.username, seededUser.password),
      });
      await new DashboardPage(pageA).goto();

      const usersPage = new UsersPage(adminPage);
      await usersPage.goto();
      await usersPage.search(seededUser.username);
      await usersPage.deleteUser(seededUser.username);
      await usersPage.shell.toasts.expectSuccess('User deleted successfully');

      // Deletion answers "unAuthorized", not "sessionExpired": the interceptor logs out at once,
      // with no refresh attempt (RefreshTokenInterceptor's doc comment, "every other 401").
      await expectSessionEndedOnNextRequest(pageA, SESSION_INVALID_TOAST);
    });

    test('a same-username demotion leaves the demoted admin signed in, only admin pages refuse it', async ({
      seeder,
      openUiPage,
      adminPage,
    }) => {
      const targetAdmin = await seeder.createUser({ role: UserRole.ADMIN });
      const pageA = await openUiPage({
        session: await loginSession(targetAdmin.username, targetAdmin.password),
      });
      const dashboardA = new DashboardPage(pageA);
      await dashboardA.goto();
      await expect(new Shell(pageA).sidebar.users).toBeVisible();
      const before = await storedSession(pageA);

      const usersPage = new UsersPage(adminPage);
      await usersPage.goto();
      await usersPage.search(targetAdmin.username);
      await usersPage.openEdit(targetAdmin.username);
      await expect(usersPage.editModal.username).toHaveValue(targetAdmin.username);
      await usersPage.editModal.roleToggle.click();
      await expect(usersPage.editModal.roleSwitch).not.toBeChecked();
      await usersPage.editModal.submit.click();
      await usersPage.shell.toasts.expectSuccess('User updated successfully');

      // The session is untouched: same tokens, dashboard reloads with no toast and no redirect.
      await pageA.reload();
      await dashboardA.expectLoaded();
      await expect(pageA).toHaveURL('/');
      expect(await storedSession(pageA)).toEqual(before);

      // The demoted admin no longer reaches an admin-only page: `adminGuard` re-reads the (now fresh,
      // USER) role on every navigation and sends them back to "/", not to "/login" (RPS-1284: a
      // permission failure is not a lost session).
      await pageA.goto('/users');
      await expect(pageA).toHaveURL('/');
      await dashboardA.expectLoaded();
      expect(await storedSession(pageA)).toEqual(before);
    });
  },
);

test.describe('RPS-1622 G06 logout revokes the refresh token family', () => {
  test('a refresh token copied before logout no longer works afterwards', async ({
    seededUser,
    openUiPage,
  }) => {
    const page = await openUiPage({
      session: await loginSession(seededUser.username, seededUser.password),
    });
    await new DashboardPage(page).goto();
    const copiedRefreshToken = (await storedSession(page)).refreshToken!;
    expect(copiedRefreshToken).toMatch(JWT_SHAPE);

    await new Shell(page).logoutViaSidebar();
    expect(await storedSession(page)).toEqual(NO_SESSION);

    const replayed = await page.request.post(`${env.apiBaseUrl}${REFRESH_PATH}`, {
      data: { refreshToken: copiedRefreshToken },
    });
    expect(replayed.status()).toBe(401);
    expect(((await replayed.json()) as { msgId: string }).msgId).toBe('refreshTokenExpired');
  });

  test('logging out calls the backend: POST /api/auth/logout answers loggedOut', async ({
    seededUser,
    openUiPage,
  }) => {
    const page = await openUiPage({
      session: await loginSession(seededUser.username, seededUser.password),
    });
    await new DashboardPage(page).goto();

    const logoutResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/auth/logout',
    );
    await new Shell(page).logoutViaSidebar();
    const response = await logoutResponse;

    expect(response.status()).toBe(200);
    expect(((await response.json()) as { msgId: string }).msgId).toBe('loggedOut');
  });
});
