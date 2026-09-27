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
 * Docker multi-platform images in the panel (RPS-1627, gap G15): a tag that is an OCI image INDEX over a
 * linux/amd64 and a linux/arm64 manifest, plus one untagged manifest, seeded over the wire with real
 * blobs (`seedPackage(repo, { docker: { platforms, untagged } })`, no `crane`/`buildx` in the `ui` runner).
 *
 * What the panel has for it: the image row, the tag row (platform `Multiplatform`), the tag's manifest list
 * (the index, then one row per platform named by its digest, each with platform, digest and config
 * digest), the tag detail (the index JSON, no config), and the deletes of a tag, of the untagged manifests
 * and of the image. It has NO page, pull command or delete of a single platform: a platform manifest is
 * deleted over the wire only, and the panel follows it (PKG-docker-11).
 *
 * PKG-docker-10 the views, PKG-docker-11 the delete flows, PKG-docker-12 a digest in the place of a tag.
 * The seeded facts (`extra`) are documented on `seedDocker`.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { rawDeleteManifest, rawGetManifest, rawHeadBlob } from '../../../src/clients/docker-raw.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { repoPath } from '../../../src/repo-url.js';
import { scanPage } from '../../../src/ui/a11y.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { asDetailPage, expectVersionNotFound } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { dockerPlatformRef } from '../../../src/ui/pages/protocols/docker.js';
import type { PackageRef, SeededPackage } from '../../../src/seed/packages.js';

const docker = DESCRIPTORS.docker;
const AMD64 = 'linux/amd64';
const ARM64 = 'linux/arm64';
const PLATFORMS = [AMD64, ARM64] as const;
const INDEX_MEDIA_TYPE = 'application/vnd.oci.image.index.v1+json';
const MISSING_DIGEST = `sha256:${'0'.repeat(64)}`;

/**
 * A size as the panel prints it (`ByteFormatter`: two decimals at most, `B`, `K`, `M`): the seed's blobs are a few
 * hundred bytes each, so a sum of a few of them is `1.06 K` and not `1083 B`.
 */
function bytes(n: number): string {
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < 2) {
    value /= 1024;
    unit += 1;
  }
  return `${Number.parseFloat(value.toFixed(2))} ${['B', 'K', 'M'][unit]}`;
}
const num = (pkg: SeededPackage, key: string): number => Number(pkg.extra[key]);
const platformBytes = (pkg: SeededPackage): number =>
  PLATFORMS.map((platform) => num(pkg, `size.${platform}`)).reduce((a, b) => a + b, 0);
const untaggedDigest = (pkg: SeededPackage): string => pkg.extra['untaggedDigests'];

const NOT_FOUND_TOAST = {
  pattern: /not found/i,
  reason:
    'by design: the error toast of the 404 a link to a tag that does not exist provokes (RPS-1625, RPS-1627)',
};

