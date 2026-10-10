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
 * UNT-01..UNT-04 (RPS-1623): what the panel does with content a package publisher controls. A package is
 * published with a README, a description and homepage/project/repository fields carrying every payload of
 * `src/ui/hostile-packages.ts` (`<script>`, event handlers, `javascript:`/`data:`/`vbscript:` links, an
 * external tracking image, relative links, an iframe, a style attribute, an HTML comment), and its detail page
 * is opened as the admin. The panel must run none of it (the `window.__pwned` canary stays unset, and the
 * page-error guard of `fixtures.ts` sees no error), load nothing from the publisher's host, and keep every
 * link either inert or safe to follow.
 *
 * Links are asserted by what their `href` may be (`hostile-checks.ts`), so the `unsafe:` rewrite is pinned as
 * a positive match and no spec here greps for a bad scheme.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import {
  expectInertReadme,
  expectInertSection,
  expectNothingRan,
  watchRequestsTo,
} from '../../../src/ui/hostile-checks.js';
import {
  HOSTILE_TEXT,
  HOSTILE_URLS,
  seedHostileCargo,
  seedHostileMaven,
  seedHostileNpm,
  seedHostileNuGet,
  seedHostilePypi,
  seedHostileRuby,
} from '../../../src/ui/hostile-packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const PACKAGES = '@packages';

const READMES = [
  { protocol: 'npm', type: RepoType.NPM, seed: seedHostileNpm },
  { protocol: 'pypi', type: RepoType.PYPI, seed: seedHostilePypi },
  { protocol: 'cargo', type: RepoType.CARGO, seed: seedHostileCargo },
  { protocol: 'nuget', type: RepoType.NUGET, seed: seedHostileNuGet },
] as const;

test.describe('Untrusted README', { tag: PACKAGES }, () => {
  for (const { protocol, type, seed } of READMES) {
    test(`UNT-01 ${protocol}: a hostile README renders inert`, async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(type);
      const pkg = await seed(repo.name, seeder.runId);
      const requests = watchRequestsTo(adminPage);
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(pkg);

      await detail.goto();

      await expect(detail.readme).toBeVisible();
      await expectInertReadme(detail.readme);
      await expectNothingRan(adminPage, requests);
    });
  }
});

test.describe('Untrusted metadata', { tag: PACKAGES }, () => {
  test('UNT-02 npm: the homepage, repository and description are text, never a link or markup', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedHostileNpm(repo.name, seeder.runId);
    const requests = watchRequestsTo(adminPage);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);

    await detail.goto();

    const metadata = detail.byId('pkg-detail-metadata');
    await expect(metadata).toContainText(HOSTILE_URLS.javascript);
    await expect(metadata).toContainText(HOSTILE_URLS.data);
    await expectInertSection(detail.root);
    await expectNothingRan(adminPage, requests);
  });

  test('UNT-02 nuget: the project, repository and licence URLs and the title are text, never a link or markup', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET);
    const pkg = await seedHostileNuGet(repo.name, seeder.runId);
    const requests = watchRequestsTo(adminPage);
    const detail = protocolPages(adminPage, DESCRIPTORS.nuget, repo.name).detail(pkg);

    await detail.goto();

    await expect(detail.root).toContainText(HOSTILE_URLS.javascript);
    await expectInertSection(detail.root);
    await expectNothingRan(adminPage, requests);
  });

  test('UNT-02 maven: the POM name, description and URLs are text, never a link or markup', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedHostileMaven(repo.name, seeder.runId);
    const requests = watchRequestsTo(adminPage);
    const detail = protocolPages(adminPage, DESCRIPTORS.maven, repo.name).detail(pkg);

    await detail.goto();

    await expect(detail.root).toContainText(HOSTILE_URLS.javascript);
    await expectInertSection(detail.root);
    await expectNothingRan(adminPage, requests);
  });

  test('UNT-02 cargo: the description is text, never markup', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const pkg = await seedHostileCargo(repo.name, seeder.runId);
    const requests = watchRequestsTo(adminPage);
    const detail = protocolPages(adminPage, DESCRIPTORS.cargo, repo.name).detail(pkg);

    await detail.goto();

    await expect(detail.root).toBeVisible();
    await expectInertSection(detail.root);
    await expectNothingRan(adminPage, requests);
  });

  test('UNT-02 ruby: the description is text, never markup', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY);
    const pkg = await seedHostileRuby(repo.name, seeder.runId);
    const requests = watchRequestsTo(adminPage);
    const detail = protocolPages(adminPage, DESCRIPTORS.ruby, repo.name).detail(pkg);

    await detail.goto();

    await expect(detail.root).toContainText(HOSTILE_TEXT);
    await expectInertSection(detail.root);
    await expectNothingRan(adminPage, requests);
  });
});

// The two protocols that turn a publisher-written URL into a link: PyPI's home page (RPS-1623: it had neither
// target nor rel, and Angular's sanitiser lets data: and vbscript: through) and Ruby's homepage.
const HOMEPAGE_LINKS = [
  {
    protocol: 'pypi',
    type: RepoType.PYPI,
    seed: (repoName: string, runId: string, index: number, homepage: string) =>
      seedHostilePypi(repoName, runId, { index, homePage: homepage }),
  },
  {
    protocol: 'ruby',
    type: RepoType.RUBY,
    seed: (repoName: string, runId: string, index: number, homepage: string) =>
      seedHostileRuby(repoName, runId, { index, homepage }),
  },
] as const;

test.describe('Homepage links', { tag: PACKAGES }, () => {
  for (const { protocol, type, seed } of HOMEPAGE_LINKS) {
    test(`UNT-03 ${protocol}: an http(s) homepage is a link that opens in a new tab without leaking the panel`, async ({
      adminPage,
      seeder,
    }) => {
      const repo = await seeder.createRepo(type);
      const pkg = await seed(repo.name, seeder.runId, 1, 'https://example.com/home');
      const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(pkg);

      await detail.goto();

      const link = detail.byId('pkg-detail-homepage');
      await expect(link).toHaveAttribute('href', 'https://example.com/home');
      await expect(link).toHaveAttribute('target', '_blank');
      await expect(link).toHaveAttribute('rel', /noopener/);
      await expect(link).toHaveAttribute('rel', /noreferrer/);
    });

    for (const [scheme, homepage] of Object.entries(HOSTILE_URLS)) {
      test(`UNT-03 ${protocol}: a ${scheme}: homepage is not a link`, async ({
        adminPage,
        seeder,
      }) => {
        const repo = await seeder.createRepo(type);
        const pkg = await seed(repo.name, seeder.runId, 1, homepage);
        const requests = watchRequestsTo(adminPage);
        const detail = protocolPages(adminPage, DESCRIPTORS[protocol], repo.name).detail(pkg);

        await detail.goto();

        // The anchor stays (the page keeps its layout) but has no href to follow.
        const link = detail.byId('pkg-detail-homepage');
        await expect(link).toBeVisible();
        await expect(link).not.toHaveAttribute('href', /./);
        await expectInertSection(detail.root);
        await expectNothingRan(adminPage, requests);
      });
    }
  }
});
