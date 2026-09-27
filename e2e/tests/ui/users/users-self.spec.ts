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
 * RPS-1622 G05: an admin acting on their OWN row of `/users`, with a second seeded admin present so
 * the "last admin" guard (`cannotDeleteLastAdminUser`/`cannotDemoteLastAdminUser`, RPS-1246) never
 * blocks the action. `assertNotAdmin` only refuses the harness admin's own username
 * (`env.adminUsername`), so a SEEDED admin resetting, demoting or deleting their own row goes through
 * the same code paths `users-reset-password.spec.ts`/`users-edit-delete.spec.ts` already cover for
 * acting on ANOTHER user; this file is the missing "acting on yourself" side.
 *
 * Every test here seeds two ADMIN accounts and logs in as the FIRST one in its own context (never
 * `adminPage`/`usersPage`, which are the harness admin): that account is both the actor and the target.
 * With the harness admin and the second seeded admin also ADMIN, the real admin count is at least
 * three, so a search scoped to the run's own users (which shows only the two seeded admins) is never
 * mistaken for "the last admin" by the panel (RPS-1246; see `users-edit-delete.spec.ts`'s own note on
 * why the REAL last-admin state cannot be reached from these specs).
 *
 * Resetting or demoting or deleting YOUR OWN row is, code-wise, exactly the credential-ending event
 * `tests/ui/auth/revocation.spec.ts` (G04) already covers from a second tab's point of view; here the
 * acting page is the SAME page whose privileges just changed, so what is asserted is what happens to
 * the very next thing that page does (the automatic list reload the edit/delete flow triggers, or the
 * next navigation), not a second, independent session.
 */
import { UserRole } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { loginSession } from '../../../src/ui/session.js';
import { NO_SESSION, storedSession } from '../auth/stored-session.js';

const SESSION_EXPIRED_TOAST = 'Session expired, please log in again.';
const SESSION_INVALID_TOAST = 'Session invalid, please log in again.';

