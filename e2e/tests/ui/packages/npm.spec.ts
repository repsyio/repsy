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
 * npm package pages (RPS-1256): the shared scenarios PKG-npm-01..06 (`registerPackageScenarios`) and
 * PKG-npm-07, the npm-only behaviour: scoped packages under `/:repo/:scope`, unscoped ones under `~`,
 * the list search that takes a scope with or without its `@`, and the README and metadata of the
 * version detail.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { buildPublishDocument, buildTarball, rawPublish } from '../../../src/clients/npm-raw.js';
import type { SeededPackage } from '../../../src/seed/packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { rowKeys } from '../../../src/ui/package-scenarios.js';

const npm = DESCRIPTORS.npm;

// RPS-1262 (1): the mobile cards of the scope page and of the version list gate Delete on `canWrite`
// (a USER may write in this edition), where the desktop rows and the other lists use `canManage`.
registerPackageScenarios(npm, {
  knownFailures: {
    '05-mobile-sublist': 'RPS-1262: mobile scope-list cards gate Delete on canWrite',
    '05-mobile-versions': 'RPS-1262: mobile version-list cards gate Delete on canWrite',
  },
});

/** What a package of a real `package.json` carries and the seeder's minimal publish does not. */
interface RichPackage {
  readme?: string;
  keywords?: string[];
  bugsUrl?: string;
}

/** Publishes `name`@1.0.0 with a README and the metadata fields the detail page shows. */
async function publishRich(
  repoName: string,
  name: string,
  rich: RichPackage,
): Promise<SeededPackage> {
  const version = '1.0.0';
  const document = buildPublishDocument({
    repoName,
    packageName: name,
    version,
    tarballBytes: buildTarball({ packageName: name, version }),
  });
  const manifest = (document.versions as Record<string, Record<string, unknown>>)[version];
  Object.assign(manifest, {
    homepage: 'https://example.com/home',
    license: 'MIT',
    repository: { type: 'git', url: 'https://example.com/repo.git' },
    ...(rich.readme === undefined ? {} : { readme: rich.readme }),
    ...(rich.keywords ? { keywords: rich.keywords } : {}),
    ...(rich.bugsUrl ? { bugs: { url: rich.bugsUrl } } : {}),
  });
  const res = await rawPublish(repoName, adminCredential(), name, document);
  expect(res.status, `npm publish of ${name}: ${res.body.toString('utf8').slice(0, 200)}`).toBe(
    200,
  );
  return { protocol: 'npm', repoName, name, version, extra: {} };
}

