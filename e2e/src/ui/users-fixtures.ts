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
 * Fixtures for the users and profile specs (RPS-1253), on top of `fixtures.ts`.
 *
 *  - `usersPage`:   the admin's `UsersPage` (built on `adminPage`, so a test tagged `@credentials`
 *                   cannot use it: those use `userPage`). Resetting or deleting a SEEDED user from the
 *                   admin's session does not touch the admin's own credentials and is not tagged.
 *  - `trackUiUser`: registers the username of a user the UI is about to create, BEFORE the click, so a
 *                   test that fails half way still leaves nothing behind: at teardown the user is looked
 *                   up by name and handed to the `seeder`, which deletes it by id. Call it again with
 *                   the new name after a rename that the seeder did not seed.
 */
import { test as uiTest } from './fixtures.js';
import { UsersPage } from './pages/users.js';

export interface UsersFixtures {
  usersPage: UsersPage;
  trackUiUser: (username: string) => void;
}

export const test = uiTest.extend<UsersFixtures>({
  usersPage: async ({ adminPage }, use) => {
    await use(new UsersPage(adminPage));
  },

  trackUiUser: async ({ seeder, panelApi }, use) => {
    const names: string[] = [];
    await use((username) => {
      names.push(username);
    });
    for (const username of names) {
      const matches = await panelApi.listUsers({ search: username, size: 50 });
      const user = matches.find((candidate) => candidate.username === username);
      if (user) {
        seeder.adoptUser(user.id);
      }
    }
  },
});

export { expect } from './fixtures.js';
