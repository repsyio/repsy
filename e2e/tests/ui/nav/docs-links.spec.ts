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
 * NAV-05 (RPS-1628 G27): the documentation links of `panel-header.component.html`. There are two:
 * the icon next to the avatar (`header-docs`, shown whether or not there is a session) and the "Docs"
 * entry of the avatar menu (`header-menu-docs`, only reachable once logged in). Both are a plain,
 * hardcoded `https://docs.repsy.io/` (there is no per-protocol deep link today, unlike the PGP
 * section's unused `docsBaseUrl`, see the PR description): the point of testing every section below is
 * that the SAME header, with the SAME link, is what every page of the SPA shares, protocol pages
 * included, not that the destination changes with the page.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { expect, test } from '../../../src/ui/fixtures.js';

const NAV = '@nav';
const DOCS_URL = 'https://docs.repsy.io/';

async function expectDocsLink(link: import('@playwright/test').Locator): Promise<void> {
  await expect(link).toBeVisible();
  await expect(link).toHaveAttribute('href', DOCS_URL);
  await expect(link).toHaveAttribute('target', '_blank');
  // `rel="noopener"`, not merely containing it (RPS-1628: a regression to "noopener noreferrer" or
  // the reverse should still be caught, but this is what the template carries today).
  await expect(link).toHaveAttribute('rel', 'noopener');
}

test.describe('NAV-05 documentation links', { tag: NAV }, () => {
  test('the header icon opens the docs, logged out and on every logged-in section alike', async ({
    page,
    adminPage,
    seeder,
  }) => {
    // Logged out: the login page still carries the icon (outside the `isAuthenticated` block).
    await page.goto('/login');
    await expectDocsLink(new Shell(page).header.docs);

    // Logged in, across sections that do not share a component tree: the dashboard, the repository
    // list and one protocol's own package list.
    await new DashboardPage(adminPage).goto();
    await expectDocsLink(new Shell(adminPage).header.docs);

    await new RepositoriesPage(adminPage).goto();
    await expectDocsLink(new Shell(adminPage).header.docs);

    const repo = await seeder.createRepo(RepoType.NPM);
    await protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list().goto();
    await expectDocsLink(new Shell(adminPage).header.docs);
  });

  test("the avatar menu's Docs entry matches the header icon", async ({ adminPage }) => {
    const shell = new Shell(adminPage);
    await new DashboardPage(adminPage).goto();

    await shell.openAvatarMenu();

    // Both are asserted against the very same constant, so a drift between the two links (one
    // updated, the other left behind) would fail here rather than pass by each looking plausible
    // on its own.
    await expectDocsLink(shell.header.menuDocs);
    await expectDocsLink(shell.header.docs);
  });
});
