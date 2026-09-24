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
 * DASH-01..04: the dashboard. Its counts are global (every worker's repositories are on the same
 * stack), so a count is only ever compared with an API read taken around the same moment, and
 * Recent Activity (the six newest repositories of ANY test) is retried until the seeded repository
 * is in it.
 */
import type { APIRequestContext } from '@playwright/test';

import { type PanelApi, RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage, RECENT_ACTIVITY_SIZE } from '../../../src/ui/pages/dashboard.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UI_REPO_TYPES, uiRepoType } from '../../../src/ui/repo-types.js';
import { JWT_SHAPE, storedSession } from '../auth/stored-session.js';

const SETTLE_TIMEOUT = 45_000;

/** What `GET /api/repos/<TYPE>/info` lists per type: the oracle for the count rows. */
async function apiCounts(panelApi: PanelApi): Promise<Record<string, number>> {
  const counts: Record<string, number> = {};
  for (const { type, slug } of UI_REPO_TYPES) {
    counts[slug] = (await panelApi.listRepos(type)).length;
  }
  return counts;
}

async function apiUsage(
  request: APIRequestContext,
  token: string,
): Promise<{ text: string; reposCount: number }> {
  const response = await request.get('/api/usages', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok()).toBe(true);
  const { data } = (await response.json()) as {
    data: { diskUsed: { text: string }; reposCount: number };
  };
  return { text: data.diskUsed.text, reposCount: data.reposCount };
}

async function apiSecurityTotal(request: APIRequestContext, token: string): Promise<number> {
  const response = await request.get('/api/repos/security-summary', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok()).toBe(true);
  const { data } = (await response.json()) as { data: Record<string, unknown> };
  return Object.keys(data).length;
}

