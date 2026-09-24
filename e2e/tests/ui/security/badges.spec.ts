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
 * SEC-02a: the security badges of the repository list, the package lists and the version lists, and
 * the modals they open. A badge exists only when the repo's type has a scanner
 * (`GET /api/security/supported-repo-types`) and the summary says something is worth showing, so
 * both are stubbed; the repositories and packages behind the rows are real.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import {
  ScanSection,
  SecurityModal,
  badgeText,
  securityBadgeIn,
  severityBadge,
} from '../../../src/ui/pages/security.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  ScanScript,
  ScanStatus,
  Severity,
  finding,
  findings,
  severityCounts,
  stubArtifactSecurityDetail,
  stubArtifactSecuritySummary,
  stubRepoSecurityDetail,
  stubRepoSecuritySummary,
  stubSupportedRepoTypes,
  stubVersionScans,
  stubVersionSecuritySummary,
  type RecentScannedVersion,
} from '../../../src/ui/security-stubs.js';
import { SECURITY_CASES, atSecuritySection, expectStaysOnPage, packageListOf } from './cases.js';

const MOCKED = '@mocked';
const ALL_TYPES = SECURITY_CASES.map(({ type }) => type);
const SCANNED_AT = '2026-09-10T12:00:00Z';

function recent(
  artifactName: string,
  artifactVersion: string,
  severity: Severity | null,
  overrides: Partial<RecentScannedVersion> = {},
): RecentScannedVersion {
  return {
    artifactName,
    artifactVersion,
    status: ScanStatus.COMPLETED,
    severity,
    scannedAt: SCANNED_AT,
    lastCompletedAt: SCANNED_AT,
    ...overrides,
  };
}

test.describe('SEC-02a repository list badges', { tag: MOCKED }, () => {
  test('a repository shows a badge per scan outcome, and only for a type with a scanner', async ({
    adminPage,
    seeder,
  }) => {
    const [high, clean, scanning, failed, rescanning, never] = await Promise.all(
      Array.from({ length: 6 }, () => seeder.createRepo(RepoType.NPM)),
    );
    const unsupported = await seeder.createRepo(RepoType.PYPI);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    const summary = await stubRepoSecuritySummary(adminPage, {
      [high.name]: { severity: Severity.HIGH, scanned: true },
      [clean.name]: { severity: null, scanned: true },
      [scanning.name]: { scanned: false, unscannedInProgressCount: 1 },
      [failed.name]: { scanned: false, unscannedFailedCount: 1 },
      [rescanning.name]: { severity: Severity.MEDIUM, scanned: true, rescanInProgressCount: 1 },
      // The summary knows nothing of `never`; `unsupported` is critical, but PyPI has no scanner.
      [unsupported.name]: { severity: Severity.CRITICAL, scanned: true },
    });
    const repos = new RepositoriesPage(adminPage);

    await repos.goto();
    await repos.search(seeder.runId);
    await expect(repos.rows()).toHaveCount(7);

    const badge = (name: string) => securityBadgeIn(repos.row(name));
    await expect(badgeText(badge(high.name))).toHaveText('High');
    await expect(badgeText(badge(high.name))).toHaveAttribute('data-severity', 'HIGH');
    await expect(badgeText(badge(clean.name))).toHaveText('Clean');
    await expect(badgeText(badge(scanning.name))).toHaveText('Scanning...');
    await expect(badgeText(badge(failed.name))).toHaveText('Scan failed');
    // A rescan keeps the last known severity and adds a marker with its own tooltip.
    await expect(badgeText(badge(rescanning.name))).toHaveText('Medium');
    await expect(badgeText(badge(rescanning.name)).locator('span[role="img"]')).toHaveAttribute(
      'title',
      /being rescanned/,
    );
    await expect(badge(never.name)).toHaveCount(0);
    await expect(badge(unsupported.name)).toHaveCount(0);

    // The panel asked for exactly the repositories it lists, in one call.
    const asked = summary.calls.flatMap(
      (url) => url.searchParams.get('repoNames')?.split(',') ?? [],
    );
    expect(asked).toEqual(expect.arrayContaining([high.name, never.name, unsupported.name]));
  });

  test('the same repository has no badge once its type has no scanner', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubRepoSecuritySummary(adminPage, {
      [repo.name]: { severity: Severity.HIGH, scanned: true },
    });
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await expect(badgeText(securityBadgeIn(repos.row(repo.name)))).toHaveText('High');

    await stubSupportedRepoTypes(adminPage, []);
    await adminPage.reload();
    await repos.goto();
    await repos.search(repo.name);

    await expect(repos.row(repo.name)).toBeVisible();
    await expect(securityBadgeIn(repos.row(repo.name))).toHaveCount(0);
  });

  test('the badge opens the repository modal: breakdown, recent scans, and a link to the version', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const cleanRepo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubRepoSecuritySummary(adminPage, {
      [repo.name]: { severity: Severity.CRITICAL, scanned: true },
      [cleanRepo.name]: { severity: null, scanned: true },
    });
    await stubRepoSecurityDetail(adminPage, repo.name, {
      ...severityCounts([Severity.CRITICAL, Severity.HIGH, Severity.HIGH]),
      recentScans: [recent(pkg.name, pkg.version, Severity.CRITICAL)],
    });
    await stubRepoSecurityDetail(adminPage, cleanRepo.name, {
      ...severityCounts([]),
      recentScans: [],
    });
    await stubVersionScans(
      adminPage,
      repo.name,
      new ScanScript({ status: ScanStatus.COMPLETED, findings: findings([Severity.CRITICAL]) }),
    );
    const repos = new RepositoriesPage(adminPage);
    const modal = new SecurityModal(adminPage, 'repo');
    await repos.goto();
    await repos.search(seeder.runId);

    await securityBadgeIn(repos.row(cleanRepo.name)).click();
    await modal.expectOpen();
    await expect(modal.root).toContainText(`${cleanRepo.name} — Security`);
    await expect(modal.breakdownEmpty).toHaveText('No known vulnerabilities found.');
    // Closed through the backdrop, which (unlike the X, see below) keeps the click off the row.
    const listPath = new URL(adminPage.url()).pathname;
    await modal.closeViaBackdrop();
    // A badge click is the badge's own: it must not have opened the repository.
    await expectStaysOnPage(adminPage, listPath);
    await expect(adminPage).toHaveURL(/\/repositories$/);

    await securityBadgeIn(repos.row(repo.name)).click();
    await modal.expectOpen();
    await expect(modal.root).toContainText(`${repo.name} — Security`);
    await expect(severityBadge(modal.breakdown, Severity.CRITICAL)).toHaveText(/Critical\s*1/);
    await expect(severityBadge(modal.breakdown, Severity.HIGH)).toHaveText(/High\s*2/);
    await expect(modal.root.locator('canvas')).toBeVisible();
    await expect(modal.recentScan(`${pkg.name}@${pkg.version}`)).toContainText(pkg.version);
    await expect(
      severityBadge(modal.recentScan(`${pkg.name}@${pkg.version}`), Severity.CRITICAL),
    ).toBeVisible();

    await modal.recentScan(`${pkg.name}@${pkg.version}`).click();

    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));
    await expect(modal.root).toHaveCount(0);
    await new ScanSection(adminPage).expectExpanded();
  });
});

