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
 * USR-01 and USR-02: creating a user from the admin's users page.
 *
 * The admin acts (`usersPage`); the created user is only ever logged in as in a SECOND, anonymous
 * context. Users the UI creates are named from `seeder.reserveUsername()` (the `e2e-` prefix sweep
 * needs) and registered with `trackUiUser` before the submit, so a failure still cleans up.
 */
import type { Locator } from '@playwright/test';

import { password as runPassword } from '../../../src/seed/run-id.js';
import { UserRole } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import type {
  ConfirmValidator,
  PasswordValidator,
  UsernameValidator,
  UserCreateModal,
} from '../../../src/ui/pages/users.js';

test.describe('USR-01 create a user', () => {
  test('a created USER appears in the list and can log in from a fresh context', async ({
    usersPage,
    seeder,
    trackUiUser,
    panelApi,
    openUiPage,
  }) => {
    const username = seeder.reserveUsername();
    const pwd = runPassword(seeder.runId);
    trackUiUser(username);

    await usersPage.goto();
    await usersPage.createUser({ username, password: pwd });

    await usersPage.shell.toasts.expectSuccess('User created successfully.');
    await expect(usersPage.createModal.root).toBeHidden();

    // The list is refreshed by the panel; a search narrows it to the new user (newest first, but
    // parallel tests add users too, so never assert a position).
    await usersPage.search(username);
    await expect(usersPage.row(username)).toBeVisible();
    await expect(usersPage.role(username)).toHaveText('USER');
    const stored = (await panelApi.listUsers({ search: username })).find(
      (user) => user.username === username,
    );
    expect(stored?.role).toBe(UserRole.USER);

    // A second, anonymous context types the credentials into the real login form.
    const userTab = await openUiPage();
    const login = new LoginPage(userTab);
    await login.goto();
    await login.login(username, pwd);
    const dashboard = new DashboardPage(userTab);
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(username);
    // A USER has no Users entry in the navigation.
    await expect(new Shell(userTab).sidebar.repositories).toBeVisible();
    await expect(new Shell(userTab).sidebar.users).toHaveCount(0);
  });

  test('creating an Admin gives the new user the Users page', async ({
    usersPage,
    seeder,
    trackUiUser,
    panelApi,
    openUiPage,
  }) => {
    const username = seeder.reserveUsername();
    const pwd = runPassword(seeder.runId);
    trackUiUser(username);

    await usersPage.goto();
    await usersPage.openCreateModal();
    await expect(usersPage.createModal.roleLabel).toHaveText('User');
    await usersPage.createModal.fill({ username, password: pwd, admin: true });
    await expect(usersPage.createModal.roleLabel).toHaveText('Admin');
    await usersPage.createModal.submit.click();
    await usersPage.shell.toasts.expectSuccess('User created successfully.');

    await usersPage.search(username);
    await expect(usersPage.role(username)).toHaveText('ADMIN');
    const stored = (await panelApi.listUsers({ search: username })).find(
      (user) => user.username === username,
    );
    expect(stored?.role).toBe(UserRole.ADMIN);

    const adminTab = await openUiPage();
    const login = new LoginPage(adminTab);
    await login.goto();
    await login.login(username, pwd);
    await new DashboardPage(adminTab).expectLoaded();
    await expect(new Shell(adminTab).sidebar.users).toBeVisible();
  });

  test('cancelling the modal creates nothing and resets the form', async ({
    usersPage,
    seeder,
    trackUiUser,
    panelApi,
  }) => {
    const username = seeder.reserveUsername();
    trackUiUser(username);

    await usersPage.goto();
    await usersPage.openCreateModal();
    await usersPage.createModal.fill({ username, password: runPassword(seeder.runId) });
    await usersPage.createModal.cancel.click();
    await expect(usersPage.createModal.root).toBeHidden();

    // Reopened, the form is empty again.
    await usersPage.openCreateModal();
    await expect(usersPage.createModal.username).toHaveValue('');
    await expect(usersPage.createModal.password).toHaveValue('');
    await expect(usersPage.createModal.confirmPassword).toHaveValue('');

    expect(await panelApi.listUsers({ search: username })).toHaveLength(0);
  });
});

interface ValidationCase {
  name: string;
  input: (modal: UserCreateModal) => Locator;
  error: (modal: UserCreateModal) => Locator;
  /** What to type into the field; `''` only touches it (focus, then blur). */
  value: string;
  validator: string;
  /** The password typed first; the confirmation cases compare against it. Default: a valid one. */
  password?: string;
}

const VALID_PASSWORD = 'Valid-Pass1';

const username = (validator: UsernameValidator) => ({
  input: (modal: UserCreateModal) => modal.username,
  error: (modal: UserCreateModal) => modal.error('username', validator),
  validator,
});
const password = (validator: PasswordValidator) => ({
  input: (modal: UserCreateModal) => modal.password,
  error: (modal: UserCreateModal) => modal.error('password', validator),
  validator,
});
const confirmation = (validator: ConfirmValidator) => ({
  input: (modal: UserCreateModal) => modal.confirmPassword,
  error: (modal: UserCreateModal) => modal.error('confirm-password', validator),
  validator,
});

