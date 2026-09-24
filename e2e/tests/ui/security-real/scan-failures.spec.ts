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
 * SEC-01 (`@scanner`), FAILED scans and re-scans: a scan the scanner fails (`fail` in the package
 * name), a scanner that refuses the submit (`unavailable`: the stub answers 503 to `POST /scan`, so the
 * backend never gets a job), and the Re-scan / Scan Now buttons that start another scan, whose result
 * the test scripts through the stub's `/control` API (`PUT /control/scripts`, per artifact name).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { Toasts } from '../../../src/ui/pages/components.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { ScanSection, badgeText, securityBadgeIn } from '../../../src/ui/pages/security.js';
import {
  POLL_STEP,
  SCANNER_TAG,
  SCANNER_VERSION,
  expect,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  test,
} from '../../../src/ui/scanner-fixtures.js';
import { FAIL_MESSAGE } from '../../../src/stubs/scanner/rules.ts';

test.describe('SEC-01 failed scans and re-scans', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  test('a scan the scanner fails shows Failed, keeps no findings, and the repo says Scan failed', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'fail'),
    });
    const scan = await newestFinishedScan(panelApi, repo.name, pkg);
    // The scanner's own message reaches the scan record.
    expect(scan).toMatchObject({ status: 'FAILED', errorMessage: FAIL_MESSAGE });
    expect(scan.highestSeverity).toBeFalsy();
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const detail = pages.detail(pkg);
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();

    await expect(section.status).toHaveText('Failed');
    await expect(section.rescanNote).toHaveText('Failed');
    await expect(section.pollingIndicator).toHaveCount(0);
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.clean).toHaveCount(0);
    await expect(section.findingRows()).toHaveCount(0);
    // A failed scan can be repeated.
    await expect(section.rescanButton).toBeEnabled();
    await expect(section.historyItem(scan.id!)).toBeVisible();

    const list = pages.list();
    await list.goto();
    await expect(badgeText(securityBadgeIn(list.row(pkg)))).toHaveText('Scan failed');
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    await expect(badgeText(securityBadgeIn(repos.row(repo.name)))).toHaveText('Scan failed');
  });

  test('a scanner that refuses the submit fails the scan with the reason, and nothing is scanned', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
    scanner,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('pypi', seeder.runId, 'unavailable'),
    });

    const scan = await newestFinishedScan(panelApi, repo.name, pkg);

    // The scanner was asked, and refused: no job was ever made.
    const calls = await scanner.calls(pkg.name);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ result: 'refused' });
    // The backend's own wording of the refusal: it carries the scanner's HTTP status.
    expect(scan).toMatchObject({ status: 'FAILED' });
    expect(scan.errorMessage).toContain('503');
    expect(scan.scannerVersion).toBeFalsy();

    const detail = protocolPages(adminPage, DESCRIPTORS.pypi, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.status).toHaveText('Failed');
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.findingRows()).toHaveCount(0);
  });

  test('Re-scan of a failed scan starts another one, and the new result replaces the failure', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
    scanner,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'fail'),
    });
    const failed = await newestFinishedScan(panelApi, repo.name, pkg);
    expect(failed.status).toBe('FAILED');
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);
    const toasts = new Toasts(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.status).toHaveText('Failed');

    // The scanner recovers: the next scan of this package finds one high issue.
    await scanner.script(pkg.name, { outcome: 'completed', findings: ['HIGH'] });
    await section.rescanButton.click();

    await toasts.expectSuccess('Vulnerability scan triggered');
    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'HIGH');
    await expect(section.findingsCount).toHaveText('1 finding');
    await expect(section.rescanNote).toHaveCount(0);
    // Both scans are in the history, and the scanner was asked twice.
    await expect(section.history.locator('[data-testid^="scan-history-item-"]')).toHaveCount(2);
    await expect(section.historyItem(failed.id!)).toBeVisible();
    const scans = await panelApi.listVersionScans(repo.name, pkg.name, pkg.version);
    expect(scans.map((scan) => scan.status)).toEqual(['COMPLETED', 'FAILED']);
    expect(scans[0].scannerVersion).toBe(SCANNER_VERSION);
    expect(await scanner.calls(pkg.name)).toHaveLength(2);
  });

  test('Re-scan of a clean version shows Rescanning with the old result until the new scan completes', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
    scanner,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'clean'),
    });
    const first = await newestFinishedScan(panelApi, repo.name, pkg);
    expect(first).toMatchObject({ status: 'COMPLETED' });
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.clean).toHaveText('Clean');

    // A new advisory has been published: the next scan finds a critical issue, and takes a while.
    await scanner.script(pkg.name, {
      findings: ['CRITICAL', 'MEDIUM'],
      queueSeconds: 5,
      runSeconds: 5,
    });
    await section.rescanButton.click();

    // Unfinished: the last known result (clean) stays, marked as being rescanned.
    await expect(section.rescanNote).toHaveText('Rescanning...');
    await expect(section.clean).toHaveText('Clean');
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.rescanButton).toBeDisabled();
    await expect(section.status).toHaveText('Queued...', POLL_STEP);
    await expect(section.status).toHaveText('Scanning...', POLL_STEP);

    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.rescanNote).toHaveCount(0);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
    await expect(section.findingsCount).toHaveText('2 findings');
    await expect(section.clean).toHaveCount(0);
    await expect(section.history.locator('[data-testid^="scan-history-item-"]')).toHaveCount(2);

    // The older scan is still there, and selecting it shows what it found: nothing.
    await section.historyItem(first.id!).getByTestId('row-select').click();
    await expect(section.findingRows()).toHaveCount(0);
    await expect(section.root.getByText('No known vulnerabilities found.')).toBeVisible();
    // The header still describes the newest completed scan.
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
  });
});
