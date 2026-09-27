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
 * A11Y-02 (RPS-1565): only one menu is open at a time. The row `...` menu closes on a click anywhere
 * outside it (a document-level handler), so a control that STOPPED its own click from reaching the
 * document (the selectors and the security badges did, RPS-1347 fixed the header's profile menu the
 * same way) left the row menu open beside the control it had just opened.
 *
 * Every click here is DISPATCHED, not performed: a real click in Chromium moves the focus to the
 * control, and the row menu closes on that (its `focusout` handler), which would hide the bug. A
 * dispatched click reaches the page without moving the focus, as a click on a button does in Safari,
 * so only the click event itself can close the menu. The dialog a badge opens moves the focus into
 * itself, which closes an open row menu on its own, so each badge test also opens a selector menu (no
 * focus handler: only the click can close it) and clicks the badge again.
 */
import type { Locator } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { SecurityModal, securityBadgeIn } from '../../../src/ui/pages/security.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  ScanScript,
  ScanStatus,
  Severity,
  findings,
  severityCounts,
  stubArtifactSecurityDetail,
  stubArtifactSecuritySummary,
  stubRepoSecurityDetail,
  stubRepoSecuritySummary,
  stubSupportedRepoTypes,
  stubVersionScans,
  stubVersionSecuritySummary,
} from '../../../src/ui/security-stubs.js';
import { expectStaysOnPage } from '../security/cases.js';

/** The row menu of a row is closed: no menu, and its toggle says so. */
async function expectRowMenuClosed(row: Locator, menu: Locator): Promise<void> {
  await expect(menu).toHaveCount(0);
  await expect(row.getByTestId('dropdown-toggle')).toHaveAttribute('aria-expanded', 'false');
}

