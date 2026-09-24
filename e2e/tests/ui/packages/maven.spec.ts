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
 * the group route `/:repo/:group`, and the ten build-tool install snippets of the version detail.
 */
import { createHash } from 'node:crypto';

import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { rawGet } from '../../../src/clients/maven-raw.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios } from '../../../src/ui/package-scenarios.js';
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
 * Opens the directory `dir` of the current listing and waits for its breadcrumb. It clicks until that
 * shows, because the FIRST click on a cold-loaded browser does not descend (RPS-1297, pinned below):
 * the repository's permissions load twice, the second load empties the directory stack while the first
 * listing is still in flight, and the next `go()` then only re-creates the root entry and re-reads it.
 */
async function enterDirectory(page: Page, dir: string): Promise<void> {
  const crumb = page.getByTestId(`maven-browser-path-${dir}/`);
  await expect(async () => {
    if (!(await crumb.isVisible())) {
      await page
        .getByTestId(browserItem(`${dir}/`))
        .getByTestId('row-open')
        .click();
    }
    await expect(crumb).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 20_000 });
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

  // RPS-1297: the first click on a directory of a freshly loaded browser does not open it (the page
  // loads the repository's permissions twice; see `enterDirectory`).
  test.fail(
    'PKG-maven-07 the first click on a directory of a freshly loaded browser opens it (RPS-1297)',
    async ({ adminPage, seeder, seedPackage }) => {
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
    },
  );

  // RPS-1262 (4): the browser page always renders a Settings button, disabled for a USER, where every
  // other package page removes it (`@if (canManage)`).
  test.fail(
    'PKG-maven-05 a USER sees no Settings button on the file browser (RPS-1262)',
    async ({ adminPage, userPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN);
      const pages = protocolPages(userPage, maven, repo.name);
      await adminPage.goto(pages.extraPath('browser'));
      await expect(adminPage.getByTestId('pkg-settings')).toBeEnabled(); // the control
      await userPage.goto(pages.extraPath('browser'));
      await expect(userPage.getByTestId('pkg-toolbar')).toBeVisible();
      await expect(userPage.getByTestId('pkg-settings')).toHaveCount(0);
    },
  );
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

  // Recorded, not asserted as a wish (RPS-1288 (4)): the group list's row is one ARTIFACT, but its
  // Delete says "Delete Group" and removes the whole group, siblings included.
  test('PKG-maven-04 deleting from the group list removes the whole group, not one artifact', async ({
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
    await list.expectRow(first);
    await list.expectRow(sibling);

    await list.deleteRow(first);
    await list.expectNoRow(first);
    await list.expectNoRow(sibling);
    await list.expectRow(other);
    await expect(list.rows()).toHaveCount(1);
  });
});

test.describe('Maven version detail', { tag: '@packages' }, () => {
  /** What each of the ten build-tool blocks is called and what it contains for `group:artifact:version`. */
  const SNIPPETS = (group: string, artifact: string, version: string) =>
    [
      ['pom', 'Pom XML', `<artifactId>${artifact}</artifactId>`],
      ['gradle-groovy', 'Gradle Groovy DSL', 'Gradle Groovy DSL'], // content: see RPS-1261 below
      ['gradle-kotlin', 'Gradle Kotlin DSL', `implementation("${group}:${artifact}:${version}")`],
      ['sbt', 'Scala SBT', `libraryDependencies += "${group}" % "${artifact}" % "${version}"`],
      ['ivy', 'Apache Ivy', `<dependency org="${group}" name="${artifact}" rev="${version}" />`],
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

  test('PKG-maven-07 the version detail shows the ten build-tool snippets', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const [group, artifact] = pkg.name.split(':');
    const detail = protocolPages(adminPage, maven, repo.name).detail(pkg);
    await detail.goto();

    // The descriptor lists exactly these ten, and the panel renders exactly these.
    const rows = SNIPPETS(group, artifact, pkg.version);
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

  // RPS-1261 (3): the "Gradle Groovy DSL" block is bound to the Groovy Grape snippet, so it repeats
  // the Grape block and the `implementation '...'` line the component builds is never shown.
  test.fail(
    'PKG-maven-07 the Gradle Groovy DSL block shows the Gradle dependency, not the Grape one (RPS-1261)',
    async ({ adminPage, seeder, seedPackage }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN);
      const pkg = await seedPackage(repo);
      const [group, artifact] = pkg.name.split(':');
      const detail = protocolPages(adminPage, maven, repo.name).detail(pkg);
      await detail.goto();
      await expect(detail.snippet('gradle-groovy')).toContainText(
        `implementation '${group}:${artifact}:${pkg.version}'`,
      );
      await expect(detail.snippet('gradle-groovy')).not.toContainText('@Grapes');
    },
  );
});
