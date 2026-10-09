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
 * ERR-01, ERR-02, ERR-03, ERR-05: what the panel does when the backend misbehaves. Every fault is a
 * Playwright `page.route` stub on top of the REAL stack (no fault injection in the backend), and the
 * mapping under test is `errorHandlerInterceptor`: status 0 -> `Connection error`, 403 -> the server's
 * `text` or `Access denied`, 503 with a `text` -> that text (RPS-1355: "try again shortly"), any other
 * >= 500 -> `Server error` (whatever the body says), other 4xx -> the server's `text`. Each test removes its own route in a `finally` (`withRoute`), and a route lives on
 * the test's own page anyway, so nothing leaks into another test or worker.
 *
 * Two facts the tests are built around. A toast is short-lived (an error toast 7 s, a success toast
 * 3 s), so it is asserted right after the request that raises it (the assertion is started BEFORE the navigation that triggers it, then awaited).
 * And the repository list is ONE `GET /api/repos` call (RPS-1268): it either answers or the page is in
 * its error state, with no partial list in between.
 */
import type { Page, Route } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Toasts } from '../../../src/ui/pages/components.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { formatApiPattern } from '../../../src/ui/routes.js';
import { errorBody, fulfillJson, type ErrorResponse } from '../../../src/ui/stub-responses.js';

const LIST_URL = /\/api\/repos(\?|$)/;
const USERS_URL = /\/api\/users(\?|$)/;
// Built at use, not at import: Repsy Cloud's path carries the owner (README, "Import time is not run time").
const nugetListUrl = (): RegExp => formatApiPattern('/api/nuget/packages');
const SCANS_URL = /\/api\/security\/scans(\?|$)/;

type Handler = (route: Route) => Promise<void>;

/** Installs `handler` on `url` for the duration of `body`, and always removes it again. */
async function withRoute(
  page: Page,
  url: RegExp,
  handler: Handler,
  body: () => Promise<void>,
): Promise<void> {
  await page.route(url, handler);
  try {
    await body();
  } finally {
    await page.unroute(url, handler);
  }
}

/** Answers with `status` and the failure envelope `fields` fill in (`ErrorResponse`, typed from the OpenAPI spec). */
const respondWith =
  (status: number, fields: Partial<ErrorResponse> = {}): Handler =>
  (route) =>
    fulfillJson<ErrorResponse>(route, status, errorBody(fields));

const abort: Handler = (route) => route.abort('failed');

/** Starts asserting a toast now and hands back the promise, so a short-lived toast cannot be missed. */
function expectToastLater(toasts: Toasts, text: string): Promise<void> {
  const raised = toasts.expectError(text);
  raised.catch(() => undefined); // awaited later; if the test fails first, do not report it twice
  return raised;
}

