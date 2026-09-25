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
 * Maven package pages (RPS-1256): the shared scenarios PKG-maven-01..06 (`registerPackageScenarios`)
 * and PKG-maven-07, the Maven-only routes: the `/browser` file browser with its download-token flow,
 * the group route `/:repo/:group`, and the install snippets of the version detail (ten build tools and
 * the repository block with the repo URL, RPS-1288 (6)).
 */
import { createHash } from 'node:crypto';

import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { groupPath, rawGet, repoTree, versionDir } from '../../../src/clients/maven-raw.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios, rowKeys } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const maven = DESCRIPTORS.maven;

registerPackageScenarios(maven);

/** `io.repsy.e2e.abc.g1:pkg-1` at `1.0.0` -> the directories a browser walks: io, repsy, e2e, abc, g1, pkg-1, 1.0.0. */
function directories(name: string, version: string): string[] {
  const [group, artifact] = name.split(':');
  return [...group.split('.'), artifact, version];
}

const browserItem = (dir: string): string => `maven-browser-item-${dir}`;

/** Opens `/:repo/browser` and waits for the root listing. */
async function openBrowser(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByTestId('maven-browser-grid')).toBeVisible();
}

/**
 * Opens the directory `dir` of the current listing with ONE click and waits for its breadcrumb. That
 * click must work even on a cold-loaded browser (RPS-1297, fixed: the permissions load once and a
 * reload of them no longer empties the directory stack).
 */
async function enterDirectory(page: Page, dir: string): Promise<void> {
  await page
    .getByTestId(browserItem(`${dir}/`))
    .getByTestId('row-open')
    .click();
  await expect(page.getByTestId(`maven-browser-path-${dir}/`)).toBeVisible();
}

