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
 * Go package pages (RPS-1257): the shared scenarios PKG-golang-01..06 (`registerPackageScenarios`) and
 * PKG-golang-07, the Go-only routes. A module path has slashes, so the versions and detail pages carry
 * it as a QUERY parameter (`/:repo/modules?modulePath=` and `/:repo/modules/version?modulePath=&version=`)
 * and the breadcrumb shows the module path. Modules are published with the raw `curl -T` request and a
 * zip built in code (never `go`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import {
  asDetailPage,
  escapeRegExp,
  registerPackageScenarios,
  rowKeys,
} from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages, type VersionsPage } from '../../../src/ui/pages/protocol.js';
import { Breadcrumb } from '../nav/breadcrumb.js';

const golang = DESCRIPTORS.golang;

registerPackageScenarios(golang);

test.describe('Go module routes', { tag: '@packages' }, () => {
  test('PKG-golang-07 module list -> versions -> detail through the query parameters', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const [one, two] = await seedVersions(repo, ['v1.0.0', 'v1.1.0']);
    const modulePath = one.name;
    const pages = protocolPages(adminPage, golang, repo.name);

    const list = pages.list();
    await list.goto();
    await list.expectRow(one);
    await expect(list.inRow(one, 'row-name')).toHaveText(modulePath);

    // The module row opens the versions page: the path is a query parameter, not a path segment.
    const versions = (await list.openRow(one)) as VersionsPage;
    await expect(adminPage).toHaveURL(
      `${env.apiBaseUrl}/${repo.name}/modules?modulePath=${encodeURIComponent(modulePath)}`,
    );
    await versions.expectLoaded();
    await expect(versions.rows()).toHaveCount(2);
    await versions.expectRow(one);
    await versions.expectRow(two);
    // The breadcrumb names the module path.
    await new Breadcrumb(adminPage).expectCrumbs(['Repositories', repo.name, modulePath]);

    // A version row opens the detail, whose query carries both the module path and the version.
    const detail = asDetailPage(await versions.openRow(two));
    await expect(adminPage).toHaveURL(
      new RegExp(
        `/${repo.name}/modules/version\\?modulePath=${escapeRegExp(encodeURIComponent(modulePath))}&version=v1\\.1\\.0(#security)?$`,
      ),
    );
    await detail.expectLoaded();
    await expect(detail.name).toHaveText(modulePath);
    await expect(detail.byId('pkg-detail-version')).toContainText('v1.1.0');
    await new Breadcrumb(adminPage).expectCrumbs(['Repositories', repo.name, modulePath, 'v1.1.0']);
  });

  test('PKG-golang-07 the detail page loads straight from its query parameters', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const mod = await seedPackage(repo);
    // A fresh navigation to the deep link, not a click through: nothing is carried over.
    const detail = protocolPages(adminPage, golang, repo.name).detail(mod);
    await adminPage.goto(detail.path());
    await detail.expectLoaded();
    await expect(detail.name).toHaveText(mod.name);
    await expect(detail.byId('pkg-detail-version')).toContainText(mod.version);
    await expect(detail.byId('pkg-detail-published')).toContainText('Published at:');
    await expect(detail.byId('pkg-detail-metadata')).toContainText(`Module Path: ${mod.name}`);
    await expect(detail.byId('pkg-detail-metadata')).toContainText('Go Version:');
  });

  test('PKG-golang-07 the GOPROXY endpoints of a version are its .info, .mod and .zip', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const mod = await seedPackage(repo);
    const detail = protocolPages(adminPage, golang, repo.name).detail(mod);
    await detail.goto();
    const base = `${env.repoBaseUrl}/${repo.name}/${mod.name}/@v/${mod.version}`;
    for (const suffix of ['.info', '.mod', '.zip']) {
      await expect(detail.byId(`pkg-detail-goproxy-${suffix}`)).toContainText(`${base}${suffix}`);
    }
    await expect(detail.snippet('go-env')).toContainText(
      `GOPROXY="${env.repoBaseUrl}/${repo.name},off"`,
    );
  });

  test('PKG-golang-07 a version that does not exist says so on its detail page', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const mod = await seedPackage(repo);
    const missing = { ...mod, version: 'v9.9.9' };
    const detail = protocolPages(adminPage, golang, repo.name).detail(missing);
    await detail.goto();
    await expect(detail.error).toBeVisible();
    await expect(detail.errorMessage).toHaveText("Version 'v9.9.9' not found");
    await expect(detail.root).toHaveCount(0);
  });

  test('PKG-golang-07 the detail route without its query parameters goes back to the module list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const mod = await seedPackage(repo);
    await adminPage.goto(`/${repo.name}/modules/version`);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    await protocolPages(adminPage, golang, repo.name).list().expectLoaded();
    await protocolPages(adminPage, golang, repo.name).list().expectRow(mod);
  });

  test('PKG-golang-07 a module path with slashes is found by the search and keeps its whole path', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const nested = await seedPackage(repo, {
      name: `e2e.repsy.test/e2e-${seeder.runId}-org/team/service`,
    });
    const other = await seedPackage(repo, { index: 2 });
    const list = protocolPages(adminPage, golang, repo.name).list();
    await list.goto();
    await list.search('team/service');
    await list.expectRow(nested);
    await list.expectNoRow(other);
    expect(await rowKeys(list)).toEqual([nested.name]);
    const versions = (await list.openRow(nested)) as VersionsPage;
    await versions.expectLoaded();
    await versions.expectRow(nested);
  });

  // RPS-1262 (3): `<app-pagination>` used to sit after the versions page's `@if/@else`, so it also
  // rendered under the empty state and, for a module with no versions, printed "1 NaN".
  test('PKG-golang-07 the empty versions page of an unknown module shows no pager (RPS-1262)', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG);
    const versions = protocolPages(adminPage, golang, repo.name).versions({
      name: `e2e.repsy.test/e2e-${seeder.runId}-none`,
      version: 'v1.0.0',
    });
    await versions.goto();
    await expect(versions.emptyList.root).toBeVisible();
    await expect(versions.pagination.root).toBeHidden();
  });
});
