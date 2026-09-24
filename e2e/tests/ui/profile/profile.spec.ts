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
 * PRO-01..03: the self-service profile page. Every test changes the credentials of, or deletes, the
 * account it is logged in as, so all of them use `userPage` (a seeded USER in its own context) and are
 * tagged `@credentials`: the foundation refuses `adminPage` for that tag, and `ProfilePage` refuses to
 * submit while logged in as the harness admin. The page is reached by a direct navigation (fewer
 * moving parts); PRO-04 covers the header link, which is a router link (RPS-1264).
 *
 * Each "did it work" check happens in a second, anonymous context that types the credentials into the
 * real login form, so the outcome is what the server accepts, not what the tab remembers.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { documentIsMarked, markDocument } from '../../../src/ui/document-marker.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { ProfilePage } from '../../../src/ui/pages/profile.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { currentUsername, loginSession } from '../../../src/ui/session.js';
import type { Page } from '@playwright/test';

const CREDENTIALS = { tag: ['@credentials'] };

/** Logs in through the login form in a new anonymous context and lands on the dashboard. */
async function loginThroughForm(
  openUiPage: () => Promise<Page>,
  username: string,
  password: string,
): Promise<DashboardPage> {
  const page = await openUiPage();
  const login = new LoginPage(page);
  await login.goto();
  await login.login(username, password);
  const dashboard = new DashboardPage(page);
  await dashboard.expectLoaded();
  return dashboard;
}

/** Tries to log in through the login form and asserts the panel refuses it. */
async function expectLoginRefused(
  openUiPage: () => Promise<Page>,
  username: string,
  password: string,
): Promise<void> {
  const page = await openUiPage();
  const login = new LoginPage(page);
  await login.goto();
  await login.login(username, password);
  await new Shell(page).toasts.expectError('Username or password is incorrect.');
  await expect(new DashboardPage(page).welcomeCard).toHaveCount(0);
  await expect(login.submit).toBeVisible();
}

test.describe('PRO-01 change password', () => {
  test(
    'a mismatch is refused, a match asks for confirmation, and only the new password logs in',
    CREDENTIALS,
    async ({ userPage, seededUser, seeder, openUiPage }) => {
      const profile = new ProfilePage(userPage);
      const newPassword = `E2e-${seeder.runId}-New1`;

      await profile.goto();
      // Pristine: nothing to submit.
      await expect(profile.passwordSubmit).toBeDisabled();

      // A confirmation that differs shows the mismatch message and keeps the submit disabled.
      await profile.fillPasswords(newPassword, `${newPassword}x`);
      await expect(profile.passwordError('password-confirmation', 'mismatch')).toBeVisible();
      await expect(profile.passwordSubmit).toBeDisabled();

      // Matching enables it; the confirmation modal can still be cancelled without changing anything.
      await profile.submitPasswordChange(newPassword);
      await profile.shell.dangerModal.cancel();
      await profile.shell.dangerModal.expectClosed();
      expect((await loginSession(seededUser.username, seededUser.password)).username).toBe(
        seededUser.username,
      );

      // Confirmed: success toast, the form resets, and this tab stays logged in.
      await profile.changePassword(newPassword);
      await profile.shell.toasts.expectSuccess('Your password updated successfully');
      await expect(profile.newPassword).toHaveValue('');
      await expect(profile.passwordConfirmation).toHaveValue('');
      await expect(profile.passwordSubmit).toBeDisabled();
      await expect(userPage).toHaveURL(/\/profile$/);
      expect(await currentUsername(userPage)).toBe(seededUser.username);

      // Re-login: the new password works, the old one is refused.
      const dashboard = await loginThroughForm(openUiPage, seededUser.username, newPassword);
      await expect(dashboard.welcomeUsername).toContainText(seededUser.username);
      await expectLoginRefused(openUiPage, seededUser.username, seededUser.password);
    },
  );

  test('the password fields validate their input', CREDENTIALS, async ({ userPage }) => {
    const profile = new ProfilePage(userPage);
    await profile.goto();

    await profile.newPassword.fill('Ab1de');
    await profile.newPassword.blur();
    await expect(profile.passwordError('new-password', 'minlength')).toBeVisible();

    await profile.newPassword.fill('alllowercase1');
    await expect(profile.passwordError('new-password', 'pattern')).toBeVisible();

    await profile.newPassword.fill('');
    await expect(profile.passwordError('new-password', 'required')).toBeVisible();

    await profile.newPassword.fill(`Aa1${'x'.repeat(48)}`);
    await expect(profile.passwordError('new-password', 'maxlength')).toBeVisible();
    await expect(profile.passwordSubmit).toBeDisabled();

    await profile.passwordConfirmation.fill('');
    await profile.passwordConfirmation.blur();
    await expect(profile.passwordError('password-confirmation', 'required')).toBeVisible();

    // The eye buttons reveal what was typed.
    await expect(profile.newPassword).toHaveAttribute('type', 'password');
    await profile.toggleVisibility(profile.newPasswordToggle);
    await expect(profile.newPassword).toHaveAttribute('type', 'text');
  });
});

