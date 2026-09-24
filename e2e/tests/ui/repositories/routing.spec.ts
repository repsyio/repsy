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
 * REPO-10: routing. `/:repoName` is the repository page of the repository with that name, and
 * anything that is neither a fixed route nor a repository is the 404 page.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';

test.describe('Repository routing', () => {
  test(
    'REPO-10: an unknown path is the 404 page, a repository name opens its page',
    { tag: ['@smoke'] },
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN);

      await adminPage.goto(`/no-such-path-${seeder.runId}`);
      await expect(adminPage.getByTestId('not-found')).toBeVisible();
      await expect(adminPage.getByTestId('not-found-code')).toHaveText('404');
      await expect(adminPage).toHaveURL('/not-found');
      await expect(adminPage).toHaveTitle('repsy | Not Found');

      // "Go to Home" leaves the 404 page for the dashboard.
      await adminPage.getByTestId('not-found-home').click();
      await expect(adminPage).toHaveURL('/');
      await expect(adminPage.getByTestId('welcome-card')).toBeVisible();

      await adminPage.goto(`/${repo.name}`);
      await expect(adminPage.getByTestId('breadcrumb-current')).toContainText(repo.name);
      await expect(adminPage).toHaveTitle('repsy | Maven Groups');
      await expect(adminPage).toHaveURL(`/${repo.name}`);
      await expect(adminPage.getByTestId('pkg-toolbar')).toBeVisible();
      await expect(adminPage.getByTestId('not-found')).toHaveCount(0);
    },
  );

  test("REPO-10: a repository of another type opens that type's page", async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);

    await adminPage.goto(`/${repo.name}`);

    await expect(adminPage.getByTestId('breadcrumb-current')).toContainText(repo.name);
    await expect(adminPage).toHaveTitle(/^repsy \| Npm|^repsy \| NPM/i);
    await expect(adminPage.getByTestId('not-found')).toHaveCount(0);
  });
});
