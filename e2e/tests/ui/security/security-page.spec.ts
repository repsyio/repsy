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
 * SEC-02d: the `/security` page (admin only), against a stubbed scan list and summary. Runs in the
 * default stack: with `SECURITY_SCANNER=disabled` there is nothing real to list, and what these tests
 * assert is what the panel draws and which requests it sends. The real Trivy path is RPS-1270.
 *
 * The scan rows are pure stub data (repo names built from the run id, so a parallel test never
 * sees them); the rows that must be CLICKED point at a package the test seeded for real, so the
 * navigation ends on a real version page (`#security`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { ScanSection, SecurityPage } from '../../../src/ui/pages/security.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  ScanScript,
  ScanStatus,
  Severity,
  findings,
  scanInfo,
  severityCounts,
  stubSecurityScans,
  stubSecurityScansSummary,
  stubSupportedRepoTypes,
  stubVersionScans,
  type VulnerabilityScanInfo,
} from '../../../src/ui/security-stubs.js';
import { SECURITY_CASES, atSecuritySection } from './cases.js';

const MOCKED = '@mocked';
const BASE = Date.parse('2026-09-10T12:00:00Z');

/** An ISO timestamp `minutesAgo` before the fixed base: rows come newest first, so order is stable. */
function at(minutesAgo: number): string {
  return new Date(BASE - minutesAgo * 60_000).toISOString();
}

/**
 * 21 scans, newest first: an unfinished NPM scan, a failed PYPI one, a clean NPM one, twelve HIGH
 * NPM, three MEDIUM PYPI, two CRITICAL MAVEN, one LOW DOCKER. Three pages of ten.
 */
function scanDataset(run: string): { scans: VulnerabilityScanInfo[]; ids: Record<string, string> } {
  const named = (kind: string, repoType: RepoType, index: number, extra = {}) =>
    scanInfo({
      repoName: `${run}-${kind}`,
      repoType,
      artifactName: `pkg-${index}`,
      artifactVersion: `1.0.${index}`,
      createdAt: at(index),
      ...extra,
    });
  const scans: VulnerabilityScanInfo[] = [
    named('npm', RepoType.NPM, 0, {
      status: ScanStatus.PENDING,
      highestSeverity: undefined,
      completedAt: null,
    }),
    named('pypi', RepoType.PYPI, 1, {
      status: ScanStatus.FAILED,
      highestSeverity: undefined,
      errorMessage: 'scanner unavailable',
    }),
    named('npm', RepoType.NPM, 2, { highestSeverity: undefined }),
  ];
  for (let index = 3; index < 15; index++) {
    scans.push(named('npm', RepoType.NPM, index, { highestSeverity: Severity.HIGH }));
  }
  for (let index = 15; index < 18; index++) {
    scans.push(named('pypi', RepoType.PYPI, index, { highestSeverity: Severity.MEDIUM }));
  }
  for (let index = 18; index < 20; index++) {
    scans.push(named('maven', RepoType.MAVEN, index, { highestSeverity: Severity.CRITICAL }));
  }
  scans.push(named('docker', RepoType.DOCKER, 20, { highestSeverity: Severity.LOW }));
  return {
    scans,
    ids: {
      pending: scans[0].id!,
      failed: scans[1].id!,
      clean: scans[2].id!,
      high: scans[3].id!,
      medium: scans[15].id!,
      critical: scans[18].id!,
      low: scans[20].id!,
    },
  };
}

const SUMMARY = {
  ...severityCounts([
    ...Array(2).fill(Severity.CRITICAL),
    ...Array(12).fill(Severity.HIGH),
    ...Array(3).fill(Severity.MEDIUM),
    Severity.LOW,
  ]),
};

const SUPPORTED = [RepoType.MAVEN, RepoType.NPM, RepoType.PYPI, RepoType.DOCKER];

