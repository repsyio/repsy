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
 * A11Y-01: an axe-core scan of the login page, the dashboard, the repository list, a repository's
 * settings page and the admin users page, of the open modals and, for every protocol, of its package
 * list, group/scope list, versions, manifests and detail pages (`src/ui/a11y.ts`). Each test attaches its
 * axe findings (`axe-<page>.json`, `axe-<page>.txt`) to the report and prints one summary line. Since
 * RPS-1266 part 4 the default mode is `enforce`: a serious or critical violation fails the test (run
 * with `REPSY_UI_OPT_IN=a11y-report` to only report while looking at a page that is not clean yet).
 *
 * Every scan runs after the page's own ready element is on screen (the layout hides the router outlet
 * for 500 ms behind a splash), on the state a user sees: at least one repository of the test's own
 * exists, so the list and settings pages hold data.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import type { PackageProtocol } from '../../../src/seed/packages.js';
import { scanPage } from '../../../src/ui/a11y.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { DESCRIPTORS, pageOf, type ProtocolListPage } from '../../../src/ui/pages/protocol.js';
import type { LevelName } from '../../../src/ui/pages/protocols/types.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { UsersPage } from '../../../src/ui/pages/users.js';

test.describe('Accessibility (axe)', { tag: '@a11y' }, () => {
  test('A11Y-01: login page', async ({ page }, testInfo) => {
    await new LoginPage(page).goto();
    const summary = await scanPage(page, testInfo, 'login');
    expect(summary.label).toBe('login');
  });

  test('A11Y-01: dashboard', async ({ adminPage, seeder }, testInfo) => {
    await seeder.createRepo(RepoType.MAVEN);
    const dashboard = new DashboardPage(adminPage);
    await dashboard.goto();
    await expect(dashboard.recentActivity).toBeVisible();
    const summary = await scanPage(adminPage, testInfo, 'dashboard');
    expect(summary.label).toBe('dashboard');
  });

  test('A11Y-01: repository list', async ({ adminPage, seeder }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await expect(repos.row(repo.name)).toBeVisible();
    const summary = await scanPage(adminPage, testInfo, 'repositories');
    expect(summary.label).toBe('repositories');
  });

  test('A11Y-01: repository settings', async ({ adminPage, seeder }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await new RepoSettingsPage(adminPage, repo.name).goto();
    const summary = await scanPage(adminPage, testInfo, 'settings');
    expect(summary.label).toBe('settings');
  });

  test('A11Y-01: users page (admin)', async ({ adminPage, seededUser }, testInfo) => {
    const users = new UsersPage(adminPage);
    await users.goto();
    await users.search(seededUser.username);
    await expect(users.row(seededUser.username)).toBeVisible();
    const summary = await scanPage(adminPage, testInfo, 'users');
    expect(summary.label).toBe('users');
  });

  // The page scans above cannot see an open modal: scan the dialog itself (RPS-1266, parts 2 and 3).
  test('A11Y-01: create-repository modal', async ({ adminPage }, testInfo) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    const modal = await repos.openCreateModal();
    await expect(modal.root).toBeVisible();
    const summary = await scanPage(
      adminPage,
      testInfo,
      'modal-repo-create',
      '[data-testid="repo-create-modal"]',
    );
    expect(summary.label).toBe('modal-repo-create');
  });

  test('A11Y-01: create-token modal', async ({ adminPage, seeder }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await settings.tokens.openCreateModal();
    const summary = await scanPage(
      adminPage,
      testInfo,
      'modal-token-create',
      '[data-testid="token-create-modal"]',
    );
    expect(summary.label).toBe('modal-token-create');
  });

  test('A11Y-01: user modals (create, delete confirmation, one-time password)', async ({
    adminPage,
    seededUser,
  }, testInfo) => {
    const users = new UsersPage(adminPage);
    await users.goto();
    await users.search(seededUser.username);
    await users.openCreateModal();
    const create = await scanPage(
      adminPage,
      testInfo,
      'modal-user-create',
      '[data-testid="user-create-modal"]',
    );
    expect(create.label).toBe('modal-user-create');
    await adminPage.keyboard.press('Escape');
    await expect(users.createModal.root).toHaveCount(0);

    await users.clickResetPassword(seededUser.username);
    await scanPage(adminPage, testInfo, 'modal-danger', '[data-testid="danger-modal"]');
    await users.shell.dangerModal.confirm();
    await users.resetPasswordModal.expectOpen();
    await scanPage(
      adminPage,
      testInfo,
      'modal-reset-password',
      '[data-testid="user-reset-password-modal"]',
    );
  });

  // The package pages of every protocol, which carry the row markup of RPS-1266 part 4 and (detail
  // pages) the highlighted code blocks. The seeded package gives every level one row.
  const LEVELS: readonly LevelName[] = ['list', 'sublist', 'versions', 'manifests', 'detail'];
  for (const protocol of Object.keys(DESCRIPTORS) as PackageProtocol[]) {
    const descriptor = DESCRIPTORS[protocol];
    const repoType = RepoType[protocol.toUpperCase() as keyof typeof RepoType];
    for (const level of LEVELS.filter((name) => descriptor.levels[name])) {
      test(`A11Y-01: ${protocol} ${level} page`, async ({
        adminPage,
        seeder,
        seedPackage,
      }, testInfo) => {
        const repo = await seeder.createRepo(repoType);
        const pkg = await seedPackage(repo);
        const page = pageOf(adminPage, descriptor, level, repo.name, pkg);
        await (page as ProtocolListPage).goto();
        const label = `pkg-${protocol}-${level}`;
        const summary = await scanPage(adminPage, testInfo, label);
        expect(summary.label).toBe(label);
      });
    }
  }
});
