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
 * A11Y-13: the whole panel with the keyboard, in ONE flow (RPS-1266 walkthrough, RPS-1315).
 *
 * Log in, create a repository, open its settings, create a deploy token and delete the repository, and
 * never touch the mouse: only Tab (and Shift+Tab), Enter, Escape and typing. Every step moves focus with
 * Tab to the control it needs (which fails when a control is not a tab stop, or sits behind another),
 * presses Enter on it, and checks where focus and the page end up. The dialogs are exercised the way
 * a keyboard user meets them: focus starts inside, Escape closes them, and Enter submits a form.
 */
import type { Locator, Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { Shell } from '../../../src/ui/pages/shell.js';

/**
 * Presses Tab (or Shift+Tab) from wherever the focus is until `target` has it, at most `max` presses.
 * Fails with the number of presses when the control cannot be reached.
 */
async function tabTo(
  page: Page,
  target: Locator,
  { back = false, max = 60 }: { back?: boolean; max?: number } = {},
): Promise<void> {
  for (let press = 0; press < max; press++) {
    if (await target.evaluate((element) => element === document.activeElement)) {
      return;
    }
    await page.keyboard.press(back ? 'Shift+Tab' : 'Tab');
  }
  if (await target.evaluate((element) => element === document.activeElement)) {
    return;
  }
  throw new Error(`Tab did not reach ${String(target)} within ${max} presses`);
}

test.describe('Keyboard walkthrough', { tag: '@a11y' }, () => {
  test('A11Y-13: login, create a repository, create a token and delete the repository with Tab, Enter and Escape only', async ({
    page,
    seeder,
  }) => {
    const repoName = seeder.reserveRepoName(RepoType.DOCKER);
    seeder.adoptRepo(repoName);
    const tokenName = `kb-token-${seeder.runId}`;

    // 1. Log in: Tab to the username, type, Tab to the password, type, Enter.
    const login = new LoginPage(page);
    await login.goto();
    await tabTo(page, login.username);
    await page.keyboard.type(env.adminUsername);
    await page.keyboard.press('Tab');
    await expect(login.password).toBeFocused();
    await page.keyboard.type(env.adminPassword);
    await page.keyboard.press('Enter');
    await expect(new DashboardPage(page).welcomeUsername).toContainText(env.adminUsername);

    // 2. The sidebar link to the repositories.
    const repos = new RepositoriesPage(page);
    await tabTo(page, new Shell(page).sidebar.repositories);
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/repositories$/);
    await expect(repos.title).toBeVisible();

    // 3. Create a repository: the Create button opens the modal with focus in its name field; typing
    // the name and pressing Enter submits, and focus goes back to the Create button.
    await tabTo(page, repos.createButton);
    await page.keyboard.press('Enter');
    await repos.modal.expectOpen();
    await expect(repos.modal.nameInput).toBeFocused();
    await repos.afterListResponse(async () => {
      await page.keyboard.type(repoName);
      await page.keyboard.press('Enter');
    });
    await repos.toasts.expectSuccess('Repository created successfully');
    await repos.modal.expectClosed();
    await expect(repos.createButton).toBeFocused();

    // 4. Find it: Shift+Tab back to the search box and type the name.
    await tabTo(page, repos.searchInput, { back: true });
    await repos.afterListResponse(() => page.keyboard.type(repoName), { q: repoName, page: 0 });
    await expect(repos.list.rows()).toHaveCount(1);
    await expect(repos.row(repoName)).toBeVisible();

    // 5. Its row menu is the next stop after the row's own link: Enter opens the menu, Tab goes into it
    // (the menu follows the toggle), Enter on Settings opens the repository's settings.
    const menu = repos.list.inRow(repoName, 'row-menu');
    await tabTo(page, menu.getByTestId('dropdown-toggle'));
    await page.keyboard.press('Enter');
    await expect(menu.getByTestId('dropdown-menu')).toBeVisible();
    await page.keyboard.press('Tab');
    await expect(menu.getByTestId('row-settings')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/${repoName}/settings$`));

    // 6. Create a deploy token: the modal opens on its name field, Enter submits, and the one-time
    // secret modal opens; Escape closes it.
    const settings = new RepoSettingsPage(page, repoName);
    const tokens = settings.tokens;
    await expect(settings.root).toBeVisible();
    await tabTo(page, tokens.createButton);
    await page.keyboard.press('Enter');
    await tokens.createModal.expectOpen();
    await expect(tokens.createModal.name).toBeFocused();
    await page.keyboard.type(tokenName);
    await page.keyboard.press('Enter');
    await tokens.infoModal.expectOpen();
    expect((await tokens.infoModal.values()).token).not.toBe('');
    await page.keyboard.press('Escape');
    await tokens.infoModal.expectClosed();
    await expect(tokens.row(tokenName)).toBeVisible();

    // 7. Delete the repository: Escape cancels the confirmation and gives the focus back to the button,
    // Enter opens it again, Tab reaches Confirm and Enter deletes.
    const { deleteButton } = settings.deleteRepo;
    const danger = settings.shell.dangerModal;
    await tabTo(page, deleteButton);
    await page.keyboard.press('Enter');
    await danger.expectOpen('Delete Repository');
    await expect(danger.cancelButton).toBeFocused();
    await page.keyboard.press('Escape');
    await danger.expectClosed();
    await expect(deleteButton).toBeFocused();
    await expect(page).toHaveURL(new RegExp(`/${repoName}/settings$`));

    await page.keyboard.press('Enter');
    await danger.expectOpen('Delete Repository');
    await tabTo(page, danger.confirmButton);
    await page.keyboard.press('Enter');
    await settings.shell.toasts.expectSuccess('Repository deleted successfully');
    await expect(page).toHaveURL(/\/repositories$/);
    await expect(settings.root).toHaveCount(0);
  });
});
