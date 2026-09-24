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

    // It lands on the image's tag list (RPS-1288 item 7), which no longer has that tag.
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${one.name}$`));
    await tags.expectLoaded();
    await tags.expectNoRow(one);
    await tags.expectRow(two);
    const images = pages.list();
    await images.goto();
    await images.expectRow(one);

    await tags.goto();
    await tags.expectNoRow(one);
    await tags.expectRow(two);
    expect((await rawGetManifest(repo.name, admin, one.name, one.version)).status).toBe(404);
    expect((await rawGetManifest(repo.name, admin, two.name, two.version)).status).toBe(200);
  });

  // RPS-1288 item 5: an image stays while it stores any manifest. Deleting its last TAG removes the tag
  // only (OCI: the manifest stays pullable by digest), so the image is listed as "No tags" with what it
  // keeps, its page stays and explains, and it goes with its last manifest.
  test.describe('an image whose last tag was deleted (RPS-1288)', () => {
    const untaggedText = /1 untagged manifest is still stored/;

    test('PKG-docker-08 stays listed as "No tags" with its untagged manifest and size, and its page explains', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const image = await seedPackage(repo);
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();

      const tags = pages.versions(image);
      await tags.goto();
      await tags.deleteRow(image);

      // The page stays (it does not leave for the list) and says what is left, with both actions.
      await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${image.name}$`));
      await expect(tags.noTags).toBeVisible();
      await expect(tags.noTags).toContainText('This image has no tags');
      await expect(tags.noTags).toContainText(untaggedText);
      await expect(tags.emptyList.root).toBeHidden();
      await expect(adminPage.getByTestId('pkg-delete-untagged')).toBeVisible();
      await expect(adminPage.getByTestId('pkg-no-tags-delete-image')).toBeVisible();

      // The manifest is still pullable by digest, and the panel counts it.
      expect(
        (await rawGetManifest(repo.name, admin, image.name, image.extra['digest'])).status,
      ).toBe(200);
      expect((await rawGetManifest(repo.name, admin, image.name, image.version)).status).toBe(404);

      // The list keeps the row: "No tags", how many untagged manifests, and the untagged size (not 0 B).
      const images = pages.list();
      await images.goto();
      await images.expectRow(image);
      await expect(images.inRow(image, 'row-no-tags')).toHaveText('No tags');
      await expect(images.inRow(image, 'row-untagged')).toHaveText('1 untagged manifest');
      await expect(images.inRow(image, 'row-size')).toContainText('untagged');
      await expect(images.inRow(image, 'row-size')).not.toContainText(/^\s*0 B/);

      // The row still opens the image page with the explanation, through its one link.
      await images.openRow(image);
      await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${image.name}$`));
      await expect(tags.noTags).toBeVisible();
    });

    test('PKG-docker-08 two images without tags are two rows, on the desktop list and on the mobile cards', async ({
      openUiPage,
      adminSession,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const first = await seedPackage(repo, { index: 1 });
      const second = await seedPackage(repo, { index: 2 });
      await panelApi.deleteDockerTag(repo.name, first.name, first.version);
      await panelApi.deleteDockerTag(repo.name, second.name, second.version);

      // Rows used to be tracked by their last update, which an image without tags does not have.
      const desktop = protocolPages(
        await openUiPage({ session: adminSession }),
        docker,
        repo.name,
      ).list();
      await desktop.goto();
      await desktop.expectRow(first);
      await desktop.expectRow(second);
      await expect(desktop.rows()).toHaveCount(2);
      await expect(desktop.inRow(first, 'row-no-tags')).toBeVisible();
      await expect(desktop.inRow(second, 'row-no-tags')).toBeVisible();

      const mobilePage = await openUiPage({
        session: adminSession,
        viewport: { width: 390, height: 844 },
      });
      const mobile = protocolPages(mobilePage, docker, repo.name).list();
      await mobile.goto();
      await expect(mobile.card(first)).toContainText('No tags');
      await expect(mobile.card(first)).toContainText('1 untagged manifest');
      await expect(mobile.card(second)).toContainText('No tags');
      await expect(mobile.card(second)).toContainText('untagged');
    });

    test('PKG-docker-08 "Delete Untagged Manifests" on its page removes the manifests and the image', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const emptied = await seedPackage(repo, { index: 1 });
      const kept = await seedPackage(repo, { index: 2 });
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();
      await panelApi.deleteDockerTag(repo.name, emptied.name, emptied.version);
      expect((await panelApi.getDockerImageSummary(repo.name, emptied.name)).tagCount).toBe(0);

      const tags = pages.versions(emptied);
      await tags.goto();
      await expect(tags.noTags).toBeVisible();
      await adminPage.getByTestId('pkg-delete-untagged').click();
      await tags.dangerModal.expectOpen('Delete Untagged Manifests');
      await tags.dangerModal.confirm();
      await tags.toasts.expectSuccess(/Deleted 1 untagged manifest/);

      // The image went with its last manifest, so the page leaves for the image list.
      await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
      const images = pages.list();
      await images.expectLoaded();
      await images.expectNoRow(emptied);
      await images.expectRow(kept);
      expect(
        (await rawGetManifest(repo.name, admin, emptied.name, emptied.extra['digest'])).status,
      ).toBe(404);
      await expect(panelApi.getDockerImageSummary(repo.name, emptied.name)).rejects.toMatchObject({
        status: 404,
      });
      expect((await rawGetManifest(repo.name, admin, kept.name, kept.version)).status).toBe(200);
    });

    test('PKG-docker-08 "Delete Image" on its page removes the image and goes to the list', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const emptied = await seedPackage(repo, { index: 1 });
      const pages = protocolPages(adminPage, docker, repo.name);
      await panelApi.deleteDockerTag(repo.name, emptied.name, emptied.version);

      const tags = pages.versions(emptied);
      await tags.goto();
      await adminPage.getByTestId('pkg-no-tags-delete-image').click();
      await tags.dangerModal.expectOpen('Delete Image');
      await tags.dangerModal.confirm();
      await tags.toasts.expectSuccess('Image deleted successfully');

      await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
      const images = pages.list();
      await images.expectLoaded();
      await images.expectNoRow(emptied);
    });

    test('PKG-docker-08 an image with one manifest and another tag left is not "No tags"', async ({
      adminPage,
      panelApi,
      seeder,
      seedVersions,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
      const pages = protocolPages(adminPage, docker, repo.name);
      await panelApi.deleteDockerTag(repo.name, one.name, one.version);

      const images = pages.list();
      await images.goto();
      await images.expectRow(one);
      await expect(images.inRow(one, 'row-no-tags')).toHaveCount(0);
      await expect(images.inRow(one, 'row-digest')).toContainText(two.extra['digest'].slice(0, 15));
      const tags = pages.versions(one);
      await tags.goto();
      await tags.expectRow(two);
      await expect(tags.noTags).toBeHidden();
    });
  });
});
