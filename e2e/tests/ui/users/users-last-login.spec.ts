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
 * USR-07: the Last Login column of `/users` (`row-last-login`, RPS-1628 G18). Before this ticket the
 * desktop cell's visible label was `timeAgo(user.lastLoginAt)`, which is `''` for a user that never
 * logged in (`timeAgo(null)`): an empty, effectively unhoverable cell with nothing to prove the popup
 * even reads on it (fixed here: `user-management.component.html` now shows the literal "Never logged
 * in" label too, matching what the mobile card already did). The hover popup (`always` on) still
 * carries "Never logged in" or the full date.
 *
 * A login is a fact of the ACCOUNT, not of the admin's own browsing, so it is made in a second context
 * (`loginSession`, a real `POST /api/auth/login`) and only then read back through the admin's list.
 */
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { loginSession } from '../../../src/ui/session.js';

// @cloud-skip: the admin Users page exists on Repsy OS only (`target.ui.hasUsersPage`).
test.describe('USR-07 users list: Last Login', { tag: ['@cloud-skip'] }, () => {
  test('a user that never logged in shows "Never logged in", both as the label and on hover', async ({
    usersPage,
    seededUser,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await expect(usersPage.rows()).toHaveCount(1);

    const cell = usersPage.list.inRow(seededUser.username, 'row-last-login');
    await expect(cell.getByTestId('tooltip-text')).toHaveText('Never logged in');
    await cell.hover();
    await expect(cell.getByTestId('tooltip-popup')).toHaveText('Never logged in');

    // The server agrees: `lastLoginAt` is absent (nullable, RPS-1628).
    const listed = (await panelApi.listUsers({ q: seededUser.username }))[0];
    expect(listed?.lastLoginAt).toBeFalsy();
  });

  test("a login in another context turns up on the admin's list once it is refreshed", async ({
    usersPage,
    seededUser,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    const cell = usersPage.list.inRow(seededUser.username, 'row-last-login');
    await expect(cell.getByTestId('tooltip-text')).toHaveText('Never logged in');

    // A real login, in a session the admin's page never touches (no shared refresh token, see
    // `session.ts`): the fact must reach the admin's list through the server, not through anything
    // the admin's own browser did.
    await loginSession(seededUser.username, seededUser.password);

    await usersPage.refresh();
    await usersPage.search(seededUser.username);
    await expect(cell.getByTestId('tooltip-text')).toHaveText(/ago|in a few seconds/);
    await cell.hover();
    await expect(cell.getByTestId('tooltip-popup')).not.toHaveText('Never logged in');
    await expect(cell.getByTestId('tooltip-popup')).not.toHaveText('');

    // Persisted: the server now carries a `lastLoginAt` for this user.
    const listed = (await panelApi.listUsers({ q: seededUser.username }))[0];
    expect(listed?.lastLoginAt).toBeTruthy();
  });

  test('a recently created user and one seeded with no login are told apart on the same page', async ({
    usersPage,
    seeder,
  }) => {
    const fresh = await seeder.createUser();
    await usersPage.goto();
    await usersPage.search(fresh.username);
    await expect(usersPage.rows()).toHaveCount(1);

    // Created moments ago, never logged in: Created has a relative time, Last Login says so plainly.
    await expect(usersPage.list.inRow(fresh.username, 'row-created')).toContainText(
      /ago|in a few seconds/,
    );
    await expect(
      usersPage.list.inRow(fresh.username, 'row-last-login').getByTestId('tooltip-text'),
    ).toHaveText('Never logged in');
  });
});
