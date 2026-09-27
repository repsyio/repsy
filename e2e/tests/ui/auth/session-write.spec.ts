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
 * AUTH-14: a WRITE whose access token expired (RPS-1621, G11). `RefreshTokenInterceptor` answers a
 * `sessionExpired` 401 with ONE shared refresh and ONE retry of the request, whatever its method; a
 * refused request was not applied (the 401 comes before the handler runs), so replaying a POST cannot
 * create anything twice. The write here is the create-repository modal (`POST /api/repos`); what the
 * tests prove is the count of calls on the wire and the state the user is left in:
 *
 *  - the refresh works: the repository is created ONCE, after 1 refresh and 2 POSTs;
 *  - the refresh fails: the POST is not sent again, the user is logged out with a toast and lands on the
 *    login form (remembering the page), the repository does not exist, no modal is left open;
 *  - the retry is refused too: no third POST, no second refresh, the same clean logout.
 *
 * As in `session.spec.ts` the 401 is stubbed (an access token cannot be expired on demand) and the rest
 * is the real backend. The tests use `adminPage` and only create a repository of their own.
 */
import type { Page, Request } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { allowLists, errorToasts } from '../../../src/ui/page-errors.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';
import {
  REFRESH_PATH,
  answerRefreshTokenExpired,
  answerSessionExpired,
  countRefreshCalls,
  expireAccessToken,
} from '../../../src/ui/session.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';

const CREATE_URL = /\/api\/repos(\?|$)/;
const RETURN_TO_REPOSITORIES = (url: URL): boolean =>
  url.pathname === '/login' && url.searchParams.get('returnUrl') === '/repositories';

/** Counts the create calls (`POST /api/repos`) the page sends. */
function countCreateCalls(page: Page): { readonly count: number } {
  const counter = { count: 0 };
  page.on('request', (request) => {
    if (request.method() === 'POST' && CREATE_URL.test(request.url())) {
      counter.count += 1;
    }
  });
  return counter;
}

test.describe('AUTH-14 a write refused with an expired access token', () => {
  test.use({
    allowedPageErrors: allowLists(
      errorToasts(
        'by design: the refresh or the retry is refused and the session ends',
        'Session expired, please log in again.',
        'Session invalid, please log in again.',
      ),
    ),
  });

  /** Opens the create modal on /repositories and fills a new repository's name (adopted, so it is swept). */
  async function fillCreateModal(page: Page, name: string) {
    const repos = new RepositoriesPage(page);
    await repos.goto();
    const modal = await repos.openCreateModal();
    await modal.selectType(uiRepoType(RepoType.NPM));
    await modal.fillName(name);
    return { repos, modal };
  }

  test('the write is retried once after a refresh, and applied once', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(name);
    const { repos, modal } = await fillCreateModal(adminPage, name);
    const before = await storedSession(adminPage);
    const refused: Request[] = [];
    await expireAccessToken(adminPage, before.token!, {
      methods: ['POST'],
      times: 1,
      refused,
    });
    const creates = countCreateCalls(adminPage);
    const refreshes = countRefreshCalls(adminPage);

    await repos.afterListResponse(() => modal.submitButton.click(), { page: 0 });

    await repos.toasts.expectSuccess('Repository created successfully');
    await modal.expectClosed();
    expect(refused).toHaveLength(1);
    expect(refreshes.count).toBe(1);
    expect(creates.count).toBe(2);
    const created = (await panelApi.listAllRepos({ type: RepoType.NPM, q: name })).filter(
      (repo) => repo.name === name,
    );
    expect(created).toHaveLength(1);
    // The user never noticed: still signed in, on the same page, with a rotated pair.
    await expect(adminPage).toHaveURL('/repositories');
    const after = await storedSession(adminPage);
    expect(after.refreshToken).toMatch(JWT_SHAPE);
    expect(after.refreshToken).not.toBe(before.refreshToken);
  });

  test('the write is not sent again when the refresh fails: logged out, nothing created', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(name);
    const { repos, modal } = await fillCreateModal(adminPage, name);
    const before = await storedSession(adminPage);
    await expireAccessToken(adminPage, before.token!, { methods: ['POST'] });
    // What the backend answers for a refresh token past its lifetime (30 days, so not waitable).
    await adminPage.route(`**${REFRESH_PATH}`, answerRefreshTokenExpired);
    const creates = countCreateCalls(adminPage);
    const refreshes = countRefreshCalls(adminPage);

    await modal.submitButton.click();

    await expect(adminPage).toHaveURL(RETURN_TO_REPOSITORIES);
    await expect(new LoginPage(adminPage).submit).toBeVisible();
    await repos.toasts.expectError('Session expired, please log in again.');
    await expect(modal.root).toHaveCount(0);
    expect(await storedSession(adminPage)).toEqual(NO_SESSION);
    expect(creates.count).toBe(1);
    expect(refreshes.count).toBe(1);
    expect(await panelApi.listAllRepos({ type: RepoType.NPM, q: name })).toHaveLength(0);
  });

  test('the write is not sent a third time when the retry is refused too: logged out, nothing created', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(name);
    const { repos, modal } = await fillCreateModal(adminPage, name);
    // Every create is refused as expired, whatever token it carries: the retry with the fresh token too.
    await adminPage.route(CREATE_URL, (route) =>
      route.request().method() === 'POST' ? answerSessionExpired(route) : route.fallback(),
    );
    const creates = countCreateCalls(adminPage);
    const refreshes = countRefreshCalls(adminPage);
    const before = await storedSession(adminPage);

    await modal.submitButton.click();

    await expect(adminPage).toHaveURL(RETURN_TO_REPOSITORIES);
    await expect(new LoginPage(adminPage).submit).toBeVisible();
    await repos.toasts.expectError('Session invalid, please log in again.');
    await expect(modal.root).toHaveCount(0);
    expect(await storedSession(adminPage)).toEqual(NO_SESSION);
    expect(creates.count).toBe(2);
    expect(refreshes.count).toBe(1);
    expect(before.token).toMatch(JWT_SHAPE);
    expect(await panelApi.listAllRepos({ type: RepoType.NPM, q: name })).toHaveLength(0);
  });
});
