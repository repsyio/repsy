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
 * RPS-1761, large lists (the OS half; the Cloud half is repsy-mono #1695). 105 repositories and 105 crate versions
 * are seeded through the API; the specs walk every page and assert that the pager windows its buttons (first, last
 * and an ellipsis), that each page holds 10 rows, that the last page holds the 5 left over, and that no item is
 * lost or shown twice. The repository list is narrowed to this test's own repositories with a search on the
 * per-test run id, since the stack is shared with parallel tests.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { publishCrate } from '../../../src/seed/packages/cargo.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { inBatches } from '../../../src/ui/edge-case-support.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { REPO_PAGE_SIZE, RepositoriesPage, rowNames } from '../../../src/ui/pages/repositories.js';

const TOTAL = 105;
const PAGE_SIZE = REPO_PAGE_SIZE;
const PAGES = Math.ceil(TOTAL / PAGE_SIZE);
const CONCURRENCY = 8;

// @cloud-skip: Repsy Cloud has its own copy (repsy-cloud/e2e/ui-cloud/edge-cases/large-lists.spec.ts, #1695).
test.describe('RPS-1761 large lists', { tag: ['@cloud-skip'] }, () => {
  test(`${TOTAL} repositories are paged ${PAGE_SIZE} per page, with every repository on exactly one page`, async ({
    adminPage,
    seeder,
  }) => {
    test.setTimeout(300_000);
    const created: string[] = [];
    await inBatches(TOTAL, CONCURRENCY, async () =>
      created.push((await seeder.createRepo(RepoType.NPM)).name),
    );
    expect(created).toHaveLength(TOTAL);

    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(`e2e-${seeder.runId}-`);
    const rows = repos.rows();
    const pager = repos.pagination;
    await expect(rows).toHaveCount(PAGE_SIZE);
    await expect(pager.root).toBeVisible();
    await expect(pager.page(1)).toBeDisabled(); // the current page
    await expect(pager.page(PAGES)).toBeVisible();
    await expect(pager.root.getByTestId('pagination-ellipsis').first()).toBeVisible();
    await expect(pager.prev).toBeDisabled();

    const seen: string[] = [...(await rowNames(rows))];
    for (let index = 1; index < PAGES; index++) {
      await repos.afterListResponse(() => pager.next.click(), { page: index });
      await expect(pager.page(index + 1)).toBeDisabled();
      await expect(rows).toHaveCount(
        index === PAGES - 1 ? TOTAL - PAGE_SIZE * (PAGES - 1) : PAGE_SIZE,
      );
      seen.push(...(await rowNames(rows)));
    }
    await expect(pager.next).toBeDisabled();
    expect(seen, 'no repository shown twice').toHaveLength(new Set(seen).size);
    expect([...seen].sort()).toEqual([...created].sort());
  });

  test(`${TOTAL} versions of one crate are paged ${PAGE_SIZE} per page, with every version on exactly one page`, async ({
    adminPage,
    seeder,
  }) => {
    test.setTimeout(300_000);
    const repo = await seeder.createRepo(RepoType.CARGO);
    const crate = `e2e_${seeder.runId}_many`;
    const versions = Array.from({ length: TOTAL }, (_, i) => `1.0.${i}`);
    let pkg = await publishCrate(repo.name, crate, versions[0]);
    await inBatches(TOTAL - 1, CONCURRENCY, async (i) => {
      pkg = await publishCrate(repo.name, crate, versions[i + 1]);
    });

    const list = protocolPages(adminPage, DESCRIPTORS.cargo, repo.name).versions(pkg);
    await list.goto();
    const rows = list.desktop.rows();
    const pager = list.pagination;
    await expect(rows).toHaveCount(PAGE_SIZE);
    await expect(pager.page(PAGES)).toBeVisible();
    await expect(pager.prev).toBeDisabled();

    const readVersions = async (): Promise<string[]> =>
      (
        await rows.evaluateAll((nodes) =>
          nodes.map((node) => node.getAttribute('data-testid') ?? ''),
        )
      ).map((id) => id.replace(/^pkg-versions-row-/, ''));

    const seen: string[] = [...(await readVersions())];
    for (let index = 1; index < PAGES; index++) {
      await pager.next.click();
      await expect(pager.page(index + 1)).toBeDisabled();
      await expect(rows).toHaveCount(
        index === PAGES - 1 ? TOTAL - PAGE_SIZE * (PAGES - 1) : PAGE_SIZE,
      );
      seen.push(...(await readVersions()));
    }
    await expect(pager.next).toBeDisabled();
    expect(seen, 'no version shown twice').toHaveLength(new Set(seen).size);
    expect([...seen].sort()).toEqual([...versions].sort());
  });
});
