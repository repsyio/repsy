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
 * CLP-01..CLP-05 (RPS-1623): the copy buttons of the panel on a plain-HTTP install. There
 * `navigator.clipboard` does not exist (`http://<lan-ip>:8080` is not a secure context), and every copy
 * button used to throw and did nothing. `simulatePlainHttp` (`src/ui/plain-http.ts`) removes the API from
 * the context and records what the fallback (`document.execCommand('copy')` on a hidden textarea) copied,
 * so a spec can read back what a copy button put on the clipboard without the clipboard permission.
 *
 * The page-error guard of `fixtures.ts` is on: a copy that throws fails the test. The reset-password
 * modal's copy buttons are in `tests/ui/users/users-reset-password-copy.spec.ts` (`@cloud-skip`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { seedHostileNpm } from '../../../src/ui/hostile-packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { simulatePlainHttp, recordedCopies } from '../../../src/ui/plain-http.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { PACKAGE_PROTOCOLS } from '../../../src/seed/packages.js';

const PACKAGES = '@packages';
/** The check mark of the code-block copy button (the markdown component draws it as inline SVG). */
const CHECK_MARK = 'path[stroke="#69FFB4"]';

test.describe('Copy buttons without navigator.clipboard', { tag: PACKAGES }, () => {
  for (const protocol of PACKAGE_PROTOCOLS) {
    test(`CLP-01 ${protocol}: the install snippet copies through the fallback and says so`, async ({
      adminPage,
      context,
      seeder,
      seedPackage,
    }) => {
      await simulatePlainHttp(context);
      const repo = await seeder.createRepo(
        RepoType[protocol.toUpperCase() as keyof typeof RepoType],
      );
      const pkg = await seedPackage(repo);
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(pkg);
      await detail.goto();
      expect(await adminPage.evaluate(() => navigator.clipboard)).toBeUndefined();

      await detail.copyButton.first().click();

      await expect(detail.copyButton.first()).toHaveAttribute('data-copied', 'true');
      const copies = await recordedCopies(adminPage);
      expect(copies).toHaveLength(1);
      expect(copies[0].ok).toBe(true);
      for (const part of DESCRIPTORS[protocol].levels.detail.installContains(repo.name, pkg)) {
        expect(copies[0].text).toContain(part);
      }
    });
  }

  test('CLP-02 a README code block copies through the fallback and shows the check mark', async ({
    adminPage,
    context,
    seeder,
  }) => {
    await simulatePlainHttp(context);
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedHostileNpm(repo.name, seeder.runId);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    await detail.goto();

    const copy = detail.readme.locator('button.copy-button');
    await expect(copy).toHaveCount(1);
    await copy.click();

    await expect(copy.locator(CHECK_MARK)).toBeVisible();
    const copies = await recordedCopies(adminPage);
    expect(copies).toHaveLength(1);
    expect(copies[0]).toMatchObject({ ok: true });
    expect(copies[0].text).toContain('install --hostile');
  });

  test('CLP-03 the one-time deploy token secret and username copy through the fallback', async ({
    adminPage,
    context,
    seeder,
  }) => {
    await simulatePlainHttp(context);
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { tokens } = settings;
    await settings.goto();

    await tokens.openCreateModal();
    await tokens.createModal.create({ name: `clp-${seeder.runId}` });
    await tokens.infoModal.expectOpen();
    const { username, token } = await tokens.infoModal.values();
    expect(token).not.toBe('');

    await tokens.infoModal.copyToken.click();
    await expect(tokens.infoModal.copyToken).toHaveAttribute('data-copied', 'true');
    await tokens.infoModal.copyUsername.click();
    await expect(tokens.infoModal.copyUsername).toHaveAttribute('data-copied', 'true');

    const copies = await recordedCopies(adminPage);
    expect(copies.map((copy) => copy.ok)).toEqual([true, true]);
    expect(copies.map((copy) => copy.text)).toEqual([token, username]);
  });

  test('CLP-05 when no copy method works, no button claims success and nothing throws', async ({
    adminPage,
    context,
    seeder,
  }) => {
    await simulatePlainHttp(context, { execResult: 'fail' });
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedHostileNpm(repo.name, seeder.runId);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);
    await detail.goto();

    await detail.copyButton.first().click();
    const codeCopy = detail.readme.locator('button.copy-button');
    await codeCopy.click();

    // Both asked the browser to copy, the browser said no, and neither shows the check mark.
    await expect.poll(async () => (await recordedCopies(adminPage)).length).toBe(2);
    expect((await recordedCopies(adminPage)).map((copy) => copy.ok)).toEqual([false, false]);
    await expect(detail.copyButton.first()).not.toHaveAttribute('data-copied', 'true');
    await expect(codeCopy.locator(CHECK_MARK)).toHaveCount(0);
  });
});