test.describe('Docker multi-platform image index', { tag: '@packages' }, () => {
  test('PKG-docker-10 the image list, the tag list and the manifest list show the index and each platform', async ({
    adminPage,
    panelApi,
    seeder,
    seedPackage,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const index = await seedPackage(repo, {
      docker: { platforms: PLATFORMS, untagged: 1 },
    });
    const pages = protocolPages(adminPage, docker, repo.name);
    const host = new URL(env.repoBaseUrl).host;

    // The summary the list is made of: the platforms' manifests are reached through the tag, so only the
    // manifest no index lists is untagged, and the size is what the two platforms' blobs add up to.
    const summary = await panelApi.getDockerImageSummary(repo.name, index.name);
    expect(summary).toMatchObject({
      tagCount: 1,
      untaggedManifestCount: 1,
      size: platformBytes(index),
      untaggedSize: num(index, 'untaggedSize'),
      digest: index.extra['digest'],
    });

    const images = pages.list();
    await images.goto();
    await images.expectRow(index);
    await expect(images.rows()).toHaveCount(1);
    await expect(images.inRow(index, 'row-digest')).toContainText(
      index.extra['digest'].slice(0, 15),
    );
    await expect(images.inRow(index, 'row-size')).toHaveText(bytes(platformBytes(index)));
    await expect(images.inRow(index, 'row-no-tags')).toHaveCount(0);

    // The image row opens the tag list: one tag, and it is a multi-platform one.
    const tags = (await images.openRow(index)) as ReturnType<typeof pages.versions>;
    await tags.expectLoaded();
    await expect(tags.rows()).toHaveCount(1);
    await tags.expectRow(index);
    await expect(tags.inRow(index, 'row-platform')).toHaveText('Multiplatform');
    await expect(tags.noTags).toBeHidden();
    await expect(adminPage.getByTestId('pkg-delete-untagged')).toBeVisible();

    // The tag's link opens its manifests: the index, then one row per platform, named by its digest.
    const manifests = await tags.openLink(index, 'manifests');
    await manifests.expectLoaded();
    await expect(manifests.installBarText).toHaveText(
      `docker pull ${host}/${repoPath(repo.name)}/${index.name}:${index.version}`,
    );
    await expect(manifests.rows()).toHaveCount(3);
    await expect(manifests.inRow(index, 'row-name')).toHaveText(index.version);
    await expect(manifests.inRow(index, 'row-platform')).toHaveText('Multiplatform');
    await expect(manifests.inRow(index, 'row-digest')).toContainText(
      index.extra['digest'].slice(0, 15),
    );
    // An index has no config of its own: the cell is empty, not the digest of a platform's config.
    await expect(manifests.inRow(index, 'row-config-digest')).toHaveText('');
    for (const platform of PLATFORMS) {
      const row = dockerPlatformRef(index, platform);
      await manifests.expectRow(row);
      await expect(manifests.inRow(row, 'row-name')).toContainText(
        index.extra[`digest.${platform}`].slice(0, 15),
      );
      await expect(manifests.inRow(row, 'row-platform')).toHaveText(platform);
      await expect(manifests.inRow(row, 'row-digest')).toContainText(
        index.extra[`digest.${platform}`].slice(0, 15),
      );
      await expect(manifests.inRow(row, 'row-config-digest')).toContainText(
        index.extra[`configDigest.${platform}`].slice(0, 15),
      );
      // The manifest list is read-only: nothing to open or delete for a platform.
      await expect(manifests.inRow(row, 'row-menu')).toHaveCount(0);
    }
    // The untagged manifest belongs to no tag, so it is not on this list.
    await expect(adminPage.getByTestId(`pkg-manifests-row-${untaggedDigest(index)}`)).toHaveCount(
      0,
    );
    await scanPage(adminPage, testInfo, 'docker-index-manifests');
  });

  test('PKG-docker-10 the manifest list of an index sorts and searches its rows', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
    const arm64 = dockerPlatformRef(index, ARM64);
    const amd64 = dockerPlatformRef(index, AMD64);
    const manifests = protocolPages(adminPage, docker, repo.name).manifests(index);
    await manifests.goto();
    await expect(manifests.rows()).toHaveCount(3);

    // The index was pushed after its platforms, so it is the newest row and the oldest list ends with it.
    await expect(manifests.rows().first()).toHaveAttribute(
      'data-testid',
      `pkg-manifests-row-${index.version}`,
    );
    await manifests.sortBy('Oldest');
    await expect(manifests.rows().last()).toHaveAttribute(
      'data-testid',
      `pkg-manifests-row-${index.version}`,
    );
    await manifests.sortBy('Newest');
    await expect(manifests.rows().first()).toHaveAttribute(
      'data-testid',
      `pkg-manifests-row-${index.version}`,
    );

    // The search matches the row name: a platform's digest finds only that platform, the tag only the index.
    await manifests.searchFor(arm64);
    await manifests.expectRow(arm64);
    await manifests.expectNoRow(amd64);
    await manifests.expectNoRow(index);
    await manifests.searchFor(index);
    await manifests.expectRow(index);
    await manifests.expectNoRow(arm64);
    await manifests.search('');
    await expect(manifests.rows()).toHaveCount(3);
  });

  test('PKG-docker-10 the mobile cards of an index show each platform with its digests', async ({
    openUiPage,
    adminSession,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
    const mobile = await openUiPage({
      session: adminSession,
      viewport: { width: 390, height: 844 },
    });
    const manifests = protocolPages(mobile, docker, repo.name).manifests(index);
    await manifests.goto();

    await expect(manifests.card(index)).toContainText('Platform: Multiplatform');
    for (const platform of PLATFORMS) {
      const card = manifests.card(dockerPlatformRef(index, platform));
      await expect(card).toBeVisible();
      await expect(card).toContainText(`Platform: ${platform}`);
      await expect(card).toContainText(`Digest: ${index.extra[`digest.${platform}`]}`);
      await expect(card).toContainText(`Config Digest: ${index.extra[`configDigest.${platform}`]}`);
    }
  });

  // RPS-1627: the API leaves `configDigest` out for an index (it does not send null), and the page asked for
  // the config of `undefined` (a runtime error) and drew an empty Config block.
  test('PKG-docker-10 the tag detail of an index shows the index and no config block', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
    const host = new URL(env.repoBaseUrl).host;
    const tags = protocolPages(adminPage, docker, repo.name).versions(index);
    await tags.goto();
    const detail = asDetailPage(await tags.openRow(index));
    await expect(adminPage).toHaveURL(
      new RegExp(`/${repo.name}/${index.name}/${index.version}/detail$`),
    );
    await detail.expectLoaded();

    await expect(detail.installText).toHaveText(
      `docker pull ${host}/${repoPath(repo.name)}/${index.name}:${index.version}`,
    );
    await expect(detail.byId('pkg-detail-version')).toContainText(index.version);
    // The manifest block is the index JSON: its media type, and each platform with the digest of its manifest.
    const manifest = detail.snippet('manifest');
    await expect(manifest).toContainText(INDEX_MEDIA_TYPE);
    for (const platform of PLATFORMS) {
      const [os, arch] = platform.split('/');
      await expect(manifest).toContainText(`"architecture":"${arch}"`);
      await expect(manifest).toContainText(`"os":"${os}"`);
      await expect(manifest).toContainText(index.extra[`digest.${platform}`]);
    }
    await expect(detail.byId('pkg-detail-snippet-config')).toHaveCount(0);
    await expect(detail.error).toHaveCount(0);
  });

  test.describe('deleting from a multi-platform image', () => {
    const untaggedText = (count: number, size: number): string =>
      `${count} untagged manifests are still stored (${bytes(size)})`;

    test('PKG-docker-11 deleting the tag keeps the index and its platforms as untagged manifests, pullable by digest', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }, testInfo) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS, untagged: 1 } });
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();
      // The index, its two platforms and the manifest nothing lists: four untagged manifests once the tag is gone.
      const keptBytes = platformBytes(index) + num(index, 'untaggedSize');

      const tags = pages.versions(index);
      await tags.goto();
      const modal = await tags.openDeleteDialog(index);
      await modal.cancel();
      await tags.expectRow(index);
      await tags.deleteRow(index);

      await expect(tags.noTags).toBeVisible();
      await expect(tags.noTags).toContainText('This image has no tags');
      await expect(tags.noTags).toContainText(untaggedText(4, keptBytes));
      await scanPage(adminPage, testInfo, 'docker-index-no-tags');

      // The tag is gone, everything else answers by digest.
      expect((await rawGetManifest(repo.name, admin, index.name, index.version)).status).toBe(404);
      expect(
        (await rawGetManifest(repo.name, admin, index.name, index.extra['digest'])).status,
      ).toBe(200);
      for (const platform of PLATFORMS) {
        const pull = await rawGetManifest(
          repo.name,
          admin,
          index.name,
          index.extra[`digest.${platform}`],
        );
        expect(pull.status, platform).toBe(200);
      }
      expect(await panelApi.getDockerImageSummary(repo.name, index.name)).toMatchObject({
        tagCount: 0,
        untaggedManifestCount: 4,
        untaggedSize: keptBytes,
      });

      // The list keeps the image as "No tags" with what it stores.
      const images = pages.list();
      await images.goto();
      await expect(images.inRow(index, 'row-no-tags')).toHaveText('No tags');
      await expect(images.inRow(index, 'row-untagged')).toHaveText('4 untagged manifests');
      await expect(images.inRow(index, 'row-size')).toContainText(bytes(keptBytes));
    });

    test('PKG-docker-11 the delete of the untagged manifests of a tagged image spares the index and its platforms', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS, untagged: 1 } });
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();
      const tags = pages.versions(index);
      await tags.goto();

      await adminPage.getByTestId('pkg-delete-untagged').click();
      await tags.dangerModal.expectOpen('Delete Untagged Manifests');
      await tags.dangerModal.confirm();
      await tags.toasts.expectSuccess(/Deleted 1 untagged manifest and 2 unused layers/);

      // Only the manifest no index lists went; the tag, the index and both platforms stay.
      expect(
        (await rawGetManifest(repo.name, admin, index.name, untaggedDigest(index))).status,
      ).toBe(404);
      expect((await rawGetManifest(repo.name, admin, index.name, index.version)).status).toBe(200);
      for (const platform of PLATFORMS) {
        expect(
          (await rawGetManifest(repo.name, admin, index.name, index.extra[`digest.${platform}`]))
            .status,
          platform,
        ).toBe(200);
      }
      expect(await panelApi.getDockerImageSummary(repo.name, index.name)).toMatchObject({
        tagCount: 1,
        untaggedManifestCount: 0,
        size: platformBytes(index),
      });
      await tags.expectRow(index);
      await tags.refresh();
      await tags.expectRow(index);
      await protocolPages(adminPage, docker, repo.name).manifests(index).goto();
      await expect(adminPage.locator('[data-testid^="pkg-manifests-row-"]')).toHaveCount(3);

      // The layers only the untagged manifest used are swept in the background; the platforms' layers never are.
      const layers = index.extra['untaggedLayerDigests'].split(',');
      for (const layer of layers) {
        await expect
          .poll(async () => (await rawHeadBlob(repo.name, admin, index.name, layer)).status, {
            message: 'the layer of the deleted untagged manifest is swept',
          })
          .toBe(404);
      }
      for (const platform of PLATFORMS) {
        const head = await rawHeadBlob(
          repo.name,
          admin,
          index.name,
          index.extra[`layerDigest.${platform}`],
        );
        expect(head.status, `layer of ${platform}`).toBe(200);
      }
    });

    test('PKG-docker-11 after its tag is deleted, "Delete Untagged Manifests" removes the index, its platforms and the image', async ({
      adminPage,
      panelApi,
      pageErrors,
      seeder,
      seedPackage,
    }) => {
      pageErrors.allowToast(
        'Image not found.',
        'by design since RPS-1579: the page reloads the tag list of the image its own delete just removed, that is a 404 with a toast, and the page leaves for the image list (see PKG-docker-08)',
      );
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
      const other = await seedPackage(repo, { index: 2 });
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();
      await panelApi.deleteDockerTag(repo.name, index.name, index.version);
      expect(
        (await panelApi.getDockerImageSummary(repo.name, index.name)).untaggedManifestCount,
      ).toBe(3);

      const tags = pages.versions(index);
      await tags.goto();
      await expect(tags.noTags).toContainText(untaggedText(3, platformBytes(index)));
      await adminPage.getByTestId('pkg-delete-untagged').click();
      await tags.dangerModal.expectOpen('Delete Untagged Manifests');
      await tags.dangerModal.confirm();
      await tags.toasts.expectSuccess(/Deleted 3 untagged manifests/);

      // The image went with its last manifest: the page leaves for the list, which keeps the other image.
      await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
      const images = pages.list();
      await images.expectLoaded();
      await images.expectNoRow(index);
      await images.expectRow(other);
      for (const digest of [
        index.extra['digest'],
        index.extra[`digest.${AMD64}`],
        index.extra[`digest.${ARM64}`],
      ]) {
        expect((await rawGetManifest(repo.name, admin, index.name, digest)).status, digest).toBe(
          404,
        );
      }
      await expect(panelApi.getDockerImageSummary(repo.name, index.name)).rejects.toMatchObject({
        status: 404,
      });
      expect((await rawGetManifest(repo.name, admin, other.name, other.version)).status).toBe(200);
    });

    test('PKG-docker-11 deleting the image from the list removes the tag, the index and its platforms', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS, untagged: 1 } });
      const other = await seedPackage(repo, { index: 2 });
      const pages = protocolPages(adminPage, docker, repo.name);
      const admin = adminCredential();

      const images = pages.list();
      await images.goto();
      const modal = await images.openDeleteDialog(index);
      await modal.cancel();
      await images.expectRow(index);
      await images.deleteRow(index);

      await images.expectNoRow(index);
      await images.expectRow(other);
      const gone = [
        index.version,
        index.extra['digest'],
        index.extra[`digest.${AMD64}`],
        index.extra[`digest.${ARM64}`],
        untaggedDigest(index),
      ];
      for (const reference of gone) {
        expect(
          (await rawGetManifest(repo.name, admin, index.name, reference)).status,
          reference,
        ).toBe(404);
      }
      await expect(panelApi.getDockerImageSummary(repo.name, index.name)).rejects.toMatchObject({
        status: 404,
      });
      // A reload agrees: the image is not listed, and the other one is untouched.
      await adminPage.reload();
      await images.expectLoaded();
      await images.expectNoRow(index);
      expect((await rawGetManifest(repo.name, admin, other.name, other.version)).status).toBe(200);
    });

    // The panel has no delete for one platform (its manifest rows are read-only), so this is the wire's: the
    // panel follows what is stored.
    test('PKG-docker-11 a platform manifest deleted over the wire leaves the manifest list, the size and the other platform', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
      const arm64 = dockerPlatformRef(index, ARM64);
      const amd64 = dockerPlatformRef(index, AMD64);
      const admin = adminCredential();
      const manifests = protocolPages(adminPage, docker, repo.name).manifests(index);
      await manifests.goto();
      await expect(manifests.rows()).toHaveCount(3);

      expect((await rawDeleteManifest(repo.name, admin, index.name, arm64.version)).status).toBe(
        202,
      );

      await manifests.refresh();
      await expect(manifests.rows()).toHaveCount(2);
      await manifests.expectNoRow(arm64);
      await manifests.expectRow(amd64);
      await manifests.expectRow(index);
      expect((await panelApi.getDockerImageSummary(repo.name, index.name)).size).toBe(
        num(index, `size.${AMD64}`),
      );
      expect((await rawGetManifest(repo.name, admin, index.name, amd64.version)).status).toBe(200);
      expect((await rawGetManifest(repo.name, admin, index.name, index.version)).status).toBe(200);
    });
  });

  // RPS-1627: the manifest list of a tag that does not exist said "Your list is empty" (a tag with no manifests
  // is impossible), so a digest typed in the place of the tag looked like an empty tag.
  test.describe('a digest in the place of a tag', () => {
    test.beforeEach(({ pageErrors }) => {
      pageErrors.allow(NOT_FOUND_TOAST.pattern, NOT_FOUND_TOAST.reason);
    });

    test('PKG-docker-12 the manifest list and the detail of an unknown digest show the not-found state', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
      const missing: PackageRef = { ...index, version: MISSING_DIGEST };
      const pages = protocolPages(adminPage, docker, repo.name);

      const manifests = pages.manifests(missing);
      await manifests.goto();
      await expect(manifests.error).toBeVisible();
      await expect(manifests.errorMessage).toHaveText(`Version '${MISSING_DIGEST}' not found`);
      await expect(manifests.emptyList.root).toHaveCount(0);
      await expect(manifests.rows()).toHaveCount(0);

      const detail = pages.detail(missing);
      await detail.goto();
      await expectVersionNotFound(detail);

      // The page still works for the tag that exists.
      const existing = pages.manifests(index);
      await existing.goto();
      await expect(existing.rows()).toHaveCount(3);
      await expect(existing.error).toHaveCount(0);
    });

    test('PKG-docker-12 the digest of a real platform is not a tag either: not-found, not the platform', async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const index = await seedPackage(repo, { docker: { platforms: PLATFORMS } });
      const platform = dockerPlatformRef(index, ARM64);
      const pages = protocolPages(adminPage, docker, repo.name);

      const manifests = pages.manifests(platform);
      await manifests.goto();
      await expect(manifests.error).toBeVisible();
      await expect(manifests.errorMessage).toHaveText(`Version '${platform.version}' not found`);
      await expect(manifests.rows()).toHaveCount(0);

      const detail = pages.detail(platform);
      await detail.goto();
      await expectVersionNotFound(detail);
    });
  });
});
