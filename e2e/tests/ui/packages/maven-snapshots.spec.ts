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
 * Maven SNAPSHOT versions, the Signed column and the file browser's not-found states (RPS-1626, G14
 * and G26 of the RPS-1616 gap analysis). The ui runner has no `mvn`, so everything is seeded over the
 * protocol port with raw PUTs (`seedMaven`): timestamped SNAPSHOT builds with the version-level and
 * artifact-level `maven-metadata.xml` a real `mvn deploy` writes, and real detached PGP signatures
 * (OpenPGP.js, a key registered on the repo) for the Signed column. The wire behaviour itself is pinned
 * in `tests/maven/` (README "SNAPSHOT and redeploy behaviour, as probed").
 *
 *  - One SNAPSHOT VERSION is one row, however many timestamped builds it has; the panel can delete only
 *    the whole version (there is no delete of a single build).
 *  - The Signed column is a boolean, `Signed` or `Unsigned`, computed by the server: the POM signature
 *    verified, or on a repo with `pgpVerifyAllSignaturesEnabled` every file of the version (of its newest
 *    build, for a SNAPSHOT) with a verified signature. A signature that does not verify is refused at the
 *    wire (422), so an "invalid" `.asc` never reaches the panel.
 *  - The file browser tells three states apart: files, an empty repository (the empty list) and a
 *    directory it cannot list (`maven-browser-not-found`).
 */
import { ERROR_CODES } from '../../../src/error-codes.js';
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import {
  adminCredential,
  artifactDir,
  parseArtifactVersions,
  rawGet,
  repoTree,
  splitPackageName,
  versionDir,
} from '../../../src/clients/maven-raw.js';
import { generateKeyPair, type PgpKeyPair } from '../../../src/clients/pgp.js';
import type { SeededPackage } from '../../../src/seed/packages.js';
import type { SeededRepo } from '../../../src/seed/seeder.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { expectVersionNotFound } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages, type VersionsPage } from '../../../src/ui/pages/protocol.js';
import { repoApiPath, repoRoute } from '../../../src/ui/routes.js';
import { errorBody, fulfillJson, type ErrorResponse } from '../../../src/ui/stub-responses.js';

const maven = DESCRIPTORS.maven;

/**
 * The directory listing the browser reads (`GET <repo API path>/contents?...`). A Playwright glob's `*`
 * does not cross a `/`, and the path differs between Repsy OS and Cloud (an owner), so this matches the
 * pathname built by `repoApiPath` and requires a query string, as the old `**\/api/repos/*\/contents?*` did.
 */
const contentsListing =
  (repoName: string) =>
  (url: URL): boolean =>
    url.pathname === repoApiPath(repoName, 'contents') && url.search !== '';

let sharedKey: Promise<PgpKeyPair> | undefined;
/** One RSA key per worker: generating one takes about a second, and each repo only needs to register it. */
function pgpKey(): Promise<PgpKeyPair> {
  sharedKey ??= generateKeyPair();
  return sharedKey;
}

/** A Maven repo that already knows the worker's PGP key, so a signed seed verifies without a key server. */
async function repoWithKey(seeder: {
  createRepo: (type: RepoType) => Promise<SeededRepo>;
  registerPgpPublicKey: (repo: string, key: string) => Promise<unknown>;
}): Promise<{ repo: SeededRepo; key: PgpKeyPair }> {
  const repo = await seeder.createRepo(RepoType.MAVEN);
  const key = await pgpKey();
  await seeder.registerPgpPublicKey(repo.name, key.publicKeyArmored);
  return { repo, key };
}

/** The Signed cell of `target`'s desktop row: its text and its lock icon agree. */
async function expectSigned(
  versions: VersionsPage,
  target: SeededPackage,
  signed: boolean,
): Promise<void> {
  await expect(versions.inRow(target, 'row-signed')).toHaveText(signed ? 'Signed' : 'Unsigned');
  await expect(
    versions.row(target).locator(signed ? 'i.ri-lock-line' : 'i.ri-lock-unlock-line'),
  ).toBeVisible();
  await expect(
    versions.row(target).locator(signed ? 'i.ri-lock-unlock-line' : 'i.ri-lock-line'),
  ).toHaveCount(0);
}

