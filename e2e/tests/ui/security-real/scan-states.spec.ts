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
 * SEC-01 (`@scanner`), the scan STATES: what the panel shows while a real scan is pending, queued,
 * scanning and completed. The stub holds each state for a few seconds when the package name says
 * `slow` (5 s to answer the submit, 6 s queued, 6 s running: `src/stubs/scanner/rules.ts`), while the
 * backend polls the scanner every second and the panel polls the backend every 3 s, so every state is
 * on screen long enough to be seen. Every wait is a bounded web-first assertion; no fixed sleep.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { ScanSection, badgeText, securityBadgeIn } from '../../../src/ui/pages/security.js';
import {
  POLL_STEP,
  SCANNER_TAG,
  SCAN_TIMEOUT,
  expect,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  test,
} from '../../../src/ui/scanner-fixtures.js';
import { Severity } from '../../../src/ui/security-stubs.js';
import { repoPath } from '../../../src/repo-url.js';

test.describe('SEC-01 scan states', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  test('a first scan goes Waiting, Queued, Scanning, Completed while the panel polls', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-high-slow'),
    });
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();

    // The backend created the scan when the package was pushed and the scanner has not accepted it
    // yet (its answer to the submit is held): PENDING, shown as Waiting.
    await expect(section.status).toHaveText('Waiting...');
    await expect(section.pollingIndicator).toContainText('Waiting...');
    await expect(section.rescanNote).toHaveText('Waiting...');
    await expect(section.headerBadge()).toHaveCount(0);
    await expect(section.clean).toHaveCount(0);
    await expect(section.findingRows()).toHaveCount(0);
    await expect(section.rescanButton).toBeDisabled();

    for (const label of ['Queued...', 'Scanning...']) {
      await expect(section.status).toHaveText(label, POLL_STEP);
      await expect(section.pollingIndicator).toContainText(label);
      await expect(section.rescanNote).toHaveText(label);
      await expect(section.findingRows()).toHaveCount(0);
    }

    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.pollingIndicator).toHaveCount(0);
    await expect(section.rescanNote).toHaveCount(0);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'HIGH');
    await expect(section.findingsCount).toHaveText('3 findings');
    await expect(section.bodyBadge(Severity.HIGH)).toHaveText(/High\s*2/);
    await expect(section.bodyBadge(Severity.MEDIUM)).toHaveText(/Medium\s*1/);
    await expect(section.findingRows()).toHaveCount(3);
    await expect(section.rescanButton).toBeEnabled();

    // The API agrees, and the scan really went through the states on its way.
    expect(await newestFinishedScan(panelApi, repo.name, pkg)).toMatchObject({
      status: 'COMPLETED',
      highestSeverity: 'HIGH',
    });
  });

  test('the lists say Scanning... while the scan is unfinished, and change to the severity without a reload', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    // The lists watch their security summary while a scan is unfinished (RPS-1128): the first
    // re-fetch after 10 s, the next after 15 s more. The scan is queued for 15 s, so it is done by the
    // second re-fetch and the badge changes on a page that was never reloaded (RPS-1352).
    test.setTimeout(180_000);

    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-critical-queue15'),
    });
    // One page per list, all loaded once and left alone, so the three polls run side by side.
    const context = adminPage.context();
    const repoPage = adminPage;
    const packagePage = await context.newPage();
    const versionPage = await context.newPage();
    const repos = new RepositoriesPage(repoPage);
    const list = protocolPages(packagePage, DESCRIPTORS.npm, repo.name).list();
    const versions = protocolPages(versionPage, DESCRIPTORS.npm, repo.name).versions(pkg);

    await repos.goto();
    await repos.search(repo.name);
    const repoBadge = badgeText(securityBadgeIn(repos.row(repo.name)));
    await expect(repoBadge).toHaveText('Scanning...');
    await list.goto();
    const packageBadge = badgeText(securityBadgeIn(list.row(pkg)));
    await expect(packageBadge).toHaveText('Scanning...');
    await versions.goto();
    const versionBadge = badgeText(securityBadgeIn(versions.row(pkg)));
    await expect(versionBadge).toHaveText('Scanning...');

    // No goto and no reload from here on: only the polling of each list can change its badge.
    for (const badge of [versionBadge, packageBadge, repoBadge]) {
      await expect(badge).toHaveText('Critical', SCAN_TIMEOUT);
    }
    expect(await newestFinishedScan(panelApi, repo.name, pkg)).toMatchObject({
      status: 'COMPLETED',
      highestSeverity: 'CRITICAL',
    });

    // A fresh load agrees with what the polling showed.
    await versions.goto();
    await expect(versionBadge).toHaveText('Critical');
    await list.goto();
    await expect(packageBadge).toHaveText('Critical');
    await repos.goto();
    await repos.search(repo.name);
    await expect(repoBadge).toHaveText('Critical');
  });

  test('a Docker image is scanned by reference and goes through the same states', async ({
    adminPage,
    seeder,
    seedPackage,
    scanner,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('docker', seeder.runId, 'vuln-low-queue4'),
    });
    const detail = protocolPages(adminPage, DESCRIPTORS.docker, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();

    await expect(section.status).toHaveText('Queued...', SCAN_TIMEOUT);
    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'LOW');
    await expect(section.findingsCount).toHaveText('1 finding');
    const [call] = await scanner.calls(pkg.name);
    expect(call.dockerImageReference).toContain(
      `/${repoPath(repo.name)}/${pkg.name}:${pkg.version}`,
    );
  });
});
