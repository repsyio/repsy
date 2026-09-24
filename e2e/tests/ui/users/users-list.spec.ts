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
 * USR-06: search, refresh and pagination of the users list with more than one page of users.
 *
 * The list is server-paged (10) and sorted newest first, and other tests create users on the same
 * stack, so nothing here assumes the unfiltered list: every view is a search for this test's run id,
 * which is a substring of exactly the users this test seeded.
 */
import { expect, test } from '../../../src/ui/users-fixtures.js';

const PAGE_SIZE = 10;

test.describe('USR-06 users list', () => {
  // 11 users: one more than a page.
  test('search, pagination and refresh with more than one page of users', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const usernames: string[] = [];
    for (let i = 0; i < PAGE_SIZE + 1; i += 1) {
      usernames.push((await seeder.createUser()).username);
    }
    // Newest first: the last seeded user leads page 1, the first seeded one is alone on page 2.
    const newest = usernames[usernames.length - 1];
    const oldest = usernames[0];

    await usersPage.goto();

    await usersPage.search(seeder.runId);
    await expect(usersPage.rows()).toHaveCount(PAGE_SIZE);
    await expect(usersPage.pagination.root).toBeVisible();
    await expect(usersPage.pagination.page(1)).toBeDisabled();
    await expect(usersPage.pagination.page(2)).toBeEnabled();
    await expect(usersPage.pagination.page(3)).toHaveCount(0);
    await expect(usersPage.pagination.prev).toBeDisabled();
    await expect(usersPage.row(newest)).toBeVisible();
    await expect(usersPage.row(oldest)).toHaveCount(0);

    // Page 2 holds the one that did not fit.
    await usersPage.pagination.next.click();
    await expect(usersPage.pagination.page(2)).toBeDisabled();
    await expect(usersPage.rows()).toHaveCount(1);
    await expect(usersPage.row(oldest)).toBeVisible();
    await expect(usersPage.pagination.next).toBeDisabled();

    // ... and back.
    await usersPage.pagination.prev.click();
    await expect(usersPage.pagination.page(1)).toBeDisabled();
    await expect(usersPage.rows()).toHaveCount(PAGE_SIZE);
    await expect(usersPage.row(newest)).toBeVisible();

    // A narrower search goes back to page 1 and drops the pagination (a single result).
    await usersPage.goToPage(2);
    await usersPage.search(usernames[4]);
    await expect(usersPage.rows()).toHaveCount(1);
    await expect(usersPage.row(usernames[4])).toBeVisible();
    await expect(usersPage.pagination.root).toBeHidden();

    // The match is case-insensitive.
    await usersPage.search(usernames[4].toUpperCase());
    await expect(usersPage.row(usernames[4])).toBeVisible();

    // No match: the empty state, no rows.
    await usersPage.search(`${seeder.runId}-nobody`);
    await expect(usersPage.empty).toBeVisible();
    await expect(usersPage.rows()).toHaveCount(0);
    // RPS-1267: a search without a match is not an invitation to create the first user. The shared
    // hippo (`empty-list`) carries the message. An empty instance cannot be reached here: the admin
    // that signs in always exists, so "No users found." only shows for an empty answer without a search.
    await expect(usersPage.empty.getByTestId('empty-list')).toBeVisible();
    await expect(usersPage.emptyMessage).toHaveText(`No user matches “${seeder.runId}-nobody”.`);
    await expect(usersPage.empty).not.toContainText('create your first user');

    // Refresh drops the search and returns to page 1 of the whole list: a user created after the
    // page was loaded, the newest of all, is on it, and the list is a full page again.
    await usersPage.search(seeder.runId);
    await usersPage.goToPage(2);
    const late = await seeder.createUser();
    await expect(usersPage.row(late.username)).toHaveCount(0);
    await usersPage.refresh();
    await expect(usersPage.searchInput).toHaveValue('');
    await expect(usersPage.pagination.page(1)).toBeDisabled();
    await expect(usersPage.rows()).toHaveCount(PAGE_SIZE);
    await expect(usersPage.row(late.username)).toBeVisible();
    expect((await panelApi.listUsers({ size: 100 })).length).toBeGreaterThan(PAGE_SIZE);
  });

  test('a slow answer of an earlier search never overwrites the newer search (RPS-1319)', async ({
    adminPage,
    usersPage,
    seeder,
  }) => {
    const mine = await seeder.createUser();
    const held = `${seeder.runId}-held`;
    await usersPage.goto();

    // Hold the answer of the search for `held` (it matches nothing) until the newer search is done.
    let releaseHeld!: () => void;
    const heldAnswer = new Promise<void>((resolve) => {
      releaseHeld = resolve;
    });
    // Resolves once the held answer has been handed to the page (or the page had cancelled the request).
    let delivered!: () => void;
    const heldDelivered = new Promise<void>((resolve) => {
      delivered = resolve;
    });
    const heldRequested = adminPage.waitForRequest((request) => {
      const url = new URL(request.url());
      return url.pathname === '/api/users' && url.searchParams.get('q') === held;
    });
    await adminPage.route(/\/api\/users\?/, async (route) => {
      if (new URL(route.request().url()).searchParams.get('q') === held) {
        await heldAnswer;
        // The panel cancels the held request when the newer search starts: handing it the answer fails then.
        const answer = await route.fetch().catch(() => undefined);
        if (answer) {
          await route.fulfill({ response: answer }).catch(() => undefined);
        }
        delivered();
        return;
      }
      await route.fallback();
    });

    await usersPage.searchInput.fill(held);
    await heldRequested;
    await usersPage.search(mine.username);
    await expect(usersPage.rows()).toHaveCount(1);
    await expect(usersPage.row(mine.username)).toBeVisible();

    releaseHeld();
    await heldDelivered;
    await usersPage.settle();
    // A stale answer would have emptied the list now (nothing matches `held`).

    await expect(usersPage.rows()).toHaveCount(1);
    await expect(usersPage.row(mine.username)).toBeVisible();
    await expect(usersPage.empty).toBeHidden();
  });

  test('typing sends one list request after a pause, not one per keystroke (RPS-1319)', async ({
    adminPage,
    usersPage,
    seeder,
  }) => {
    const mine = await seeder.createUser();
    await usersPage.goto();

    const searches: string[] = [];
    adminPage.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname === '/api/users' && url.searchParams.has('q')) {
        searches.push(url.searchParams.get('q') ?? '');
      }
    });

    const answered = usersPage.listResponse(mine.username, 0);
    await usersPage.searchInput.pressSequentially(mine.username, { delay: 20 });
    await answered;
    await expect(usersPage.row(mine.username)).toBeVisible();
    await usersPage.settle();

    expect(searches).toEqual([mine.username]);
  });

  test('a lone user has no pagination and the list shows what the server holds', async ({
    usersPage,
    seededUser,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);

    await expect(usersPage.rows()).toHaveCount(1);
    await expect(usersPage.pagination.root).toBeHidden();
    await expect(usersPage.list.inRow(seededUser.username, 'row-role')).toHaveText('USER');
    // Created a moment ago (moment's relative time; "in a few seconds" if the clocks differ a little).
    await expect(usersPage.list.inRow(seededUser.username, 'row-created')).toContainText(
      /ago|in a few seconds/,
    );
  });
});