test.describe('Maven file browser', { tag: '@packages' }, () => {
  test('PKG-maven-07 the browser walks the directories down to the artifact, back and forward, and filters by file name', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, maven, repo.name);
    const dirs = directories(pkg.name, pkg.version);
    const grid = adminPage.getByTestId('maven-browser-grid');
    const path = adminPage.getByTestId('maven-browser-path');
    const item = (name: string) => adminPage.getByTestId(browserItem(name));

    await openBrowser(adminPage, pages.extraPath('browser'));
    await expect(path).toContainText(`${repo.name}:`);
    // The root of the repo holds the top of the group id, and nothing of the other groups.
    await expect(item(`${dirs[0]}/`)).toBeVisible();

    // Down: each click opens the directory, the breadcrumb grows, the parent link `../` appears.
    for (const dir of dirs) {
      await enterDirectory(adminPage, dir);
      await expect(item(`${dir}/`)).toHaveCount(0);
      await expect(item('../')).toBeVisible();
    }

    // The version directory holds the files that were published.
    const jar = `${pkg.name.split(':')[1]}-${pkg.version}.jar`;
    const pom = `${pkg.name.split(':')[1]}-${pkg.version}.pom`;
    await expect(item(jar)).toBeVisible();
    await expect(item(pom)).toBeVisible();
    await expect(item(jar).getByTestId('row-name')).toHaveText(jar);
    await expect(path).toContainText(`${dirs.join('/')}/`);

    // The File name filter narrows the files on screen, case-insensitively, and clears again.
    const filter = adminPage.getByTestId('pkg-search').getByTestId('search-input');
    await expect(filter).toHaveAttribute('placeholder', 'File name');
    await filter.fill('.POM');
    await expect(item(pom)).toBeVisible();
    await expect(item(jar)).toHaveCount(0);
    await filter.fill(`zzz-no-such-file-${seeder.runId}`);
    await expect(grid).toBeHidden();
    await expect(adminPage.getByTestId('empty-list')).toBeVisible();
    await filter.fill('');
    await expect(item(jar)).toBeVisible();
    await expect(item(pom)).toBeVisible();

    // Back goes up one directory at a time, forward returns.
    const back = adminPage.getByTestId('maven-browser-back');
    const forward = adminPage.getByTestId('maven-browser-forward');
    const versionDir = dirs[dirs.length - 1];
    const artifactDir = dirs[dirs.length - 2];
    await back.click();
    await expect(item(`${versionDir}/`)).toBeVisible();
    await expect(item(jar)).toHaveCount(0);
    await back.click();
    await expect(item(`${artifactDir}/`)).toBeVisible();
    await forward.click();
    await expect(item(`${versionDir}/`)).toBeVisible();
    await forward.click();
    await expect(item(jar)).toBeVisible();

    // `../` goes up too, and a breadcrumb jumps straight to its directory.
    await item('../').getByTestId('row-open').click();
    await expect(item(`${versionDir}/`)).toBeVisible();
    await adminPage.getByTestId('maven-browser-path-root').click();
    await expect(item(`${dirs[0]}/`)).toBeVisible();
    await expect(item(`${dirs[1]}/`)).toHaveCount(0);
  });

  test('PKG-maven-07 opening a file downloads it through a one-minute download token', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, maven, repo.name);
    const dirs = directories(pkg.name, pkg.version);
    const artifact = pkg.name.split(':')[1];
    const jar = `${artifact}-${pkg.version}.jar`;
    const remotePath = `/${dirs.join('/')}/${jar}`;

    await openBrowser(adminPage, pages.extraPath('browser'));
    for (const dir of dirs) {
      await enterDirectory(adminPage, dir);
    }

    // A navigation cannot carry an Authorization header, so the SPA first asks the panel API for a
    // token that opens only this path, then navigates to the file with it in the query string.
    const tokenResponse = adminPage.waitForResponse(
      (res) =>
        res.url().includes(`/api/repos/${repo.name}/download-token`) &&
        res.request().method() === 'POST',
    );
    const fileRequest = adminPage.waitForRequest(
      (req) =>
        req.url().includes(`/${repo.name}${remotePath}`) && req.url().includes('downloadToken='),
    );
    const download = adminPage.waitForEvent('download');
    await adminPage.getByTestId(browserItem(jar)).getByTestId('row-open').click();

    const token = await tokenResponse;
    expect(token.status()).toBe(200);
    expect(new URL(token.url()).searchParams.get('path')).toBe(remotePath);
    const request = await fileRequest;
    expect(request.method()).toBe('GET');
    expect(request.headers()['authorization']).toBeUndefined();

    const file = await download;
    expect(file.suggestedFilename()).toBe(jar);
    const path = await file.path();
    expect(path).toBeTruthy();

    // Byte for byte what the repository serves to the admin over the protocol port.
    const { readFile } = await import('node:fs/promises');
    const downloaded = await readFile(path);
    const served = await rawGet(repo.name, adminCredential(), remotePath);
    expect(served.status).toBe(200);
    expect(createHash('sha256').update(downloaded).digest('hex')).toBe(
      createHash('sha256').update(served.body).digest('hex'),
    );
    // The page stays where it was: a download is not a navigation away.
    await expect(adminPage.getByTestId('maven-browser-grid')).toBeVisible();
  });

  // RPS-1297: the first click on a directory of a freshly loaded browser opens it (the page used to
  // load the repository's permissions twice, and the second load emptied the directory stack).
  test('PKG-maven-07 the first click on a directory of a freshly loaded browser opens it (RPS-1297)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    await openBrowser(adminPage, protocolPages(adminPage, maven, repo.name).extraPath('browser'));
    const top = directories(pkg.name, pkg.version)[0];
    await adminPage
      .getByTestId(browserItem(`${top}/`))
      .getByTestId('row-open')
      .click();
    await expect(adminPage.getByTestId(`maven-browser-path-${top}/`)).toBeVisible({
      timeout: 3000,
    });
  });

  // RPS-1262 (4): the browser page used to render a Settings button disabled for a USER, where every
  // other package page removes it (`@if (canManage)`).
  test('PKG-maven-05 a USER sees no Settings button on the file browser (RPS-1262)', async ({
    adminPage,
    userPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pages = protocolPages(userPage, maven, repo.name);
    await adminPage.goto(pages.extraPath('browser'));
    await expect(adminPage.getByTestId('pkg-settings')).toBeEnabled(); // the control
    await userPage.goto(pages.extraPath('browser'));
    await expect(userPage.getByTestId('pkg-toolbar')).toBeVisible();
    await expect(userPage.getByTestId('pkg-settings')).toHaveCount(0);
  });
});

