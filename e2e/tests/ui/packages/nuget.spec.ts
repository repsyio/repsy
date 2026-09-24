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
 * NuGet package pages (RPS-1257): the shared scenarios PKG-nuget-01..06 (`registerPackageScenarios`)
 * and PKG-nuget-07, the NuGet-only parts: stable and pre-release versions side by side, the Version
 * Allowance setting (`releases`/`snapshots`) against the list, an unlisted version, and the detail
 * (four install snippets, dependencies, metadata). Packages are published with the raw
 * `dotnet nuget push` request and a `.nupkg` built in code (never `dotnet`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { buildNupkg, rawPublish, rawRelist, rawUnlist } from '../../../src/clients/nuget-raw.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios, rowKeys } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const nuget = DESCRIPTORS.nuget;

// RPS-1262 (2): like Cargo's, the NuGet version list has only the `hidden ... lg:block` desktop table,
// so a phone renders nothing but the pager. RPS-1262 (3): unlike every other version list it has no
// search box (the sort is there).
registerPackageScenarios(nuget, {
  knownFailures: {
    '02-versions-search': 'RPS-1262: the NuGet version list has no search box',
    '05-mobile-versions': 'RPS-1262: the version list renders no cards below lg',
  },
});

test.describe('NuGet package pages', { tag: '@packages' }, () => {
  test('PKG-nuget-07 stable and pre-release versions are listed side by side, newest first', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const [stable, pre, next] = await seedVersions(repo, ['1.0.0', '2.0.0-beta.1', '2.0.0']);
    const pages = protocolPages(adminPage, nuget, repo.name);
    const versions = pages.versions(stable);
    await versions.goto();
    await expect(versions.rows()).toHaveCount(3);
    await expect.poll(() => rowKeys(versions)).toEqual([next.version, pre.version, stable.version]);

    // A pre-release detail opens like any other and shows its own version.
    const detail = pages.detail(pre);
    await detail.goto();
    await expect(detail.byId('pkg-detail-version')).toHaveText('2.0.0-beta.1');
    await expect(detail.installText).toContainText(
      `dotnet add package ${pre.name} --version 2.0.0-beta.1`,
    );

    // The package row shows the latest version.
    const list = pages.list();
    await list.goto();
    await expect(list.rows()).toHaveCount(1);
    await expect(list.inRow(stable, 'row-latest')).toContainText('2.0.0');
  });

  test('PKG-nuget-07 a repository set to stable only refuses pre-releases, and the list keeps what it has', async ({
    adminPage,
    panelApi,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const [first] = await seedVersions(repo, ['1.0.0', '2.0.0-beta.1']);
    const settings = await panelApi.getSettings(repo.name);
    await panelApi.updateSettings(repo.name, {
      privateRepo: settings.privateRepo,
      allowOverride: settings.allowOverride,
      releases: true,
      snapshots: false,
    });

    const admin = adminCredential();
    const refused = await rawPublish(
      repo.name,
      admin,
      buildNupkg({ packageId: first.name, version: '3.0.0-rc.1' }),
    );
    expect(refused.status, 'a pre-release push is refused').toBeGreaterThanOrEqual(400);
    const accepted = await rawPublish(
      repo.name,
      admin,
      buildNupkg({ packageId: first.name, version: '3.0.0' }),
    );
    expect(accepted.status, 'a stable push is accepted').toBeLessThan(300);

    const versions = protocolPages(adminPage, nuget, repo.name).versions(first);
    await versions.goto();
    await expect(versions.rows()).toHaveCount(3);
    await expect.poll(() => rowKeys(versions)).toEqual(['3.0.0', '2.0.0-beta.1', '1.0.0']);
    await expect(versions.row({ ...first, version: '3.0.0-rc.1' })).toHaveCount(0);
  });

  test('PKG-nuget-07 an unlisted version stays on the versions page and its detail says Listed: No', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const admin = adminCredential();
    const pages = protocolPages(adminPage, nuget, repo.name);

    const listed = pages.detail(two);
    await listed.goto();
    await expect(listed.byId('pkg-detail-listed')).toHaveText('Yes');

    const unlist = await rawUnlist(repo.name, admin, two.name, two.version);
    expect(unlist.status, 'unlist').toBe(204);
    const versions = pages.versions(one);
    await versions.goto();
    await versions.expectRow(one);
    await versions.expectRow(two);
    await listed.goto();
    await expect(listed.byId('pkg-detail-listed')).toHaveText('No');
    // Metadata block repeats it.
    await expect(listed.byId('pkg-detail-metadata')).toContainText('Listed');

    const relist = await rawRelist(repo.name, admin, two.name, two.version);
    expect(relist.status, 'relist').toBe(200);
    await listed.goto();
    await expect(listed.byId('pkg-detail-listed')).toHaveText('Yes');
  });

  test('PKG-nuget-07 the detail shows the four install commands, no dependencies and the nuspec metadata', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, nuget, repo.name).detail(pkg);
    await detail.goto();
    const source = `${env.repoBaseUrl}/${repo.name}/v3/index.json`;

    await expect(detail.name).toHaveText(pkg.name);
    await expect(detail.byId('pkg-detail-version')).toHaveText('1.0.0');
    await expect(detail.snippet('package-reference')).toContainText(
      `<PackageReference Include="${pkg.name}" Version="1.0.0" />`,
    );
    await expect(detail.installText).toContainText(
      `dotnet add package ${pkg.name} --version 1.0.0`,
    );
    await expect(detail.snippet('dotnet-cli-url')).toContainText(`--source "${source}"`);
    await expect(detail.snippet('package-manager')).toContainText(
      `Install-Package ${pkg.name} -Version 1.0.0`,
    );
    await expect(detail.snippet('package-manager-url')).toContainText(`-Source "${source}"`);
    await expect(detail.byId('pkg-detail-dependencies')).toContainText('No dependencies');
    await expect(detail.byId('pkg-detail-metadata')).toContainText('Authors');
    await expect(detail.byId('pkg-detail-metadata')).toContainText('repsy-e2e');
    await expect(detail.byId('pkg-detail-published')).not.toBeEmpty();
    await expect(detail.readme).toHaveCount(0);
  });
});
