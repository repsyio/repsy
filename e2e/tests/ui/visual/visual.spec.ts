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
 * Visual regression baseline (RPS-1652, H8): one screenshot of each of a small, stable set of pages, compared
 * with the PNGs committed under `tests/ui/__screenshots__` (README.md "UI suite: visual regression").
 *
 * It exists for what the functional suite cannot see: a moved button, a lost colour, a table that overflows,
 * a component rendered in the wrong place (the two things the gap analysis called "eyeball only": the
 * portal-to-body dropdowns and the global `router-outlet + *` display rule). It is deliberately SMALL:
 * every baseline is a file to review when the design changes on purpose, so a page earns one only when it
 * is a different layout (a form, a list, a detail, a settings page, a list with badges).
 *
 * Runs in its own project (`ui-visual`, `run.sh test --protocol ui-visual`), Chromium only, in the one runner
 * image, at 1280x800, light theme, UTC. The dynamic parts (names with the run id, dates, sizes, URLs with
 * the stack's port) are masks (`src/ui/visual.ts`), and the data is seeded here, so the spec does not depend on what other specs or a
 * previous run left in the stack.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  Severity,
  stubRepoSecuritySummary,
  stubSupportedRepoTypes,
} from '../../../src/ui/security-stubs.js';
import { byTestIds, dynamicRowCells, expectVisual } from '../../../src/ui/visual.js';

const VISUAL = '@visual';

test.describe('Visual regression', { tag: VISUAL }, () => {
  test('VIS-01 the login page', async ({ page }) => {
    await new LoginPage(page).goto();
    await expectVisual(page, 'login');
  });

  test('VIS-02 the repository list, one repository of several types', async ({
    adminPage,
    seeder,
  }) => {
    // Sequential, so that the list (newest first) has the same order in every run.
    for (const type of [RepoType.MAVEN, RepoType.NPM, RepoType.DOCKER]) {
      await seeder.createRepo(type);
    }
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(seeder.runId);
    await expect(repos.rows()).toHaveCount(3);
    await expectVisual(adminPage, 'repository-list', [
      dynamicRowCells(adminPage),
      repos.searchInput,
    ]);
  });

  test('VIS-03 a package list and the detail of one version (npm)', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);

    const list = pages.list();
    await list.goto();
    await list.expectRow(pkg);
    await expectVisual(adminPage, 'package-list', [
      dynamicRowCells(adminPage),
      byTestIds(adminPage, ['breadcrumb']),
    ]);

    const detail = pages.detail(pkg);
    await detail.goto();
    await expect(detail.readme.or(detail.install).first()).toBeVisible();
    await expectVisual(adminPage, 'package-detail', [
      byTestIds(adminPage, [
        'pkg-detail-name',
        'pkg-detail-version',
        'pkg-detail-published',
        'pkg-detail-install',
        'pkg-detail-snippet-npmrc',
        'breadcrumb',
      ]),
      dynamicRowCells(adminPage),
    ]);
  });

  test('VIS-04 the repository settings', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expectVisual(adminPage, 'repository-settings', [
      byTestIds(adminPage, ['breadcrumb', 'settings-repo-name', 'repo-info', 'repo-url']),
      dynamicRowCells(adminPage),
    ]);
  });

  test(
    'VIS-05 the security badges of the repository list',
    { tag: '@mocked' },
    async ({ adminPage, seeder }) => {
      // The scanner is off on the e2e stack, so the badges come from stubs typed with the generated models.
      const high = await seeder.createRepo(RepoType.NPM);
      const clean = await seeder.createRepo(RepoType.NPM);
      const scanning = await seeder.createRepo(RepoType.NPM);
      const failed = await seeder.createRepo(RepoType.NPM);
      await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
      await stubRepoSecuritySummary(adminPage, {
        [high.name]: { severity: Severity.HIGH, scanned: true },
        [clean.name]: { severity: null, scanned: true },
        [scanning.name]: { scanned: false, unscannedInProgressCount: 1 },
        [failed.name]: { scanned: false, unscannedFailedCount: 1 },
      });
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      await repos.search(seeder.runId);
      await expect(repos.rows()).toHaveCount(4);
      await expect(byTestIds(adminPage, ['row-security'])).toHaveCount(8);
      await expectVisual(adminPage, 'security-badges', [
        dynamicRowCells(adminPage),
        repos.searchInput,
      ]);
    },
  );
});
