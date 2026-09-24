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
 * SEC-02b: the scan section of a version's detail page (`<app-security-scan-section>`) and the
 * "See Security Details" link that opens it, against stubbed scan endpoints. The version pages are
 * real (a package seeded over raw HTTP); the scanner is not, so a `ScanScript` plays it and a test
 * moves the newest scan through its states while the panel polls (`GET /api/repos/{repo}/scans/{id}`
 * every 3 s while a scan is unfinished). Waits are bounded web-first assertions on what the panel shows.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { Toasts } from '../../../src/ui/pages/components.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { ScanSection } from '../../../src/ui/pages/security.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  ScanScript,
  ScanStatus,
  Severity,
  finding,
  findings,
  pageableOf,
  stubSupportedRepoTypes,
  stubVersionScans,
} from '../../../src/ui/security-stubs.js';
import { SECURITY_CASES } from './cases.js';

const MOCKED = '@mocked';
/** The panel polls an unfinished scan every 3 s, so one step needs a little more than that. */
const POLL_STEP = { timeout: 15_000 };
const ALL_TYPES = SECURITY_CASES.map(({ type }) => type);

test.describe('SEC-02b scan section', { tag: MOCKED }, () => {
  for (const { protocol, type } of SECURITY_CASES) {
    test(`${protocol}: "See Security Details" opens the section; no scanner means no section and no link`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(type);
      const pkg = await seedPackage(repo);
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(pkg);
      const script = new ScanScript({
        status: ScanStatus.COMPLETED,
        findings: findings([Severity.HIGH, Severity.LOW]),
      });
      await stubSupportedRepoTypes(adminPage, ALL_TYPES);
      await stubVersionScans(adminPage, repo.name, script, type);
      const section = new ScanSection(adminPage);

      await detail.goto();

      // Present but closed: a one-line summary is all there is until the link or the header is used.
      await expect(section.root).toBeVisible();
      await expect(section.headerBadge()).toHaveAttribute('data-severity', 'HIGH');
      await expect(section.findingsCount).toHaveText('2 findings');
      await section.expectCollapsed();
      await expect(section.detailsLink).toBeVisible();

      await section.detailsLink.click();

      await expect(adminPage).toHaveURL(/#security$/);
      await section.expectExpanded();
      await expect(section.status).toHaveText('Completed');
      await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*1/);
      await expect(section.bodyBadge(Severity.LOW)).toHaveText(/Low\s*1/);
      await expect(section.findingRows()).toHaveCount(2);

      // The same page while the type has no scanner (the e2e default): neither section nor link.
      await stubSupportedRepoTypes(adminPage, []);
      await adminPage.reload();
      await detail.expectLoaded();
      await expect(section.root).toHaveCount(0);
      await expect(section.detailsLink).toHaveCount(0);
    });
  }

  test('a scanner for another type does not give this type a section', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    await stubSupportedRepoTypes(adminPage, [RepoType.MAVEN, RepoType.PYPI]);
    await stubVersionScans(
      adminPage,
      repo.name,
      new ScanScript({ status: ScanStatus.COMPLETED, findings: findings([Severity.HIGH]) }),
    );

    await detail.goto();

    await expect(new ScanSection(adminPage).root).toHaveCount(0);
    await expect(new ScanSection(adminPage).detailsLink).toHaveCount(0);
  });

  test('a first scan goes Waiting, Queued, Scanning, Completed while the panel polls', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const reported = findings([
      Severity.CRITICAL,
      Severity.HIGH,
      Severity.HIGH,
      Severity.MEDIUM,
      Severity.LOW,
    ]);
    const script = new ScanScript({ status: ScanStatus.PENDING, findings: reported });
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    await stubVersionScans(adminPage, repo.name, script);
    const section = new ScanSection(adminPage);

    await detail.goto();

    // Nothing has completed: no severity, no "Clean", only the note that a first scan is waiting.
    await expect(section.rescanNote).toHaveText('Waiting...');
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.clean).toHaveCount(0);
    await expect(section.findingsCount).toHaveCount(0);

    await section.detailsLink.click();
    await section.expectExpanded();
    await expect(section.status).toHaveText('Waiting...');
    await expect(section.pollingIndicator).toContainText('Waiting...');
    await expect(section.rescanButton).toBeDisabled();
    await expect(section.findingRows()).toHaveCount(0);

    for (const [status, label] of [
      [ScanStatus.QUEUED, 'Queued...'],
      [ScanStatus.RUNNING, 'Scanning...'],
    ] as const) {
      script.setStatus(status);
      await expect(section.status).toHaveText(label, POLL_STEP);
      await expect(section.pollingIndicator).toContainText(label);
      await expect(section.rescanNote).toHaveText(label);
    }

    script.setStatus(ScanStatus.COMPLETED);
    await expect(section.status).toHaveText('Completed', POLL_STEP);

    // Done: polling indicator gone, the newest completed scan's numbers everywhere.
    await expect(section.pollingIndicator).toHaveCount(0);
    await expect(section.rescanNote).toHaveCount(0);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
    await expect(section.findingsCount).toHaveText('5 findings');
    await expect(section.bodyBadge(Severity.CRITICAL)).toHaveText(/Critical\s*1/);
    await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*2/);
    await expect(section.bodyBadge(Severity.MEDIUM)).toHaveText(/Medium\s*1/);
    await expect(section.bodyBadge(Severity.LOW)).toHaveText(/Low\s*1/);
    await expect(section.bodyBadge(Severity.UNKNOWN)).toHaveCount(0);
    await expect(section.findingRows()).toHaveCount(5);
    await expect(section.rescanButton).toBeEnabled();
    // It really polled through the states, not one lucky read.
    expect(script.calls.detail).toBeGreaterThanOrEqual(3);
  });

  test('a scan that never completes shows Failed, and a failed first scan can be repeated', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const script = new ScanScript({
      status: ScanStatus.RUNNING,
      findings: findings([Severity.HIGH]),
    });
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    await stubVersionScans(adminPage, repo.name, script);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.status).toHaveText('Scanning...');

    script.setStatus(ScanStatus.FAILED);

    await expect(section.status).toHaveText('Failed', POLL_STEP);
    await expect(section.pollingIndicator).toHaveCount(0);
    await expect(section.rescanNote).toHaveText('Failed');
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.findingRows()).toHaveCount(0);
    await expect(section.rescanButton).toBeEnabled();
  });

  test('a version that was never scanned says so, and Scan Now starts its first scan', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const script = new ScanScript();
    const stubs = await stubVersionScans(adminPage, repo.name, script);
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    const section = new ScanSection(adminPage);
    const toasts = new Toasts(adminPage);

    await detail.goto();

    await expect(section.neverScanned).toHaveText('Not scanned yet');
    await section.detailsLink.click();
    await expect(section.emptyBody).toHaveText('No vulnerability scans yet for this version.');
    await expect(section.status).toHaveCount(0);
    await expect(section.rescanButton).toHaveCount(0);

    await section.scanNowButton.click();

    await toasts.expectSuccess('Vulnerability scan triggered');
    expect(stubs.trigger.count).toBe(1);
    expect(stubs.trigger.last()?.pathname).toContain(`/versions/${pkg.version}/scan`);
    await expect(section.status).toHaveText('Waiting...');
    await expect(section.neverScanned).toHaveCount(0);
    await expect(section.rescanNote).toHaveText('Waiting...');
    await expect(section.historyItem(script.newest.id)).toBeVisible();
  });

  test('a clean version says Clean and has no findings', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.pypi, repo.name).detail(pkg);
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    await stubVersionScans(
      adminPage,
      repo.name,
      new ScanScript({ status: ScanStatus.COMPLETED }),
      RepoType.PYPI,
    );
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();

    await expect(section.clean).toHaveText('Clean');
    await expect(section.headerBadge()).toHaveCount(0);
    await section.expectExpanded();
    await expect(section.status).toHaveText('Completed');
    await expect(section.root.getByText('No known vulnerabilities found.')).toBeVisible();
    await expect(section.findingsTable).toHaveCount(0);
    await expect(section.pollingIndicator).toHaveCount(0);
  });

  test('Re-scan of a scanned version keeps the last known result until the new scan completes', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const script = new ScanScript({
      status: ScanStatus.COMPLETED,
      findings: findings([Severity.HIGH, Severity.MEDIUM]),
    });
    const stubs = await stubVersionScans(adminPage, repo.name, script);
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    const section = new ScanSection(adminPage);
    const toasts = new Toasts(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.findingsCount).toHaveText('2 findings');
    await expect(section.rescanNote).toHaveCount(0);

    await section.rescanButton.click();

    await toasts.expectSuccess('Vulnerability scan triggered');
    expect(stubs.trigger.count).toBe(1);
    // The new scan is unfinished, the numbers are still the previous scan's, and it says so.
    await expect(section.rescanNote).toHaveText('Rescanning...');
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'HIGH');
    await expect(section.findingsCount).toHaveText('2 findings');
    await expect(section.status).toHaveText('Waiting...');
    await expect(section.rescanButton).toBeDisabled();
    await expect(section.history.locator('[data-testid^="scan-history-item-"]')).toHaveCount(2);

    // The rescan finds one critical issue; the panel shows it once the scan completes.
    script.newest.findings = [finding(Severity.CRITICAL)];
    script.setStatus(ScanStatus.RUNNING);
    await expect(section.status).toHaveText('Scanning...', POLL_STEP);
    script.setStatus(ScanStatus.COMPLETED);
    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.rescanNote).toHaveCount(0);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
    await expect(section.findingsCount).toHaveText('1 finding');
    await expect(section.rescanButton).toBeEnabled();
  });

  test('the history lists every scan and selecting an older one shows its own findings', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const older = findings([Severity.CRITICAL, Severity.CRITICAL, Severity.HIGH]);
    const script = new ScanScript(
      { status: ScanStatus.COMPLETED, findings: findings([Severity.LOW]) },
      { status: ScanStatus.COMPLETED, findings: older },
      { status: ScanStatus.FAILED },
    );
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    await stubVersionScans(adminPage, repo.name, script);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();

    await expect(section.history.locator('[data-testid^="scan-history-item-"]')).toHaveCount(3);
    await expect(section.findingRows()).toHaveCount(1);
    await expect(section.bodyBadge(Severity.LOW)).toHaveText(/Low\s*1/);

    const olderScan = script.scans[1];
    await section.historyItem(olderScan.id).getByTestId('row-select').click();

    await expect(section.findingRows()).toHaveCount(3);
    await expect(section.findingRow(older[0].id!)).toContainText(older[0].cveId!);
    await expect(section.bodyBadge(Severity.CRITICAL)).toHaveText(/Critical\s*2/);
    await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*1/);
    await expect(section.bodyBadge(Severity.LOW)).toHaveCount(0);
    // The header still describes the version's newest completed scan.
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'LOW');
  });

  test('findings are paged ten at a time and can be sorted by severity', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const script = new ScanScript({
      status: ScanStatus.COMPLETED,
      findings: findings([
        Severity.LOW,
        Severity.HIGH,
        Severity.UNKNOWN,
        Severity.CRITICAL,
        Severity.MEDIUM,
        Severity.LOW,
        Severity.HIGH,
        Severity.UNKNOWN,
        Severity.MEDIUM,
        Severity.CRITICAL,
        Severity.LOW,
        Severity.HIGH,
      ]),
    });
    const stubs = await stubVersionScans(adminPage, repo.name, script);
    await stubSupportedRepoTypes(adminPage, ALL_TYPES);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    const firstRowSeverity = () =>
      section.findingRows().first().getByTestId('severity-badge').getAttribute('data-severity');

    // Ascending by default: the worst first. Twelve findings, so two pages.
    await expect(section.findingRows()).toHaveCount(10);
    await expect.poll(firstRowSeverity).toBe('CRITICAL');
    await expect(section.findingsPagination.root).toBeVisible();

    const twoButton = section.findingsPagination.page(2);
    await twoButton.click();
    await expect.poll(() => pageableOf(stubs.findings.last()!).page).toBe(1);
    await expect(section.findingRows()).toHaveCount(2);

    // Sorting again reverses the order and starts over at page 1.
    await section.sortBySeverity.click();
    await expect
      .poll(() => pageableOf(stubs.findings.last()!))
      .toMatchObject({
        page: 0,
        sortDirection: 'DESC',
      });
    await expect(section.findingRows()).toHaveCount(10);
    await expect.poll(firstRowSeverity).toBe('UNKNOWN');
  });
});
