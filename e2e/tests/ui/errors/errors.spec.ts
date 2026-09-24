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
 * ERR-01, ERR-02, ERR-03: what the panel does when the backend misbehaves. Every fault is a
 * Playwright `page.route` stub on top of the REAL stack (no fault injection in the backend), and the
 * mapping under test is `errorHandlerInterceptor`: status 0 -> `Connection error`, 403 -> the server's
 * `text` or `Access denied`, >= 500 -> `Server error` (whatever the body says), other 4xx -> the
 * server's `text`. Each test removes its own route in a `finally` (`withRoute`), and a route lives on
 * the test's own page anyway, so nothing leaks into another test or worker.
 *
 * Two facts the tests are built around. A toast is short-lived (an error toast 7 s, a success toast
 * 3 s), so it is asserted right after the request that raises it (the assertion is started BEFORE the navigation that triggers it, then awaited).
 * And the repository list fires nine parallel `GET /api/repos/<TYPE>/info` calls, one per type, and
 * renders whatever arrives: stubbing all nine and stubbing only one are different scenarios.
 */
import type { Page, Route } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Toasts } from '../../../src/ui/pages/components.js';
import { UsersPage } from '../../../src/ui/pages/users.js';

const INFO_URL = /\/api\/repos\/[A-Z]+\/info(\?|$)/;
const USERS_URL = /\/api\/users(\?|$)/;
const NUGET_LIST_URL = /\/api\/nuget\/packages\/[^/?]+(\?|$)/;
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

const respondWith =
  (status: number, json: unknown = {}): Handler =>
  (route) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });

const abort: Handler = (route) => route.abort('failed');

/** Starts asserting a toast now and hands back the promise, so a short-lived toast cannot be missed. */
function expectToastLater(toasts: Toasts, text: string): Promise<void> {
  const raised = toasts.expectError(text);
  raised.catch(() => undefined); // awaited later; if the test fails first, do not report it twice
  return raised;
}

test.describe('Error handling', () => {
  test('ERR-01: a 500 on every repository type shows a "Server error" toast and no rows', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(
      adminPage,
      INFO_URL,
      respondWith(500, { text: 'internal detail that must not reach the user' }),
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
      },
    );
  });

  test('ERR-01: the list shows its error state, not the empty state, when every type fails', async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(adminPage, INFO_URL, respondWith(500), async () => {
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
    await withRoute(adminPage, INFO_URL, respondWith(500), async () => {
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
    await withRoute(adminPage, NUGET_LIST_URL, respondWith(500), async () => {
      const raised = expectToastLater(list.toasts, 'Server error');
      await adminPage.goto(list.path());
      await raised;

      await expect(list.error).toBeVisible();
      await expect(list.errorMessage).toHaveText('Error Occurred');
      await expect(list.rows()).toHaveCount(0);
    });
  });

  test('ERR-01: one failing type raises the toast and a warning while the other types still list', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const maven = await seeder.createRepo(RepoType.MAVEN);
    const npm = await seeder.createRepo(RepoType.NPM);

    await withRoute(
      adminPage,
      INFO_URL,
      (route) =>
        route.request().url().includes('/NPM/info') ? respondWith(500)(route) : route.fallback(),
      async () => {
        const raised = expectToastLater(repos.toasts, 'Server error');
        await repos.goto();
        await raised;

        // Only this test's own repositories, found by its run id: the maven one is listed, the npm
        // one (the failed type) is not, and the failure did not take the other eight types with it.
        await repos.search(`e2e-${seeder.runId}-`);
        await expect(repos.row(maven.name)).toBeVisible();
        await expect(repos.row(npm.name)).toHaveCount(0);

        // The list is not the error state: a warning names the type that is missing.
        await expect(repos.error).toHaveCount(0);
        await expect(adminPage.getByTestId('repo-warning')).toBeVisible();
        await expect(adminPage.getByTestId('repo-warning-message')).toContainText('npm');
      },
    );
  });

  test('ERR-02: an aborted request shows a "Connection error" toast', async ({ adminPage }) => {
    const repos = new RepositoriesPage(adminPage);
    await withRoute(adminPage, INFO_URL, abort, async () => {
      const raised = expectToastLater(repos.toasts, 'Connection error');
      await adminPage.goto('/repositories');
      await raised;

      await expect(repos.title).toBeVisible();
      await expect(repos.spinner.root).toBeHidden();
      await expect(repos.rows()).toHaveCount(0);
    });
  });

  test('ERR-02: an aborted request on another page (users) gives the same toast', async ({
    adminPage,
  }) => {
    const users = new UsersPage(adminPage);
    await withRoute(adminPage, USERS_URL, abort, async () => {
      const raised = expectToastLater(users.shell.toasts, 'Connection error');
      await adminPage.goto('/users');
      await raised;
      await expect(users.title).toBeVisible();
    });
  });

  test('ERR-03: a 403 without a body on an admin endpoint shows "Access denied"', async ({
    adminPage,
  }) => {
    const users = new UsersPage(adminPage);
    await withRoute(adminPage, USERS_URL, respondWith(403), async () => {
      const raised = expectToastLater(users.shell.toasts, 'Access denied');
      await adminPage.goto('/users');
      await raised;
      await expect(users.title).toBeVisible();
      await expect(users.rows()).toHaveCount(0);
    });
  });

  test('ERR-03: a 403 that carries a server text shows that text instead', async ({
    adminPage,
  }) => {
    const users = new UsersPage(adminPage);
    await withRoute(
      adminPage,
      USERS_URL,
      respondWith(403, { text: 'Only administrators may list users' }),
      async () => {
        const raised = expectToastLater(users.shell.toasts, 'Only administrators may list users');
        await adminPage.goto('/users');
        await raised;
        await expect(users.shell.toasts.toast().filter({ hasText: 'Access denied' })).toHaveCount(
          0,
        );
      },
    );
  });

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
});