const CASES: ValidationCase[] = [
  { name: 'empty username', value: '', ...username('required') },
  { name: 'username under 3 characters', value: 'ab', ...username('minlength') },
  { name: 'username over 25 characters', value: 'a'.repeat(26), ...username('maxlength') },
  { name: 'username with a space and upper case', value: 'Bad Name', ...username('pattern') },
  { name: 'empty password', value: '', ...password('required') },
  { name: 'password under 6 characters', value: 'Ab1de', ...password('minlength') },
  { name: 'password over 50 characters', value: `Aa1${'x'.repeat(48)}`, ...password('maxlength') },
  { name: 'password without an upper-case letter', value: 'lower-only1', ...password('pattern') },
  { name: 'password without a digit', value: 'NoDigitsHere', ...password('pattern') },
  // With a password typed the empty confirmation is "required", not a mismatch (RPS-1282).
  { name: 'empty confirmation', value: '', ...confirmation('required') },
  {
    name: 'empty confirmation and empty password',
    value: '',
    password: '',
    ...confirmation('required'),
  },
  { name: 'confirmation that differs', value: 'Other-Pass1', ...confirmation('mismatch') },
];

test.describe('USR-02 create validation', () => {
  for (const testCase of CASES) {
    test(`${testCase.name} shows the ${testCase.validator} message and blocks the submit`, async ({
      usersPage,
    }) => {
      await usersPage.goto();
      await usersPage.openCreateModal();
      const modal = usersPage.createModal;

      // A valid password first: the confirmation cases compare against it, the others overwrite it.
      await modal.password.fill(testCase.password ?? VALID_PASSWORD);
      // Typing then blurring is what marks the field touched, which is what shows the message.
      await testCase.input(modal).fill(testCase.value);
      await testCase.input(modal).blur();

      // Keyed by validator, never by text.
      await expect(testCase.error(modal)).toBeVisible();
      await expect(modal.submit).toBeDisabled();
    });
  }

  test('an empty confirmation is required, not a mismatch, and turns into one once typed', async ({
    usersPage,
  }) => {
    await usersPage.goto();
    await usersPage.openCreateModal();
    const modal = usersPage.createModal;

    await modal.password.fill(VALID_PASSWORD);
    await modal.confirmPassword.fill('x');
    await modal.confirmPassword.blur();
    await expect(modal.error('confirm-password', 'mismatch')).toBeVisible();

    await modal.confirmPassword.fill('');
    await expect(modal.error('confirm-password', 'required')).toBeVisible();
    await expect(modal.error('confirm-password', 'mismatch')).toHaveCount(0);
  });

  test('the messages carry their text', async ({ usersPage }) => {
    await usersPage.goto();
    await usersPage.openCreateModal();
    const modal = usersPage.createModal;

    await modal.username.fill('ab');
    await modal.username.blur();
    await expect(modal.error('username', 'minlength')).toContainText(
      'Should be minimum 3 characters',
    );
    await modal.username.fill('Bad Name');
    await expect(modal.error('username', 'pattern')).toContainText(
      'Can contain only lowercase letters, digits, underscores and hyphens',
    );
    await modal.password.fill('NoDigitsHere');
    await modal.password.blur();
    await expect(modal.error('password', 'pattern')).toContainText(
      'Should contain at least 1 lowercase, 1 uppercase letter and 1 digit',
    );
    await modal.confirmPassword.fill('different');
    await modal.confirmPassword.blur();
    await expect(modal.error('confirm-password', 'mismatch')).toContainText(
      'Passwords do not match',
    );
  });

  // RPS-1261: the modal's template was saved double-encoded, so its bullet rendered as the three
  // characters "â€¢".
  test('the messages start with a bullet, not mojibake RPS-1261', async ({ usersPage }) => {
    await usersPage.goto();
    await usersPage.openCreateModal();
    const modal = usersPage.createModal;

    await modal.username.fill('ab');
    await modal.username.blur();
    await expect(modal.error('username', 'minlength')).toHaveText(
      '• Should be minimum 3 characters',
    );
    await modal.password.fill('Ab1de');
    await modal.password.blur();
    await expect(modal.error('password', 'minlength')).toHaveText(
      '• Should be minimum 6 characters',
    );
    await modal.confirmPassword.fill('x');
    await modal.confirmPassword.blur();
    await expect(modal.error('confirm-password', 'mismatch')).toHaveText(
      '• Passwords do not match',
    );
  });

  test('a valid form enables the submit button', async ({ usersPage, seeder }) => {
    await usersPage.goto();
    await usersPage.openCreateModal();
    const modal = usersPage.createModal;

    await expect(modal.submit).toBeDisabled();
    await modal.fill({ username: seeder.reserveUsername(), password: VALID_PASSWORD });
    await expect(modal.submit).toBeEnabled();
    // Breaking only the confirmation disables it again.
    await modal.confirmPassword.fill('Different-1');
    await expect(modal.submit).toBeDisabled();
  });

  test('a duplicate username is refused by the server and the modal stays open', async ({
    usersPage,
    seededUser,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.createUser({ username: seededUser.username, password: VALID_PASSWORD });

    await usersPage.shell.toasts.expectError(/in use/i);
    await expect(usersPage.createModal.root).toBeVisible();
    // Still exactly one user of that name.
    const matches = await panelApi.listUsers({ search: seededUser.username });
    expect(matches.filter((user) => user.username === seededUser.username)).toHaveLength(1);
  });
});
