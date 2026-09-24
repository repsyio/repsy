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
 * A11Y-05..08: the dialog semantics and the labelling of the panel's forms (RPS-1266, parts 2 and 3).
 *
 * Every modal is a `dialog` (`alertdialog` for the confirmation) that is modal and named by its title;
 * focus moves into it when it opens, Tab and Shift+Tab stay inside it, Escape closes it and focus goes
 * back to the button that opened it. The forms label every control (`getByLabel` works, `label for`
 * points at its own control) and no element id occurs twice on a page, so the create-token modal that
 * sits over the rename form of `/:repo/settings` no longer steals its labels.
 *
 * The password "eyes" are Font Awesome glyphs and the harness blocks the CDN, so they have no box and a
 * real click is refused: like the other UI tests these dispatch the click. What is asserted here is
 * their accessible name and `aria-pressed`, which follow the state.
 */
import type { Locator, Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { securityBadgeIn, SecurityModal } from '../../../src/ui/pages/security.js';
import {
  Severity,
  severityCounts,
  stubRepoSecurityDetail,
  stubRepoSecuritySummary,
  stubSupportedRepoTypes,
} from '../../../src/ui/security-stubs.js';
import { UsersPage } from '../../../src/ui/pages/users.js';

const insideOf = (dialog: Locator): Promise<boolean> =>
  dialog.evaluate((element) => element.contains(document.activeElement));

interface DialogExpectation {
  /** The accessible name: the dialog's title. */
  name: string | RegExp;
  role?: 'dialog' | 'alertdialog';
  /** The button that opened it: focus returns to it when the dialog closes. */
  opener?: Locator;
}

/**
 * The whole contract of one open modal: role, `aria-modal`, name, focus inside, a Tab cycle that never
 * leaves it, and Escape (which closes it and hands focus back to the opener).
 */
async function expectDialogContract(
  page: Page,
  dialog: Locator,
  { name, role = 'dialog', opener }: DialogExpectation,
): Promise<void> {
  await expect(dialog).toHaveAttribute('role', role);
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  await expect(dialog).toHaveAccessibleName(name);

  await expect.poll(() => insideOf(dialog), { message: 'focus moves into the dialog' }).toBe(true);

  // More presses than the dialog has controls: a leak would show as focus outside on some press.
  for (let press = 0; press < 14; press++) {
    await page.keyboard.press('Tab');
    expect(await insideOf(dialog), `Tab #${press + 1} stays inside`).toBe(true);
  }
  for (let press = 0; press < 14; press++) {
    await page.keyboard.press('Shift+Tab');
    expect(await insideOf(dialog), `Shift+Tab #${press + 1} stays inside`).toBe(true);
  }

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  if (opener) {
    await expect(opener, 'focus returns to the opener').toBeFocused();
  }
}

/** Ids that occur more than once in the document, and `label[for]` that point at nothing usable. */
async function idProblems(page: Page): Promise<{ duplicates: string[]; brokenLabels: string[] }> {
  return page.evaluate(() => {
    const seen = new Map<string, number>();
    for (const element of Array.from(document.querySelectorAll('[id]'))) {
      seen.set(element.id, (seen.get(element.id) ?? 0) + 1);
    }
    const duplicates = Array.from(seen)
      .filter(([, count]) => count > 1)
      .map(([id]) => id);
    const brokenLabels = Array.from(document.querySelectorAll('label[for]'))
      .filter((label) => {
        const target = document.getElementById(label.getAttribute('for') ?? '');
        return !target || !/^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName);
      })
      .map((label) => label.getAttribute('for') ?? '');
    return { duplicates, brokenLabels };
  });
}