test.describe('Only one menu is open at a time', { tag: '@a11y' }, () => {
  test('A11Y-02: opening the type selector closes an open row menu, and the row menu closes the selector', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const rowMenu = repos.list.inRow(repo.name, 'row-menu');
    const selectorMenu = adminPage.getByTestId('repo-type-filter').getByTestId('selector-menu');

    const menu = await repos.list.openRowMenu(repo.name);
    await repos.typeFilterToggle.dispatchEvent('click');
    await expect(selectorMenu).toBeVisible();
    await expectRowMenuClosed(rowMenu, menu);

    // The other way round, and the selector still closes on its own toggle.
    await repos.list.openRowMenu(repo.name);
    await expect(selectorMenu).toHaveCount(0);
    await repos.typeFilterToggle.dispatchEvent('click');
    await expect(selectorMenu).toBeVisible();
    await repos.typeFilterToggle.dispatchEvent('click');
    await expect(selectorMenu).toHaveCount(0);
  });

  test('A11Y-02: opening the sort selector closes an open row menu, on a package list and on a version list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);

    for (const page of [pages.list(), pages.versions(pkg)]) {
      await page.goto();
      const rowMenu = page.inRow(pkg, 'row-menu');
      const menu = await page.openRowMenu(pkg);

      await page.sort.getByTestId('sort-selector-toggle').dispatchEvent('click');

      await expect(page.sortMenu).toBeVisible();
      await expectRowMenuClosed(rowMenu, menu);

      // The other way round: the row menu takes the focus and the click, the sort menu goes.
      await page.openRowMenu(pkg);
      await expect(page.sortMenu).toHaveCount(0);
    }
  });

  test.describe('security badges', { tag: '@mocked' }, () => {
    test('A11Y-02: the repository badge opens its modal, closes an open row menu, and does not open the row', async ({
      adminPage,
      seeder,
    }) => {
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
      await expect(securityBadgeIn(repos.row(repo.name))).toBeVisible();
      const rowMenu = repos.list.inRow(repo.name, 'row-menu');
      const menu = await repos.list.openRowMenu(repo.name);
      const listPath = new URL(adminPage.url()).pathname;

      await securityBadgeIn(repos.row(repo.name)).dispatchEvent('click');

      const modal = new SecurityModal(adminPage, 'repo');
      await modal.expectOpen();
      await expectRowMenuClosed(rowMenu, menu);
      await expectStaysOnPage(adminPage, listPath);

      // An open selector menu has no focus handler: only the click can close it.
      await modal.closeViaBackdrop();
      await repos.typeFilterToggle.dispatchEvent('click');
      const selectorMenu = adminPage.getByTestId('repo-type-filter').getByTestId('selector-menu');
      await expect(selectorMenu).toBeVisible();
      await securityBadgeIn(repos.row(repo.name)).dispatchEvent('click');
      await modal.expectOpen();
      await expect(selectorMenu).toHaveCount(0);
    });

    test('A11Y-02: the package badge opens its modal, closes an open row menu, and does not open the row', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      const pkg = await seedPackage(repo);
      await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
      await stubArtifactSecuritySummary(adminPage, repo.name, {
        [pkg.name]: { severity: Severity.MEDIUM, findingCount: 1, scanned: true },
      });
      await stubArtifactSecurityDetail(adminPage, repo.name, {
        ...severityCounts([Severity.MEDIUM]),
        recentScans: [],
      });
      const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
      await list.goto();
      await expect(securityBadgeIn(list.row(pkg))).toBeVisible();
      const rowMenu = list.inRow(pkg, 'row-menu');
      const menu = await list.openRowMenu(pkg);
      const listPath = new URL(adminPage.url()).pathname;

      await securityBadgeIn(list.row(pkg)).dispatchEvent('click');

      const modal = new SecurityModal(adminPage, 'package');
      await modal.expectOpen();
      await expectRowMenuClosed(rowMenu, menu);
      await expectStaysOnPage(adminPage, listPath);

      // An open sort menu has no focus handler: only the click can close it.
      await modal.closeViaBackdrop();
      await list.sort.getByTestId('sort-selector-toggle').dispatchEvent('click');
      await expect(list.sortMenu).toBeVisible();
      await securityBadgeIn(list.row(pkg)).dispatchEvent('click');
      await modal.expectOpen();
      await expect(list.sortMenu).toHaveCount(0);
    });

    test('A11Y-02: the version badge opens its modal, closes an open row menu, and does not open the row', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      const pkg = await seedPackage(repo);
      await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
      await stubVersionSecuritySummary(adminPage, repo.name, {
        [pkg.version]: {
          severity: Severity.HIGH,
          findingCount: 1,
          scanned: true,
          latestScanStatus: ScanStatus.COMPLETED,
        },
      });
      await stubVersionScans(
        adminPage,
        repo.name,
        new ScanScript({ status: ScanStatus.COMPLETED, findings: findings([Severity.HIGH]) }),
        RepoType.NPM,
      );
      const versions = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).versions(pkg);
      await versions.goto();
      await expect(securityBadgeIn(versions.row(pkg))).toBeVisible();
      const rowMenu = versions.inRow(pkg, 'row-menu');
      const menu = await versions.openRowMenu(pkg);
      const listPath = new URL(adminPage.url()).pathname;

      await securityBadgeIn(versions.row(pkg)).dispatchEvent('click');

      const modal = new SecurityModal(adminPage, 'version');
      await modal.expectOpen();
      await expectRowMenuClosed(rowMenu, menu);
      await expectStaysOnPage(adminPage, listPath);

      // An open sort menu has no focus handler: only the click can close it.
      await modal.closeViaBackdrop();
      await versions.sort.getByTestId('sort-selector-toggle').dispatchEvent('click');
      await expect(versions.sortMenu).toBeVisible();
      await securityBadgeIn(versions.row(pkg)).dispatchEvent('click');
      await modal.expectOpen();
      await expect(versions.sortMenu).toHaveCount(0);
    });
  });
});
