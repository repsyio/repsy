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
 * USR-04: an admin resets a seeded user's password. The admin acts (`usersPage`), the reset user is
 * only ever logged in as in a second, anonymous context; the harness admin's own credentials are
 * never touched (`resetPassword` refuses the admin's row). Not tagged `@credentials`: that tag is for
 * tests that change the credentials of the account they are logged in as.
 */
import { loginSession } from '../../../src/ui/session.js';
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { Shell } from '../../../src/ui/pages/shell.js';

/** What the panel's own login form accepts (login.component.ts): the generated password must match. */
const LOGIN_PASSWORD = /^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\S+$).{6,50}$/;

test.describe('USR-04 reset password', () => {
  test('the one-time modal shows the new password; it logs in and the old one does not', async ({
    usersPage,
    seededUser,
    openUiPage,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.clickResetPassword(seededUser.username);
    await usersPage.shell.dangerModal.confirm();

    await usersPage.shell.toasts.expectSuccess('Password reset successfully');
    const modal = usersPage.resetPasswordModal;
    await modal.expectOpen();
    await expect(modal.subject).toHaveText(seededUser.username);

    // Masked until revealed; the value is a fresh password, not the seeded one.
    await expect(modal.value).toHaveAttribute('type', 'password');
    const newPassword = await modal.secret();
    expect(newPassword).not.toBe(seededUser.password);
    expect(newPassword).toMatch(LOGIN_PASSWORD);
    await modal.reveal();
    await expect(modal.value).toHaveValue(newPassword);

    // Shown once: closing removes the modal, and its input, from the page.
    await modal.dismiss();

    const fresh = await openUiPage();
    const freshLogin = new LoginPage(fresh);
    await freshLogin.goto();
    await freshLogin.login(seededUser.username, newPassword);
    const dashboard = new DashboardPage(fresh);
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(seededUser.username);

    const stale = await openUiPage();
    const staleLogin = new LoginPage(stale);
    await staleLogin.goto();
    await staleLogin.login(seededUser.username, seededUser.password);
    await new Shell(stale).toasts.expectError('Username or password is incorrect.');
    await expect(new DashboardPage(stale).welcomeCard).toHaveCount(0);
    await expect(staleLogin.submit).toBeVisible();
  });

  test('cancelling the confirmation resets nothing', async ({ usersPage, seededUser }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.clickResetPassword(seededUser.username);
    await usersPage.shell.dangerModal.cancel();

    await usersPage.shell.dangerModal.expectClosed();
    await expect(usersPage.resetPasswordModal.root).toBeHidden();
    // The old password still logs in (a fresh API login, no UI needed).
    const session = await loginSession(seededUser.username, seededUser.password);
    expect(session.username).toBe(seededUser.username);
  });
});
