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
 * SEC-01 (`@scanner`): a package pushed to a repo is scanned by the REAL backend-to-scanner path, and
 * the panel shows the result everywhere. The scanner is the stub of `src/stubs/scanner/`, which speaks
 * the `repsy-scanner-trivy` contract and reports what the artifact's NAME says (`vuln-critical` is 2
 * critical + 1 high finding), so a test knows the exact findings without configuring anything.
 *
 * Nothing is stubbed in the browser: the push goes over the protocol port (`seedPackage`), the backend
 * submits the artifact to the scanner, polls it, stores the findings, and the panel reads them back.
 * Runs only against a stack started with `./run.sh local up --scanner` and `REPSY_UI_OPT_IN=scanner`.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import {
  ScanSection,
  SecurityModal,
  badgeText,
  securityBadgeIn,
} from '../../../src/ui/pages/security.js';
import {
  SCANNER_TAG,
  SCANNER_VERSION,
  SCAN_TIMEOUT,
  expect,
  expectSubmitted,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  submittedNameOf,
  test,
} from '../../../src/ui/scanner-fixtures.js';
import { Severity } from '../../../src/ui/security-stubs.js';
import { SECURITY_CASES, atSecuritySection, packageListOf } from '../security/cases.js';

/**
 * The CVE ids of the findings the table shows, in row order. Rows of one severity are ordered by
 * finding id (a random UUID), so a test compares each severity's block as a set, never row by row.
 */
async function cveIds(section: ScanSection): Promise<string[]> {
  const texts = await section.findingRows().allTextContents();
  return texts.map((text) => /CVE-\d+-\d+/.exec(text)?.[0] ?? `(no CVE in "${text}")`);
}