test.describe('Error handling', () => {
  test.use({
    allowedPageErrors: errorToasts(
      'by design: every case stubs the fault whose toast it asserts',
      'Server error',
      'Connection error',
      'Access denied',
      'Only administrators may list users',
      'You do not have permission to view this page',
      'The server is busy, try again in a moment',
      'That name is already taken',
      'An error occurred',
    ),
  });

  test('ERR-01: a 500 on the repository list shows a "Server error" toast and no rows', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const requests: string[] = [];
    adminPage.on('request', (request) => {
      if (request.method() === 'GET' && LIST_URL.test(request.url())) {
        requests.push(request.url());
      }
    });
    await withRoute(
      adminPage,
      LIST_URL,
      respondWith(500, { detail: 'internal detail that must not reach the user' }),
      async () => {
        const raised = expectToastLater(repos.toasts, 'Server error');
        await adminPage.goto('/repositories');
        await raised;

        // The toast carries the fixed text, never the body of a 5xx answer.
        await expect(repos.toasts.error().first().getByTestId('toast-message')).toHaveText(
          'Server error',
        );
        await expect(repos.toasts.toast().filter({ hasText: 'internal detail' })).toHaveCount(0);
        // The page survives: the toolbar is there, the spinner is gone, nothing is listed.
        await expect(repos.title).toBeVisible();
        await expect(repos.spinner.root).toBeHidden();
        await expect(repos.rows()).toHaveCount(0);
        // One request, one failure, one toast: not nine requests with nine outcomes.
        expect(requests).toHaveLength(1);
        await expect(repos.toasts.error()).toHaveCount(1);
      },
    );
  });

  test('ERR-01: the list shows its error state, not the empty state, when the request fails', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(adminPage, LIST_URL, respondWith(500), async () => {
      const raised = expectToastLater(repos.toasts, 'Server error');
      await adminPage.goto('/repositories');
      await raised;

      await expect(repos.error).toBeVisible();
      await expect(adminPage.getByTestId('repo-error-message')).not.toBeEmpty();
      // A failed load is not "you have no repositories".
      await expect(repos.emptyList.root).toHaveCount(0);
      await expect(repos.spinner.root).toBeHidden();
      await expect(repos.rows()).toHaveCount(0);
      await expect(adminPage.getByTestId('repo-warning')).toHaveCount(0);
    });
  });

  test('ERR-01: the refresh button retries a failed list and the error state goes away', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(adminPage, LIST_URL, respondWith(500), async () => {
      await adminPage.goto('/repositories');
      await expect(repos.error).toBeVisible();
    });

    // The stub is gone: the same button that was pressed for a fresh list now retries for real.
    await repos.refresh();
    await expect(repos.error).toHaveCount(0);
    await expect(repos.rows().first()).toBeVisible();
    await expect(adminPage.getByTestId('repo-warning')).toHaveCount(0);
  });

  test('ERR-01: a package list that fails shows its error block next to the toast', async ({
    adminPage,
    seeder,
  }) => {
    // NuGet is one of the lists that keeps the failure for the page (`pkg-error`); no package needs to
    // exist, only the repository, because the list call is stubbed.
    const repo = await seeder.createRepo(RepoType.NUGET);
    const list = protocolPages(adminPage, DESCRIPTORS.nuget, repo.name).list();
    await withRoute(adminPage, nugetListUrl(), respondWith(500), async () => {
      const raised = expectToastLater(list.toasts, 'Server error');
      await adminPage.goto(list.path());
      await raised;

      await expect(list.error).toBeVisible();
      await expect(list.errorMessage).toHaveText('Error Occurred');
      await expect(list.rows()).toHaveCount(0);
    });
  });

  test('ERR-01: a search that fails shows the error state, and refresh brings the list back', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const maven = await seeder.createRepo(RepoType.MAVEN);
    await repos.goto();

    await withRoute(
      adminPage,
      LIST_URL,
      (route) =>
        route.request().url().includes('q=') ? respondWith(500)(route) : route.fallback(),
      async () => {
        const raised = expectToastLater(repos.toasts, 'Server error');
        await repos.search(maven.name);
        await raised;

        // No partial state: not the rows of the old list, not a warning, the whole error state.
        await expect(repos.error).toBeVisible();
        await expect(repos.rows()).toHaveCount(0);
        await expect(adminPage.getByTestId('repo-warning')).toHaveCount(0);
      },
    );

    // The stub is gone: refresh is the Retry, and it also empties the search box.
    await repos.refresh();
    await expect(repos.error).toHaveCount(0);
    await expect(repos.searchInput).toHaveValue('');
    await expect(repos.rows().first()).toBeVisible();
  });

  test('ERR-02: an aborted request shows a "Connection error" toast', async ({ adminPage }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(adminPage, LIST_URL, abort, async () => {
      const raised = expectToastLater(repos.toasts, 'Connection error');
      await adminPage.goto('/repositories');
      await raised;

      await expect(repos.title).toBeVisible();
      await expect(repos.spinner.root).toBeHidden();
      await expect(repos.rows()).toHaveCount(0);
    });
  });

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'ERR-02: an aborted request on another page (users) gives the same toast',
    { tag: ['@cloud-skip'] },
    async ({ adminPage }) => {
      const users = new UsersPage(adminPage);
      await withRoute(adminPage, USERS_URL, abort, async () => {
        const raised = expectToastLater(users.shell.toasts, 'Connection error');
        await adminPage.goto('/users');
        await raised;
        await expect(users.title).toBeVisible();
      });
    },
  );

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'ERR-03: a 403 without a body on an admin endpoint shows "Access denied"',
    { tag: ['@cloud-skip'] },
    async ({ adminPage }) => {
      const users = new UsersPage(adminPage);
      await withRoute(adminPage, USERS_URL, respondWith(403), async () => {
        const raised = expectToastLater(users.shell.toasts, 'Access denied');
        await adminPage.goto('/users');
        await raised;
        await expect(users.title).toBeVisible();
        await expect(users.rows()).toHaveCount(0);
      });
    },
  );

  test(
    'ERR-03: a 403 that carries a server text shows that text instead',
    { tag: ['@cloud-skip'] },
    async ({ adminPage }) => {
      const users = new UsersPage(adminPage);
      await withRoute(
        adminPage,
        USERS_URL,
        respondWith(403, { detail: 'Only administrators may list users' }),
        async () => {
          const raised = expectToastLater(users.shell.toasts, 'Only administrators may list users');
          await adminPage.goto('/users');
          await raised;
          await expect(users.shell.toasts.toast().filter({ hasText: 'Access denied' })).toHaveCount(
            0,
          );
        },
      );
    },
  );

  test('ERR-03: a 403 on /security toasts and sends the user back to the dashboard', async ({
    adminPage,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const toasts = new Toasts(adminPage);
    await withRoute(adminPage, SCANS_URL, respondWith(403), async () => {
      // Two toasts: the interceptor's generic one and the page's own explanation.
      const generic = expectToastLater(toasts, 'Access denied');
      const explained = expectToastLater(toasts, 'You do not have permission to view this page');
      await adminPage.goto('/security');
      await generic;
      await explained;

      await expect(adminPage).toHaveURL(/\/$/);
      await dashboard.expectLoaded();
    });
  });

  // ERR-05 (RPS-1629): the 5xx and 4xx branches the cases above do not reach. The backend answers 503 with a
  // Retry-After for "try again shortly" (`resourceBusy`, `scanExecutorSaturated`, RPS-1355) and a `text` that
  // says so in plain words: that one text is shown. Every other 5xx stays generic, and a 4xx shows its text.
  test('ERR-05: a 503 with a text and a Retry-After toasts that text, once, and the page survives', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const busy = 'The server is busy, try again in a moment';
    await withRoute(
      adminPage,
      LIST_URL,
      (route) =>
        fulfillJson<ErrorResponse>(
          route,
          503,
          errorBody({ status: 503, code: 'resourceBusy', detail: busy }),
          {
            'Retry-After': '1',
          },
        ),
      async () => {
        const raised = expectToastLater(repos.toasts, busy);
        await adminPage.goto('/repositories');
        await raised;

        await expect(repos.toasts.error().first().getByTestId('toast-message')).toHaveText(busy);
        await expect(repos.toasts.toast().filter({ hasText: 'Server error' })).toHaveCount(0);
        await expect(repos.toasts.error()).toHaveCount(1);
        // The page is in its error state, with the refresh button as the retry, like after a 500.
        await expect(repos.title).toBeVisible();
        await expect(repos.error).toBeVisible();
        await expect(repos.spinner.root).toBeHidden();
      },
    );

    await repos.refresh();
    await expect(repos.error).toHaveCount(0);
    await expect(repos.rows().first()).toBeVisible();
  });

  const cases: [string, number, Partial<ErrorResponse> | undefined][] = [
    ['a 503 without a body', 503, undefined],
    ['a 503 whose body has no detail', 503, {}],
    ['a 502 with a text', 502, { detail: 'upstream detail that must not reach the user' }],
    ['a 504 with a text', 504, { detail: 'gateway detail that must not reach the user' }],
  ];
  for (const [label, status, body] of cases) {
    test(`ERR-05: ${label} toasts the generic "Server error"`, async ({ adminPage }) => {
      const repos = new RepositoriesPage(adminPage);
      await withRoute(
        adminPage,
        LIST_URL,
        (route) =>
          body === undefined
            ? route.fulfill({ status, headers: status === 503 ? { 'Retry-After': '1' } : {} })
            : fulfillJson<ErrorResponse>(
                route,
                status,
                errorBody(body),
                status === 503 ? { 'Retry-After': '1' } : {},
              ),
        async () => {
          const raised = expectToastLater(repos.toasts, 'Server error');
          await adminPage.goto('/repositories');
          await raised;

          await expect(repos.toasts.error()).toHaveCount(1);
          await expect(
            repos.toasts.toast().filter({ hasText: 'detail that must not' }),
          ).toHaveCount(0);
          await expect(repos.title).toBeVisible();
          await expect(repos.error).toBeVisible();
        },
      );
    });
  }

  test('ERR-05: a 4xx with a code toasts its detail, and one without a text toasts "An error occurred"', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(
      adminPage,
      LIST_URL,
      respondWith(409, { code: 'nameTaken', detail: 'That name is already taken' }),
      async () => {
        const raised = expectToastLater(repos.toasts, 'That name is already taken');
        await adminPage.goto('/repositories');
        await raised;
        await expect(repos.toasts.error()).toHaveCount(1);
      },
    );
    await withRoute(adminPage, LIST_URL, respondWith(400), async () => {
      await repos.refreshButton.click();
      await repos.toasts.expectError('An error occurred');
    });
  });
});