for (const { protocol, type, packageLevel } of SECURITY_CASES) {
  test.describe(`SEC-02a ${protocol} package and version badges`, { tag: MOCKED }, () => {
    test('the package list shows the package badge, its modal opens, and no scanner means no badge', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(type);
      const pkg = await seedPackage(repo);
      const pages = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name);
      const list = packageListOf(pages, pkg, packageLevel);
      await stubSupportedRepoTypes(adminPage, ALL_TYPES);
      const summary = await stubArtifactSecuritySummary(adminPage, repo.name, {
        [pkg.name]: { severity: Severity.MEDIUM, findingCount: 4, scanned: true },
      });
      await stubArtifactSecurityDetail(adminPage, repo.name, {
        ...severityCounts([Severity.MEDIUM, Severity.MEDIUM, Severity.LOW, Severity.LOW]),
        recentScans: [recent(pkg.name, pkg.version, Severity.MEDIUM)],
      });
      await stubVersionScans(
        adminPage,
        repo.name,
        new ScanScript({ status: ScanStatus.COMPLETED, findings: findings([Severity.MEDIUM]) }),
        type,
      );
      const modal = new SecurityModal(adminPage, 'package');

      await list.goto();

      const badge = securityBadgeIn(list.row(pkg));
      await expect(badgeText(badge)).toHaveText('Medium');
      await expect(badgeText(badge)).toHaveAttribute('data-severity', 'MEDIUM');
      expect(summary.count).toBeGreaterThanOrEqual(1);

      await badge.click();
      await modal.expectOpen();
      await expect(modal.root).toContainText(pkg.name);
      await expect(severityBadge(modal.breakdown, Severity.MEDIUM)).toHaveText(/Medium\s*2/);
      await expect(severityBadge(modal.breakdown, Severity.LOW)).toHaveText(/Low\s*2/);
      await modal.recentScan(pkg.version).click();
      const detail = pages.detail(pkg);
      await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));
      await new ScanSection(adminPage).expectExpanded();

      // The same list once the type has no scanner: the row is there, its badge is not.
      await stubSupportedRepoTypes(adminPage, []);
      await adminPage.goto(list.path());
      await list.expectLoaded();
      await list.expectRow(pkg);
      await expect(securityBadgeIn(list.row(pkg))).toHaveCount(0);
    });

    test('the version list badges each version by its newest scan', async ({
      adminPage,
      seeder,
      seedVersions,
    }) => {
      const repo = await seeder.createRepo(type);
      const [scanned, running, failed, unscanned] = await seedVersions(repo, [
        '1.0.0',
        '1.1.0',
        '1.2.0',
        '1.3.0',
      ]);
      const versions = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).versions(scanned);
      await stubSupportedRepoTypes(adminPage, ALL_TYPES);
      await stubVersionSecuritySummary(adminPage, repo.name, {
        [scanned.version]: {
          severity: Severity.HIGH,
          findingCount: 2,
          scanned: true,
          latestScanStatus: ScanStatus.COMPLETED,
        },
        [running.version]: {
          scanned: false,
          findingCount: 0,
          latestScanStatus: ScanStatus.RUNNING,
        },
        [failed.version]: { scanned: false, findingCount: 0, latestScanStatus: ScanStatus.FAILED },
      });
      await stubVersionScans(
        adminPage,
        repo.name,
        new ScanScript({
          status: ScanStatus.COMPLETED,
          findings: [finding(Severity.HIGH), finding(Severity.HIGH)],
        }),
        type,
      );
      const modal = new SecurityModal(adminPage, 'version');

      await versions.goto();

      const badge = (version: { version: string; name: string }) =>
        securityBadgeIn(versions.row(version));
      await expect(badgeText(badge(scanned))).toHaveText('High');
      await expect(badgeText(badge(running))).toHaveText('Scanning...');
      await expect(badgeText(badge(failed))).toHaveText('Scan failed');
      await expect(badge(unscanned)).toHaveCount(0);

      await badge(scanned).click();

      await modal.expectOpen();
      await expect(modal.root).toContainText(scanned.version);
      await expect(severityBadge(modal.breakdown, Severity.HIGH)).toHaveText(/High\s*2/);
      await expect(modal.viewDetails).toBeVisible();
      await modal.viewDetails.click();
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(scanned);
      await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));
    });
  });
}

