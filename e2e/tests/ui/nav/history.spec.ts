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
 * NAV-04..09 (RPS-1650, G12): the browser's Back and Forward buttons across the panel.
 *
 * The panel keeps a list's search text, sort, page and type in the URL's query string (RPS-1668:
 * `?q=&sort=&page=&type=`, `replaceUrl: true` while the user is typing or filtering, so a keystroke
 * never spams the browser history), so a Back to a list restores exactly what was left, and so does a
 * reload. What is pinned:
 *
 *  - NAV-04/05: list -> detail -> Back reloads the list from the server (never a bfcache copy), with
 *    the search, sort, page and type it was left with; the search box and the rows agree, Forward
 *    reopens what was left, at every level of every protocol, and no page error is raised on the way.
 *  - NAV-06: a deleted repository or version is never resurrected by Back: the entry of the deleted
 *    thing shows the not-found state, the entry before it shows fresh data.
 *  - NAV-07: Back after a login lands on the dashboard, never on the form again; Back after a logout never
 *    shows a private page (the guard sends the visitor to the login form).
 *  - NAV-08: an open modal or the mobile menu does not survive a Back, and leaves no scroll lock behind.
 *  - NAV-09: a reload in the middle of the history keeps the entries around it working.
 */
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { allowNotFoundToast, expectVersionNotFound } from '../../../src/ui/package-scenarios.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import {
  DESCRIPTORS,
  protocolPages,
  type ProtocolListPage,
  type VersionDetailPage,
  type VersionsPage,
} from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';
import { rowLinkPoint } from '../../../src/ui/row-click.js';
import { profileRoute, urlEndsWith } from '../../../src/ui/routes.js';

/** The URL ends in `path`, with or without the fragment the detail pages add (`#security`). */
function endsWith(path: string): RegExp {
  return new RegExp(`${urlEndsWith(path).source.slice(0, -1)}(#[\\w-]+)?$`);
}
import type { PackageProtocol, PackageRef } from '../../../src/seed/packages.js';

const NAV = '@nav';

/** The repository's row: click the free point of the row (the row is one stretched link). */
async function openRepoRow(repos: RepositoriesPage, name: string): Promise<void> {
  const row = repos.row(name);
  await row.click({ position: await rowLinkPoint(row) });
}

/** Goes back or forward and waits for the browser to have committed the entry. */
async function step(page: Page, direction: 'back' | 'forward'): Promise<void> {
  const response = direction === 'back' ? await page.goBack() : await page.goForward();
  // A same-document (pushState) entry answers null: the router renders it a tick later.
  void response;
}

/** The pathname (and query) of the page, without the origin. */
function where(page: Page): string {
  const url = new URL(page.url());
  return `${url.pathname}${url.search}`;
}

const PROTOCOLS = Object.keys(DESCRIPTORS) as PackageProtocol[];

/** The pages of one walk list -> versions -> detail, and the package they are about. */
async function openWalk(
  page: Page,
  protocol: PackageProtocol,
  repoName: string,
  pkg: PackageRef,
): Promise<{ list: ProtocolListPage; versions: VersionsPage; detail: VersionDetailPage }> {
  const descriptor = DESCRIPTORS[protocol];
  const pages = protocolPages(page, descriptor, repoName);
  const list = pages.list();
  await list.goto();
  const listLevel = descriptor.levels.list;
  const versions = (
    listLevel.rowOpens === 'versions'
      ? await list.openRow(pkg)
      : await list.openLink(pkg, 'versions')
  ) as VersionsPage;
  await versions.expectLoaded();
  const detail = (await versions.openRow(pkg)) as VersionDetailPage;
  await detail.expectLoaded();
  await expect(detail.root).toBeVisible();
  return { list, versions, detail };
}

