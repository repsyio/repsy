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
 * Proof that the seeding layer and the descriptor-driven page objects work end to end (RPS-1255),
 * before the real package scenarios (RPS-1256/1257) build on them: for each first-batch protocol a
 * package is published over raw HTTP into a fresh repo (no toolchain in the ui image) and the panel
 * shows it on its list, versions and detail pages. The two structural tests need no stack: the
 * registry and the descriptors cover all nine protocols, and a protocol whose seeder is not written
 * yet fails with a clear message rather than a confusing one.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import {
  PACKAGE_PROTOCOLS,
  type PackageProtocol,
  type PackageRef,
  SEEDERS,
} from '../../../src/seed/packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import {
  DESCRIPTORS,
  type ProtocolListPage,
  type ProtocolPage,
  protocolPages,
  VersionDetailPage,
} from '../../../src/ui/pages/protocol.js';

const SEEDED_NOW: readonly PackageProtocol[] = ['maven', 'npm', 'docker', 'pypi'];
const NOT_YET: readonly PackageProtocol[] = ['cargo', 'nuget', 'helm', 'golang', 'ruby'];

const REPO_TYPE: Record<PackageProtocol, RepoType> = {
  maven: RepoType.MAVEN,
  npm: RepoType.NPM,
  docker: RepoType.DOCKER,
  pypi: RepoType.PYPI,
  cargo: RepoType.CARGO,
  nuget: RepoType.NUGET,
  helm: RepoType.HELM,
  golang: RepoType.GOLANG,
  ruby: RepoType.RUBY,
};

/** A row click lands on the descriptor's page: the detail (its root shows) or a list (the row shows). */
async function expectOpened(opened: ProtocolPage, pkg: PackageRef): Promise<void> {
  if (opened instanceof VersionDetailPage) {
    await expect(opened.root).toBeVisible();
  } else {
    await opened.expectRow(pkg);
  }
}

/** The stack's own repo URL shows in the install text exactly where the descriptor says. */
async function expectRepoUrlIn(detail: VersionDetailPage): Promise<void> {
  const host = new URL(env.repoBaseUrl).host;
  const where = detail.detail.repoUrlIn;
  if (where === 'install') {
    await expect(detail.installText).toContainText(host);
  } else if (where === 'none') {
    await expect(detail.installText).not.toContainText(host);
  }
}

/** After the only version is deleted the package is gone from the list, or (docker) still listed. */
async function expectAfterLastVersionDelete(
  list: ProtocolListPage,
  pkg: PackageRef,
): Promise<void> {
  if (list.descriptor.lastVersionRemovesPackage === true) {
    await expect(list.emptyList.root).toBeVisible();
  } else {
    await list.expectRow(pkg);
  }
}

