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

/**
 * RPS-1761, download counters (the OS half; the Cloud half is repsy-mono #1695). A crate and a nupkg are
 * published, downloaded twice through the real protocol endpoints, and the "Downloads" figure of the panel is
 * read after a reload (the counter is written by the server after the response, so the spec polls).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { rawDownload } from '../../../src/clients/cargo-raw.js';
import { buildNupkg, rawDownloadNupkg, rawPublish } from '../../../src/clients/nuget-raw.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { publishCrate } from '../../../src/seed/packages/cargo.js';
import { defaultPackageName, expectPublished } from '../../../src/seed/packages/shared.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const DOWNLOADS = 2;

// @cloud-skip: Repsy Cloud has its own copy (repsy-cloud/e2e/ui-cloud/edge-cases/download-counters.spec.ts, #1695).
test.describe('RPS-1761 download counters', { tag: ['@cloud-skip'] }, () => {
  test('Cargo: two real crate downloads raise the list column and the detail page from 0 to 2', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const pkg = await publishCrate(repo.name, `e2e_${seeder.runId}_dl`, '1.0.0');
    const pages = protocolPages(adminPage, DESCRIPTORS.cargo, repo.name);

    const list = pages.list();
    await list.goto();
    await expect(list.inRow(pkg, 'row-downloads')).toHaveText('0');

    for (let i = 0; i < DOWNLOADS; i++) {
      const res = await rawDownload(repo.name, adminCredential(), pkg.name, pkg.version);
      expect(res.status, `download ${i + 1}`).toBe(200);
    }

    await expect
      .poll(
        async () => {
          await adminPage.reload();
          await list.expectLoaded();
          return (await list.inRow(pkg, 'row-downloads').textContent())?.trim();
        },
        { message: 'the crate list counts the downloads', timeout: 30_000 },
      )
      .toBe(String(DOWNLOADS));

    const detail = pages.detail(pkg);
    await detail.goto();
    await expect(detail.byId('pkg-detail-metadata')).toContainText(`Downloads: ${DOWNLOADS}`);
  });

  test('NuGet: two real nupkg downloads raise the package list column from 0 to 2', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const name = defaultPackageName('nuget', seeder.runId, 1);
    const version = '1.0.0';
    expectPublished(
      await rawPublish(repo.name, adminCredential(), buildNupkg({ packageId: name, version })),
      `PUT ${name}.${version}`,
    );
    const pkg = { protocol: 'nuget' as const, repoName: repo.name, name, version, extra: {} };

    const list = protocolPages(adminPage, DESCRIPTORS.nuget, repo.name).list();
    await list.goto();
    await expect(list.inRow(pkg, 'row-downloads')).toHaveText('0');

    for (let i = 0; i < DOWNLOADS; i++) {
      const res = await rawDownloadNupkg(repo.name, adminCredential(), name.toLowerCase(), version);
      expect(res.status, `download ${i + 1}`).toBe(200);
    }

    await expect
      .poll(
        async () => {
          await adminPage.reload();
          await list.expectLoaded();
          return (await list.inRow(pkg, 'row-downloads').textContent())?.trim();
        },
        { message: 'the package list counts the downloads', timeout: 30_000 },
      )
      .toBe(String(DOWNLOADS));
  });
});