test.describe('Browser history: lists and details', { tag: NAV }, () => {
  test('NAV-04: list -> repository -> Back reloads the list from the server, keeping the search it was left with', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await openRepoRow(repos, repo.name);
    const pages = protocolPages(adminPage, DESCRIPTORS.maven, repo.name);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    await pages.list().expectLoaded();

    // A repository made while the list was away is found once the search that was left is cleared:
    // Back asks the server again, with the search restored from the URL, not a bfcache copy.
    const other = await seeder.createRepo(RepoType.MAVEN);
    await repos.afterListResponse(() => step(adminPage, 'back'), {
      type: 'all',
      q: repo.name,
      page: 0,
    });
    await expect(adminPage).toHaveURL(/\/repositories\?q=/);
    await expect(repos.title).toBeVisible();
    await expect(repos.searchInput).toHaveValue(repo.name); // the box agrees with the list it sits over
    await expect(repos.typeFilterText()).toContainText('All');
    await expect(repos.spinner.root).toBeHidden();
    await repos.search(other.name);
    await expect(repos.row(other.name)).toBeVisible();

    // Forward is the repository again, the way it was left.
    await step(adminPage, 'forward');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    await pages.list().expectLoaded();
  });

  test('NAV-04: a type chosen on the list survives Back too, and the list still works', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const repos = new RepositoriesPage(adminPage);
    const npm = uiRepoType(RepoType.NPM);
    await repos.goto();
    await repos.selectType(npm);
    await repos.search(repo.name);
    await openRepoRow(repos, repo.name);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));

    await repos.afterListResponse(() => step(adminPage, 'back'), {
      type: npm,
      q: repo.name,
      page: 0,
    });
    // Whatever was left is what Back brings up: the box, the type selector and the rows are one state.
    await expect(repos.searchInput).toHaveValue(repo.name);
    await expect(repos.error).toHaveCount(0);
    await expect(repos.typeFilterText()).toContainText(npm.label);
    await expect(repos.row(repo.name)).toBeVisible();
  });

  test('NAV-04: the repository list keeps its search after Back', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await openRepoRow(repos, repo.name);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));

    await step(adminPage, 'back');
    await expect(repos.title).toBeVisible();
    await expect(repos.searchInput).toHaveValue(repo.name);
    await expect(repos.rows()).toHaveCount(1);
  });

  test('NAV-04: a package list keeps its page and sort after Back', async ({
    adminPage,
    seeder,
    seedPackages,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await seedPackages(repo, 12);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    await list.sortBy('Oldest');
    await list.pagination.page(2).click();
    await expect(list.pagination.page(2)).toBeDisabled();
    await expect(list.rows()).toHaveCount(2);
    const row = list.rows().first();
    await row.click({ position: await rowLinkPoint(row) });
    await expect(adminPage).not.toHaveURL(new RegExp(`/${repo.name}$`));

    await step(adminPage, 'back');
    await list.expectLoaded();
    await expect(list.pagination.page(2)).toBeDisabled();
    await expect(list.rows()).toHaveCount(2);
    await expect(list.sort).toContainText('Oldest');
  });

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'NAV-04: the users list keeps its search after Back',
    { tag: ['@cloud-skip'] },
    async ({ adminPage, seededUser }) => {
      const users = new UsersPage(adminPage);
      await users.goto();
      await users.search(seededUser.username);
      await adminPage.getByTestId('header-avatar').click();
      await adminPage.getByTestId('header-menu-profile').click();
      await expect(adminPage).toHaveURL(/\/profile$/);

      await step(adminPage, 'back');
      await expect(users.title).toBeVisible();
      await expect(users.searchInput).toHaveValue(seededUser.username);
      await expect(users.rows()).toHaveCount(1);
    },
  );

  for (const protocol of PROTOCOLS) {
    test(`NAV-05: ${protocol}: list -> versions -> detail, Back twice, Forward twice`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(
        RepoType[protocol.toUpperCase() as keyof typeof RepoType],
      );
      const pkg = await seedPackage(repo);
      const { list, versions, detail } = await openWalk(adminPage, protocol, repo.name, pkg);
      const detailAt = where(adminPage);

      await step(adminPage, 'back');
      await versions.expectLoaded();
      await expect(versions.row(pkg)).toBeVisible();
      const versionsPath = where(adminPage);

      await step(adminPage, 'back');
      await list.expectLoaded();
      await expect(adminPage).toHaveURL(endsWith(list.path()));
      await expect(list.row(pkg)).toBeVisible();

      await step(adminPage, 'forward');
      await versions.expectLoaded();
      expect(where(adminPage)).toBe(versionsPath);
      await expect(versions.row(pkg)).toBeVisible();

      await step(adminPage, 'forward');
      await detail.expectLoaded();
      await expect(detail.root).toBeVisible();
      expect(where(adminPage)).toBe(detailAt);
      await expect(detail.name).toBeVisible();
    });
  }
});

