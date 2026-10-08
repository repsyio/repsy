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
 * Fixtures of the access token specs. The tokens belong to a USER, and the list, "revoke all" and the limit of
 * 50 are per user: sharing the harness admin between parallel tests would make them read each other's tokens.
 * So every test gets its OWN throwaway account (`seeder.createUser()`, tracked and deleted with the test, and its
 * tokens with it), logged in through the API, and a page of a second context logged in as it. This is the same
 * shape as `accountUser` in the Cloud package. It does not use `seededUser`/`userPage`: those skip a target
 * without the USER role, and the access token pages exist on both products.
 */
import type { Page } from '@playwright/test';

import { AccessTokenApi } from './access-token-api.js';
import { expect, test as base } from './fixtures.js';
import { loginSession, type UiSession } from './session.js';

export interface TokenUser {
  username: string;
  password: string;
  session: UiSession;
}

export interface AccessTokenFixtures {
  /** A throwaway account for this test, logged in. */
  tokenUser: TokenUser;
  /** A page of a NEW context logged in as `tokenUser`. */
  tokenPage: Page;
  /** The access token API of `tokenUser`. */
  tokenApi: AccessTokenApi;
}

export const test = base.extend<AccessTokenFixtures>({
  tokenUser: async ({ seeder }, use) => {
    const seeded = await seeder.createUser();
    const session = await loginSession(seeded.username, seeded.password);
    await use({ username: seeded.username, password: seeded.password, session });
  },

  tokenPage: async ({ openUiPage, tokenUser }, use) => {
    await use(await openUiPage({ session: tokenUser.session }));
  },

  tokenApi: async ({ tokenUser }, use) => {
    await use(new AccessTokenApi(tokenUser.session.token));
  },
});

export { expect };