test.describe('package seeding proof', () => {
  test('the registry and the descriptors cover all nine protocols', () => {
    expect(Object.keys(SEEDERS).sort()).toEqual([...PACKAGE_PROTOCOLS].sort());
    expect(Object.keys(DESCRIPTORS).sort()).toEqual([...PACKAGE_PROTOCOLS].sort());
    for (const protocol of PACKAGE_PROTOCOLS) {
      const descriptor = DESCRIPTORS[protocol];
      expect(descriptor.protocol).toBe(protocol);
      expect(descriptor.levels.detail.path('r', { name: 'n', version: '1' })).toContain('/r/');
    }
  });

  for (const protocol of NOT_YET) {
    test(`the ${protocol} seeder says it is not implemented yet`, async () => {
      await expect(SEEDERS[protocol]('any-repo', { runId: 'x' }, {})).rejects.toThrow(
        /not implemented yet \(RPS-1257/,
      );
    });
  }

  for (const protocol of SEEDED_NOW) {
    test(`a raw-seeded ${protocol} package shows on the list, versions and detail pages`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(REPO_TYPE[protocol]);
      const pkg = await seedPackage(repo);
      const pages = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name);

      const list = pages.list();
      await list.goto();
      await list.expectRow(pkg);

      // A row click goes where the descriptor says (the latest detail, or the versions/tags page).
      const opened = await list.openRow(pkg);
      await expectOpened(opened, pkg);

      const versions = pages.versions(pkg);
      await versions.goto();
      await versions.expectRow(pkg);

      const detail = pages.detail(pkg);
      await detail.goto();
      await expect(detail.root).toBeVisible();
      await expect(detail.install).toBeVisible();
      for (const part of detail.detail.installContains(repo.name, pkg)) {
        await expect(detail.installText).toContainText(part);
      }
      await expectRepoUrlIn(detail);
    });

    // The descriptor's own data (search term, sort names, delete dialogs and toasts) is only worth
    // having if the panel agrees, so drive the page objects through it once.
    test(`the ${protocol} descriptor drives search, sort and both deletes`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(REPO_TYPE[protocol]);
      // One after the other, so "Oldest" and "Newest" have an order to show.
      const first = await seedPackage(repo, { index: 1 });
      const second = await seedPackage(repo, { index: 2 });
      const list = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).list();
      await list.goto();
      await list.expectRow(first);
      await list.expectRow(second);

      await list.search('zzz-no-such-package');
      await expect(list.emptyList.root).toBeVisible();
      await list.searchFor(first);
      await list.expectRow(first);

      await list.search('');
      await list.expectRow(second);
      await list.sortBy('Oldest');
      await expect(list.rows().first()).toHaveAttribute(
        'data-testid',
        `pkg-list-row-${list.keyOf(first)}`,
      );
      await list.sortBy('Newest');
      await expect(list.rows().first()).toHaveAttribute(
        'data-testid',
        `pkg-list-row-${list.keyOf(second)}`,
      );

      // Delete from the list's row menu: the dialog title and the toast are the descriptor's.
      await list.deleteRow(second);
      await list.expectNoRow(second);
      await list.expectRow(first);

      // Delete from the detail page: it lands where the descriptor says (the list, for these four).
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(first);
      await detail.goto();
      await detail.delete();
      expect(detail.detail.delete?.landsOn).toBe('list');
      await expectAfterLastVersionDelete(list, first);
    });

    test(`a mobile viewport shows the ${protocol} card list, not the table`, async ({
      openUiPage,
      adminSession,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(REPO_TYPE[protocol]);
      const pkg = await seedPackage(repo);
      const mobile = await openUiPage({
        session: adminSession,
        viewport: { width: 390, height: 844 },
      });
      const list = protocolPages(mobile, DESCRIPTORS[protocol], repo.name).list();
      await list.goto();
      await expect(list.card(pkg)).toBeVisible();
      await expect(list.row(pkg)).toBeHidden();
    });

    test(`twelve seeded ${protocol} packages paginate at ten per page`, async ({
      adminPage,
      seeder,
      seedPackages,
    }) => {
      const repo = await seeder.createRepo(REPO_TYPE[protocol]);
      await seedPackages(repo, 12);
      const list = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).list();
      await list.goto();
      await expect(list.rows()).toHaveCount(10);
      await expect(list.pagination.root).toBeVisible();
      await list.pagination.root.getByTestId('pagination-page-2').click();
      await expect(list.rows()).toHaveCount(2);
    });
  }

  // The special route shapes the generic levels do not cover on their own.
  test('maven: the artifact list of a group and the file browser route load', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.maven, repo.name);

    const group = pages.sublist(pkg);
    await group.goto();
    await group.expectRow(pkg);
    await expect(group.browseFilesButton).toBeVisible();

    await adminPage.goto(pages.extraPath('browser'));
    await expect(adminPage.getByTestId('pkg-toolbar')).toBeVisible();
    await expect(adminPage.getByTestId('maven-browser-grid')).toBeVisible();
  });

  test('npm: a scoped package opens its scope page, an unscoped one lives under ~', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const scoped = await seedPackage(repo, { index: 1 });
    const unscoped = await seedPackage(repo, { index: 2, scoped: false });
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);

    const list = pages.list();
    await list.goto();
    await list.expectRow(scoped);
    await list.expectRow(unscoped);

    const scope = await list.openLink(scoped, 'sublist');
    await scope.expectLoaded();
    await scope.expectRow(scoped);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/e2e-[a-z0-9]+$`));

    const tilde = pages.sublist(unscoped);
    expect(tilde.path()).toBe(`/${repo.name}/~`);
    await tilde.goto();
    await tilde.expectRow(unscoped);

    const versions = pages.versions(unscoped);
    expect(versions.path()).toBe(`/${repo.name}/~/${unscoped.name}`);
    await versions.goto();
    await versions.expectRow(unscoped);
  });

  test('docker: tag -> manifests -> tag detail, with the install bar on the tag and manifest lists', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const image = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.docker, repo.name);

    const tags = pages.versions(image);
    await tags.goto();
    await expect(tags.installBarText).toContainText(`${repo.name}/${image.name}`);

    const manifests = await tags.openLink(image, 'manifests');
    await manifests.expectLoaded();
    await manifests.expectRow(image);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${image.name}/${image.version}$`));
    await expect(manifests.installBarText).toContainText(`${image.name}:${image.version}`);

    await tags.goto();
    const detail = await tags.openRow(image);
    await expect(detail.page).toHaveURL(/\/detail$/);
    await expect(adminPage.getByTestId('pkg-detail-snippet-config')).toBeVisible();
  });
});