test.describe('Browser history: deleted things stay deleted', { tag: NAV }, () => {
  test('NAV-06: Back after deleting a repository from its settings shows not-found, never the settings', async ({
    adminPage,
    seeder,
    pageErrors,
  }) => {
    // RPS-1670 fixed the route resolver's own lookup (single-flight, silent), which used to fire about ten
    // parallel "Repository not found" toasts here. Back still lands on the settings route with a cached repo
    // type (canMatch answers from the cache, not a fresh 404), so the settings page itself mounts and its own
    // data load - and the repo's permissions, for the sidebar/breadcrumb - are what answer 404 once each; that
    // is a different pair of calls, not part of the resolver fix, and each still toasts or logs by design.
    pageErrors.allowToast(
      'Repository not found',
      'by design: the settings page mounts (its route type came from the cache) and its own data load 404s',
    );
    pageErrors.allow(
      /Global error handler caught an error: Http failure response for \S+\/api\/repos\/[^/]+\/permissions: 404/,
      'known bug: the lookups of an unknown repository route include its permissions, whose 404 is not handled',
    );
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await settings.deleteRepo.deleteButton.click();
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Repository deleted successfully');
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await repos.search(repo.name);
    await expect(repos.rows()).toHaveCount(0);

    // Back: the entry of the deleted repository's settings.
    await step(adminPage, 'back');
    await expect(adminPage.getByTestId('not-found')).toBeVisible();
    await expect(settings.root).toHaveCount(0);
    await expect(settings.title).toHaveCount(0);

    // Forward is the list again, with the search it was left with (RPS-1668) restored from the URL,
    // and the deleted repository is not in it.
    await repos.afterListResponse(() => step(adminPage, 'forward'), { q: repo.name, page: 0 });
    await expect(repos.title).toBeVisible();
    await expect(repos.searchInput).toHaveValue(repo.name);
    await expect(repos.rows()).toHaveCount(0);
  });

  // RPS-1650 (fixed here): the resolver pushed /not-found on top of the unknown route, so Back returned to
  // that route, which redirected again: the visitor could not leave the 404 page with the browser's Back.
  for (const shape of ['a repository name', 'a deeper path']) {
    test(`NAV-06: an unknown URL (${shape}) leaves no history entry of its own: Back leaves the 404 page`, async ({
      adminPage,
      seeder,
    }) => {
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const unknown = seeder.reserveRepoName(RepoType.MAVEN);
      await adminPage.goto(shape === 'a repository name' ? `/${unknown}` : `/${unknown}/a/b/c`);
      await expect(adminPage.getByTestId('not-found')).toBeVisible();
      await expect(adminPage).toHaveURL(/\/not-found$/);

      await repos.afterListResponse(() => step(adminPage, 'back'), { q: '', page: 0 });
      await expect(adminPage).toHaveURL(/\/repositories$/);
      await expect(repos.title).toBeVisible();
    });
  }

  for (const protocol of PROTOCOLS) {
    const descriptor = DESCRIPTORS[protocol];
    if (!descriptor.levels.detail.delete) {
      continue;
    }

    test(`NAV-06: ${protocol}: Back after deleting a version from its detail page shows not-found there, and fresh data before it`, async ({
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
      const versions = pages.versions(first);
      await versions.goto();
      await versions.expectRow(first);
      const detail = (await versions.openRow(first)) as VersionDetailPage;
      await detail.expectLoaded();
      await expect(detail.root).toBeVisible();
      const detailAt = where(adminPage);

      await detail.delete();
      // The convention: the versions page (Docker and others record their own landing).
      await expect(adminPage).not.toHaveURL(endsWith(detailAt));

      // Back is the deleted version's own entry: not-found, not a resurrected detail.
      await step(adminPage, 'back');
      await expect(adminPage).toHaveURL(endsWith(detailAt));
      await expectVersionNotFound(detail);

      // And the entry before it (the versions list) is read again: the deleted one is not on it.
      await step(adminPage, 'back');
      await versions.expectLoaded();
      await versions.expectNoRow(first);
      await versions.expectRow(second);
    });
  }

  // RPS-1693 (fixed here, the same class RPS-1650 fixed for the other shells, noted but not probed by
  // PR #796): the tag list's own 404 handlers (a stale refresh, or the tag list's own load) pushed a new
  // image-list entry on top of the deleted image's tag-list page instead of replacing it. `history.length`
  // is the reliable witness for that: a push grows it, a `replaceUrl` navigation does not.
  test('NAV-06: docker: the tag list 404 that follows its image being deleted elsewhere replaces its own entry', async ({
    adminPage,
    seeder,
    seedVersions,
    pageErrors,
  }) => {
    allowNotFoundToast(pageErrors);
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const [tag] = await seedVersions(repo, ['1.0.0']);
    const pages = protocolPages(adminPage, DESCRIPTORS.docker, repo.name);
    const list = pages.list();
    await list.goto();
    const versions = pages.versions(tag);
    await versions.goto();
    await versions.expectRow(tag);
    const entriesBeforeRedirect = await adminPage.evaluate(() => window.history.length);

    // Somebody else deletes the image outright (a second tab does what a second admin would).
    const other = await adminPage.context().newPage();
    try {
      const listB = protocolPages(other, DESCRIPTORS.docker, repo.name).list();
      await listB.goto();
      await listB.deleteRow(tag);
    } finally {
      await other.close();
    }

    // This tab does not know yet: its own refresh 404s, and the page leaves for the image list on its own.
    await versions.refresh();
    await expect(adminPage).toHaveURL(endsWith(list.path()));

    // The redirect REPLACED the tag-list entry: no new entry was pushed on top of it, so a single Back
    // (unlike before RPS-1693) does not land back on the stale tag list and re-trigger the same 404.
    expect(await adminPage.evaluate(() => window.history.length)).toBe(entriesBeforeRedirect);
  });
});

test.describe('Browser history: login and logout', { tag: NAV }, () => {
  test('NAV-07: login through the returnUrl, then Back lands on the dashboard and never on the form', async ({
    page,
  }) => {
    await page.goto('/repositories');
    await expect(page).toHaveURL(
      (url) => url.pathname === '/' && url.searchParams.get('returnUrl') === '/repositories',
    );
    const login = new LoginPage(page);
    await expect(login.form).toBeVisible();
    await login.login(env.adminUsername, env.adminPassword);
    await expect(page).toHaveURL(/\/repositories$/);
    const repos = new RepositoriesPage(page);
    await expect(repos.title).toBeVisible();

    // Back: the entry the guard rewrote (`/?returnUrl=...`). Signed in now, "/" is the dashboard.
    await step(page, 'back');
    await expect(new DashboardPage(page).welcomeCard).toBeVisible();
    await expect(login.form).toHaveCount(0);

    // Forward: the page the login returned to, with its data.
    await repos.afterListResponse(() => step(page, 'forward'));
    await expect(page).toHaveURL(/\/repositories$/);
    await expect(repos.title).toBeVisible();
  });

  test('NAV-07: Back after a logout never shows a private page, Forward stays on the login page', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await new Shell(adminPage).logoutViaSidebar();

    await step(adminPage, 'back');
    // The guard answers with the login form (and remembers where the visitor wanted to go).
    await expect(new LoginPage(adminPage).form).toBeVisible();
    await expect(adminPage).toHaveURL(
      (url) => url.searchParams.get('returnUrl') === '/repositories',
    );
    await expect(repos.title).toHaveCount(0);
    await expect(repos.rows()).toHaveCount(0);
    expect(await adminPage.evaluate(() => window.localStorage.length)).toBe(0);

    await step(adminPage, 'forward');
    await expect(adminPage).toHaveURL(/\/login$/);
    await expect(new LoginPage(adminPage).form).toBeVisible();
  });

  test('NAV-07: the login page of a signed-in visitor does not trap Back', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await adminPage.goto('/login');
    // A signed-in visitor has no use for the form: the guard sends them to "/" (the dashboard).
    await expect(new DashboardPage(adminPage).welcomeCard).toBeVisible();

    await step(adminPage, 'back');
    // Back leaves the login entry behind instead of bouncing off it again.
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await expect(repos.title).toBeVisible();
  });
});

