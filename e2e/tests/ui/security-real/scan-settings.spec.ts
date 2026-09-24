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
 * SEC-01 (`@scanner`), the Vulnerability Scanning setting with a REAL scanner behind it: the section
 * exists exactly for the repo types the backend's scanner supports (no stubbed
 * `supported-repo-types`), and switching it off in the UI really stops the pushes of that repo from
 * being scanned, while Scan Now still scans a version on demand.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { Toasts } from '../../../src/ui/pages/components.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { ScanSection, VulnerabilityScanningSection } from '../../../src/ui/pages/security.js';
import {
  POLL_STEP,
  SCANNER_TAG,
  expect,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  submittedNameOf,
  test,
} from '../../../src/ui/scanner-fixtures.js';

test.describe('SEC-01 the Vulnerability Scanning setting', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  test('the section shows for the types the scanner supports and for no other', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    // The backend's own answer, not a stub: the stub scanner speaks for the Trivy adapter's types.
    expect(await panelApi.supportedScanRepoTypes()).toEqual(['DOCKER', 'MAVEN', 'NPM', 'PYPI']);
    const scanning = new VulnerabilityScanningSection(adminPage);

    for (const type of [RepoType.MAVEN, RepoType.NPM, RepoType.PYPI, RepoType.DOCKER]) {
      const repo = await seeder.createRepo(type);
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      await expect(scanning.root, `${type} has a scanner`).toBeVisible();
      await scanning.expectChecked(true);
    }

    for (const type of [RepoType.CARGO, RepoType.NUGET, RepoType.GOLANG]) {
      const repo = await seeder.createRepo(type);
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      await expect(settings.visibility.root).toBeVisible();
      await expect(scanning.root, `${type} has no scanner`).toHaveCount(0);
    }
  });

  test('switched off in the UI, a push is not scanned; switched on again, the next push is', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
    scanner,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const scanning = new VulnerabilityScanningSection(adminPage);
    await settings.goto();
    await scanning.expectChecked(true);

    await scanning.flip();
    await settings.shell.toasts.expectSuccess('Automatic security scanning is now disabled');
    await expect
      .poll(async () => (await panelApi.getSettings(repo.name)).securityScanEnabled)
      .toBe(false);
    const unscanned = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-critical', 'off'),
    });

    await scanning.flip();
    await settings.shell.toasts.expectSuccess('Automatic security scanning is now enabled');
    await expect
      .poll(async () => (await panelApi.getSettings(repo.name)).securityScanEnabled)
      .toBe(true);
    const scanned = await seedPackage(repo, {
      name: scanPackageName('npm', seeder.runId, 'vuln-high', 'on'),
    });

    // The push after the switch-on is scanned end to end ...
    await submittedNameOf(scanner, scanned);
    expect(await newestFinishedScan(panelApi, repo.name, scanned)).toMatchObject({
      status: 'COMPLETED',
      highestSeverity: 'HIGH',
    });
    // ... and the one before it never reached the scanner (the scans of the second one are finished,
    // so a scan of the first one would have shown up by now: both were pushed a moment apart).
    expect(await scanner.calls(unscanned.name)).toEqual([]);
    expect(await panelApi.listVersionScans(repo.name, unscanned.name, unscanned.version)).toEqual(
      [],
    );

    // The unscanned version says so, and Scan Now scans it on demand despite the setting.
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(unscanned);
    const section = new ScanSection(adminPage);
    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.emptyBody).toHaveText('No vulnerability scans yet for this version.');
    await section.scanNowButton.click();
    await new Toasts(adminPage).expectSuccess('Vulnerability scan triggered');
    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'CRITICAL');
    await expect(section.findingsCount).toHaveText('3 findings');
    expect(await scanner.calls(unscanned.name)).toHaveLength(1);
  });

  test('Scan Now on a repo with scanning switched off scans the version anyway', async ({
    adminPage,
    seeder,
    seedPackage,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    await seeder.setSettings(repo.name, { privateRepo: true, securityScanEnabled: false });
    expect((await panelApi.getSettings(repo.name)).securityScanEnabled).toBe(false);
    const pkg = await seedPackage(repo, {
      name: scanPackageName('pypi', seeder.runId, 'vuln-medium'),
    });
    const detail = protocolPages(adminPage, DESCRIPTORS.pypi, repo.name).detail(pkg);
    const section = new ScanSection(adminPage);

    await adminPage.goto(`${detail.path()}#security`);
    await detail.expectLoaded();
    await expect(section.neverScanned).toHaveText('Not scanned yet');
    await section.scanNowButton.click();

    await expect(section.status).toHaveText('Completed', POLL_STEP);
    await expect(section.headerBadge()).toHaveAttribute('data-severity', 'MEDIUM');
    await expect(section.neverScanned).toHaveCount(0);
  });
});