test.describe('npm scopes', { tag: '@packages' }, () => {
  test('PKG-npm-07 a scoped package lives under /:repo/:scope (no @ in the route), an unscoped one under ~', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const scoped = await seedPackage(repo, { index: 1 });
    const unscoped = await seedPackage(repo, { index: 2, scoped: false });
    const pages = protocolPages(adminPage, npm, repo.name);
    const scope = scoped.name.slice(1, scoped.name.indexOf('/'));

    const list = pages.list();
    await list.goto();
    await list.expectRow(scoped);
    await list.expectRow(unscoped);

    // A scoped row shows its scope as a link (with the @); an unscoped row shows a plain "~".
    await expect(list.inRow(scoped, 'row-scope-link')).toContainText(`@${scope}`);
    await expect(list.inRow(unscoped, 'row-scope-link')).toHaveCount(0);
    await expect(list.inRow(unscoped, 'row-scope')).toHaveText('~');

    // The scope link opens the scope page: the route has the scope without its @, and lists its packages.
    const scopePage = await list.openLink(scoped, 'sublist');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${scope}$`));
    await scopePage.expectLoaded();
    await scopePage.expectRow(scoped);
    await scopePage.expectNoRow(unscoped);
    await expect(scopePage.searchInput).toHaveAttribute('placeholder', 'package');

    // A scope page's row opens its versions (not the detail), and the version list has the dist-tags.
    const versions = (await scopePage.openRow(scoped)) as ReturnType<typeof pages.versions>;
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${scope}/[^/]+$`));
    await versions.expectLoaded();
    await versions.expectRow(scoped);
    await expect(adminPage.getByTestId('pkg-dist-tag-latest')).toBeVisible();

    // The unscoped package is under ~, on its own page and its own versions and detail routes.
    const tilde = pages.sublist(unscoped);
    expect(tilde.path()).toBe(`/${repo.name}/~`);
    await tilde.goto();
    await tilde.expectRow(unscoped);
    await tilde.expectNoRow(scoped);
    const tildeVersions = pages.versions(unscoped);
    await tildeVersions.goto();
    await tildeVersions.expectRow(unscoped);
    const tildeDetail = pages.detail(unscoped);
    await tildeDetail.goto();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/~/[^/]+/${unscoped.version}$`));
    await expect(tildeDetail.installText).toContainText(`npm install ${unscoped.name}`);
  });

  test('PKG-npm-07 the list search takes a scope with or without its @ and does not match the package name', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const id = seeder.runId;
    const inA = await seedPackage(repo, { name: `@e2e-${id}-a/widget` });
    const inB = await seedPackage(repo, { name: `@e2e-${id}-b/widget` });
    const list = protocolPages(adminPage, npm, repo.name).list();
    await list.goto();
    await expect(list.searchInput).toHaveAttribute('placeholder', '@scope');
    await expect(list.rows()).toHaveCount(2);

    // With the @ (as the placeholder shows it) and without: the same, one scope.
    for (const term of [`@e2e-${id}-a`, `e2e-${id}-a`]) {
      await list.search(term);
      await list.expectRow(inA);
      await expect.poll(() => rowKeys(list)).toEqual([inA.name]);
    }
    await list.search(`@e2e-${id}-b`);
    await expect.poll(() => rowKeys(list)).toEqual([inB.name]);

    // A shared prefix finds both scopes; the package name alone finds none (recorded, RPS-1288 (2):
    // the search matches the scope only, not the whole `@scope/name`).
    await list.search(`@e2e-${id}`);
    await expect
      .poll(async () => (await rowKeys(list)).sort())
      .toEqual([inA.name, inB.name].sort());
    await list.search('widget');
    await expect(list.emptyList.root).toBeVisible();
    await list.search(inA.name);
    await expect(list.emptyList.root).toBeVisible();

    await list.search('');
    await expect(list.rows()).toHaveCount(2);
  });
});

test.describe('npm version detail', { tag: '@packages' }, () => {
  test('PKG-npm-07 the README renders as markdown, the metadata shows homepage, licence and repository', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await publishRich(repo.name, `@e2e-${seeder.runId}/rich`, {
      readme: '# Rich Title\n\nSome **bold** text and a [link](https://example.com).\n',
    });
    const detail = protocolPages(adminPage, npm, repo.name).detail(pkg);
    await detail.goto();

    // Rendered, not the raw source: a heading, a strong, a link that opens in a new tab safely.
    await expect(detail.readme).toBeVisible();
    await expect(
      detail.readme.getByRole('heading', { name: 'Rich Title', level: 1 }),
    ).toBeVisible();
    await expect(detail.readme.locator('strong')).toHaveText('bold');
    const link = detail.readme.getByRole('link', { name: 'link' });
    await expect(link).toHaveAttribute('href', 'https://example.com');
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', /noopener/);
    await expect(detail.readme).not.toContainText('# Rich Title');
    await expect(detail.readme).not.toContainText('**bold**');

    const metadata = detail.byId('pkg-detail-metadata');
    await expect(metadata.getByText('Homepage URL:').locator('..')).toContainText(
      'https://example.com/home',
    );
    await expect(metadata.getByText('Licence:').locator('..')).toContainText('MIT');
    await expect(metadata.getByText('Repository URL:').locator('..')).toContainText(
      'https://example.com/repo.git',
    );
  });

  test('PKG-npm-07 a package without a README has no README section', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, npm, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.root).toBeVisible();
    await expect(detail.readme).toHaveCount(0);
    await expect(
      detail.byId('pkg-detail-metadata').getByText('No homepage URL found!'),
    ).toBeVisible();
  });

  // RPS-1261 (4): the "Bugs URL" line prints the package NAME, and "Keywords" is the literal
  // "No keywords found!" whatever the package.json says.
  test.fail(
    'PKG-npm-07 the detail shows the Bugs URL of the package.json (RPS-1261)',
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      const pkg = await publishRich(repo.name, `@e2e-${seeder.runId}/bugs`, {
        bugsUrl: 'https://example.com/bugs',
      });
      const detail = protocolPages(adminPage, npm, repo.name).detail(pkg);
      await detail.goto();
      await expect(
        detail.byId('pkg-detail-metadata').getByText('Bugs URL:').locator('..'),
      ).toContainText('https://example.com/bugs');
    },
  );

  test.fail(
    'PKG-npm-07 the detail shows the keywords of the package.json (RPS-1261)',
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      const pkg = await publishRich(repo.name, `@e2e-${seeder.runId}/keys`, {
        keywords: ['alpha', 'beta'],
      });
      const detail = protocolPages(adminPage, npm, repo.name).detail(pkg);
      await detail.goto();
      const keywords = detail.byId('pkg-detail-metadata').getByText('Keywords:').locator('..');
      await expect(keywords).toContainText('alpha');
      await expect(keywords).toContainText('beta');
    },
  );
});