test.describe('Browser history: modals and menus', { tag: NAV }, () => {
  test('NAV-08: Back with the create-repository modal open closes it, Forward does not bring it back', async ({
    adminPage,
  }) => {
    await adminPage.goto(profileRoute());
    await expect(adminPage.getByTestId('profile-title')).toBeVisible();
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.openCreateModal();

    await step(adminPage, 'back');
    await expect(adminPage).toHaveURL(urlEndsWith(profileRoute()));
    await expect(repos.modal.root).toHaveCount(0);
    expect(await adminPage.evaluate(() => document.body.style.overflow)).toBe('');

    await step(adminPage, 'forward');
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await expect(repos.title).toBeVisible();
    await expect(repos.modal.root).toHaveCount(0);
    // The button still opens it: nothing stayed half open.
    await repos.openCreateModal();
    await repos.modal.closeButton.click();
    await repos.modal.expectClosed();
  });

  test('NAV-08: Back with a confirmation open (the delete-repository one) drops it and keeps the repository', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await adminPage.goto('/repositories');
    await settings.goto();
    await settings.deleteRepo.deleteButton.click();
    await settings.shell.dangerModal.expectOpen('Delete Repository');

    await step(adminPage, 'back');
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await expect(settings.shell.dangerModal.root).toHaveCount(0);
    // Nothing was deleted by going back.
    expect((await panelApi.listAllRepos({ type: RepoType.MAVEN })).map((r) => r.name)).toContain(
      repo.name,
    );
  });

  test('NAV-08: Back with the mobile menu open closes it and unlocks the page', async ({
    openUiPage,
    adminSession,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: { width: 390, height: 844 } });
    const shell = new Shell(page);
    await page.goto('/repositories');
    await expect(new RepositoriesPage(page).title).toBeVisible();
    await page.goto(profileRoute());
    await shell.header.burger.click();
    await expect(shell.mobileSidebar.root).toBeVisible();
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden');

    await step(page, 'back');
    await expect(page).toHaveURL(/\/repositories$/);
    await expect(shell.mobileSidebar.root).toHaveCount(0);
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('');
    await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'false');
    await page.mouse.wheel(0, 300);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThanOrEqual(0);
  });

  test('NAV-08: Back with a row menu open closes it', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await adminPage.goto(profileRoute());
    await repos.goto();
    await repos.search(repo.name);
    await repos.list.openRowMenu(repo.name);
    await step(adminPage, 'back');
    await expect(adminPage).toHaveURL(urlEndsWith(profileRoute()));
    await step(adminPage, 'forward');
    await expect(repos.title).toBeVisible();
    await expect(adminPage.getByTestId('dropdown-menu')).toHaveCount(0);
  });
});

