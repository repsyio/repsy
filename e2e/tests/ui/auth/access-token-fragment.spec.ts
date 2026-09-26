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
 * AUTH-15: a `#access_token=...` URL fragment is just a fragment (RPS-1621, G24). The SPA used to have an
 * initializer that stored any `access_token` of the fragment as the panel's access token and went to `/`:
 * a leftover of an OAuth redirect this backend never produces (there is no `access_token` fragment
 * anywhere in `repsy-backend`; the only `access_token` is a field of the Docker token endpoint's JSON).
 * It wrote only the access token, never the refresh token, over an existing session, so a crafted link
 * logged the victim out or planted a token. It is removed; these tests pin that the fragment does nothing.
 */
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';

const FRAGMENT = '#access_token=planted.by.a.link&token_type=bearer&state=xyz';

test.describe('AUTH-15 a #access_token fragment', () => {
  test('leaves an existing session untouched and stays on the requested page', async ({
    adminPage,
  }) => {
    await new DashboardPage(adminPage).goto();
    const before = await storedSession(adminPage);
    expect(before.token).toMatch(JWT_SHAPE);

    // A different path, so this is a full page load and the SPA boots with the fragment in its URL.
    const repos = new RepositoriesPage(adminPage);
    await repos.afterListResponse(() => adminPage.goto(`/repositories${FRAGMENT}`));

    await expect(repos.title).toBeVisible();
    await expect(adminPage).toHaveURL(/\/repositories#access_token=/);
    expect(await storedSession(adminPage)).toEqual(before);
    await expect(new LoginPage(adminPage).form).toHaveCount(0);
  });

  test('does not sign a visitor in or store anything', async ({ page }) => {
    await page.goto(`/${FRAGMENT}`);

    await expect(new LoginPage(page).submit).toBeVisible();
    expect(await storedSession(page)).toEqual(NO_SESSION);
    expect(await page.evaluate(() => window.localStorage.length)).toBe(0);
  });
});
