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
 * SEC-01 (`@scanner`), the two pages that add up the scans of many repos, with REAL data: the
 * `/security` page (severity distribution, scan list, filters) and the dashboard's Security Overview.
 *
 * Both cover the whole instance, and other tests scan in parallel, so every test here works on ONE
 * repo of its own: `/security` filters by the repo name (the backend matches it exactly), and the
 * dashboard number is compared with what the backend itself says instead of a fixed value.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { ScanSection, SecurityPage } from '../../../src/ui/pages/security.js';
import {
  SCANNER_TAG,
  SCAN_TIMEOUT,
  expect,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  test,
} from '../../../src/ui/scanner-fixtures.js';
import { Severity } from '../../../src/ui/security-stubs.js';
import { atSecuritySection } from '../security/cases.js';

test.describe('SEC-01 the /security page and the dashboard', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  test('/security lists the scans of a repo with their outcomes and filters them', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = async (directive: string) =>
      seedPackage(repo, { name: scanPackageName('npm', seeder.runId, directive) });
    const critical = await pkg('vuln-critical');
    const high = await pkg('vuln-high');
    const clean = await pkg('clean');
    const failed = await pkg('fail');
    const scans = new Map<string, string>();
    for (const seeded of [critical, high, clean, failed]) {
      scans.set(seeded.name, (await newestFinishedScan(panelApi, repo.name, seeded)).id!);
    }
    const scanId = (seeded: { name: string }): string => scans.get(seeded.name)!;
    const security = new SecurityPage(adminPage);

    await security.goto();
    await security.search(repo.name);

    // One row per scan of the repo, newest first (the four packages were pushed in this order).
    await expect(security.rows()).toHaveCount(4, SCAN_TIMEOUT);
    await expect(security.artifactOf(scanId(critical))).toHaveText(
      `${repo.name} / ${critical.name}`,
    );
    await expect(security.metaOf(scanId(critical))).toContainText('NPM');
    await expect(security.metaOf(scanId(critical))).toContainText(critical.version);
    const badgeOf = (seeded: { name: string }) =>
      security.row(scanId(seeded)).getByTestId('severity-badge');
    await expect(badgeOf(critical)).toHaveAttribute('data-severity', 'CRITICAL');
    await expect(badgeOf(high)).toHaveAttribute('data-severity', 'HIGH');
    await expect(security.row(scanId(clean)).getByTestId('row-clean')).toHaveText('Clean');
    await expect(security.row(scanId(failed)).getByTestId('row-failed')).toHaveText('Scan Failed');
    await expect(security.row(scanId(failed)).getByTestId('row-status')).toHaveText('FAILED');
    await expect(security.row(scanId(critical)).getByTestId('row-status')).toHaveText('COMPLETED');

    // Severity filter: the scans whose worst finding is that severity.
    await security.severityFilter.choose('HIGH');
    await expect(security.rows()).toHaveCount(1);
    await expect(security.row(scanId(high))).toBeVisible();
    await security.severityFilter.choose('CRITICAL');
    await expect(security.rows()).toHaveCount(1);
    await expect(security.row(scanId(critical))).toBeVisible();
    await security.severityFilter.choose('LOW');
    await expect(security.empty).toHaveText('No vulnerability scans match these filters.');

    // Repo type filter on top of All severities: the repo is NPM, so PYPI has nothing of it.
    await security.severityFilter.choose('ALL');
    await expect(security.rows()).toHaveCount(4);
    await security.typeFilter.choose('PYPI');
    await expect(security.empty).toHaveText('No vulnerability scans match these filters.');
    await security.typeFilter.choose('NPM');
    await expect(security.rows()).toHaveCount(4);

    // A row opens the scanned version's page at its security section, on the scan's real findings.
    await security.row(scanId(high)).click();
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(high);
    await expect(adminPage).toHaveURL(atSecuritySection(detail.path()));
    const section = new ScanSection(adminPage);
    await section.expectExpanded();
    await expect(section.status).toHaveText('Completed');
    await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*2/);
    await expect(section.bodyBadge(Severity.MEDIUM)).toHaveText(/Medium\s*1/);
  });

  test("the severity distribution is the backend's summary of every scanned version, this repo included", async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const critical = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-critical'),
    });
    const high = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-high'),
    });
    await newestFinishedScan(panelApi, repo.name, critical);
    await newestFinishedScan(panelApi, repo.name, high);
    const security = new SecurityPage(adminPage);
    const severities = [
      [Severity.CRITICAL, 'criticalCount'],
      [Severity.HIGH, 'highCount'],
      [Severity.MEDIUM, 'mediumCount'],
      [Severity.LOW, 'lowCount'],
      [Severity.UNKNOWN, 'unknownCount'],
    ] as const;
    // What the card shows: one badge per severity that has findings (`Critical 2`), none otherwise.
    const shown = async (): Promise<Record<string, number>> => {
      const counts: Record<string, number> = {};
      for (const [severity, key] of severities) {
        const texts = await security.distributionBadge(severity).allInnerTexts();
        counts[key] = texts.reduce((sum, text) => sum + Number(/\d+/.exec(text)?.[0]), 0);
      }
      return counts;
    };

    // This repo alone adds 2 critical + 3 high + 1 medium findings (vuln-critical: 2 C + 1 H,
    // vuln-high: 2 H + 1 M), and the instance-wide summary contains them. Other tests scan in parallel,
    // so the card is compared with the backend's answer at the same moment, retried until they agree.
    await expect
      .poll(async () => {
        await security.goto();
        const summary = await panelApi.securityScansSummary();
        const card = await shown();
        const same = severities.every(([, key]) => card[key] === (summary[key] ?? 0));
        return same && card.criticalCount >= 2 && card.highCount >= 3 && card.mediumCount >= 1;
      }, SCAN_TIMEOUT)
      .toBe(true);
    await expect.poll(() => security.chartHasPixels()).toBe(true);
  });

  test('/security tells the types the scanner supports apart from those it does not', async ({
    adminPage,
  }) => {
    const security = new SecurityPage(adminPage);

    await security.goto();

    // The type filter offers All plus exactly the types the backend's scanner supports.
    expect(await security.typeFilter.options()).toEqual(['ALL', 'DOCKER', 'MAVEN', 'NPM', 'PYPI']);
  });

  test('the dashboard counts the repos with critical or high findings the way the backend does', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const criticalOrHigh = async (): Promise<number> =>
      Object.values(await panelApi.repoSecuritySummary()).filter(
        (entry) => entry.severity === Severity.CRITICAL || entry.severity === Severity.HIGH,
      ).length;
    const repo = await seeder.createRepo(RepoType.PYPI);
    const clean = await seeder.createRepo(RepoType.PYPI);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('pypi', seeder.runId, 'vuln-critical'),
    });
    const cleanPkg = await seedPackage(clean, {
      name: scanPackageName('pypi', seeder.runId, 'clean'),
    });
    await newestFinishedScan(panelApi, repo.name, pkg);
    await newestFinishedScan(panelApi, clean.name, cleanPkg);

    // The backend's own summary of the repo: worst severity critical, the clean repo has none.
    const summary = await panelApi.repoSecuritySummary([repo.name, clean.name]);
    expect(summary[repo.name]).toMatchObject({ severity: Severity.CRITICAL, scanned: true });
    expect(summary[clean.name]?.severity).toBeFalsy();
    expect(summary[clean.name]).toMatchObject({ scanned: true });
    expect(await criticalOrHigh()).toBeGreaterThanOrEqual(1);

    await dashboard.open();

    // Other tests may add scanned repos while this reads, so the card is compared with the backend's
    // answer at the same moment (retried until both agree), not with a number fixed in advance.
    await expect
      .poll(async () => {
        await adminPage.reload();
        await dashboard.securityCriticalHigh.waitFor();
        const shown = Number(await dashboard.securityCriticalHigh.innerText());
        return shown === (await criticalOrHigh()) && shown >= 1;
      }, SCAN_TIMEOUT)
      .toBe(true);
  });
});