test.describe('PRO-02 change username', () => {
  test(
    'the page reloads as the new name and the repository URLs keep working',
    CREDENTIALS,
    async ({ userPage, seededUser, seeder, openUiPage }) => {
      const profile = new ProfilePage(userPage);
      const renamed = seeder.reserveUsername();
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const token = await seeder.createToken(repo.name);
      const repoRoot = async () =>
        (
          await fetch(`${env.repoBaseUrl}/${repo.name}/`, {
            headers: {
              Authorization: `Basic ${Buffer.from(`e2e-probe:${token.token}`).toString('base64')}`,
            },
          })
        ).status;

      await profile.goto();
      // Unchanged: nothing to submit, no warning.
      await expect(profile.username).toHaveValue(seededUser.username);
      await expect(profile.usernameSubmit).toBeDisabled();
      await expect(profile.usernameWarning).toHaveCount(0);
      expect(await repoRoot()).toBe(200);

      // Editing shows the warning about repository URLs; the confirmation can be cancelled.
      await profile.submitUsernameChange(renamed);
      await expect(profile.usernameWarning).toBeVisible();
      await profile.shell.dangerModal.cancel();
      await profile.shell.dangerModal.expectClosed();
      expect(await currentUsername(userPage)).toBe(seededUser.username);

      // Confirmed: the SPA reloads with the new tokens and the new name.
      await profile.changeUsername(renamed);
      await expect(profile.username).toHaveValue(renamed);
      expect(await currentUsername(userPage)).toBe(renamed);
      const dashboard = new DashboardPage(userPage);
      await dashboard.goto();
      await expect(dashboard.welcomeUsername).toContainText(renamed);

      // Same account (the seeder cleans it up by id), new name; the old name is gone.
      const stored = await seeder.adoptUserByUsername(renamed);
      expect(stored).toBe(seededUser.id);
      await expectLoginRefused(openUiPage, seededUser.username, seededUser.password);
      await loginThroughForm(openUiPage, renamed, seededUser.password);

      // The repository is untouched: its protocol URL still answers, and the panel still opens it.
      expect(await repoRoot()).toBe(200);
      await userPage.goto(`/${repo.name}`);
      await expect(userPage).toHaveURL(new RegExp(`/${repo.name}$`));
      await expect(userPage.getByTestId('pkg-toolbar')).toBeVisible();
    },
  );

  test('the username field validates its input', CREDENTIALS, async ({ userPage }) => {
    const profile = new ProfilePage(userPage);
    await profile.goto();

    await profile.username.fill('Bad Name');
    await profile.username.blur();
    await expect(profile.usernameError('pattern')).toBeVisible();
    await expect(profile.usernameSubmit).toBeDisabled();

    await profile.username.fill('ab');
    await expect(profile.usernameError('minlength')).toBeVisible();

    await profile.username.fill('a'.repeat(26));
    await expect(profile.usernameError('maxlength')).toBeVisible();

    await profile.username.fill('');
    await expect(profile.usernameError('required')).toBeVisible();
    await expect(profile.usernameSubmit).toBeDisabled();
  });

  test(
    'a taken username is refused by the server and nothing changes',
    CREDENTIALS,
    async ({ userPage, seededUser, seeder }) => {
      const other = await seeder.createUser();
      const profile = new ProfilePage(userPage);
      await profile.goto();

      await profile.submitUsernameChange(other.username);
      await profile.shell.dangerModal.confirm();

      await profile.shell.toasts.expectError(/in use/i);
      expect(await currentUsername(userPage)).toBe(seededUser.username);
      await expect(userPage).toHaveURL(/\/profile$/);
    },
  );
});

test.describe('PRO-03 delete account', () => {
  test(
    'the account is deleted, the session ends and the login fails',
    CREDENTIALS,
    async ({ userPage, seededUser, panelApi, openUiPage }) => {
      const profile = new ProfilePage(userPage);
      await profile.goto();

      // Cancelling keeps the account.
      await profile.requestAccountDeletion();
      await profile.shell.dangerModal.cancel();
      await profile.shell.dangerModal.expectClosed();
      expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(1);

      await profile.requestAccountDeletion();
      await profile.shell.dangerModal.confirm();
      await profile.shell.toasts.expectSuccess('Account deleted successfully.');

      // Logged out: the login page, and the stored session is gone.
      await expect(userPage).toHaveURL(/\/login$/);
      await expect(new LoginPage(userPage).submit).toBeVisible();
      expect(await currentUsername(userPage)).toBeNull();

      expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(0);
      await expectLoginRefused(openUiPage, seededUser.username, seededUser.password);
    },
  );
});

test.describe('PRO-04 header Profile link (RPS-1264)', () => {
  // A router link: no document load (the SPA keeps its state, the splash does not come back) and the
  // dropdown closes. It used to be a raw relative `href`, i.e. a full reload of the whole app.
  const origins = [
    { name: 'the dashboard', open: async (page: Page) => new DashboardPage(page).goto() },
    {
      name: 'the repository list',
      open: async (page: Page) => new RepositoriesPage(page).goto(),
    },
  ];

  for (const { name, open } of origins) {
    test(`from ${name} it navigates without reloading and closes the menu`, async ({
      userPage,
      seededUser,
    }) => {
      const shell = new Shell(userPage);
      await open(userPage);
      await markDocument(userPage);

      await shell.openAvatarMenu();
      await shell.header.profile.click();

      const profile = new ProfilePage(userPage);
      await expect(profile.title).toBeVisible();
      await expect(profile.username).toHaveValue(seededUser.username);
      await expect(userPage).toHaveURL('/profile');
      await expect(shell.header.menu).toHaveCount(0);
      expect(await documentIsMarked(userPage)).toBe(true);
    });
  }

  test('from a repository page it still goes to /profile', async ({ userPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const shell = new Shell(userPage);
    await userPage.goto(`/${repo.name}`);
    await expect(userPage.getByTestId('breadcrumb')).toBeVisible();
    await markDocument(userPage);

    await shell.openAvatarMenu();
    await shell.header.profile.click();

    await expect(userPage).toHaveURL('/profile');
    await expect(new ProfilePage(userPage).title).toBeVisible();
    expect(await documentIsMarked(userPage)).toBe(true);
  });
});