test.describe('SEC-01 a pushed package is scanned and shown', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  for (const { protocol, type, packageLevel } of SECURITY_CASES) {
    test(`${protocol}: the scanner gets the artifact, and the badges, the scan section and the findings show what it reported`, async ({
      adminPage,
      seeder,
      seedPackage,
      panelApi,
      scanner,
    }) => {
      const repo = await seeder.createRepo(type);
      // Scanning a repo's pushes is on unless the repo says otherwise.
      expect((await panelApi.getSettings(repo.name)).securityScanEnabled).toBe(true);

      const pkg = await seedPackage(repo, {
        name: scanPackageName(protocol, seeder.runId, 'vuln-critical'),
      });

      // The real path: the backend handed the scanner this very artifact.
      const artifactName = await submittedNameOf(scanner, pkg);
      const calls = await scanner.calls(artifactName);
      expect(calls).toHaveLength(1);
      expectSubmitted(calls[0], { protocol, repoName: repo.name, repoType: type, pkg });

      // The backend stored the scan the scanner reported, with the scanner's own version.
      const scan = await newestFinishedScan(panelApi, repo.name, pkg);
      expect(scan).toMatchObject({
        status: 'COMPLETED',
        highestSeverity: 'CRITICAL',
        scannerVersion: SCANNER_VERSION,
      });

      // The version page: the scan section, expanded by its link, with the exact findings.
      const pages = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name);
      const detail = pages.detail(pkg);
      const section = new ScanSection(adminPage);
      await detail.goto();
      await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
      await expect(section.findingsCount).toHaveText('3 findings');
      await section.expectCollapsed();
      await section.detailsLink.click();
      await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));
      await section.expectExpanded();
      await expect(section.status).toHaveText('Completed');
      await expect(section.pollingIndicator).toHaveCount(0);
      await expect(section.bodyBadge(Severity.CRITICAL)).toHaveText(/Critical\s*2/);
      await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*1/);
      await expect(section.bodyBadge(Severity.MEDIUM)).toHaveCount(0);
      // Worst first: the two critical findings, then the high one.
      await expect(section.findingRows()).toHaveCount(3);
      const ids = await cveIds(section);
      expect(ids.slice(0, 2).sort()).toEqual(['CVE-2099-1001', 'CVE-2099-1002']);
      expect(ids[2]).toBe('CVE-2099-2001');
      await expect(section.findingRows().nth(2)).toContainText('stub-lib-high-1');
      await expect(section.history.locator('[data-testid^="scan-history-item-"]')).toHaveCount(1);
      await expect(section.historyItem(scan.id!)).toBeVisible();

      // The lists: one badge per level, all Critical (the version list, the package list, the repo list).
      const versions = pages.versions(pkg);
      await versions.goto();
      await expect(badgeText(securityBadgeIn(versions.row(pkg)))).toHaveText('Critical');

      const list = packageListOf(pages, pkg, packageLevel);
      await list.goto();
      const packageBadge = securityBadgeIn(list.row(pkg));
      await expect(badgeText(packageBadge)).toHaveText('Critical');

      // The package badge's modal reads the same numbers from the backend and links to the scan.
      const modal = new SecurityModal(adminPage, 'package');
      await packageBadge.click();
      await modal.expectOpen();
      await expect(modal.breakdown).toContainText('Critical');
      await expect(modal.breakdown).toContainText('2');
      await modal.recentScan(pkg.version).click();
      await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));

      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      await repos.search(repo.name);
      await expect(badgeText(securityBadgeIn(repos.row(repo.name)))).toHaveText('Critical');
    });
  }

  test('a package with no findings is Clean, everywhere', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'clean'),
    });
    const scan = await newestFinishedScan(panelApi, repo.name, pkg);
    expect(scan).toMatchObject({ status: 'COMPLETED', scannerVersion: SCANNER_VERSION });
    expect(scan.highestSeverity).toBeFalsy();

    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${pages.detail(pkg).path()}#security`);
    await pages.detail(pkg).expectLoaded();

    await expect(section.clean).toHaveText('Clean');
    await expect(section.status).toHaveText('Completed');
    await expect(section.root.getByText('No known vulnerabilities found.')).toBeVisible();
    await expect(section.findingsTable).toHaveCount(0);

    const list = pages.list();
    await list.goto();
    await expect(badgeText(securityBadgeIn(list.row(pkg)))).toHaveText('Clean');
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await expect(badgeText(securityBadgeIn(repos.row(repo.name)))).toHaveText('Clean');
  });

  test('twelve findings are two pages, and the table pages through them worst first', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-many'),
    });
    await newestFinishedScan(panelApi, repo.name, pkg);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${pages.detail(pkg).path()}#security`);
    await pages.detail(pkg).expectLoaded();

    await expect(section.findingsCount).toHaveText('12 findings');
    await expect(section.findingRows()).toHaveCount(10, SCAN_TIMEOUT);
    await expect(section.bodyBadge(Severity.CRITICAL)).toHaveText(/Critical\s*2/);
    await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*3/);
    await expect(section.bodyBadge(Severity.MEDIUM)).toHaveText(/Medium\s*4/);
    await expect(section.bodyBadge(Severity.LOW)).toHaveText(/Low\s*2/);
    await expect(section.bodyBadge(Severity.UNKNOWN)).toHaveText(/Unknown\s*1/);
    await expect(section.findingsPagination.root).toBeVisible();
    const first = await cveIds(section);
    expect(first.slice(0, 2).sort()).toEqual(['CVE-2099-1001', 'CVE-2099-1002']);
    expect(first.slice(2, 5).sort()).toEqual(['CVE-2099-2001', 'CVE-2099-2002', 'CVE-2099-2003']);
    expect(first.slice(5, 9).sort()).toEqual([
      'CVE-2099-3001',
      'CVE-2099-3002',
      'CVE-2099-3003',
      'CVE-2099-3004',
    ]);
    expect(first[9]).toMatch(/^CVE-2099-400[12]$/);

    const twoButton = section.findingsPagination.page(2);
    await twoButton.click();

    await expect(section.findingRows()).toHaveCount(2);
    // The last page holds the two least serious findings: the second low one and the unknown one.
    const second = await cveIds(section);
    expect(second[0]).toMatch(/^CVE-2099-400[12]$/);
    expect(second[1]).toBe('CVE-2099-5001');
  });
});
