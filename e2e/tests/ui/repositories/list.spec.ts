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
 * REPO-04, REPO-05, REPO-08, REPO-09: the repository list. It filters and pages CLIENT-SIDE over
 * every repository of the stack (other tests' repositories and the nine defaults included), so a
 * test narrows the list with a search string that only its own repositories contain
 * (`e2e-<runid>-`) before it looks at rows, and asserts anything about the unfiltered list by size.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { REPO_PAGE_SIZE, RepositoriesPage, rowNames } from '../../../src/ui/pages/repositories.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';

test.describe('Repository list', () => {
  test('REPO-04: search filters by substring, the type selector by type, refresh resets', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const npm = uiRepoType(RepoType.NPM);
    const maven = await seeder.createRepo(RepoType.MAVEN);
    const npmRepo = await seeder.createRepo(RepoType.NPM);
    const pypi = await seeder.createRepo(RepoType.PYPI);
    const mine = `e2e-${seeder.runId}-`;
    await repos.goto();

    // Substring search, case-insensitive: all three, then just the npm one by its type word.
    await repos.search(mine);
    await expect(repos.rows()).toHaveCount(3);
    for (const repo of [maven, npmRepo, pypi]) {
      await expect(repos.row(repo.name)).toBeVisible();
    }
    await repos.search(`${mine}NPM`.toUpperCase());
    await expect(repos.rows()).toHaveCount(1);
    await expect(repos.row(npmRepo.name)).toBeVisible();

    // The type selector fetches only that type; the search box is not part of that filter.
    await repos.selectType(npm);
    await expect(repos.typeFilterText()).toHaveText(npm.label);
    await repos.search(mine);
    await expect(repos.row(npmRepo.name)).toBeVisible();
    await expect(repos.row(maven.name)).toHaveCount(0);
    await expect(repos.row(pypi.name)).toHaveCount(0);

    // Back to All: the other types come back.
    await repos.selectType('all');
    await repos.search(mine);
    await expect(repos.rows()).toHaveCount(3);

    // Refresh reloads the list and drops the search filter: a full page of ten rows comes back
    // (nine defaults plus this test's three repositories, whatever else is on the stack).
    await repos.search(`${mine}npm`);
    await expect(repos.rows()).toHaveCount(1);
    await repos.refresh();
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
  });

  test('REPO-04: refresh also clears the search box', async ({ adminPage, seeder }) => {
    test.fail(
      true,
      'The search box keeps its text after refresh (and after a type change) while the list is ' +
        'unfiltered again: RPS-1283',
    );
    const repos = new RepositoriesPage(adminPage);
    await seeder.createRepo(RepoType.MAVEN);
    await repos.goto();
    await repos.search(`e2e-${seeder.runId}-`);
    await expect(repos.rows()).toHaveCount(1);

    await repos.refresh();

    // Whichever way it is fixed, list and box must agree: the box is empty and the list unfiltered,
    // or the filter is still applied.
    const boxIsEmpty = (await repos.searchInput.inputValue()) === '';
    const listIsFiltered = (await repos.rows().count()) === 1;
    expect(boxIsEmpty || listIsFiltered, 'the box shows a filter the list no longer applies').toBe(
      true,
    );
  });

  test('REPO-05: eleven repositories paginate by ten, with prev/next disabled at the ends', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const names: string[] = [];
    for (let count = 0; count < REPO_PAGE_SIZE + 1; count++) {
      names.push((await seeder.createRepo(RepoType.MAVEN)).name);
    }
    await repos.goto();
    await repos.search(`e2e-${seeder.runId}-`);

    // Page 1: ten rows, no previous page.
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
    await expect(repos.pagination.root).toBeVisible();
    await expect(repos.pagination.prev).toBeDisabled();
    await expect(repos.pagination.next).toBeEnabled();
    await expect(repos.pagination.page(1)).toBeDisabled();
    await expect(repos.pagination.page(2)).toBeEnabled();
    const firstPage = await rowNames(repos.rows());

    // Page 2: the eleventh, no next page.
    await repos.pagination.next.click();
    await expect(repos.rows()).toHaveCount(1);
    await expect(repos.pagination.next).toBeDisabled();
    await expect(repos.pagination.prev).toBeEnabled();
    await expect(repos.pagination.page(2)).toBeDisabled();
    const secondPage = await rowNames(repos.rows());
    expect([...firstPage, ...secondPage].sort()).toEqual([...names].sort());

    // A page number button goes to that page, "previous" goes back to page 1.
    await repos.pagination.root.getByTestId('pagination-page-1').click();
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
    await repos.pagination.root.getByTestId('pagination-page-2').click();
    await expect(repos.rows()).toHaveCount(1);
    await repos.pagination.prev.click();
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
    await expect(repos.pagination.prev).toBeDisabled();
  });

  test('REPO-05: a new search starts on page 1 again', async ({ adminPage, seeder }) => {
    test.fail(
      true,
      'The list keeps the old page index when the search changes, so after narrowing and widening ' +
        'the search it shows page 1 with page 2 marked as current: RPS-1283',
    );
    const repos = new RepositoriesPage(adminPage);
    for (let count = 0; count < REPO_PAGE_SIZE + 1; count++) {
      await seeder.createRepo(RepoType.MAVEN);
    }
    const mine = `e2e-${seeder.runId}-`;
    await repos.goto();
    await repos.search(mine);
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
    await repos.pagination.next.click();
    await expect(repos.rows()).toHaveCount(1);

    // Three of the eleven end in "maven-1" (1, 10, 11): a single page. Then back to all eleven.
    await repos.search(`${mine}maven-1`);
    await expect(repos.rows()).toHaveCount(3);
    await repos.search(mine);

    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);
    await expect(repos.pagination.page(1)).toBeDisabled();
    await expect(repos.pagination.page(2)).toBeEnabled();
    await expect(repos.pagination.next).toBeEnabled();
  });

  test('REPO-08: a search that matches nothing shows the empty state', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    // The list is not empty to begin with (the defaults are there), so the empty state is a result
    // of the search, not of the load.
    await expect(repos.rows().first()).toBeVisible();
    await expect(repos.emptyList.root).toHaveCount(0);

    await repos.search(`no-such-repository-${seeder.runId}`);

    await expect(repos.emptyList.root).toBeVisible();
    await expect(repos.rows()).toHaveCount(0);
    // Clearing the search brings the rows back.
    await repos.search('');
    await expect(repos.rows().first()).toBeVisible();
    await expect(repos.emptyList.root).toHaveCount(0);
  });

  test('REPO-09: a USER sees the rows but no Create button and no row menu', async ({
    userPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(userPage);
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await repos.goto();
    await repos.search(repo.name);

    await expect(repos.row(repo.name)).toBeVisible();
    await expect(repos.visibility(repo.name)).toHaveText('Private');
    await expect(repos.createButton).toHaveCount(0);
    await expect(repos.rowMenus()).toHaveCount(0);
    await expect(repos.list.inRow(repo.name, 'row-delete')).toHaveCount(0);
    // The toolbar itself works for a USER.
    await expect(repos.refreshButton).toBeVisible();
    await expect(repos.typeFilterText()).toHaveText('All');
  });
});