/** The directory names a version directory is reached by in the browser, e.g. io/repsy/.../pkg-1/2.0.0-SNAPSHOT. */
function directories(name: string, version: string): string[] {
  const [group, artifact] = name.split(':');
  return [...group.split('.'), artifact, version];
}

const browserItem = (name: string): string => `maven-browser-item-${name}`;

async function openBrowser(page: Page, repoName: string): Promise<void> {
  await page.goto(repoRoute(repoName, 'browser'));
  await expect(
    page
      .getByTestId('maven-browser-grid')
      .or(page.getByTestId('empty-list'))
      .or(page.getByTestId('maven-browser-not-found')),
  ).toBeVisible();
}

async function enterDirectory(page: Page, dir: string): Promise<void> {
  await page
    .getByTestId(browserItem(`${dir}/`))
    .getByTestId('row-open')
    .click();
  await expect(page.getByTestId(`maven-browser-path-${dir}/`).last()).toBeVisible();
  await expect(page.getByTestId('maven-browser-grid')).toBeVisible();
}

test.describe('Maven SNAPSHOT versions', { tag: '@packages' }, () => {
  test('PKG-maven-11 a SNAPSHOT with several timestamped builds is one row, listed beside the releases', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const [release, snapshot] = await seedVersions(repo, ['1.0.0', '2.0.0-SNAPSHOT'], {
      maven: { builds: 3 },
    });
    // The storage really holds three builds of the SNAPSHOT (and its metadata), not one.
    const [group, artifact] = splitPackageName(snapshot.name);
    const tree = Object.keys(await repoTree(repo.name));
    const snapshotFiles = tree.filter((file) => file.includes('/2.0.0-SNAPSHOT/'));
    expect(snapshotFiles.filter((file) => file.endsWith('.jar'))).toHaveLength(3);
    expect(snapshotFiles.some((file) => file.endsWith('/maven-metadata.xml'))).toBe(true);

    const versions = protocolPages(adminPage, maven, repo.name).versions(release);
    await versions.goto();
    await expect(versions.rows()).toHaveCount(2);
    await expect(versions.inRow(snapshot, 'row-name')).toHaveText('2.0.0-SNAPSHOT');
    await expect(versions.inRow(release, 'row-name')).toHaveText('1.0.0');

    // Newest first by default, the SNAPSHOT above the release it precedes; Oldest reverses it.
    await expect(versions.rows().getByTestId('row-name')).toHaveText(['2.0.0-SNAPSHOT', '1.0.0']);
    await versions.sortBy('Oldest');
    await expect(versions.rows().getByTestId('row-name')).toHaveText(['1.0.0', '2.0.0-SNAPSHOT']);

    // The version search finds the SNAPSHOT by its suffix, and the list has no `group`/`artifact` clutter.
    await versions.search('SNAPSHOT');
    await expect(versions.rows()).toHaveCount(1);
    await versions.expectRow(snapshot);
    expect(group).toContain('io.repsy.e2e');
    expect(artifact).toBe('pkg-1');
  });

  // RPS-1665: the "Newest" order is by Maven version semantics, not the version NAME as a string
  // (which would put `1.9.0` above `1.10.0` and `2.0.0-SNAPSHOT` above `2.0.0`).
  test('PKG-maven-11 Newest orders the versions by version, not by the characters of the name', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const created = await seedVersions(repo, ['1.9.0', '1.10.0', '1.10.0-SNAPSHOT', '1.11.0']);
    const versions = protocolPages(adminPage, maven, repo.name).versions(created[0]);
    await versions.goto();
    await expect(versions.rows().getByTestId('row-name')).toHaveText([
      '1.11.0',
      '1.10.0',
      '1.10.0-SNAPSHOT',
      '1.9.0',
    ]);
  });

  test('PKG-maven-11 the detail of a SNAPSHOT names it, and every snippet asks for it', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const snapshot = await seedPackage(repo, { version: '1.4.0-SNAPSHOT', maven: { builds: 2 } });
    const [group, artifact] = splitPackageName(snapshot.name);
    const detail = protocolPages(adminPage, maven, repo.name).detail(snapshot);
    await detail.goto();

    await expect(detail.byId('pkg-detail-meta-group')).toContainText(group);
    await expect(detail.byId('pkg-detail-meta-artifact')).toContainText(
      `${artifact} (1.4.0-SNAPSHOT)`,
    );
    await expect(detail.installText).toContainText('<version>1.4.0-SNAPSHOT</version>');
    for (const slug of [
      'gradle-kotlin',
      'sbt',
      'ivy',
      'grape',
      'leiningen',
      'buildr',
      'purl',
      'bazel',
    ]) {
      await expect(detail.snippet(slug)).toContainText('1.4.0-SNAPSHOT');
    }
    await expect(detail.snippet('purl')).toContainText(
      `pkg:maven/${group}/${artifact}@1.4.0-SNAPSHOT`,
    );
    // The repository block a SNAPSHOT needs is the plain one: a Maven `<repository>` resolves releases AND
    // snapshots unless it says otherwise, and it does not.
    const repository = detail.snippet('repository');
    await expect(repository).toContainText('<repositories>');
    await expect(repository).toContainText(`<name>${repo.name} on Repsy</name>`);
    await expect(repository).not.toContainText('<snapshots>');
    await expect(adminPage).toHaveURL(new RegExp(`/${group}/${artifact}/1\\.4\\.0-SNAPSHOT$`));
  });

  test('PKG-maven-11 deleting a SNAPSHOT from the versions list removes every build and leaves the release', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const [release, snapshot] = await seedVersions(repo, ['1.0.0', '2.0.0-SNAPSHOT'], {
      maven: { builds: 3 },
    });
    const [group, artifact] = splitPackageName(snapshot.name);
    const versions = protocolPages(adminPage, maven, repo.name).versions(release);
    await versions.goto();

    // The dialog is the plain one (the release stays), and cancelling deletes nothing.
    const dialog = await versions.openDeleteDialog(snapshot);
    await expect(dialog.message).toHaveCount(0);
    await dialog.cancel();
    await dialog.expectClosed();
    await versions.expectRow(snapshot);
    expect(
      Object.keys(await repoTree(repo.name)).filter((f) => f.includes('/2.0.0-SNAPSHOT/')),
    ).not.toHaveLength(0);

    await versions.deleteRow(snapshot);
    await versions.expectNoRow(snapshot);
    await versions.expectRow(release);

    // Every timestamped build, the version-level metadata and the SNAPSHOT's checksums went; the release is intact.
    const tree = Object.keys(await repoTree(repo.name));
    expect(tree.filter((file) => file.includes('/2.0.0-SNAPSHOT/'))).toEqual([]);
    expect(tree.filter((file) => file.includes('/1.0.0/')).length).toBeGreaterThanOrEqual(2);
    const dir = versionDir(group, artifact, '2.0.0-SNAPSHOT');
    const jar = await rawGet(
      repo.name,
      adminCredential(),
      `/${dir}/${artifact}-2.0.0-20260101.100002-3.jar`,
    );
    expect(jar.status).toBe(404);
    // The artifact's metadata no longer lists the deleted version, and still lists the release.
    const metadata = await rawGet(
      repo.name,
      adminCredential(),
      `/${artifactDir(group, artifact)}/maven-metadata.xml`,
    );
    expect(metadata.status).toBe(200);
    expect(parseArtifactVersions(metadata.body.toString('utf8'))).toEqual(['1.0.0']);

    // Reloading shows the same: one row.
    await versions.goto();
    await expect(versions.rows()).toHaveCount(1);
  });

  test('PKG-maven-11 the detail Delete of a SNAPSHOT removes the version and lands on the versions list', async ({
    adminPage,
    pageErrors,
    seeder,
    seedVersions,
  }) => {
    // The last step opens the deleted version's URL on purpose: the panel toasts the server's 404.
    pageErrors.allowToast(
      'Artifact version is not found.',
      'the spec opens a version it just deleted',
    );
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const [release, snapshot] = await seedVersions(repo, ['1.0.0', '2.0.0-SNAPSHOT'], {
      maven: { builds: 2 },
    });
    const pages = protocolPages(adminPage, maven, repo.name);
    const detail = pages.detail(snapshot);
    await detail.goto();
    await detail.delete();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/[^/]+/[^/]+$`));
    const versions = pages.versions(release);
    await versions.expectLoaded();
    await versions.expectRow(release);
    await versions.expectNoRow(snapshot);
    // Opening the deleted SNAPSHOT's detail again is the not-found state (RPS-1625), not an empty page.
    await adminPage.goto(detail.path());
    await expectVersionNotFound(detail);
  });

  test('PKG-maven-11 the file browser lists every timestamped build of a SNAPSHOT and its metadata', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const snapshot = await seedPackage(repo, { version: '3.0.0-SNAPSHOT', maven: { builds: 2 } });
    await openBrowser(adminPage, repo.name);
    for (const dir of directories(snapshot.name, snapshot.version)) {
      await enterDirectory(adminPage, dir);
    }
    const artifact = splitPackageName(snapshot.name)[1];
    for (const build of ['20260101.100000-1', '20260101.100001-2']) {
      await expect(
        adminPage.getByTestId(browserItem(`${artifact}-3.0.0-${build}.jar`)),
      ).toBeVisible();
      await expect(
        adminPage.getByTestId(browserItem(`${artifact}-3.0.0-${build}.pom`)),
      ).toBeVisible();
    }
    await expect(adminPage.getByTestId(browserItem('maven-metadata.xml'))).toBeVisible();
    // The literal `-SNAPSHOT` file names are not there: a real deploy sends timestamped ones.
    await expect(adminPage.getByTestId(browserItem(`${artifact}-3.0.0-SNAPSHOT.jar`))).toHaveCount(
      0,
    );
  });
});

// @cloud-skip: registers PGP public keys through the OS panel API (`registerPgpPublicKey`) and flips
// `pgpVerifyAllSignaturesEnabled`, neither of which Repsy Cloud's panel has (its signature settings differ).
test.describe('Maven Signed column', { tag: ['@packages', '@cloud-skip'] }, () => {
  test('PKG-maven-12 signed and unsigned releases and SNAPSHOTs show Signed and Unsigned, with the matching lock', async ({
    adminPage,
    seeder,
    seedVersions,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    const [unsigned] = await seedVersions(repo, ['1.0.0']);
    const signed = await seedPackage(repo, { version: '2.0.0', maven: { sign: { key } } });
    const signedSnapshot = await seedPackage(repo, {
      version: '3.0.0-SNAPSHOT',
      maven: { builds: 2, sign: { key } },
    });
    const unsignedSnapshot = await seedPackage(repo, {
      version: '4.0.0-SNAPSHOT',
      maven: { builds: 2 },
    });

    const versions = protocolPages(adminPage, maven, repo.name).versions(unsigned);
    await versions.goto();
    await expect(versions.rows()).toHaveCount(4);
    await expectSigned(versions, unsigned, false);
    await expectSigned(versions, signed, true);
    await expectSigned(versions, signedSnapshot, true);
    await expectSigned(versions, unsignedSnapshot, false);
  });

  test('PKG-maven-12 the mobile cards say Signed or Unsigned too', async ({
    openUiPage,
    adminSession,
    seeder,
    seedVersions,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    const [unsigned] = await seedVersions(repo, ['1.0.0']);
    const signed = await seedPackage(repo, { version: '2.0.0-SNAPSHOT', maven: { sign: { key } } });
    const mobile = await openUiPage({
      session: adminSession,
      viewport: { width: 390, height: 844 },
    });
    const versions = protocolPages(mobile, maven, repo.name).versions(unsigned);
    await versions.goto();
    await expect(versions.card(signed).getByTestId('row-signed')).toContainText('Signed');
    await expect(versions.card(signed).getByTestId('row-signed')).not.toContainText('Unsigned');
    await expect(versions.card(unsigned).getByTestId('row-signed')).toContainText('Unsigned');
    await expect(versions.row(signed)).toBeHidden();
  });

  test('PKG-maven-12 the detail page says whether the version is signed, in words and with the lock', async ({
    adminPage,
    seeder,
    seedVersions,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    const [unsigned] = await seedVersions(repo, ['1.0.0']);
    const signed = await seedPackage(repo, { version: '2.0.0-SNAPSHOT', maven: { sign: { key } } });
    const pages = protocolPages(adminPage, maven, repo.name);

    const signedDetail = pages.detail(signed);
    await signedDetail.goto();
    await expect(signedDetail.byId('pkg-detail-meta-signed')).toHaveText(/Signed:\s*Yes/);
    await expect(
      signedDetail.byId('pkg-detail-meta-signed').locator('i.ri-lock-line'),
    ).toBeVisible();

    const unsignedDetail = pages.detail(unsigned);
    await unsignedDetail.goto();
    await expect(unsignedDetail.byId('pkg-detail-meta-signed')).toHaveText(/Signed:\s*No/);
    await expect(
      unsignedDetail.byId('pkg-detail-meta-signed').locator('i.ri-lock-unlock-line'),
    ).toBeVisible();
  });

  test('PKG-maven-12 on a verify-all repo a version is signed only when every file of it is', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: true });
    const pomOnly = await seedPackage(repo, {
      version: '1.0.0',
      maven: { sign: { key, files: 'pom' } },
    });
    const everyFile = await seedPackage(repo, {
      version: '2.0.0',
      maven: { sign: { key, files: 'all' } },
    });
    const snapshot = await seedPackage(repo, {
      version: '3.0.0-SNAPSHOT',
      maven: { builds: 2, sign: { key, files: 'all' } },
    });

    const versions = protocolPages(adminPage, maven, repo.name).versions(pomOnly);
    await versions.goto();
    await expectSigned(versions, pomOnly, false);
    await expectSigned(versions, everyFile, true);
    await expectSigned(versions, snapshot, true);

    // A newer build without signatures makes the SNAPSHOT unsigned: the newest build is what counts.
    await seedPackage(repo, {
      version: '3.0.0-SNAPSHOT',
      maven: { builds: 1, firstBuild: 3 },
    });
    await expect(async () => {
      await versions.goto();
      await expectSigned(versions, snapshot, false);
    }).toPass({ timeout: 30_000 });
    await expectSigned(versions, everyFile, true);
  });

  // RPS-1323 (and RPS-1316): turning verify-all on or off recomputes `signed` of every version of the repo
  // in the background, and verifies the `.asc` files that were stored while it was off, so a publisher who
  // signed every file stays signed, and one who signed the POM only shows Unsigned exactly while it is on.
  test('PKG-maven-12 turning verify-all on and off recomputes the Signed column of the stored versions', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    const pomOnly = await seedPackage(repo, {
      version: '1.0.0',
      maven: { sign: { key, files: 'pom' } },
    });
    const everyFile = await seedPackage(repo, {
      version: '2.0.0',
      maven: { sign: { key, files: 'all' } },
    });
    const versions = protocolPages(adminPage, maven, repo.name).versions(pomOnly);
    await versions.goto();
    await expectSigned(versions, pomOnly, true);
    await expectSigned(versions, everyFile, true);

    await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: true });
    await expect(async () => {
      await versions.goto();
      await expectSigned(versions, pomOnly, false);
      await expectSigned(versions, everyFile, true);
    }).toPass({ timeout: 30_000 });

    await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: false });
    await expect(async () => {
      await versions.goto();
      await expectSigned(versions, pomOnly, true);
      await expectSigned(versions, everyFile, true);
    }).toPass({ timeout: 30_000 });
  });

  test('PKG-maven-12 the file browser lists the signature files next to what they sign', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const { repo, key } = await repoWithKey(seeder);
    const signed = await seedPackage(repo, {
      version: '1.0.0',
      maven: { sign: { key, files: 'all' } },
    });
    const artifact = splitPackageName(signed.name)[1];
    await openBrowser(adminPage, repo.name);
    for (const dir of directories(signed.name, signed.version)) {
      await enterDirectory(adminPage, dir);
    }
    for (const file of ['jar', 'jar.asc', 'pom', 'pom.asc']) {
      await expect(adminPage.getByTestId(browserItem(`${artifact}-1.0.0.${file}`))).toBeVisible();
    }
  });
});

test.describe('Maven file browser states', { tag: '@packages' }, () => {
  test('PKG-maven-13 an empty repository shows the empty list, not the not-found state, and nothing to go back to', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await openBrowser(adminPage, repo.name);
    await expect(adminPage.getByTestId('empty-list')).toBeVisible();
    await expect(adminPage.getByTestId('maven-browser-not-found')).toHaveCount(0);
    await expect(adminPage.getByTestId('maven-browser-grid')).toHaveCount(0);
    await expect(adminPage.getByTestId('spinner')).toHaveCount(0);
    await expect(adminPage.getByTestId('maven-browser-path')).toContainText(`${repo.name}:`);
    // Back and forward have nowhere to go and do nothing, quietly.
    await adminPage.getByTestId('maven-browser-back').click();
    await adminPage.getByTestId('maven-browser-forward').click();
    await expect(adminPage.getByTestId('empty-list')).toBeVisible();
    // The file name filter of an empty listing does not raise an error either.
    await adminPage.getByTestId('pkg-search').getByTestId('search-input').fill('anything');
    await expect(adminPage.getByTestId('empty-list')).toBeVisible();
  });

  test('PKG-maven-13 a cold load shows the listing after one click on a directory, however slow the listing is (RPS-1297)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    // The root listing answers late: the click cannot land before the grid exists, and the path is never lost.
    await adminPage.route(contentsListing(repo.name), async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      await route.continue();
    });
    await adminPage.goto(repoRoute(repo.name, 'browser'));
    const top = directories(pkg.name, pkg.version)[0];
    await adminPage
      .getByTestId(browserItem(`${top}/`))
      .getByTestId('row-open')
      .click();
    await expect(adminPage.getByTestId(`maven-browser-path-${top}/`)).toBeVisible();
    await expect(adminPage.getByTestId('maven-browser-grid')).toBeVisible();
    await expect(adminPage.getByTestId(browserItem('../'))).toBeVisible();
  });

  test.describe('a directory that is gone', () => {
    test.use({
      allowedPageErrors: errorToasts(
        'the spec lists a directory that was deleted: the server answers 404 and the panel toasts it',
        'Resource not found.',
      ),
    });

    test('PKG-maven-13 opening a directory deleted meanwhile shows not-found under its own path, never the old listing', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN);
      // Two groups under one parent directory, so the parent survives the deletion of the first.
      const gone = await seedPackage(repo, { index: 1 });
      const kept = await seedPackage(repo, { index: 2 });
      const goneDirs = directories(gone.name, gone.version);
      const parentDirs = goneDirs.slice(0, -3); // io/repsy/e2e/<run>
      const goneGroupDir = goneDirs[goneDirs.length - 3]; // g1
      const keptGroupDir = directories(kept.name, kept.version)[goneDirs.length - 3]; // g2
      await openBrowser(adminPage, repo.name);
      for (const dir of parentDirs) {
        await enterDirectory(adminPage, dir);
      }
      await expect(adminPage.getByTestId(browserItem(`${goneGroupDir}/`))).toBeVisible();

      const [group, artifact] = splitPackageName(gone.name);
      await panelApi.deleteMavenArtifactVersion(repo.name, group, artifact, gone.version);

      await adminPage
        .getByTestId(browserItem(`${goneGroupDir}/`))
        .getByTestId('row-open')
        .click();
      // The path asked for, the not-found state, and nothing of the directory it came from.
      await expect(adminPage.getByTestId(`maven-browser-path-${goneGroupDir}/`)).toBeVisible();
      await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();
      await expect(adminPage.getByTestId('maven-browser-not-found')).toContainText(
        'Repository files not found!',
      );
      await expect(adminPage.getByTestId('maven-browser-grid')).toHaveCount(0);
      await expect(adminPage.getByTestId('empty-list')).toHaveCount(0);
      await expect(adminPage.getByTestId('spinner')).toHaveCount(0);

      // The file name filter of that state does not raise an error, and the state stays.
      await adminPage.getByTestId('pkg-search').getByTestId('search-input').fill('zzz');
      await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();

      // Back lists the parent again, which still holds the other group and no longer the deleted one.
      await adminPage.getByTestId('maven-browser-back').click();
      await expect(adminPage.getByTestId(browserItem(`${keptGroupDir}/`))).toBeVisible();
      await expect(adminPage.getByTestId(browserItem(`${goneGroupDir}/`))).toHaveCount(0);
      await expect(adminPage.getByTestId('maven-browser-not-found')).toHaveCount(0);
    });

    test('PKG-maven-13 a repository emptied while the browser is open shows the not-found state, and its root shows the empty list', async ({
      adminPage,
      panelApi,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN);
      const pkg = await seedPackage(repo);
      const dirs = directories(pkg.name, pkg.version);
      await openBrowser(adminPage, repo.name);
      await enterDirectory(adminPage, dirs[0]);
      const [group, artifact] = splitPackageName(pkg.name);
      await panelApi.deleteMavenArtifactVersion(repo.name, group, artifact, pkg.version);

      await adminPage
        .getByTestId(browserItem(`${dirs[1]}/`))
        .getByTestId('row-open')
        .click();
      await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();
      await adminPage.getByTestId('maven-browser-path-root').click();
      await expect(adminPage.getByTestId('empty-list')).toBeVisible();
      await expect(adminPage.getByTestId('maven-browser-not-found')).toHaveCount(0);
    });
  });

  test.describe('the first listing fails', () => {
    test.use({
      allowedPageErrors: errorToasts(
        'the spec stubs the listing to fail, and the panel toasts the answer',
        'Resource not found.',
        'Server error',
      ),
    });

    for (const status of [404, 500]) {
      test(`PKG-maven-13 a root listing that answers ${status} shows the not-found state and no endless spinner`, async ({
        adminPage,
        seeder,
      }) => {
        const repo = await seeder.createRepo(RepoType.MAVEN);
        await adminPage.route(contentsListing(repo.name), (route) =>
          fulfillJson<ErrorResponse>(
            route,
            status,
            errorBody({
              status: 404,
              code: ERROR_CODES.ITEM_NOT_FOUND,
              detail: 'Resource not found.',
            }),
          ),
        );
        await openBrowser(adminPage, repo.name);
        await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();
        await expect(adminPage.getByTestId('maven-browser-grid')).toHaveCount(0);
        await expect(adminPage.getByTestId('spinner')).toHaveCount(0);
        // The toolbar still works: Configure opens, and the filter does not raise an error.
        await adminPage.getByTestId('pkg-search').getByTestId('search-input').fill('x');
        await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();
        await adminPage.getByTestId('pkg-configure').click();
        await expect(adminPage.getByTestId('maven-browser-not-found')).toBeVisible();
      });
    }
  });

  test('PKG-maven-13 the file name filter is emptied with the directory it was typed in', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const dirs = directories(pkg.name, pkg.version);
    await openBrowser(adminPage, repo.name);
    await enterDirectory(adminPage, dirs[0]);
    await enterDirectory(adminPage, dirs[1]);
    const filter = adminPage.getByTestId('pkg-search').getByTestId('search-input');
    await filter.fill(dirs[2].slice(0, 3));
    await expect(adminPage.getByTestId(browserItem('../'))).toHaveCount(0);
    // Down one directory: the box is empty again, and so is the filter (the whole listing shows).
    await adminPage.getByTestId('maven-browser-back').click();
    await expect(filter).toHaveValue('');
    await expect(adminPage.getByTestId(browserItem('../'))).toBeVisible();
    await expect(adminPage.getByTestId(browserItem(`${dirs[1]}/`))).toBeVisible();
  });

  // Real groups repeat a segment (`org.apache.maven.maven`, `com.foo.foo:foo`): the breadcrumb and Back must not
  // confuse the copies.
  test('PKG-maven-13 a path that repeats a directory name walks down and up one directory at a time', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo, { name: 'com.foo.foo:foo' });
    const path = adminPage.getByTestId('maven-browser-path');
    await openBrowser(adminPage, repo.name);
    for (const dir of ['com', 'foo', 'foo', 'foo']) {
      await adminPage
        .getByTestId(browserItem(`${dir}/`))
        .getByTestId('row-open')
        .click();
      await expect(adminPage.getByTestId('maven-browser-grid')).toBeVisible();
    }
    await expect(path).toHaveText(new RegExp(`${repo.name}:\\s*/com/foo/foo/foo/$`));
    // The version directory is the next level; the copies of `foo/` in the breadcrumb are four separate buttons.
    await expect(adminPage.getByTestId('maven-browser-path-foo/')).toHaveCount(3);
    await expect(adminPage.getByTestId(browserItem(pkg.version + '/'))).toBeVisible();
    // The second `foo/` of the breadcrumb goes to com/foo/foo/ (its own level, not the first or the last).
    await adminPage.getByTestId('maven-browser-path-foo/').nth(1).click();
    await expect(path).toHaveText(new RegExp(`${repo.name}:\\s*/com/foo/foo/$`));
    await expect(adminPage.getByTestId(browserItem('foo/'))).toBeVisible();
  });
});
