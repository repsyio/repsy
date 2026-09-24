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

/**
 * Helm package pages (RPS-1257): the shared scenarios PKG-helm-01..06 (`registerPackageScenarios`)
 * and PKG-helm-07, the Helm-only parts. Helm has TWO backend modules behind one panel, `oci`
 * (`helm push oci://`, the default seed) and `classic` (ChartMuseum, `helm cm-push`), and both feed
 * the same chart list; the specs publish a chart to each and confirm both render, open and delete.
 * Charts are built in code (never `helm`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios, rowKeys } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const helm = DESCRIPTORS.helm;

registerPackageScenarios(helm);

test.describe('Helm charts: OCI and classic', { tag: '@packages' }, () => {
  test('PKG-helm-07 a chart published to each module renders in the same list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM);
    const oci = await seedPackage(repo, { index: 1, variant: 'oci' });
    const classic = await seedPackage(repo, { index: 2, variant: 'classic' });
    expect(oci.extra['variant']).toBe('oci');
    expect(classic.extra['variant']).toBe('classic');
    const pages = protocolPages(adminPage, helm, repo.name);

    const list = pages.list();
    await list.goto();
    await expect(list.rows()).toHaveCount(2);
    await list.expectRow(oci);
    await list.expectRow(classic);
    // Both rows carry their latest version.
    await expect(list.inRow(oci, 'row-latest-link')).toContainText('1.0.0');
    await expect(list.inRow(classic, 'row-latest-link')).toContainText('1.0.0');

    for (const chart of [oci, classic]) {
      const detail = pages.detail(chart);
      await detail.goto();
      await expect(detail.name).toHaveText(chart.name);
      await expect(detail.byId('pkg-detail-version')).toContainText('1.0.0');
      await expect(detail.byId('pkg-detail-type')).toHaveText('application');
      await expect(detail.byId('pkg-detail-digest')).toContainText('sha256:');
      await expect(detail.snippet('chart-yaml')).toContainText(`name: ${chart.name}`);
      await expect(detail.snippet('chart-yaml')).toContainText('version: 1.0.0');
      await expect(detail.snippet('oci')).toContainText(`helm pull oci://`);
    }
  });

  test('PKG-helm-07 versions of one chart published through both modules list together', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM);
    const name = `e2e-${seeder.runId}-mixed`;
    const viaOci = await seedPackage(repo, { name, version: '1.0.0', variant: 'oci' });
    const viaClassic = await seedPackage(repo, { name, version: '2.0.0', variant: 'classic' });
    const pages = protocolPages(adminPage, helm, repo.name);

    const list = pages.list();
    await list.goto();
    await expect(list.rows()).toHaveCount(1);
    await expect(list.inRow(viaOci, 'row-latest-link')).toContainText('2.0.0');

    const versions = pages.versions(viaOci);
    await versions.goto();
    await expect.poll(() => rowKeys(versions)).toEqual(['2.0.0', '1.0.0']);
    await versions.expectRow(viaClassic);

    // The Latest link opens the latest version's detail, not the versions page.
    await list.goto();
    await list.inRow(viaOci, 'row-latest-link').click();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${name}/2\\.0\\.0$`));
    await pages.detail(viaClassic).expectLoaded();
  });

  test('PKG-helm-07 a classic chart is deleted from the list like an OCI one', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM);
    const oci = await seedPackage(repo, { index: 1, variant: 'oci' });
    const classic = await seedPackage(repo, { index: 2, variant: 'classic' });
    const list = protocolPages(adminPage, helm, repo.name).list();
    await list.goto();

    await list.deleteRow(classic);
    await list.expectNoRow(classic);
    await list.expectRow(oci);
    await list.goto();
    await list.expectNoRow(classic);
    await list.expectRow(oci);

    await list.deleteRow(oci);
    await list.expectNoRow(oci);
    await expect(list.emptyList.root).toBeVisible();
  });

  // RPS-1262 (3): the Helm version list had no `<app-pagination>` at all, so twelve versions all
  // rendered on one page. It pages client-side now (the API returns every version).
  test('PKG-helm-07 twelve versions of a chart page at ten per page (RPS-1262)', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM);
    const versions = Array.from({ length: 12 }, (_, i) => `1.0.${i}`);
    const seeded = await seedVersions(repo, versions);
    const page = protocolPages(adminPage, helm, repo.name).versions(seeded[0]);
    await page.goto();
    await expect(page.rows()).toHaveCount(10);
    await expect(page.pagination.root).toBeVisible();
  });

  // RPS-1302: after the LAST version was deleted the panel navigated to the chart's versions page,
  // which no longer exists, and that load showed two error toasts, "Chart not found." and
  // "[object Object]", next to the success toast. It now lands on the chart list.
  test('PKG-helm-07 deleting the last version shows no error toast and lands on the chart list (RPS-1302)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM);
    const chart = await seedPackage(repo);
    const pages = protocolPages(adminPage, helm, repo.name);
    const detail = pages.detail(chart);
    await detail.goto();
    // The page it must NOT ask for: the versions of the chart that is gone (a fix that never asks
    // just lets the wait run out), so the toasts are read after they would show.
    const goneAnswered = adminPage
      .waitForResponse(
        (res) =>
          res.request().method() === 'GET' &&
          new URL(res.url()).pathname === `/api/helm/charts/${repo.name}/${chart.name}` &&
          res.status() === 404,
        { timeout: 5_000 },
      )
      .catch(() => undefined);
    await detail.delete();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    const list = pages.list();
    await list.expectLoaded();
    await list.expectNoRow(chart);
    expect(await goneAnswered, 'a request for the versions of the removed chart').toBeUndefined();
    // Read at once, not polled: toasts dismiss themselves after three seconds.
    // eslint-disable-next-line playwright/prefer-to-have-count -- an immediate read, toHaveCount polls
    expect(await detail.toasts.error().count(), 'error toasts after the delete').toBe(0);
  });
});