// @cloud-skip: the admin Users page exists on Repsy OS only (target.ui.hasUsersPage).
test.describe(
  'RPS-1622 G05 an admin acting on their own row of /users',
  { tag: ['@cloud-skip'] },
  () => {
    test.use({
      allowedPageErrors: errorToasts(
        'by design: acting on your own row ends or narrows the very session doing the acting',
        SESSION_EXPIRED_TOAST,
        SESSION_INVALID_TOAST,
        'Access Denied. Please check your credentials.',
      ),
    });

    test('the last-admin warning does not fire on your own row while another admin exists', async ({
      seeder,
      openUiPage,
    }) => {
      const self = await seeder.createUser({ role: UserRole.ADMIN });
      await seeder.createUser({ role: UserRole.ADMIN });
      const page = await openUiPage({ session: await loginSession(self.username, self.password) });
      const usersPage = new UsersPage(page);

      await usersPage.goto();
      await usersPage.search(self.username);
      await usersPage.openEdit(self.username);

      await expect(usersPage.editModal.lastAdminWarning).toHaveCount(0);
      await expect(usersPage.editModal.roleSwitch).toBeEnabled();
      await usersPage.editModal.cancel.click();
      await expect(usersPage.editModal.root).toBeHidden();

      // Cancelling touched nothing: still an admin, still signed in.
      await usersPage.search(self.username);
      await expect(usersPage.role(self.username)).toHaveText('ADMIN');
    });

    test('resetting your own password succeeds, then your own session ends on the next request', async ({
      seeder,
      openUiPage,
    }) => {
      const self = await seeder.createUser({ role: UserRole.ADMIN });
      await seeder.createUser({ role: UserRole.ADMIN });
      const page = await openUiPage({ session: await loginSession(self.username, self.password) });
      const usersPage = new UsersPage(page);

      await usersPage.goto();
      await usersPage.search(self.username);
      await usersPage.resetPassword(self.username);
      await usersPage.shell.toasts.expectSuccess('Password reset successfully');
      await expect(usersPage.resetPasswordModal.subject).toHaveText(self.username);
      const newPassword = await usersPage.resetPasswordModal.secret();
      expect(newPassword).not.toBe(self.password);
      await usersPage.resetPasswordModal.dismiss();

      // The request that reset the password ran on the tokens issued BEFORE the reset (still valid when
      // it was sent); `UserTxService.resetUserPassword` bumped `token_version` in that same request, so
      // the NEXT one - the list refresh the search box fires - is the acting admin's own session dying,
      // exactly the mechanics revocation.spec.ts pins for a second tab (`sessionExpired`, a refused
      // refresh, then a logout with the same toast).
      await usersPage.search(self.username);
      await expect(page).toHaveURL(/\/login(\?.*)?$/);
      await new Shell(page).toasts.expectError(SESSION_EXPIRED_TOAST);
      await expect(new LoginPage(page).submit).toBeVisible();
      expect(await storedSession(page)).toEqual(NO_SESSION);

      // The new password is real and logs in.
      const dashboard = await (async () => {
        const fresh = await openUiPage();
        const login = new LoginPage(fresh);
        await login.goto();
        await login.login(self.username, newPassword);
        const page2 = new DashboardPage(fresh);
        await page2.expectLoaded();
        return page2;
      })();
      await expect(dashboard.welcomeUsername).toContainText(self.username);
    });

    test('demoting yourself keeps you signed in; the list refresh it triggers is refused, not a logout', async ({
      seeder,
      openUiPage,
    }) => {
      const self = await seeder.createUser({ role: UserRole.ADMIN });
      await seeder.createUser({ role: UserRole.ADMIN });
      const page = await openUiPage({ session: await loginSession(self.username, self.password) });
      const usersPage = new UsersPage(page);

      await usersPage.goto();
      await usersPage.search(self.username);
      await usersPage.openEdit(self.username);
      await expect(usersPage.editModal.lastAdminWarning).toHaveCount(0);
      await expect(usersPage.editModal.roleSwitch).toBeEnabled();
      await usersPage.editModal.roleToggle.click();
      await expect(usersPage.editModal.roleSwitch).not.toBeChecked();

      // The edit flow reloads the list right after a successful submit (see
      // users-edit-delete.spec.ts): that reload is GET /api/users, ADMIN-gated
      // (`UserController#list`), and the role it now reads for `self` is USER. It answers 403
      // accessDenied, which `RefreshTokenInterceptor` never touches (RPS-1284): the session survives.
      await usersPage.editModal.submit.click();
      await usersPage.shell.toasts.expectSuccess('User updated successfully');
      await usersPage.shell.toasts.expectError('Access Denied. Please check your credentials.');

      // Still signed in as `self`, just no longer an admin: the dashboard (no role requirement) loads
      // fine, and a direct visit to /users bounces to "/" instead of ending the session.
      await new DashboardPage(page).goto();
      await expect(page).not.toHaveURL(/\/login/);
      await page.goto('/users');
      await expect(page).toHaveURL('/');
      await expect(new DashboardPage(page).welcomeCard).toBeVisible();
    });

    test('deleting yourself succeeds, then your own session ends on the next request', async ({
      seeder,
      openUiPage,
    }) => {
      const self = await seeder.createUser({ role: UserRole.ADMIN });
      const other = await seeder.createUser({ role: UserRole.ADMIN });
      const page = await openUiPage({ session: await loginSession(self.username, self.password) });
      const usersPage = new UsersPage(page);

      await usersPage.goto();
      await usersPage.search(self.username);
      await usersPage.deleteUser(self.username);
      await usersPage.shell.toasts.expectSuccess('User deleted successfully');

      // The delete flow's own list reload runs as the now-deleted user: `unAuthorized`, not
      // `sessionExpired`, so `RefreshTokenInterceptor` logs out at once with no refresh attempt (the
      // same "unAuthorized" mechanics revocation.spec.ts pins for an admin deleting someone else).
      await expect(page).toHaveURL(/\/login(\?.*)?$/);
      await new Shell(page).toasts.expectError(SESSION_INVALID_TOAST);
      await expect(new LoginPage(page).submit).toBeVisible();
      expect(await storedSession(page)).toEqual(NO_SESSION);

      // `other` (the second seeded admin, untouched) still exists and still logs in.
      const session = await loginSession(other.username, other.password);
      expect(session.username).toBe(other.username);
    });
  },
);
