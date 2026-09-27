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
 * NAV-14..18 (RPS-1650, G25, the rest of the family `settings/repo-deleted.spec.ts` (SET-14) started): the thing
 * an open page shows is deleted, revoked or renamed by somebody else. What SET-14 covers for the repository
 * settings, these cover for the rest of the panel:
 *
 *  - NAV-14: a version deleted under its open detail page, on every protocol that can delete from there;
 *  - NAV-15: a package deleted under its open versions page, and a repository deleted under its open package
 *    list: the page answers "not found" once and stays alive (a click on a stale row is RPS-1625, PKG-09);
 *  - NAV-16: a deploy token revoked under the open token list;
 *  - NAV-17: a user deleted under the open Users page (Repsy OS only);
 *  - NAV-18: the signed-in user deleted while their session is open (Repsy OS only).
 *
 * Every case asserts the honest answer (an error toast, never a success one), that nothing is changed by
 * the stale click, and that the page is neither frozen nor broken. The toast is the by-design companion of
 * the 404, so each test allows it through the page-error fixture with its reason.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { allowNotFoundToast } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import type { PackageProtocol } from '../../../src/seed/packages.js';

const NAV = '@nav';
const PROTOCOLS = Object.keys(DESCRIPTORS) as PackageProtocol[];

test.describe('Stale pages: versions and packages', { tag: NAV }, () => {
  for (const protocol of PROTOCOLS) {
    const descriptor = DESCRIPTORS[protocol];
    if (!descriptor.levels.detail.delete) {
      continue;
    }

    test(`NAV-14: ${protocol}: Delete on a detail page whose version somebody else deleted says "not found" and nothing else`, async ({
      adminPage,
      seeder,
      seedVersions,
      pageErrors,
    }) => {
      allowNotFoundToast(pageErrors);
      const repo = await seeder.createRepo(
        RepoType[protocol.toUpperCase() as keyof typeof RepoType],
      );
      const [first, second] = await seedVersions(repo, ['1.0.0', '2.0.0']);
      const pages = protocolPages(adminPage, descriptor, repo.name);
      const detail = pages.detail(first);
      await detail.goto();
      await expect(detail.root).toBeVisible();

      // Somebody else deletes it (a second tab does what a second admin would).
      const other = await adminPage.context().newPage();
      try {
        const detailB = protocolPages(other, descriptor, repo.name).detail(first);
        await detailB.goto();
        await detailB.delete();
      } finally {
        await other.close();
      }

      // This page does not know: its Delete asks, and the answer is "not found", not "deleted".
      await detail.openDeleteDialog();
      await detail.dangerModal.confirm();
      await expect(detail.toasts.error(/not found/i).first()).toBeVisible();
      await expect(detail.toasts.success()).toHaveCount(0);
      await detail.dangerModal.expectClosed();
      await expect(detail.spinner.root).toBeHidden();

      // The other version is untouched, and is still there to open.
      const remaining = pages.detail(second);
      await remaining.goto();
      await expect(remaining.root).toBeVisible();
    });
  }

  test('NAV-15: the versions page of a package somebody else deleted says "not found" on refresh and stays usable', async ({
    adminPage,
    seeder,
    seedPackage,
    pageErrors,
  }) => {
    allowNotFoundToast(pageErrors);
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const versions = pages.versions(pkg);
    await versions.goto();
    await versions.expectRow(pkg);

    const other = await adminPage.context().newPage();
    try {
      const detailB = protocolPages(other, DESCRIPTORS.npm, repo.name).detail(pkg);
      await detailB.goto();
      await detailB.delete();
    } finally {
      await other.close();
    }

    await versions.refreshButton.click();
    await expect(versions.toasts.error(/not found/i).first()).toBeVisible();
    await expect(versions.toasts.success()).toHaveCount(0);
    await expect(versions.spinner.root).toBeHidden();
    await expect(versions.toolbar).toBeVisible();
    // The page is alive: the repository's list still loads and is empty.
    const list = pages.list();
    await list.goto();
    await list.expectNoRow(pkg);
  });

  // Pinned: the refresh that answered "not found" leaves the deleted package's rows on screen.
  test('NAV-15: after that refresh the versions page no longer lists the deleted package [known failure: RPS-1670]', async ({
    adminPage,
    seeder,
    seedPackage,
    pageErrors,
  }) => {
    test.fail(
      true,
      'RPS-1670: a list that could not be refreshed because its package is gone keeps showing the deleted versions',
    );
    allowNotFoundToast(pageErrors);
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const versions = pages.versions(pkg);
    await versions.goto();
    await versions.expectRow(pkg);

    const other = await adminPage.context().newPage();
    try {
      const detailB = protocolPages(other, DESCRIPTORS.npm, repo.name).detail(pkg);
      await detailB.goto();
      await detailB.delete();
    } finally {
      await other.close();
    }
    await versions.refreshButton.click();
    await expect(versions.toasts.error(/not found/i).first()).toBeVisible();
    await expect(versions.spinner.root).toBeHidden();
    await versions.expectNoRow(pkg);
  });

  test('NAV-15: the package list of a repository somebody else deleted says "Repository not found" on refresh and stays usable', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
    pageErrors,
  }) => {
    pageErrors.allowToast(
      'Repository not found',
      'by design: the repository is deleted under the open list and the refresh that follows is answered with 404',
    );
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    await list.expectRow(pkg);

    await panelApi.deleteRepo(repo.name);
    await list.refreshButton.click();
    await expect(list.toasts.error('Repository not found').first()).toBeVisible();
    await expect(list.toasts.success()).toHaveCount(0);
    await expect(list.spinner.root).toBeHidden();
    await expect(list.toolbar).toBeVisible();

    // The repository list, one click away in the sidebar, no longer has it.
    await adminPage.getByTestId('sidebar-link-repositories').click();
    const repos = new RepositoriesPage(adminPage);
    await expect(repos.title).toBeVisible();
    await repos.search(repo.name);
    await expect(repos.rows()).toHaveCount(0);
  });
});

