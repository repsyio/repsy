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
 * NAV-02: the panel at a phone's width (390x844). A second context with that viewport is opened
 * through `openUiPage` (never a Playwright project of its own), logged in as the harness admin.
 * Below `md` the desktop sidebar is `display: none` and the header's burger (`aria-label="Toggle
 * menu"`) opens the mobile sidebar, which only exists in the DOM while open; below `lg` every list
 * shows its mobile card variant (`<page>-cards`, `<page>-card-<key>`) and its desktop grid is hidden.
 *
 * Known mobile gaps are not pinned here: the Cargo and NuGet version lists have no card variant and
 * npm/PyPI mobile lists gate Delete on the wrong permission (RPS-1262); the package stories own those.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { loginSession } from '../../../src/ui/session.js';

const PHONE = { width: 390, height: 844 };

test.describe('Mobile viewport', () => {
  test('NAV-02: at phone width the desktop sidebar is hidden and the burger is the way in', async ({
    openUiPage,
    adminSession,
    adminPage,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const shell = new Shell(page);
    await new DashboardPage(page).goto();

    await expect(shell.sidebar.root).toBeHidden();
    await expect(shell.header.burger).toBeVisible();
    await expect(shell.header.burger).toHaveAttribute('aria-label', 'Toggle menu');
    await expect(shell.mobileSidebar.root).toHaveCount(0);

    // The header still works without the sidebar: its avatar menu reaches Profile and Logout.
    await shell.openAvatarMenu();
    await expect(shell.header.profile).toBeVisible();
    await expect(shell.header.logout).toBeVisible();

    // And at the desktop width it is the other way round (the project's default viewport).
    await new DashboardPage(adminPage).goto();
    await expect(new Shell(adminPage).sidebar.root).toBeVisible();
    await expect(new Shell(adminPage).header.burger).toBeHidden();
  });

  test('NAV-02: the burger opens the mobile sidebar, its links work and it closes', async ({
    openUiPage,
    adminSession,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const shell = new Shell(page);
    const dashboard = new DashboardPage(page);
    const repos = new RepositoriesPage(page);
    const users = new UsersPage(page);

    await test.step('open, and follow the Repositories link', async () => {
      await dashboard.goto();
      await shell.header.burger.click();
      await expect(shell.mobileSidebar.root).toBeVisible({ timeout: 3_000 });
      await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'true');
      // RPS-1267: the "Search docs" box did nothing and is gone.
      await expect(page.getByTestId('mobile-sidebar-search')).toHaveCount(0);
      await shell.mobileSidebar.link('repositories').click();
      await expect(page).toHaveURL(/\/repositories$/);
      await expect(repos.title).toBeVisible();
      await expect(shell.mobileSidebar.root).toHaveCount(0);
      await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'false');
    });

    await test.step('a link between two pages of the same layout closes it too', async () => {
      await shell.header.burger.click();
      await shell.mobileSidebar.link('users').click();
      await expect(page).toHaveURL(/\/users$/);
      await expect(users.title).toBeVisible();
      await expect(shell.mobileSidebar.root).toHaveCount(0);
    });

    await test.step('open again (fresh load) and follow the Users link (admin)', async () => {
      await dashboard.goto();
      await shell.header.burger.click();
      await shell.mobileSidebar.link('users').click();
      await expect(page).toHaveURL(/\/users$/);
      await expect(users.title).toBeVisible();
    });

    await test.step('the X closes it', async () => {
      await dashboard.goto();
      await shell.header.burger.click();
      await expect(shell.mobileSidebar.root).toBeVisible();
      await shell.mobileSidebar.close.click();
      await expect(shell.mobileSidebar.root).toHaveCount(0);
      await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'false');
    });

    await test.step('Escape closes it, other keys (Tab) do not', async () => {
      await shell.header.burger.click();
      await expect(shell.mobileSidebar.root).toBeVisible();
      await page.keyboard.press('Tab');
      await expect(shell.mobileSidebar.root).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(shell.mobileSidebar.root).toHaveCount(0);
      await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'false');
    });

    await test.step('the backdrop (right of the 240 px panel) closes it', async () => {
      await shell.header.burger.click();
      await expect(shell.mobileSidebar.root).toBeVisible();
      await page.getByTestId('mobile-sidebar-backdrop').click({ position: { x: 360, y: 400 } });
      await expect(shell.mobileSidebar.root).toHaveCount(0);
    });
  });

  test('NAV-02: a USER opens the mobile sidebar without Users and Security, and logs out', async ({
    openUiPage,
    seededUser,
  }) => {
    const session = await loginSession(seededUser.username, seededUser.password);
    const page = await openUiPage({ session, viewport: PHONE });
    const shell = new Shell(page);

    await new DashboardPage(page).goto();
    await shell.header.burger.click();
    await expect(shell.mobileSidebar.link('repositories')).toBeVisible({ timeout: 3_000 });
    await expect(shell.mobileSidebar.link('users')).toHaveCount(0);
    await expect(shell.mobileSidebar.link('security')).toHaveCount(0);
    await shell.mobileSidebar.logout.click();
    await expect(page).toHaveURL(/\/login(\?.*)?$/);
  });

  test('NAV-02: the repository list and the users list show cards, not the grid', async ({
    openUiPage,
    adminSession,
    seeder,
    seededUser,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const repo = await seeder.createRepo(RepoType.MAVEN);

    const repos = new RepositoriesPage(page);
    await repos.goto();
    await repos.search(repo.name);
    await expect(repos.cards).toBeVisible();
    await expect(repos.card(repo.name)).toBeVisible();
    await expect(repos.list.container).toBeHidden();
    await expect(repos.row(repo.name)).toBeHidden();

    const users = new UsersPage(page);
    await users.goto();
    await users.search(seededUser.username);
    await expect(page.getByTestId('user-cards')).toBeVisible();
    await expect(page.getByTestId(`user-card-${seededUser.username}`)).toBeVisible();
    await expect(users.list.container).toBeHidden();
    await expect(users.row(seededUser.username)).toBeHidden();
  });

  test('NAV-02: a package list and its versions show cards, not the grid', async ({
    openUiPage,
    adminSession,
    seeder,
    seedPackage,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(page, DESCRIPTORS.maven, repo.name);

    for (const list of [pages.list(), pages.sublist(pkg), pages.versions(pkg)]) {
      await list.goto();
      await expect(list.cards).toBeVisible();
      await expect(list.card(pkg)).toBeVisible();
      await expect(list.desktop.container).toBeHidden();
      await expect(list.row(pkg)).toBeHidden();
    }
  });
});
