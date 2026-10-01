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
import type { Route } from '@playwright/test';

import { PanelHttpError, RepoType } from '../../../src/api/panel-api.js';
import type {
  AllowedKeyserverItem,
  KeyStoreItem,
  RestResponseListAllowedKeyserverItem,
  RestResponsePagedModelKeyStoreItem,
} from '../../../src/api/generated/index.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { fulfillJson } from '../../../src/ui/stub-responses.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { RepoSettingsReadback } from '../../../src/ui/pages/repo-settings/readback.js';

const SETTINGS = '@settings';

const ALLOWED_SERVERS_URL = /\/api\/mvn\/key-stores\/allowed-servers/;
const keyStoresUrl = (repoName: string) => new RegExp(`/api/mvn/key-stores/${repoName}(\\?|$)`);

function allowedKeyserversBody(data: AllowedKeyserverItem[]): RestResponseListAllowedKeyserverItem {
  return { msgId: 'allowedKeyserversFetched', data };
}

/** A page of `KeyStoreItem`s, in the shape `PagedModel` serialises (`content` plus `page`). */
function keyStoresPage(
  items: KeyStoreItem[],
  page: number,
  size: number,
  totalElements: number,
): RestResponsePagedModelKeyStoreItem {
  return {
    msgId: 'keyStoresFetched',
    data: {
      content: items,
      page: { size, number: page, totalElements, totalPages: Math.ceil(totalElements / size) },
    },
  };
}

function stubKeyStore(index: number): KeyStoreItem {
  return {
    id: `stub-keystore-${index}`,
    allowedKeyserverId: `stub-allowed-${index}`,
    host: `stub-${index}.example.test`,
    displayName: `Stub Server ${index}`,
  };
}

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
    await settings.shell.toasts.expectSuccess('Key store added');
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
    await settings.shell.toasts.expectSuccess('Key store added');
    await expect(pgp.keyStore(first.host)).toBeVisible();

    await pgp.deleteButton(second.host).click();
    await settings.shell.dangerModal.expectOpen('Delete key store');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    await expect(pgp.keyStore(second.host)).toBeVisible();

    await pgp.deleteButton(second.host).click();
    await settings.shell.dangerModal.expectOpen('Delete key store');
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Key store deleted');
    await expect(pgp.keyStore(second.host)).toHaveCount(0);
    await expect(pgp.keyStore(first.host)).toBeVisible();
    await expect
      .poll(async () => (await readback.keyStores(repo.name)).map((k) => k.host))
      .toEqual([first.host]);
  });

  test('SET-08b the section starts empty: no selector or Add until the instance allows a keyserver, no "Additional keyservers" list until one is registered', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { pgp } = settings;

    // Before any keyserver is registered: the built-ins are still informational, the toggles are
    // there (they are independent of whether anything is registered), but there is no "Additional
    // keyservers" list to show.
    await settings.goto();
    await expect(pgp.keyStores).toHaveCount(0);
    await expect(pgp.builtIn).toBeVisible();
    await expect(pgp.verifyAllToggle).toBeVisible();
    await expect(pgp.keyServerLookupToggle).toBeVisible();

    // An instance that allows no keyserver at all (stubbed: the stock instance always ships at least
    // the three of `V0007__Allowed_Keyserver.sql`, so this state cannot be reached with real data):
    // the selector and the Add button are not shown, since there is nothing to add.
    await adminPage.route(ALLOWED_SERVERS_URL, (route: Route) =>
      fulfillJson<RestResponseListAllowedKeyserverItem>(route, 200, allowedKeyserversBody([])),
    );
    await settings.reload();
    await expect(pgp.serverSelector).toHaveCount(0);
    await expect(pgp.addButton).toHaveCount(0);
    // Still true and still visible: an empty selector is not the same as a broken section.
    await expect(pgp.builtIn).toBeVisible();
    await expect(pgp.builtInServer('keyserver.ubuntu.com')).toBeVisible();
  });

  test('SET-08c scrolling the additional keyservers list to the bottom loads the next page', async ({
    adminPage,
    seeder,
  }) => {
    // Page size is 5 (`SignatureComponent.pageSize`) and a real repo can never hold more than the
    // instance's three allowed keyservers at once (`ux_key_store__repo_id__allowed_keyserver_id`), so
    // "loading more on scroll" is stubbed: seven key stores over two pages.
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const all = Array.from({ length: 7 }, (_, index) => stubKeyStore(index));
    const requestedPages: number[] = [];
    await adminPage.route(keyStoresUrl(repo.name), (route: Route) => {
      if (route.request().method() !== 'GET') {
        return route.fallback();
      }
      const url = new URL(route.request().url());
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 5);
      requestedPages.push(page);
      return fulfillJson<RestResponsePagedModelKeyStoreItem>(
        route,
        200,
        keyStoresPage(all.slice(page * size, (page + 1) * size), page, size, all.length),
      );
    });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { pgp } = settings;

    await settings.goto();

    await expect(pgp.keyStores.locator('[data-testid^="settings-pgp-keystore-"]')).toHaveCount(5);
    for (const item of all.slice(0, 5)) {
      await expect(pgp.keyStore(item.host)).toBeVisible();
    }
    for (const item of all.slice(5)) {
      await expect(pgp.keyStore(item.host)).toHaveCount(0);
    }

    // Scrolled to the bottom: `onScroll` reads it straight off the DOM element, so the event is
    // dispatched by hand rather than relying on real scroll physics for a handful of short rows.
    await pgp.keyStores.evaluate((element) => {
      element.scrollTop = element.scrollHeight - element.clientHeight;
      element.dispatchEvent(new Event('scroll'));
    });

    await expect(pgp.keyStores.locator('[data-testid^="settings-pgp-keystore-"]')).toHaveCount(7);
    for (const item of all) {
      await expect(pgp.keyStore(item.host)).toBeVisible();
    }
    expect(requestedPages).toEqual([0, 1]);
  });

  test('SET-08d PGP settings are rejected for a repo type that does not support them (RPS-1449/1469: the instance queue can also reject one under 52+ concurrent toggles, answering 200; not reproducible in e2e, see the tickets)', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    // The PGP section itself only ever renders for a Maven repo, so the panel never sends this by
    // clicking anything; the rejection (`rejectPgpSettingsForUnsupportedType`, `RepoTxService`) is
    // reached directly, the same way a misbehaving or future client could.
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    const rejected = await panelApi
      .updateSettings(repo.name, { pgpVerifyAllSignaturesEnabled: true })
      .catch((error: unknown) => error);

    expect(rejected).toBeInstanceOf(PanelHttpError);
    expect((rejected as PanelHttpError).status).toBe(400);
    expect((rejected as PanelHttpError).body as Record<string, unknown>).toMatchObject({
      msgId: 'pgpSettingsUnsupported',
    });

    // Nothing about the repo changed: there is no PGP state to read back for a non-Maven repo (the
    // section is simply absent), but the settings endpoint still agrees nothing was touched.
    const settingsAfter = await panelApi.getSettings(repo.name);
    expect(settingsAfter.pgpVerifyAllSignaturesEnabled).toBeUndefined();

    // A working repo settings admin page never shows the section for this type, either.
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expect(settings.pgp.root).toHaveCount(0);
  });
});
