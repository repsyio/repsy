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
 * Docker package pages (RPS-1256): the shared scenarios PKG-docker-01..06 (`registerPackageScenarios`)
 * and PKG-docker-07, the Docker-only journey image -> tags -> manifests -> tag detail (with the
 * config digest) and deleting a tag. `target.name` is the image, `target.version` its tag.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { rawGetManifest } from '../../../src/clients/docker-raw.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { asDetailPage, registerPackageScenarios } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { env } from '../../../src/env.js';

const docker = DESCRIPTORS.docker;

registerPackageScenarios(docker);

test.describe('Docker image, tags, manifests and tag detail', { tag: '@packages' }, () => {
  test('PKG-docker-07 image -> tags -> manifests -> tag detail with the config digest', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const pages = protocolPages(adminPage, docker, repo.name);
    const host = new URL(env.repoBaseUrl).host;

    // The image row opens the TAG list, which carries the pull command of the image.
    const images = pages.list();
    await images.goto();
    await images.expectRow(one);
    const tags = (await images.openRow(one)) as ReturnType<typeof pages.versions>;
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${one.name}$`));
    await tags.expectLoaded();
    await tags.expectRow(one);
    await tags.expectRow(two);
    await expect(tags.installBar).toBeVisible();
    await expect(tags.installBarText).toContainText(`${host}/${repo.name}/${one.name}`);

    // A tag's inner link opens the manifest list of that tag: keyed by the tag, with its own pull command.
    const manifests = await tags.openLink(one, 'manifests');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${one.name}/${one.version}$`));
    await manifests.expectLoaded();
    await manifests.expectRow(one);
    await expect(manifests.installBarText).toContainText(`${one.name}:${one.version}`);
    // The manifest list is read-only: no row menu, and its row does not open anything.
    await expect(manifests.inRow(one, 'row-menu')).toHaveCount(0);

    // Manifests can be searched by name (placeholder `manifest`).
    await expect(manifests.searchInput).toHaveAttribute('placeholder', 'manifest');
    await manifests.search(`zzz-no-such-${seeder.runId}`);
    await expect(manifests.emptyList.root).toBeVisible();
    await manifests.searchFor(one);
    await manifests.expectRow(one);
    await manifests.search('');
    await manifests.expectRow(one);

    // The tag's row itself opens the tag detail: pull command, manifest, config, and the config digest.
    await tags.goto();
    const opened = asDetailPage(await tags.openRow(one));
    await expect(adminPage).toHaveURL(
      new RegExp(`/${repo.name}/${one.name}/${one.version}/detail$`),
    );
    await opened.expectLoaded();
    await expect(opened.installText).toContainText(
      `${host}/${repo.name}/${one.name}:${one.version}`,
    );
    await expect(opened.name).toHaveText(one.name);
    await expect(opened.byId('pkg-detail-version')).toContainText(one.version);
    await expect(opened.byId('pkg-detail-published')).toContainText('Uploaded at:');

    // The manifest block names the config blob by its digest; the config block is that blob's content.
    await expect(opened.snippet('manifest')).toContainText(one.extra['configDigest']);
    await expect(opened.snippet('manifest')).toContainText(
      'application/vnd.docker.distribution.manifest.v2+json',
    );
    await expect(opened.snippet('config')).toContainText('"architecture": "amd64"');
    await expect(opened.snippet('config')).toContainText('"os": "linux"');
    // The other tag is another manifest, so another config digest.
    expect(two.extra['configDigest']).not.toBe(one.extra['configDigest']);
  });

  test('PKG-docker-07 a manifest row is found by its id and shows its name and platform', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const image = await seedPackage(repo);
    const manifests = protocolPages(adminPage, docker, repo.name).manifests(image);
    await manifests.goto();
    // The row is keyed by the tag (a single-platform image pushed by tag names its manifest so).
    await expect(manifests.rows()).toHaveCount(1);
    await manifests.expectRow(image);
    await expect(manifests.inRow(image, 'row-name')).toHaveText(image.version);
    await expect(manifests.inRow(image, 'row-platform')).toHaveText('linux/amd64');
  });

  test('PKG-docker-07 the mobile manifest card shows the manifest digest and the config digest', async ({
    openUiPage,
    adminSession,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const image = await seedPackage(repo);
    const mobile = await openUiPage({
      session: adminSession,
      viewport: { width: 390, height: 844 },
    });
    const manifests = protocolPages(mobile, docker, repo.name).manifests(image);
    await manifests.goto();
    await expect(manifests.card(image)).toBeVisible();
    // Each digest under its own label (the desktop grid is asserted below, RPS-1261).
    await expect(manifests.card(image)).toContainText('Platform: linux/amd64');
    await expect(manifests.card(image)).toContainText(`Digest: ${image.extra['digest']}`);
    await expect(manifests.card(image)).toContainText(
      `Config Digest: ${image.extra['configDigest']}`,
    );
  });

  // RPS-1261 (2): the DESKTOP manifest table's Digest cell used to show the platform and its Config Digest
  // cell the manifest digest; the mobile card above was always right.
  test('PKG-docker-07 the desktop manifest row shows the digest and the config digest in their own columns (RPS-1261)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const image = await seedPackage(repo);
    const manifests = protocolPages(adminPage, docker, repo.name).manifests(image);
    await manifests.goto();
    // The cells cut a digest to 15 characters (`sha256:e4cb7d5c...`); the full one is in the tooltip.
    await expect(manifests.inRow(image, 'row-digest')).toContainText(
      image.extra['digest'].slice(0, 15),
    );
    await expect(manifests.inRow(image, 'row-config-digest')).toContainText(
      image.extra['configDigest'].slice(0, 15),
    );
  });

  test('PKG-docker-07 deleting a tag from its detail page removes that tag only, and the registry agrees', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const pages = protocolPages(adminPage, docker, repo.name);
    const admin = adminCredential();
    expect((await rawGetManifest(repo.name, admin, one.name, one.version)).status).toBe(200);

    // Tag list -> tag detail -> Delete: the dialog says "Delete Version", the toast "Tag deleted".
    const tags = pages.versions(one);
    await tags.goto();
    const opened = asDetailPage(await tags.openRow(one));
    await opened.expectLoaded();
    await opened.delete();

    // It lands on the image list (recorded: RPS-1288 (7) asks for one convention), and the image is still there.
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    const images = pages.list();
    await images.expectLoaded();
    await images.expectRow(one);

    await tags.goto();
    await tags.expectNoRow(one);
    await tags.expectRow(two);
    expect((await rawGetManifest(repo.name, admin, one.name, one.version)).status).toBe(404);
    expect((await rawGetManifest(repo.name, admin, two.name, two.version)).status).toBe(200);
  });
});
