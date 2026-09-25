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
 * SEC-02c: the Vulnerability Scanning section of `/:repo/settings`. It is a section of its own that
 * renders only when the repo's type has a scanner (`GET /api/security/supported-repo-types`), which
 * the e2e stack does not, so the type list is stubbed. The settings themselves are REAL: the toggle
 * PUTs `/api/repos/{repo}/settings` to the backend, and a test asserts both the request body the panel
 * sent and that the setting was stored (through the panel API and after a reload).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { UI_REPO_TYPES } from '../../../src/ui/repo-types.js';
import { VulnerabilityScanningSection } from '../../../src/ui/pages/security.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import { stubSupportedRepoTypes } from '../../../src/ui/security-stubs.js';

const MOCKED = '@mocked';

/** Any type but `type`, to prove a scanner for one type does not switch the section on for another. */
function otherType(type: RepoType): RepoType {
  return type === RepoType.MAVEN ? RepoType.NPM : RepoType.MAVEN;
}

test.describe('SEC-02c Vulnerability Scanning setting', { tag: MOCKED }, () => {
  for (const { type, label } of UI_REPO_TYPES) {
    test(`${label}: the section shows only while ${label} has a scanner`, async ({
      adminPage,
      seeder,
    }) => {
      const repo = await seeder.createRepo(type);
      const settings = new RepoSettingsPage(adminPage, repo.name);
      const scanning = new VulnerabilityScanningSection(adminPage);

      // The e2e default: no scanner at all, so no section. Stubbed, not assumed, so that the test also
      // holds on the scanner stack (the stub scanner supports maven, npm, pypi and docker there).
      await stubSupportedRepoTypes(adminPage, []);
      await settings.goto();
      await expect(settings.visibility.root).toBeVisible();
      await expect(scanning.root).toHaveCount(0);

      // A scanner for another type is no scanner for this one.
      await stubSupportedRepoTypes(adminPage, [otherType(type)]);
      await settings.reload();
      await expect(scanning.root).toHaveCount(0);

      // A scanner for this type: the section, on (the default), with its explanation.
      await stubSupportedRepoTypes(adminPage, [type]);
      await settings.reload();
      await expect(scanning.root).toBeVisible();
      await expect(scanning.root).toContainText('Vulnerability Scanning');
      await scanning.expectChecked(true);
      await expect(scanning.label).toHaveText('Allow');
      await expect(scanning.root).toContainText('Every push triggers a vulnerability scan.');
    });
  }

  test('toggling PUTs securityScanEnabled with the rest of the form, stores it, and it survives a reload', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: false });
    await seeder.setSettings(repo.name, { allowOverride: true });
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const scanning = new VulnerabilityScanningSection(adminPage);
    await settings.goto();
    await scanning.expectChecked(true);
    expect((await panelApi.getSettings(repo.name)).securityScanEnabled).toBe(true);

    const put = adminPage.waitForRequest(
      (request) =>
        request.method() === 'PUT' && request.url().endsWith(`/api/repos/${repo.name}/settings`),
    );
    await scanning.flip();

    // The request: the whole general form, with the one field that changed.
    const body = (await put).postDataJSON();
    expect(body).toEqual({ privateRepo: false, allowOverride: true, securityScanEnabled: false });
    await settings.shell.toasts.expectSuccess('Automatic security scanning is now disabled');
    await scanning.expectChecked(false);
    await expect(scanning.root).toContainText('Pushes will not trigger a scan.');
    await expect
      .poll(async () => (await panelApi.getSettings(repo.name)).securityScanEnabled)
      .toBe(false);

    // Stored, not just shown: a reload reads it back.
    await settings.reload();
    await scanning.expectChecked(false);

    const putOn = adminPage.waitForRequest(
      (request) =>
        request.method() === 'PUT' && request.url().endsWith(`/api/repos/${repo.name}/settings`),
    );
    await scanning.flip();
    expect((await putOn).postDataJSON()).toMatchObject({ securityScanEnabled: true });
    await settings.shell.toasts.expectSuccess('Automatic security scanning is now enabled');
    await expect
      .poll(async () => (await panelApi.getSettings(repo.name)).securityScanEnabled)
      .toBe(true);
    // Nothing else moved with it.
    expect(await panelApi.getSettings(repo.name)).toMatchObject({
      privateRepo: false,
      allowOverride: true,
    });
  });

  test('Maven and NuGet send their release and snapshot switches along', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const maven = await seeder.createRepo(RepoType.MAVEN);
    const nuget = await seeder.createRepo(RepoType.NUGET);
    await seeder.setSettings(maven.name, {
      allowOverride: false,
      releases: true,
      snapshots: false,
    });
    await seeder.setSettings(nuget.name, { allowOverride: true, releases: false, snapshots: true });
    await stubSupportedRepoTypes(adminPage, [RepoType.MAVEN, RepoType.NUGET]);
    const scanning = new VulnerabilityScanningSection(adminPage);

    for (const [repo, expected] of [
      [maven, { privateRepo: true, allowOverride: false, releases: true, snapshots: false }],
      [nuget, { privateRepo: true, allowOverride: true, releases: false, snapshots: true }],
    ] as const) {
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      await scanning.expectChecked(true);
      const put = adminPage.waitForRequest(
        (request) =>
          request.method() === 'PUT' && request.url().endsWith(`/api/repos/${repo.name}/settings`),
      );

      await scanning.flip();

      expect((await put).postDataJSON()).toEqual({ ...expected, securityScanEnabled: false });
      await settings.shell.toasts.expectSuccess('Automatic security scanning is now disabled');
      await expect
        .poll(async () => (await panelApi.getSettings(repo.name)).securityScanEnabled)
        .toBe(false);
    }
  });
});
