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
 * RPS-1761, double submit on the create modals (the OS half; the Cloud half is repsy-mono #1695). The server
 * answer is held back (`page.route` with a delay) so a second click lands while the first request is in flight;
 * the requests are counted, because a duplicate would otherwise hide behind a 409 (user and repository names are
 * unique) or the list showing one row either way.
 *
 * Create user and create deploy token are the two forms of the story. Create repository is added as the third
 * create modal of the panel.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { password as runPassword } from '../../../src/seed/run-id.js';
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { RepoCreateModal } from '../../../src/ui/pages/repo-create-modal.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';

/** How long the server answer is held back, long enough for several extra clicks. */
const HOLD_MS = 1500;

// @cloud-skip: Repsy Cloud has its own copy (repsy-cloud/e2e/ui-cloud/edge-cases/double-submit.spec.ts, #1695),
// and the user half needs the OS-only Users page.
test.describe('RPS-1761 double submit', { tag: ['@cloud-skip'] }, () => {
  test('create user: a double click sends one POST /api/users and makes one user', async ({
    usersPage,
    adminPage,
    seeder,
    trackUiUser,
    panelApi,
  }) => {
    const username = seeder.reserveUsername();
    const pwd = runPassword(seeder.runId);
    trackUiUser(username);
    await usersPage.goto();

    let posts = 0;
    await adminPage.route(
      (url) => url.pathname === '/api/users',
      async (route) => {
        if (route.request().method() === 'POST') {
          posts++;
          await new Promise((resolve) => setTimeout(resolve, HOLD_MS));
        }
        await route.continue();
      },
    );

    await usersPage.openCreateModal();
    await usersPage.createModal.fill({ username, password: pwd });
    await expect(usersPage.createModal.submit).toBeEnabled();
    await usersPage.createModal.submit.dblclick();

    await usersPage.shell.toasts.expectSuccess('User created successfully.');
    await expect(usersPage.createModal.root).toBeHidden();
    await usersPage.search(username);
    await expect(usersPage.rows()).toHaveCount(1);
    expect(posts, 'POST /api/users requests').toBe(1);
    const stored = (await panelApi.listUsers({ q: username })).filter(
      (user) => user.username === username,
    );
    expect(stored).toHaveLength(1);
  });

  test('create deploy token: a double click and an Enter press send one request and make one token', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    let posts = 0;
    await adminPage.route(
      (url) => url.pathname.endsWith(`/${repo.name}/deploy-tokens`),
      async (route) => {
        if (route.request().method() === 'POST') {
          posts++;
          await new Promise((resolve) => setTimeout(resolve, HOLD_MS));
        }
        await route.continue();
      },
    );

    const { tokens } = settings;
    const modal = await tokens.openCreateModal();
    await modal.fill({ name: 'double-submit' });
    await expect(modal.submitButton).toBeEnabled();
    await modal.submitButton.dblclick();
    await modal.name.press('Enter').catch(() => undefined); // the modal may be gone by now: nothing must be sent

    await tokens.infoModal.expectOpen();
    await tokens.infoModal.close();
    await tokens.expectRowCount(1);
    await expect(tokens.row('double-submit')).toBeVisible();
    expect(posts, 'POST deploy-tokens requests').toBe(1);
  });

  test('create repository: a double click sends one request and makes one repository', async ({
    adminPage,
    seeder,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(name);
    await adminPage.goto('/repositories');

    let posts = 0;
    await adminPage.route(
      (url) => url.pathname === '/api/repos',
      async (route) => {
        if (route.request().method() === 'POST') {
          posts++;
          await new Promise((resolve) => setTimeout(resolve, HOLD_MS));
        }
        await route.continue();
      },
    );

    await adminPage.getByTestId('repo-create').first().click();
    const modal = new RepoCreateModal(adminPage);
    await modal.expectOpen();
    await modal.selectType(uiRepoType(RepoType.NPM));
    await modal.fillName(name);
    await expect(modal.submitButton).toBeEnabled();
    await modal.submitButton.dblclick();

    await modal.expectClosed();
    await expect(adminPage.getByTestId(`repo-row-${name}`).first()).toBeVisible();
    expect(posts, 'POST /api/repos requests').toBe(1);
  });
});