test.describe('Maven group page', { tag: '@packages' }, () => {
  test('PKG-maven-07 the group route lists the group and reaches its artifacts, versions and file browser', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const first = await seedPackage(repo);
    const second = await seedPackage(repo, {
      name: maven.levels.sublist!.siblingName!(first, 1),
      version: '2.0.0',
    });
    const pages = protocolPages(adminPage, maven, repo.name);
    const [group] = first.name.split(':');

    // The list row's own link goes to `/:repo/:group`.
    const list = pages.list();
    await list.goto();
    const groupPage = await list.openLink(first, 'sublist');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${group}$`));
    await groupPage.expectLoaded();
    await groupPage.expectRow(first);
    await groupPage.expectRow(second);
    await expect(groupPage.searchInput).toHaveAttribute('placeholder', 'artifact');

    // An artifact's row opens its latest detail, its link the version list.
    const versions = await groupPage.openLink(second, 'versions');
    await versions.expectLoaded();
    await versions.expectRow(second);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${group}/[^/]+$`));

    // Browse Files is on every list page and opens the browser.
    await groupPage.goto();
    await groupPage.browseFilesButton.click();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/browser$`));
    await expect(adminPage.getByTestId('maven-browser-grid')).toBeVisible();
  });

  // RPS-1288 (2): the list search used to match the group only; it matches the whole `group:artifact`
  // key a row shows, next to its parts.
  test('PKG-maven-04 the list search finds a row by group, by artifact and by the whole group:artifact', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const first = await seedPackage(repo);
    const sibling = await seedPackage(repo, { name: maven.levels.sublist!.siblingName!(first, 1) });
    const other = await seedPackage(repo, { index: 2 }); // another group
    const list = protocolPages(adminPage, maven, repo.name).list();
    await list.goto();
    await expect(list.rows()).toHaveCount(3);

    // The group finds the two artifacts of it, the whole key exactly one row.
    await list.search(first.name.split(':')[0]);
    await expect
      .poll(async () => (await rowKeys(list)).sort())
      .toEqual([first.name, sibling.name].sort());
    await list.search(sibling.name);
    await expect.poll(() => rowKeys(list)).toEqual([sibling.name]);
    await list.search(other.name);
    await expect.poll(() => rowKeys(list)).toEqual([other.name]);
    // An artifact name alone finds its row too.
    await list.search(other.name.split(':')[1]);
    await expect.poll(() => rowKeys(list)).toEqual([other.name]);
    await list.search(`${other.name}-no-such`);
    await expect(list.emptyList.root).toBeVisible();
  });

  // RPS-1288 (4), decided: the group list's row is one ARTIFACT, but its Delete says "Delete Group" and
  // removes the whole group, siblings included. The confirmation says so: it names the group and counts
  // the artifacts and versions that go.
  test('PKG-maven-04 deleting from the group list removes the whole group, and the dialog says what goes', async ({
    adminPage,
    seeder,
    seedPackage,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    // Two artifacts of one group: `first` with two versions, its sibling with one.
    const [first] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const sibling = await seedPackage(repo, { name: maven.levels.sublist!.siblingName!(first, 1) });
    const other = await seedPackage(repo, { index: 2 }); // another group
    const [group] = first.name.split(':');
    const list = protocolPages(adminPage, maven, repo.name).list();
    await list.goto();
    await list.expectRow(first);
    await list.expectRow(sibling);

    const dialog = await list.openDeleteDialog(first);
    await expect(dialog.message).toContainText(`The whole group ${group} will be deleted`);
    await expect(dialog.message).toContainText('not just this artifact');
    await expect(dialog.message).toContainText('2 artifacts and 3 versions');
    // Cancelling deletes nothing.
    await dialog.cancel();
    await dialog.expectClosed();
    await list.expectRow(first);
    await list.expectRow(sibling);

    await list.deleteRow(first);
    await list.expectNoRow(first);
    await list.expectNoRow(sibling);
    await list.expectRow(other);
    await expect(list.rows()).toHaveCount(1);
  });

  // RPS-1349: the "root group" delete (`com.acme` prefixes every other group of the repo). The database
  // removes only the artifacts with that exact group name, and so must the storage: the nested group
  // `com.acme.sub` keeps its rows and every one of its files, and nothing of the root group is orphaned.
  test('PKG-maven-04 deleting a root group removes exactly what the dialog counts and keeps the nested group', async ({
    adminPage,
    seeder,
    seedPackage,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    // Root group: `alpha` with two versions and its sibling `beta` with one (2 artifacts, 3 versions).
    const [alpha] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const beta = await seedPackage(repo, { name: maven.levels.sublist!.siblingName!(alpha, 1) });
    const [group] = alpha.name.split(':');
    // The nested group `<root>.sub`: `gamma` with two versions, its own group.
    const nestedGroup = `${group}.sub`;
    const [gamma] = await seedVersions(repo, ['1.0.0', '2.0.0'], { name: `${nestedGroup}:gamma` });
    const list = protocolPages(adminPage, maven, repo.name).list();
    await list.goto();
    await list.expectRow(alpha);
    await list.expectRow(beta);
    await list.expectRow(gamma);

    const nestedFiles = (tree: Record<string, string>) =>
      Object.fromEntries(
        Object.entries(tree).filter(([file]) => file.startsWith(`${groupPath(nestedGroup)}/`)),
      );
    const before = await repoTree(repo.name);
    const nestedBefore = nestedFiles(before);
    // jar, pom (x2 versions) and the artifact metadata: the nested group has files to lose.
    expect(Object.keys(nestedBefore).length).toBeGreaterThanOrEqual(5);
    expect(Object.keys(before).length).toBeGreaterThan(Object.keys(nestedBefore).length);

    // The dialog counts the root group's own artifacts and versions, not the nested group's.
    const dialog = await list.openDeleteDialog(alpha);
    await expect(dialog.message).toContainText(`The whole group ${group} will be deleted`);
    await expect(dialog.message).toContainText('2 artifacts and 3 versions');
    await dialog.confirm();
    await list.toasts.expectSuccess('Group deleted successfully');

    // The panel: the root group's rows are gone, the nested group's stay.
    await list.expectNoRow(alpha);
    await list.expectNoRow(beta);
    await list.expectRow(gamma);
    await expect(list.rows()).toHaveCount(1);

    // The storage agrees with the panel: the nested group's files are all there, byte for byte, and
    // nothing else is left (no orphaned file of the root group).
    const after = await repoTree(repo.name);
    expect(after).toEqual(nestedBefore);
    for (const artifact of [alpha, beta]) {
      const pom = `${versionDir(group, artifact.name.split(':')[1], artifact.version)}/${artifact.name.split(':')[1]}-${artifact.version}.pom`;
      const served = await rawGet(repo.name, adminCredential(), `/${pom}`);
      expect(served.status).toBe(404);
    }
    const gammaPom = `${versionDir(nestedGroup, 'gamma', '1.0.0')}/gamma-1.0.0.pom`;
    expect((await rawGet(repo.name, adminCredential(), `/${gammaPom}`)).status).toBe(200);

    // And the nested group is still a working group: its detail loads, and its own summary counts stay.
    const detail = protocolPages(adminPage, maven, repo.name).detail(gamma);
    await detail.goto();
    await expect(detail.byId('pkg-detail-meta-group')).toContainText(nestedGroup);
    const nestedList = protocolPages(adminPage, maven, repo.name).list();
    await nestedList.goto();
    const nestedDialog = await nestedList.openDeleteDialog(gamma);
    await expect(nestedDialog.message).toContainText(
      `The whole group ${nestedGroup} will be deleted`,
    );
    await expect(nestedDialog.message).toContainText('1 artifact and 2 versions');
    await nestedDialog.cancel();
  });
});

test.describe('Maven version detail', { tag: '@packages' }, () => {
  /**
   * What each of the eleven blocks (the ten build-tool ones and the repository one) is called and what it
   * contains for `group:artifact:version` in `repoName`.
   */
  const SNIPPETS = (group: string, artifact: string, version: string, repoName: string) =>
    [
      ['pom', 'Pom XML', `<artifactId>${artifact}</artifactId>`],
      ['repository', 'Apache Maven Repository', `<url>${env.repoBaseUrl}/${repoName}</url>`],
      ['gradle-groovy', 'Gradle Groovy DSL', 'Gradle Groovy DSL'], // content: see the RPS-1261 test below
      ['gradle-kotlin', 'Gradle Kotlin DSL', `implementation("${group}:${artifact}:${version}")`],
      ['sbt', 'Scala SBT', `libraryDependencies += "${group}" % "${artifact}" % "${version}"`],
      [
        'ivy',
        'Apache Ivy',
        // RPS-1395: with Ivy's default configuration mapping a bare line also asks for sources/javadoc.
        `<dependency org="${group}" name="${artifact}" rev="${version}" conf="default->default" />`,
      ],
      [
        'grape',
        'Groovy Grape',
        `@Grab(group='${group}', module='${artifact}', version='${version}')`,
      ],
      ['leiningen', 'Leiningen', `[${group}/${artifact} "${version}"]`],
      ['buildr', 'Apache Buildr', `'${group}:${artifact}:jar:${version}'`],
      ['purl', 'Purl', `pkg:maven/${group}/${artifact}@${version}`],
      ['bazel', 'Bazel', `artifact = "${group}:${artifact}:${version}"`],
    ] as const;

  // RPS-1348: the last version of the only artifact of a group takes the artifact and the group with it
  // (the server answers GROUP), so the confirmation says so; a version that is not that one does not.
  const ONLY_VERSION_OF = (artifact: string): string => `This is the only version of ${artifact}`;
  const GROUP_GOES_TOO = 'the artifact and the group are removed too';

  test("PKG-maven-04 the detail of the last version of a group's only artifact says the artifact and the group go too", async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const [group, artifact] = pkg.name.split(':');
    const pages = protocolPages(adminPage, maven, repo.name);
    const detail = pages.detail(pkg);
    await detail.goto();

    const dialog = await detail.openDeleteDialog();
    await expect(dialog.message).toContainText(ONLY_VERSION_OF(artifact));
    await expect(dialog.message).toContainText(`only artifact of the group ${group}`);
    await expect(dialog.message).toContainText(GROUP_GOES_TOO);
    // Cancelling deletes nothing.
    await dialog.cancel();
    await dialog.expectClosed();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${group}/${artifact}/`));
    expect(Object.keys(await repoTree(repo.name)).length).toBeGreaterThan(0);

    // What the dialog said happens: the package list is empty, and so is the storage (no group left).
    await detail.openDeleteDialog();
    await dialog.confirm();
    await detail.toasts.expectSuccess('Version deleted successfully');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    const list = pages.list();
    await list.goto();
    await expect(list.emptyList.root).toBeVisible();
    expect(await repoTree(repo.name)).toEqual({});
  });

  test('PKG-maven-04 the detail dialog stays plain when the version is not the last of the only artifact of its group', async ({
    adminPage,
    seeder,
    seedPackage,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    // `many` has two versions; `only` is the last version of its artifact but has a sibling in its group.
    const [many] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const only = await seedPackage(repo, { name: maven.levels.sublist!.siblingName!(many, 1) });
    const pages = protocolPages(adminPage, maven, repo.name);

    for (const target of [many, only]) {
      const detail = pages.detail(target);
      await detail.goto();
      const dialog = await detail.openDeleteDialog();
      await expect(dialog.message).toHaveCount(0);
      await dialog.cancel();
      await dialog.expectClosed();
    }
  });

  test('PKG-maven-04 the versions list says the same for its last version, and deleting it lands on the package list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const artifact = pkg.name.split(':')[1];
    const pages = protocolPages(adminPage, maven, repo.name);
    const versions = pages.versions(pkg);
    await versions.goto();
    await versions.expectRow(pkg);

    const dialog = await versions.openDeleteDialog(pkg);
    await expect(dialog.message).toContainText(ONLY_VERSION_OF(artifact));
    await expect(dialog.message).toContainText(GROUP_GOES_TOO);
    await dialog.cancel();
    await dialog.expectClosed();
    await versions.expectRow(pkg);

    await versions.deleteRow(pkg);
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    const list = pages.list();
    await list.goto();
    await expect(list.emptyList.root).toBeVisible();
  });

  // RPS-1296: the API's `artifactVersionName` used to be the artifact's latest version, so every
  // snippet (and the Delete button) of an older version's detail named the latest one.
  test('PKG-maven-07 the detail of an older version shows that version, not the latest', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const [older] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const detail = protocolPages(adminPage, maven, repo.name).detail(older);
    await detail.goto();
    await expect(detail.byId('pkg-detail-meta-artifact')).toContainText('(1.0.0)');
    await expect(detail.installText).toContainText('<version>1.0.0</version>');
    const [group, artifact] = older.name.split(':');
    await expect(detail.snippet('gradle-kotlin')).toContainText(
      `implementation("${group}:${artifact}:1.0.0")`,
    );
    await expect(detail.snippet('purl')).toContainText(`pkg:maven/${group}/${artifact}@1.0.0`);
    await expect(detail.snippet('purl')).not.toContainText('2.0.0');
  });

  test('PKG-maven-07 the version detail shows the build-tool snippets and the repository block', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const [group, artifact] = pkg.name.split(':');
    const detail = protocolPages(adminPage, maven, repo.name).detail(pkg);
    await detail.goto();

    // The descriptor lists exactly these eleven, and the panel renders exactly these.
    const rows = SNIPPETS(group, artifact, pkg.version, repo.name);
    expect(rows.map(([slug]) => slug).sort()).toEqual([...maven.levels.detail.snippets].sort());
    for (const [slug, heading, content] of rows) {
      await expect(detail.snippet(slug)).toBeVisible();
      await expect(detail.snippet(slug)).toContainText(heading);
      await expect(detail.snippet(slug)).toContainText(content);
    }
    // The primary block is the dependency the rest of the page is about, and it has its copy button.
    await expect(detail.installText).toContainText(`<version>${pkg.version}</version>`);
    await expect(detail.copyButton).toBeVisible();
    await expect(detail.byId('pkg-detail-meta-group')).toContainText(group);
    await expect(detail.byId('pkg-detail-meta-artifact')).toContainText(
      `${artifact} (${pkg.version})`,
    );
  });

  // RPS-1261 (3): the "Gradle Groovy DSL" block used to be bound to the Groovy Grape snippet, so the
  // `implementation '...'` line the component builds was never shown.
  test('PKG-maven-07 the Gradle Groovy DSL block shows the Gradle dependency, not the Grape one (RPS-1261)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const [group, artifact] = pkg.name.split(':');
    const detail = protocolPages(adminPage, maven, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.snippet('gradle-groovy')).toContainText(
      `implementation '${group}:${artifact}:${pkg.version}'`,
    );
    await expect(detail.snippet('gradle-groovy')).not.toContainText('@Grapes');
  });
});