test.describe('Dialogs: semantics, focus and keyboard', { tag: '@a11y' }, () => {
  test('A11Y-05: the create-repository modal', async ({ adminPage }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    const modal = await repos.openCreateModal();

    await expectDialogContract(adminPage, modal.root, {
      name: 'Create Repository',
      opener: repos.createButton,
    });
  });

  test('A11Y-05: the create-token modal', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const modal = await settings.tokens.openCreateModal();

    await expectDialogContract(adminPage, modal.root, {
      name: 'Create Deploy Token',
      opener: settings.tokens.createButton,
    });
  });

  test('A11Y-05: the user create, edit, reset and delete dialogs', async ({
    adminPage,
    seededUser,
  }) => {
    const users = new UsersPage(adminPage);
    await users.goto();
    await users.search(seededUser.username);

    await users.openCreateModal();
    await expectDialogContract(adminPage, users.createModal.root, {
      name: 'Create User',
      opener: users.createButton,
    });

    // The edit item lives in a row menu that closes on choice: there is no opener to return to.
    await users.openEdit(seededUser.username);
    await expectDialogContract(adminPage, users.editModal.root, { name: 'Edit User' });

    // The confirmation is an alertdialog; the key button that opened it gets the focus back.
    const keyButton = users.list.inRow(seededUser.username, 'row-reset-password');
    await users.clickResetPassword(seededUser.username);
    await expectDialogContract(adminPage, users.shell.dangerModal.root, {
      name: /Reset Password/,
      role: 'alertdialog',
      opener: keyButton,
    });

    // Confirming it shows the one-time secret, which is a dialog of its own. The list reloads
    // meanwhile and re-renders the key button, so there is no element left to give the focus back to.
    await users.resetPassword(seededUser.username);
    await expectDialogContract(adminPage, users.resetPasswordModal.root, {
      name: `New password for ${seededUser.username}`,
    });
  });

  test('A11Y-05: the delete-repository confirmation is an alertdialog that starts on Cancel', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await settings.deleteRepo.deleteButton.click();

    const dialog = adminPage.getByTestId('danger-modal');
    await expect(dialog).toHaveAttribute('role', 'alertdialog');
    await expect(dialog).toHaveAccessibleDescription(/Do you want to delete repository\?/i);
    await expect(adminPage.getByTestId('danger-modal-cancel')).toBeFocused();
    await expectDialogContract(adminPage, dialog, {
      name: 'Delete Repository',
      role: 'alertdialog',
      opener: settings.deleteRepo.deleteButton,
    });
    // Escape cancelled it: the repository is still there.
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/settings`));
  });

  test('A11Y-05: the config modal of a package list', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const list = protocolPages(adminPage, DESCRIPTORS.maven, repo.name).list();
    await list.goto();
    await list.configureButton.click();

    await expectDialogContract(adminPage, adminPage.getByTestId('config-modal'), {
      name: /Maven Configuration/,
      opener: list.configureButton,
    });
  });

  test(
    'A11Y-05: the security modal, portaled to the body',
    { tag: '@mocked' },
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
      await stubRepoSecuritySummary(adminPage, {
        [repo.name]: { severity: Severity.HIGH, scanned: true },
      });
      await stubRepoSecurityDetail(adminPage, repo.name, {
        ...severityCounts([Severity.HIGH]),
        recentScans: [],
      });
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      await repos.search(repo.name);
      const badge = securityBadgeIn(repos.row(repo.name));
      await badge.click();
      const modal = new SecurityModal(adminPage, 'repo');
      await modal.expectOpen();

      await expectDialogContract(adminPage, modal.root, {
        name: `${repo.name} — Security`,
        opener: badge,
      });
      // Still a child of the body (the row underneath is not its parent), and the row did not open.
      await expect(adminPage).toHaveURL(/\/repositories$/);
    },
  );

  test('A11Y-05: the backdrop is not a control and a click on it still closes the modal', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    const modal = await repos.openCreateModal();
    const backdrop = adminPage.getByTestId('repo-create-backdrop');

    expect(await backdrop.evaluate((element) => element.tagName)).toBe('DIV');
    await expect(backdrop).toHaveAttribute('aria-hidden', 'true');
    await expect(adminPage.getByRole('button', { name: /^$/ })).toHaveCount(0);

    await backdrop.click({ position: { x: 5, y: 5 } });
    await expect(modal.root).toHaveCount(0);
  });

  test('A11Y-05: a modal keeps Enter-to-submit', async ({ adminPage, seeder }) => {
    const repos = new RepositoriesPage(adminPage);
    const name = seeder.reserveRepoName(RepoType.DOCKER);
    seeder.adoptRepo(name);
    await repos.goto();
    const modal = await repos.openCreateModal();

    // The name field has the focus already: type and press Enter.
    await expect(modal.nameInput).toBeFocused();
    await modal.nameInput.fill(name);
    await repos.afterListResponse(() => adminPage.keyboard.press('Enter'));

    await repos.toasts.expectSuccess('Repository created successfully');
    await expect(modal.root).toHaveCount(0);
  });
});

test.describe('Forms: labels, names and unique ids', { tag: '@a11y' }, () => {
  test('A11Y-06: ids are unique and labels resolve on /:repo/settings with the token modal open', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const modal = await settings.tokens.openCreateModal();

    expect(await idProblems(adminPage)).toEqual({ duplicates: [], brokenLabels: [] });

    // Each label reaches ITS control: the modal's "Name" is the token name, not the rename input.
    await modal.root.getByLabel('Name *', { exact: true }).fill('token-by-label');
    await expect(modal.name).toHaveValue('token-by-label');
    await expect(settings.info.renameInput).not.toHaveValue('token-by-label');
    await modal.root.getByLabel('Username', { exact: true }).fill('user-by-label');
    await expect(modal.username).toHaveValue('user-by-label');
    await modal.root.getByLabel('Description', { exact: true }).fill('text-by-label');
    await expect(modal.description).toHaveValue('text-by-label');
    await expect(settings.info.descriptionInput).not.toHaveValue('text-by-label');
    await expect(modal.root.getByRole('radiogroup', { name: 'Access Type' })).toBeVisible();
    await expect(modal.root.getByLabel('Expiration Date')).toBeVisible();
    // The rename form's own labels still reach it.
    await expect(adminPage.getByLabel('New Repository Name')).toHaveCount(1);
  });

  test('A11Y-06: the create-repository form is labelled', async ({ adminPage }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    const repoModal = await repos.openCreateModal();
    expect(await idProblems(adminPage)).toEqual({ duplicates: [], brokenLabels: [] });
    await repoModal.root.getByLabel('Name *', { exact: true }).fill('by-label');
    await expect(repoModal.nameInput).toHaveValue('by-label');
    await repoModal.root.getByLabel('Description', { exact: true }).fill('by-label');
    // The type selector is named by its label and its value.
    await expect(repoModal.root.getByTestId('selector-toggle')).toHaveAccessibleName(/^Type \w+/);
  });

  test('A11Y-06: the create-user form is labelled', async ({ adminPage }) => {
    const users = new UsersPage(adminPage);
    await users.goto();
    await users.openCreateModal();
    expect(await idProblems(adminPage)).toEqual({ duplicates: [], brokenLabels: [] });
    const modal = users.createModal;
    await modal.root.getByLabel('Username *', { exact: true }).fill('by-label');
    await expect(modal.username).toHaveValue('by-label');
    await modal.root.getByLabel('Password *', { exact: true }).fill('Passw0rd-x');
    await expect(modal.password).toHaveValue('Passw0rd-x');
    await modal.root.getByLabel('Confirm Password *', { exact: true }).fill('Passw0rd-x');
    await expect(modal.confirmPassword).toHaveValue('Passw0rd-x');
    // The role switch is named "Role" plus its state.
    await expect(modal.roleSwitch).toHaveAccessibleName('Role User');
  });

  test('A11Y-06: the login form is labelled and its eye follows the state', async ({ page }) => {
    const login = new LoginPage(page);
    await login.goto();

    expect(await idProblems(page)).toEqual({ duplicates: [], brokenLabels: [] });
    await page.getByLabel('Username', { exact: true }).fill('by-label');
    await expect(login.username).toHaveValue('by-label');
    await page.getByLabel('Password', { exact: true }).fill('by-label');
    await expect(login.password).toHaveValue('by-label');

    await expect(login.passwordToggle).toHaveAccessibleName('Show password');
    await expect(login.passwordToggle).toHaveAttribute('aria-pressed', 'false');
    await login.passwordToggle.dispatchEvent('click');
    await expect(login.passwordToggle).toHaveAccessibleName('Hide password');
    await expect(login.passwordToggle).toHaveAttribute('aria-pressed', 'true');
  });

  test('A11Y-07: icon-only controls have names', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const token = await seeder.createToken(repo.name);

    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await expect(repos.refreshButton).toHaveAccessibleName('Refresh');

    const list = protocolPages(adminPage, DESCRIPTORS.maven, repo.name).list();
    await list.goto();
    await expect(list.refreshButton).toHaveAccessibleName('Refresh');

    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expect(settings.pgp.addButton).toHaveAccessibleName('Add keyserver');
    await expect(settings.tokens.rotateButton(token.name)).toHaveAccessibleName(
      `Rotate deploy token ${token.name}`,
    );
    await expect(settings.tokens.configureButton(token.name)).toHaveAccessibleName(
      `Configure deploy token ${token.name}`,
    );
    await expect(settings.tokens.revokeButton(token.name)).toHaveAccessibleName(
      `Revoke deploy token ${token.name}`,
    );

    // Every modal's X is named.
    const modal = await settings.tokens.openCreateModal();
    await expect(modal.closeButton).toHaveAccessibleName('Close dialog');
  });

  test('A11Y-07: the eyes of the create-user and reset-password modals follow the state', async ({
    adminPage,
    seededUser,
  }) => {
    const users = new UsersPage(adminPage);
    await users.goto();
    await users.search(seededUser.username);
    await users.openCreateModal();
    const eye = adminPage.getByTestId('user-create-password-toggle');
    const confirmEye = adminPage.getByTestId('user-create-confirm-password-toggle');

    await expect(eye).toHaveAccessibleName('Show password');
    await expect(confirmEye).toHaveAccessibleName('Show password confirmation');
    await eye.dispatchEvent('click');
    await expect(eye).toHaveAccessibleName('Hide password');
    await expect(eye).toHaveAttribute('aria-pressed', 'true');
    await expect(confirmEye).toHaveAttribute('aria-pressed', 'false');
    await adminPage.keyboard.press('Escape');

    await users.resetPassword(seededUser.username);
    await expect(users.resetPasswordModal.toggle).toHaveAccessibleName('Show password');
    await users.resetPasswordModal.reveal();
    await expect(users.resetPasswordModal.toggle).toHaveAccessibleName('Hide password');
    // The secret input is labelled, not just placed under a heading.
    await expect(users.resetPasswordModal.value).toHaveAccessibleName('New Password');
  });
});
