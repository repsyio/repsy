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
 * SET-08: the PGP Signature Key Stores section of a Maven repo. The selector offers the key servers
 * the instance allows (`GET /api/mvn/key-stores/allowed-servers`); adding one registers it on the
 * repo, and it can be deleted again through the danger modal. The built-in servers are listed for
 * information only.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { RepoSettingsReadback } from '../../../src/ui/pages/repo-settings/readback.js';

const SETTINGS = '@settings';

const label = (server: { displayName: string; host: string }) =>
  `${server.displayName} (${server.host})`;

test.describe('Repository settings: PGP key stores', { tag: SETTINGS }, () => {
  test('SET-08 add an allowed key server, see it listed, delete it', async ({
    adminPage,
    seeder,
    adminSession,
  }) => {
    const readback = new RepoSettingsReadback(adminSession.token);
    const allowed = await readback.allowedKeyServers();
    // A stock instance ships several allowed servers; the test needs two to prove that picking one
    // in the selector is what gets added.
    expect(allowed.length).toBeGreaterThanOrEqual(2);
    const [first, second] = allowed;

    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const { pgp } = settings;

    // The selector offers exactly what the API allows; nothing is registered yet.
    expect(await pgp.serverLabels()).toEqual(allowed.map(label));
    await expect(pgp.keyStores).toHaveCount(0);
    expect(await readback.keyStores(repo.name)).toEqual([]);

    // Built-in servers are shown, and are not part of the repo's own list.
    await expect(pgp.builtIn).toBeVisible();
    await expect(pgp.builtInServer('keyserver.ubuntu.com')).toBeVisible();
    await expect(pgp.builtInServer('keys.openpgp.org')).toBeVisible();

    // Add the SECOND one (not the selector's default), so a default-only pass would be caught.
    await pgp.select(label(second));
    await pgp.addButton.click();
    await settings.shell.toasts.expectSuccess('Key Store added');
    await expect(pgp.keyStore(second.host)).toBeVisible();
    await expect(pgp.keyStore(second.host)).toContainText(second.displayName);
    await expect(pgp.keyStore(first.host)).toHaveCount(0);
    expect((await readback.keyStores(repo.name)).map((k) => k.host)).toEqual([second.host]);

    // It is still there after a reload.
    await settings.reload();
    await expect(pgp.keyStore(second.host)).toBeVisible();

    // Add the first as well, then delete the second; only the first remains.
    await pgp.select(label(first));
    await pgp.addButton.click();
    await settings.shell.toasts.expectSuccess('Key Store added');
    await expect(pgp.keyStore(first.host)).toBeVisible();

    await pgp.deleteButton(second.host).click();
    await settings.shell.dangerModal.expectOpen('Delete Key Store');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    await expect(pgp.keyStore(second.host)).toBeVisible();

    await pgp.deleteButton(second.host).click();
    await settings.shell.dangerModal.expectOpen('Delete Key Store');
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Key Store deleted');
    await expect(pgp.keyStore(second.host)).toHaveCount(0);
    await expect(pgp.keyStore(first.host)).toBeVisible();
    await expect
      .poll(async () => (await readback.keyStores(repo.name)).map((k) => k.host))
      .toEqual([first.host]);
  });
});
