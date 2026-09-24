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
 * REPO-06 and REPO-07: deleting a repository from the list through the row's dropdown and the
 * "Delete Repository" danger modal.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';

test.describe('Delete repository', () => {
  test(
    'REPO-06: Delete in the row menu, confirmed, removes the repository',
    { tag: ['@smoke'] },
    async ({ adminPage, seeder, panelApi }) => {
      const repos = new RepositoriesPage(adminPage);
      const doomed = await seeder.createRepo(RepoType.MAVEN);
      const kept = await seeder.createRepo(RepoType.MAVEN);
      await repos.goto();
      await repos.search(`e2e-${seeder.runId}-`);
      await expect(repos.row(doomed.name)).toBeVisible();

      await repos.startDelete(doomed.name);
      await expect(repos.dangerModal.title).toHaveText('Delete Repository');
      await repos.confirmDelete();

      await repos.toasts.expectSuccess('Repository deleted successfully');
      await repos.dangerModal.expectClosed();
      // The deletion reloaded the list (the search box keeps its text, but the list is unfiltered
      // again), so narrow it once more.
      await repos.search(`e2e-${seeder.runId}-`);
      await expect(repos.row(kept.name)).toBeVisible();
      await expect(repos.row(doomed.name)).toHaveCount(0);
      const remaining = (await panelApi.listAllRepos({ type: RepoType.MAVEN })).map(
        (repo) => repo.name,
      );
      expect(remaining).not.toContain(doomed.name);
      expect(remaining).toContain(kept.name);
    },
  );

  test('REPO-07: Cancel in the danger modal keeps the repository', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await repos.goto();
    await repos.search(repo.name);

    await repos.startDelete(repo.name);
    await repos.dangerModal.cancel();

    await repos.dangerModal.expectClosed();
    await expect(repos.toasts.toast()).toHaveCount(0);
    await expect(repos.row(repo.name)).toBeVisible();
    expect((await panelApi.listAllRepos({ type: RepoType.MAVEN })).map((r) => r.name)).toContain(
      repo.name,
    );

    // The modal's close (x) is a cancel as well.
    await repos.startDelete(repo.name);
    await repos.dangerModal.close();
    await repos.dangerModal.expectClosed();
    await expect(repos.row(repo.name)).toBeVisible();
    expect((await panelApi.listAllRepos({ type: RepoType.MAVEN })).map((r) => r.name)).toContain(
      repo.name,
    );
  });
});
