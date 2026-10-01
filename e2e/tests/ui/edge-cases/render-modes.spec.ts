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
 * RPS-1761, rendering modes the default 1440x900 run never sees: 200% zoom, forced colours and a slow API.
 *
 *  - Zoom 200% is a viewport half the size in CSS pixels (a browser zoomed to 200% reports 720x450), so the
 *    panel's mobile/tablet breakpoints are what 200% really exercises; the check reuses `expectNoHorizontalOverflow`
 *    (NAV-10) on the repository list and with the create modal open.
 *  - Forced colours (`forced-colors: active`, Windows High Contrast) replaces the page's colours; the create
 *    flow must stay operable and the controls visible.
 *  - A slow API (`GET /api/repos` held back 2 s) must show the spinner, then the rows, never an empty list
 *    or an error state in between.
 *
 * Repsy Cloud has no copy of these yet (follow-up on the Cloud side); they are tagged @cloud-skip so the Cloud
 * suite does not run an unverified one.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { expectNoHorizontalOverflow, expectReachable } from '../../../src/ui/layout.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';

const LATENCY_MS = 2000;

// @cloud-skip: no Cloud counterpart yet.
test.describe('RPS-1761 rendering modes', { tag: ['@cloud-skip'] }, () => {
  test('200% zoom (720x450): the repository list and the create modal do not overflow horizontally', async ({
    adminPage,
    seeder,
  }) => {
    await seeder.createRepo(RepoType.NPM);
    await adminPage.setViewportSize({ width: 720, height: 450 });
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();

    await expectNoHorizontalOverflow(adminPage, 'repository list at 200%');

    const modal = await repos.openCreateModal();
    await expectNoHorizontalOverflow(adminPage, 'create modal at 200%');
    await expectReachable(adminPage, modal.submitButton, 'Create at 200%');
  });

  test('forced colours: a repository can be created through the modal', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    await adminPage.emulateMedia({ forcedColors: 'active' });
    expect(await adminPage.evaluate(() => matchMedia('(forced-colors: active)').matches)).toBe(
      true,
    );
    const npm = uiRepoType(RepoType.NPM);
    const name = seeder.reserveRepoName(npm.type);
    seeder.adoptRepo(name);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();

    const modal = await repos.openCreateModal();
    for (const control of [
      modal.typeToggle,
      modal.nameInput,
      modal.descriptionInput,
      modal.submitButton,
    ]) {
      await expect(control).toBeVisible();
    }
    await modal.create({ type: npm, name, description: 'forced colours' });
    await repos.toasts.expectSuccess('Repository created successfully');
    await modal.expectClosed();
    await repos.search(name);
    await expect(repos.row(name)).toBeVisible();
    const stored = (await panelApi.listAllRepos({ type: npm.type, q: name })).find(
      (repo) => repo.name === name,
    );
    expect(stored?.description).toBe('forced colours');
  });

  test(`a ${LATENCY_MS} ms repository list shows the spinner first, then the rows, and never an error`, async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await adminPage.route(
      (url) => url.pathname === '/api/repos',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, LATENCY_MS));
        await route.continue();
      },
    );
    const repos = new RepositoriesPage(adminPage);
    await adminPage.goto('/repositories');

    await expect(repos.spinner.root).toBeVisible();
    await expect(repos.error).toBeHidden();
    await expect(repos.rows().first()).toBeVisible({ timeout: LATENCY_MS * 3 });
    await expect(repos.spinner.root).toBeHidden();
    await expect(repos.error).toBeHidden();
    await repos.search(repo.name);
    await expect(repos.row(repo.name)).toBeVisible();
  });
});