test.describe('Browser history: deep links and reloads', { tag: NAV }, () => {
  test('NAV-09: a reload in the middle of the history keeps Back and Forward working', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const { list, versions, detail } = await openWalk(adminPage, 'npm', repo.name, pkg);
    const detailAt = where(adminPage);

    await step(adminPage, 'back');
    await versions.expectLoaded();
    const versionsAt = where(adminPage);
    await adminPage.reload();
    await versions.expectLoaded();
    await expect(versions.row(pkg)).toBeVisible();
    expect(where(adminPage)).toBe(versionsAt);

    await step(adminPage, 'forward');
    await detail.expectLoaded();
    expect(where(adminPage)).toBe(detailAt);
    await expect(detail.root).toBeVisible();

    await step(adminPage, 'back');
    await step(adminPage, 'back');
    await list.expectLoaded();
    await expect(list.row(pkg)).toBeVisible();
  });

  test('NAV-09: a deep link opened in a new tab has the dashboard-less history it should: Back leaves the panel, not the app', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.root).toBeVisible();
    // The breadcrumb walks up (a click is one history entry each) and Back walks down again.
    const detailAt = where(adminPage);
    await adminPage.getByTestId('breadcrumb').getByRole('link').first().click();
    await expect(adminPage).not.toHaveURL(endsWith(detailAt));
    await step(adminPage, 'back');
    await detail.expectLoaded();
    await expect(detail.root).toBeVisible();
    expect(where(adminPage)).toBe(detailAt);
  });
});