test.describe('Dashboard', () => {
  test(
    'DASH-01: the cards render and the per-type counts equal the API',
    { tag: ['@smoke'] },
    async ({ adminPage, adminSession, seeder, panelApi }) => {
      const dashboard = new DashboardPage(adminPage);
      await seeder.createRepo(RepoType.MAVEN);
      await seeder.createRepo(RepoType.NPM);

      // Parallel workers create and delete repositories at any time: compare against API reads taken
      // right before and after the page loaded, and only accept a window in which nothing moved.
      await expect(async () => {
        const before = await apiCounts(panelApi);
        await dashboard.open({ admin: true });
        const shown = await dashboard.displayedCounts();
        const usage = await apiUsage(adminPage.request, adminSession.token);
        const securityTotal = await apiSecurityTotal(adminPage.request, adminSession.token);
        const after = await apiCounts(panelApi);

        expect(after, 'a repository was created or deleted while loading: retry').toEqual(before);
        expect(shown).toEqual(before);

        const total = Object.values(before).reduce((sum, count) => sum + count, 0);
        await expect(dashboard.diskUsageRepoCount).toHaveText(String(usage.reposCount));
        expect(usage.reposCount).toBe(total);
        await expect(dashboard.securityTotal).toHaveText(String(securityTotal));
        await expect(dashboard.diskUsageTotal).toHaveText(usage.text || '0 B');
      }).toPass({ timeout: SETTLE_TIMEOUT });

      // The seeded repositories are among what was counted, on top of the seeded defaults.
      const shown = await dashboard.displayedCounts();
      expect(shown.maven).toBeGreaterThanOrEqual(2);
      expect(shown.npm).toBeGreaterThanOrEqual(2);

      await expect(dashboard.welcomeUsername).toContainText(adminSession.username);
      await expect(dashboard.diskUsageTotal).toHaveText(/^\s*\d+(\.\d+)? ?(B|KB|MB|GB|TB)\s*$/i);
      await expect(dashboard.diskUsageCard).toBeVisible();
      await expect(dashboard.securityCard).toBeVisible();
      // No scanner in the e2e stack: nothing can have critical or high findings.
      await expect(dashboard.securityCriticalHigh).toHaveText('0');
      await expect(dashboard.repoCountCard).toBeVisible();
      await expect(dashboard.recentActivity).toBeVisible();
      await expect(dashboard.createButton).toBeVisible();
      for (const type of UI_REPO_TYPES) {
        await expect(dashboard.countRow(type)).toBeVisible();
      }
    },
  );

  test('DASH-02: Recent Activity lists the newest repository and a click opens it', async ({
    adminPage,
    seeder,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const repo = await seeder.createRepo(RepoType.MAVEN);

    // The list holds the six newest repositories of every test on the stack: reload until ours is in.
    await expect(async () => {
      await dashboard.open({ admin: true });
      await expect(dashboard.recentRow(repo.name)).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: SETTLE_TIMEOUT });

    expect(await dashboard.recentRows().count()).toBeLessThanOrEqual(RECENT_ACTIVITY_SIZE);
    const row = dashboard.recentRow(repo.name);
    await expect(row.getByTestId('row-name')).toHaveText(repo.name);
    await expect(row.getByTestId('row-disk-usage')).toHaveText('0 B');
    await expect(row.getByTestId('row-created')).toContainText('ago');

    await row.click();

    await expect(adminPage).toHaveURL(`/${repo.name}`);
    await expect(adminPage.getByTestId('breadcrumb-current')).toContainText(repo.name);
    await expect(adminPage).toHaveTitle('repsy | Maven Groups');
  });

  test('DASH-03: a repository count row opens the list filtered to that type', async ({
    adminPage,
    seeder,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const repos = new RepositoriesPage(adminPage);
    const maven = uiRepoType(RepoType.MAVEN);
    const mavenRepo = await seeder.createRepo(RepoType.MAVEN);
    const npmRepo = await seeder.createRepo(RepoType.NPM);
    await dashboard.open({ admin: true });

    const infoRequests: string[] = [];
    adminPage.on('request', (request) => {
      if (/\/api\/repos\/[A-Z]+\/info$/.test(request.url())) {
        infoRequests.push(request.url());
      }
    });
    await repos.afterInfoResponses(() => dashboard.countRow(maven).click(), [maven]);

    await expect(adminPage).toHaveURL('/repositories');
    await expect(repos.typeFilterText()).toHaveText(maven.label);
    // Only the Maven list was fetched: the type came from the click, not from a nine-way load.
    expect(infoRequests).toHaveLength(1);
    expect(infoRequests[0]).toMatch(/\/api\/repos\/MAVEN\/info$/);

    // Narrow the (client-side) list to this test's repositories before looking at rows.
    await repos.search(`e2e-${seeder.runId}-`);
    await expect(repos.row(mavenRepo.name)).toBeVisible();
    await expect(repos.row(npmRepo.name)).toHaveCount(0);
  });

  test('DASH-04: a USER has no Create button and the counts are not requested', async ({
    userPage,
    seededUser,
  }) => {
    const dashboard = new DashboardPage(userPage);
    const requests = dashboard.trackRepoRequests();

    await dashboard.open({ admin: false });

    await expect(dashboard.welcomeUsername).toContainText(seededUser.username);
    await expect(dashboard.diskUsageCard).toBeVisible();
    await expect(dashboard.securityCard).toBeVisible();
    await expect(dashboard.repoCountCard).toBeVisible();
    await expect(dashboard.recentActivity).toBeVisible();
    await expect(dashboard.createButton).toHaveCount(0);
    // Counts are fetched for admins only; Recent Activity still asks for every type.
    expect(requests.counts()).toEqual([]);
    expect(requests.infos()).toHaveLength(UI_REPO_TYPES.length);
  });

  test('DASH-04: a USER sees the newest repositories in Recent Activity', async ({
    userPage,
    seeder,
  }) => {
    const dashboard = new DashboardPage(userPage);
    const repo = await seeder.createRepo(RepoType.MAVEN);

    await expect(async () => {
      await dashboard.open({ admin: false });
      await expect(dashboard.recentRow(repo.name)).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 20_000 });
  });

  // RPS-1284: Recent Activity asks for the usage of every repository, which needs MANAGE. A USER used
  // to be answered 401 `unAuthorized` (a session-lost status: the SPA had to special-case it), and is
  // now answered 403 `accessDenied`. The dashboard shows the rows with an unknown disk usage, raises
  // no toast for the refusals, and the session is untouched.
  test('DASH-04: a USER is answered 403 for every repository usage, silently, and stays signed in', async ({
    userPage,
  }) => {
    const dashboard = new DashboardPage(userPage);
    const usages: { status: number; body: Promise<{ msgId?: string } | null> }[] = [];
    userPage.on('response', (response) => {
      if (/\/api\/repos\/[^/]+\/usage(\?|$)/.test(response.url())) {
        usages.push({
          status: response.status(),
          body: response.json().catch(() => null),
        });
      }
    });

    await dashboard.open({ admin: false });
    await expect.poll(() => usages.length, { timeout: 20_000 }).toBeGreaterThan(0);
    await dashboard.settle();

    expect(usages.map((usage) => usage.status).filter((status) => status !== 403)).toEqual([]);
    expect((await usages[0].body)?.msgId).toBe('accessDenied');
    await expect(new Shell(userPage).toasts.toast()).toHaveCount(0);
    await expect(userPage).toHaveURL('/');
    expect((await storedSession(userPage)).token).toMatch(JWT_SHAPE);
  });
});