test.describe('Stale pages: tokens and users', { tag: NAV }, () => {
  test('NAV-16: a deploy token revoked under the open list: Revoke and Rotate say "not found", a reload drops the row', async ({
    adminPage,
    seeder,
    pageErrors,
  }) => {
    pageErrors.allowToast(
      'Deploy token not found.',
      'by design: the token is revoked under the open list and the stale Revoke and Rotate are answered with 404',
    );
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const token = await seeder.createToken(repo.name, { name: `stale-${seeder.runId}` });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expect(settings.tokens.row(token.name)).toBeVisible();

    await seeder.revokeNow(repo.name, token.id);

    await settings.tokens.revokeButton(token.name).click();
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectError('Deploy token not found.');
    await expect(settings.shell.toasts.success()).toHaveCount(0);
    await settings.shell.dangerModal.expectClosed();

    // Rotate on the same stale row: the same honest answer, and no one-time secret is shown.
    await settings.tokens.rotateButton(token.name).click();
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectError('Deploy token not found.');
    await expect(settings.tokens.infoModal.root).toHaveCount(0);
    await expect(settings.shell.toasts.success()).toHaveCount(0);

    // The page still works: a token made now is listed, and after a reload the revoked one is gone.
    const fresh = await seeder.createToken(repo.name, { name: `fresh-${seeder.runId}` });
    await settings.reload();
    await expect(settings.tokens.row(fresh.name)).toBeVisible();
    await expect(settings.tokens.row(token.name)).toHaveCount(0);
  });

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'NAV-17: a user deleted under the open Users page: Edit, Reset password and Delete say "not found", refresh drops the row',
    { tag: ['@cloud-skip'] },
    async ({ adminPage, seededUser, panelApi, pageErrors }) => {
      pageErrors.allowToast(
        'User not found.',
        'by design: the user is deleted under the open Users page and the stale Edit, Reset and Delete are answered with 404',
      );
      const users = new UsersPage(adminPage);
      await users.goto();
      await users.search(seededUser.username);
      await expect(users.row(seededUser.username)).toBeVisible();

      await panelApi.deleteRepoUser(seededUser.id);

      // Edit: the save is refused, and the modal is still there to be cancelled (nothing was renamed).
      await users.openEdit(seededUser.username);
      await users.editModal.username.fill(`${seededUser.username}x`);
      await users.editModal.submit.click();
      await users.shell.toasts.expectError('User not found.');
      await expect(users.shell.toasts.success()).toHaveCount(0);
      await users.editModal.cancel.click();
      await expect(users.editModal.root).toHaveCount(0);

      // Reset password: no one-time password is shown for a user that is gone.
      await users.clickResetPassword(seededUser.username);
      await users.shell.dangerModal.confirm();
      await expect(users.resetPasswordModal.root).toHaveCount(0);
      await expect(users.shell.toasts.error('User not found.').first()).toBeVisible();

      // Delete: the same.
      await users.deleteUser(seededUser.username);
      await expect(users.shell.toasts.error('User not found.').first()).toBeVisible();
      await expect(users.shell.toasts.success()).toHaveCount(0);

      // Refresh: the list tells the truth again.
      await users.refresh();
      await users.search(seededUser.username);
      await expect(users.rows()).toHaveCount(0);
    },
  );

  // @cloud-skip: seeds a USER, and the Users page and its deletion are Repsy OS only.
  test(
    'NAV-18: the user whose account another admin deleted is sent to the login form on their next click, and Back does not bring the panel back',
    { tag: ['@cloud-skip'] },
    async ({ userPage, seededUser, panelApi, pageErrors }) => {
      pageErrors.allowToast(
        'Session invalid, please log in again.',
        'by design: the account is deleted while its session is open, and the next request answers 401',
      );
      const repos = new RepositoriesPage(userPage);
      await repos.goto();

      await panelApi.deleteRepoUser(seededUser.id);
      await repos.refreshButton.click();

      await expect(userPage).toHaveURL(
        (url) => url.pathname === '/login' && url.searchParams.get('returnUrl') === '/repositories',
      );
      await userPage.getByTestId('login-form').waitFor();
      // A reload, and Back, stay on the login form: no private page is shown from the dead session.
      await userPage.reload();
      await expect(userPage.getByTestId('login-form')).toBeVisible();
      await userPage.goBack();
      await expect(repos.title).toHaveCount(0);
      expect(await userPage.evaluate(() => window.localStorage.length)).toBe(0);
    },
  );
});
