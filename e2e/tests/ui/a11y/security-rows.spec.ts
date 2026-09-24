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
 * A11Y-14: the security scan rows are links too (RPS-1315, following A11Y-08..10).
 *
 * The `/security` scan rows and the "Recently Scanned Versions" rows of the repository and package
 * security modals used to be `role="button"` divs that navigated in code. Each is now a `row-link-host`
 * with ONE real `a.row-link` (named after the row) that leads to the version's detail page on its
 * security tab. Mocked scanner data (`@mocked`), real repositories and packages.
 */
import type { Locator } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { scanPage } from '../../../src/ui/a11y.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { SecurityModal, SecurityPage, securityBadgeIn } from '../../../src/ui/pages/security.js';
import {
  ScanStatus,
  Severity,
  scanInfo,
  severityCounts,
  stubArtifactSecurityDetail,
  stubArtifactSecuritySummary,
  stubRepoSecurityDetail,
  stubRepoSecuritySummary,
  stubSecurityScans,
  stubSecurityScansSummary,
  stubSupportedRepoTypes,
} from '../../../src/ui/security-stubs.js';
import { atSecuritySection } from '../security/cases.js';

const MOCKED = '@mocked';
const SCANNED_AT = '2026-09-10T12:00:00Z';

/** What the accessibility tree sees of a security row: a plain container with one named link. */
async function expectRowIsLink(row: Locator, name: string, href: string): Promise<void> {
  await expect(row).toBeVisible();
  await expect(row).not.toHaveAttribute('role', /.+/);
  await expect(row).not.toHaveAttribute('tabindex', /.+/);
  await expect(row).toHaveClass(/(^|\s)row-link-host(\s|$)/);
  const links = row.locator('a.row-link');
  await expect(links).toHaveCount(1);
  await expect(links).toHaveAttribute('href', href);
  await expect(links).toHaveAccessibleName(name);
  await expect(row.locator('[role="button"]')).toHaveCount(0);
}

test.describe('Security rows are links', { tag: ['@a11y', MOCKED] }, () => {
  test('A11Y-14: a /security scan row is one link to the version on its security tab', async ({
    adminPage,
    seeder,
    seedPackage,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const scan = scanInfo({
      repoName: repo.name,
      repoType: RepoType.NPM,
      artifactName: pkg.name,
      artifactVersion: pkg.version,
    });
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubSecurityScans(adminPage, [scan]);
    await stubSecurityScansSummary(adminPage, severityCounts([Severity.HIGH]));
    const security = new SecurityPage(adminPage);
    await security.goto();

    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg).path();
    const row = security.row(scan.id!);
    await expectRowIsLink(row, `${repo.name} / ${pkg.name} ${pkg.version}`, `${detail}#security`);
    await scanPage(adminPage, testInfo, 'security-rows');

    // Enter on the focused link opens it, like a click on the row.
    await row.locator('a.row-link').focus();
    await adminPage.keyboard.press('Enter');
    await expect(adminPage).toHaveURL(atSecuritySection(detail));
  });

  test('A11Y-14: a recent scan of the repository modal is one link to the version', async ({
    adminPage,
    seeder,
    seedPackage,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubRepoSecuritySummary(adminPage, {
      [repo.name]: { severity: Severity.HIGH, scanned: true },
    });
    await stubRepoSecurityDetail(adminPage, repo.name, {
      ...severityCounts([Severity.HIGH]),
      recentScans: [
        {
          artifactName: pkg.name,
          artifactVersion: pkg.version,
          status: ScanStatus.COMPLETED,
          severity: Severity.HIGH,
          scannedAt: SCANNED_AT,
          lastCompletedAt: SCANNED_AT,
        },
      ],
    });
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await securityBadgeIn(repos.row(repo.name)).click();
    const modal = new SecurityModal(adminPage, 'repo');
    await modal.expectOpen();

    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg).path();
    const row = modal.recentScan(`${pkg.name}@${pkg.version}`);
    await expectRowIsLink(row, `${pkg.name}@${pkg.version}`, `${detail}#security`);
    await scanPage(
      adminPage,
      testInfo,
      'repo-security-modal-rows',
      '[data-testid="repo-security-modal"]',
    );

    await row.locator('a.row-link').focus();
    await adminPage.keyboard.press('Enter');
    await expect(adminPage).toHaveURL(atSecuritySection(detail));
    await expect(modal.root).toHaveCount(0);
  });

  test('A11Y-14: a recent scan of the package modal is one link to the version', async ({
    adminPage,
    seeder,
    seedPackage,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubArtifactSecuritySummary(adminPage, repo.name, {
      [pkg.name]: { severity: Severity.HIGH, findingCount: 1, scanned: true },
    });
    await stubArtifactSecurityDetail(adminPage, repo.name, {
      ...severityCounts([Severity.HIGH]),
      recentScans: [
        {
          artifactName: pkg.name,
          artifactVersion: pkg.version,
          status: ScanStatus.COMPLETED,
          severity: Severity.HIGH,
          scannedAt: SCANNED_AT,
          lastCompletedAt: SCANNED_AT,
        },
      ],
    });
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const list = pages.list();
    await list.goto();
    await securityBadgeIn(list.row(pkg)).click();
    const modal = new SecurityModal(adminPage, 'package');
    await modal.expectOpen();

    const detail = pages.detail(pkg).path();
    const row = modal.recentScan(pkg.version);
    await expectRowIsLink(row, pkg.version, `${detail}#security`);
    await scanPage(
      adminPage,
      testInfo,
      'package-security-modal-rows',
      '[data-testid="package-security-modal"]',
    );

    await row.locator('a.row-link').focus();
    await adminPage.keyboard.press('Enter');
    await expect(adminPage).toHaveURL(atSecuritySection(detail));
    await expect(modal.root).toHaveCount(0);
  });
});