test.describe('SEC-02d /security', { tag: MOCKED }, () => {
  test(
    'draws the severity distribution and one row per scan, with a badge per outcome',
    { tag: ['@smoke'] },
    async ({ adminPage, seeder }) => {
      const { scans, ids } = scanDataset(seeder.runId);
      await stubSupportedRepoTypes(adminPage, SUPPORTED);
      await stubSecurityScans(adminPage, scans);
      await stubSecurityScansSummary(adminPage, SUMMARY);
      const security = new SecurityPage(adminPage);

      await security.goto();

      // The distribution card: a badge (with its count) per severity that has findings, none for the
      // empty ones, and the doughnut actually painted.
      await expect(security.distributionBadge(Severity.CRITICAL)).toHaveText(/Critical\s*2/);
      await expect(security.distributionBadge(Severity.HIGH)).toHaveText(/High\s*12/);
      await expect(security.distributionBadge(Severity.MEDIUM)).toHaveText(/Medium\s*3/);
      await expect(security.distributionBadge(Severity.LOW)).toHaveText(/Low\s*1/);
      await expect(security.distributionBadge(Severity.UNKNOWN)).toHaveCount(0);
      await expect(security.distributionEmpty).toHaveCount(0);
      await expect(security.chartCanvas).toBeVisible();
      await expect.poll(() => security.chartHasPixels()).toBe(true);

      // Ten rows a page, newest first: the unfinished, the failed and the clean scan lead.
      await expect(security.rows()).toHaveCount(10);
      const pending = security.row(ids.pending);
      await expect(pending.getByTestId('row-status')).toHaveText('PENDING');
      await expect(pending.getByTestId('severity-badge')).toHaveCount(0);
      await expect(pending.getByTestId('row-clean')).toHaveCount(0);
      await expect(pending.getByTestId('row-failed')).toHaveCount(0);

      await expect(security.row(ids.failed).getByTestId('row-failed')).toHaveText('Scan Failed');
      await expect(security.row(ids.failed).getByTestId('row-status')).toHaveText('FAILED');

      await expect(security.row(ids.clean).getByTestId('row-clean')).toHaveText('Clean');
      await expect(security.row(ids.clean).getByTestId('row-status')).toHaveText('COMPLETED');

      const high = security.row(ids.high);
      await expect(high.getByTestId('severity-badge')).toHaveAttribute('data-severity', 'HIGH');
      await expect(high.getByTestId('severity-badge')).toHaveText('High');
      const highScan = scans.find((scan) => scan.id === ids.high)!;
      await expect(security.artifactOf(ids.high)).toHaveText(
        `${highScan.repoName} / ${highScan.artifactName}`,
      );
      await expect(security.metaOf(ids.high)).toContainText('NPM');
      await expect(security.metaOf(ids.high)).toContainText(highScan.artifactVersion!);
    },
  );

  test('filters by severity, repo type and repository name, and Refresh clears them', async ({
    adminPage,
    seeder,
  }) => {
    const { scans } = scanDataset(seeder.runId);
    await stubSupportedRepoTypes(adminPage, SUPPORTED);
    const list = await stubSecurityScans(adminPage, scans);
    const summary = await stubSecurityScansSummary(adminPage, SUMMARY);
    const security = new SecurityPage(adminPage);
    await security.goto();
    const lastQuery = (name: string) => list.last()?.searchParams.get(name) ?? null;

    // The type filter offers All plus exactly the supported types, sorted.
    expect(await security.typeFilter.options()).toEqual(['ALL', 'DOCKER', 'MAVEN', 'NPM', 'PYPI']);
    expect(await security.severityFilter.options()).toEqual([
      'ALL',
      'CRITICAL',
      'HIGH',
      'MEDIUM',
      'LOW',
      'UNKNOWN',
    ]);

    // Severity: sent as `severity`, and only that severity's rows come back.
    await security.severityFilter.choose('CRITICAL');
    await expect.poll(() => lastQuery('severity')).toBe('CRITICAL');
    await expect(security.rows()).toHaveCount(2);
    await expect(security.list.getByTestId('severity-badge')).toHaveCount(2);
    for (const badge of await security.list.getByTestId('severity-badge').all()) {
      await expect(badge).toHaveAttribute('data-severity', 'CRITICAL');
    }
    await expect(security.pagination.root).toBeHidden();

    // Repo type on top of it: no critical scan is an NPM one, so the list is the empty state.
    await security.typeFilter.choose('NPM');
    await expect.poll(() => lastQuery('repoType')).toBe('NPM');
    expect(lastQuery('severity')).toBe('CRITICAL');
    await expect(security.empty).toHaveText('No vulnerability scans match these filters.');
    await expect(security.list).toHaveCount(0);

    // The card is the system-wide distribution: no filter moves it, and it is not re-fetched.
    await expect(security.distributionBadge(Severity.HIGH)).toHaveText(/High\s*12/);
    expect(summary.count).toBe(1);

    // Back to All severities: NPM's own rows (clean + pending + 12 HIGH = 14), two pages.
    await security.severityFilter.choose('ALL');
    await expect.poll(() => lastQuery('severity')).toBeNull();
    expect(lastQuery('repoType')).toBe('NPM');
    await expect(security.rows()).toHaveCount(10);
    await expect(security.pagination.page(2)).toBeVisible();

    // The repository search: sent as `repoName`, a substring of the repo name. It starts a new query
    // from page 1.
    await security.typeFilter.choose('ALL');
    await security.search(`${seeder.runId}-maven`);
    await expect.poll(() => lastQuery('repoName')).toBe(`${seeder.runId}-maven`);
    await expect(security.rows()).toHaveCount(2);
    await expect(security.metaOf(scans[18].id!)).toContainText('MAVEN');

    // Refresh puts everything back: both selectors on All, page 1, no filter sent.
    await security.refreshButton.click();
    await expect.poll(() => lastQuery('repoName')).toBeNull();
    expect(lastQuery('severity')).toBeNull();
    expect(lastQuery('repoType')).toBeNull();
    await expect(security.severityFilter.toggle).toHaveText('ALL');
    await expect(security.typeFilter.toggle).toHaveText('ALL');
    await expect(security.rows()).toHaveCount(10);
  });

  test('Refresh also empties the search box', async ({ adminPage, seeder }) => {
    const { scans } = scanDataset(seeder.runId);
    await stubSupportedRepoTypes(adminPage, SUPPORTED);
    await stubSecurityScans(adminPage, scans);
    await stubSecurityScansSummary(adminPage, SUMMARY);
    const security = new SecurityPage(adminPage);
    await security.goto();
    await security.search(`${seeder.runId}-maven`);
    await expect(security.rows()).toHaveCount(2);

    await security.refreshButton.click();

    await expect(security.rows()).toHaveCount(10);
    await expect(security.searchInput).toHaveValue('');
  });

  test('pages through 21 scans, ten at a time', async ({ adminPage, seeder }) => {
    const { scans } = scanDataset(seeder.runId);
    await stubSupportedRepoTypes(adminPage, SUPPORTED);
    const list = await stubSecurityScans(adminPage, scans);
    await stubSecurityScansSummary(adminPage, SUMMARY);
    const security = new SecurityPage(adminPage);
    await security.goto();

    await expect(security.pagination.root).toBeVisible();
    await expect(security.rows()).toHaveCount(10);

    const third = security.pagination.page(3);
    await third.click();
    await expect.poll(() => list.last()?.searchParams.get('page')).toBe('2');
    await expect(security.rows()).toHaveCount(1);
    await expect(security.row(scans[20].id!)).toBeVisible();

    await security.pagination.prev.click();
    await expect.poll(() => list.last()?.searchParams.get('page')).toBe('1');
    await expect(security.rows()).toHaveCount(10);
    await expect(security.row(scans[10].id!)).toBeVisible();
    await expect(security.row(scans[0].id!)).toHaveCount(0);

    const first = security.pagination.page(1);
    await first.click();
    await expect.poll(() => list.last()?.searchParams.get('page')).toBe('0');
    await expect(security.row(scans[0].id!)).toBeVisible();
  });

  test('says so when nothing was found: no findings and no scans', async ({ adminPage }) => {
    await stubSupportedRepoTypes(adminPage, SUPPORTED);
    await stubSecurityScans(adminPage, []);
    await stubSecurityScansSummary(adminPage, severityCounts([]));
    const security = new SecurityPage(adminPage);

    await security.goto();

    await expect(security.distributionEmpty).toHaveText('No known vulnerabilities found.');
    await expect(security.chart).toHaveCount(0);
    await expect(security.empty).toHaveText('No vulnerability scans match these filters.');
    await expect(security.rows()).toHaveCount(0);
    await expect(security.pagination.root).toBeHidden();
  });

  test('is reached from the admin sidebar, also while no scanner is configured', async ({
    adminPage,
  }) => {
    // "No scanner" is `supported-repo-types` = `[]`, which is also what the e2e stack answers
    // (`SECURITY_SCANNER=disabled`); stubbed explicitly anyway. The scan list and its summary are
    // stubbed empty because `/security` lists the scans of the WHOLE instance, so a package test
    // running in parallel with a scan row would break the empty-state assertions (RPS-1303).
    await stubSupportedRepoTypes(adminPage, []);
    await stubSecurityScans(adminPage, []);
    await stubSecurityScansSummary(adminPage, severityCounts([]));
    const dashboard = new DashboardPage(adminPage);
    await dashboard.goto();

    await new Shell(adminPage).sidebar.security.click();

    const security = new SecurityPage(adminPage);
    await expect(adminPage).toHaveURL(/\/security$/);
    await security.expectLoaded();
    await expect(security.distributionEmpty).toHaveText('No known vulnerabilities found.');
    await expect(security.empty).toHaveText('No vulnerability scans match these filters.');
    // No scanner, so the type filter has nothing to offer but All.
    expect(await security.typeFilter.options()).toEqual(['ALL']);
  });

  for (const { protocol, type } of SECURITY_CASES) {
    test(`a ${protocol} row opens the version's page at #security`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(type);
      const pkg = await seedPackage(repo);
      const pages = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name);
      const scan = scanInfo({
        repoName: repo.name,
        repoType: type,
        artifactName: pkg.name,
        artifactVersion: pkg.version,
        highestSeverity: Severity.HIGH,
      });
      // Also the stubs the version page will ask for, so the section it lands on has a scan to show.
      const script = new ScanScript({
        status: ScanStatus.COMPLETED,
        findings: findings([Severity.HIGH, Severity.MEDIUM]),
      });
      await stubSupportedRepoTypes(adminPage, SUPPORTED);
      await stubSecurityScans(adminPage, [scan]);
      await stubSecurityScansSummary(adminPage, severityCounts([Severity.HIGH]));
      await stubVersionScans(adminPage, repo.name, script, type);
      const security = new SecurityPage(adminPage);
      await security.goto();

      await security.row(scan.id!).click();

      // The panel builds the route itself (`buildArtifactDetailRoute`); the descriptor is the oracle.
      const detailPath = pages.detail(pkg).path();
      await expect(adminPage).toHaveURL(atSecuritySection(detailPath));
      const section = new ScanSection(adminPage);
      await expect(section.root).toBeVisible();
      await section.expectExpanded();
      await expect(section.status).toHaveText('Completed');
      await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*1/);
    });
  }

  test('a docker scan of a bare digest has no page to open, so its row does nothing', async ({
    adminPage,
    seeder,
  }) => {
    const digest = scanInfo({
      repoName: `${seeder.runId}-docker`,
      repoType: RepoType.DOCKER,
      artifactName: 'image',
      artifactVersion: `sha256:${'a'.repeat(64)}`,
      highestSeverity: Severity.LOW,
    });
    await stubSupportedRepoTypes(adminPage, SUPPORTED);
    await stubSecurityScans(adminPage, [digest]);
    await stubSecurityScansSummary(adminPage, severityCounts([Severity.LOW]));
    const security = new SecurityPage(adminPage);
    await security.goto();

    await security.row(digest.id!).click();

    await expect(adminPage).toHaveURL(/\/security$/);
    await expect(security.row(digest.id!)).toBeVisible();
  });
});