test.describe('SEC-02a closing a security modal', { tag: MOCKED }, () => {
  // The modals used to be rendered INSIDE the clickable row or card that hosts the badge, so a click
  // on the X reached the row and opened the repository or the package (RPS-1295); they are now moved
  // to the end of the body. The first two tests use a clean repository (a short dialog), the last one
  // a tall dialog with a chart.
  test('the X of the repository modal closes it and stays on the list', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubRepoSecuritySummary(adminPage, {
      [repo.name]: { severity: null, scanned: true },
    });
    await stubRepoSecurityDetail(adminPage, repo.name, { ...severityCounts([]), recentScans: [] });
    const repos = new RepositoriesPage(adminPage);
    const modal = new SecurityModal(adminPage, 'repo');
    await repos.goto();
    await repos.search(repo.name);
    await securityBadgeIn(repos.row(repo.name)).click();
    await modal.expectOpen();

    const listPath = new URL(adminPage.url()).pathname;
    await modal.close();

    await expectStaysOnPage(adminPage, listPath);
    await expect(adminPage).toHaveURL(/\/repositories$/);
  });

  test('the X of the package modal closes it and stays on the package list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubArtifactSecuritySummary(adminPage, repo.name, {
      [pkg.name]: { severity: null, findingCount: 0, scanned: true },
    });
    await stubArtifactSecurityDetail(adminPage, repo.name, {
      ...severityCounts([]),
      recentScans: [],
    });
    const modal = new SecurityModal(adminPage, 'package');
    await list.goto();
    await securityBadgeIn(list.row(pkg)).click();
    await modal.expectOpen();

    const listPath = new URL(adminPage.url()).pathname;
    await modal.close();

    await expectStaysOnPage(adminPage, listPath);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
  });

  test('the X of a repository modal with a chart can be reached at 1440x900', async ({
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
    const modal = new SecurityModal(adminPage, 'repo');
    await repos.goto();
    await repos.search(repo.name);
    await securityBadgeIn(repos.row(repo.name)).click();
    await modal.expectOpen();
    await expect(modal.root.locator('canvas')).toBeVisible();

    // A trial click runs every actionability check, hit target included, without clicking.
    await modal.closeButton.click({ trial: true, timeout: 3_000 });
  });
});
