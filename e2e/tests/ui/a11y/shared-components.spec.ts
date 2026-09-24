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
 * A11Y-02..04: the ARIA contract of the panel's shared interactive components (RPS-1266, part 1):
 * the row `...` menu (`app-dropdown`), the toast stack and pagination. The axe scans of `a11y.spec.ts`
 * cannot see an open menu or a toast, so these tests assert the attributes a screen reader relies on
 * and the keyboard behaviour, on the real pages: the repository list for the menu and the paging, and
 * the same page with one failing `info` call for the toast.
 *
 * Toast timing is driven with Playwright's clock (`page.clock`): an error toast lasts 7 s, is held
 * while it is hovered or focused, and a success toast still lasts 3 s (the latter is a unit test, a
 * success toast is too short-lived to assert its disappearance in a real browser).
 */
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { REPO_PAGE_SIZE, RepositoriesPage } from '../../../src/ui/pages/repositories.js';

/** axe (WCAG 2.0/2.1 A and AA) on ONE component: the whole page still carries other debt (RPS-1266). */
async function expectNoAxeViolations(page: Page, selector: string): Promise<void> {
  const results = await new AxeBuilder({ page })
    .include(selector)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  expect(
    results.violations.map((violation) => `${violation.id}: ${violation.help}`),
    `axe on ${selector}`,
  ).toEqual([]);
}

const NPM_INFO_URL = /\/api\/repos\/NPM\/info(\?|$)/;

test.describe('Shared components: ARIA contract', { tag: '@a11y' }, () => {
  test('A11Y-02: the row menu is a labelled menu button with menu items', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const dropdown = repos.list.inRow(repo.name, 'row-menu');
    const toggle = dropdown.getByTestId('dropdown-toggle');
    const menu = dropdown.getByTestId('dropdown-menu');

    // Collapsed: a real button with a name, announcing a menu, no menu in the DOM.
    await expect(toggle).toHaveAttribute('aria-haspopup', 'menu');
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).toHaveAccessibleName('More options');
    await expect(menu).toHaveCount(0);
    // The host is not a button, so the toggle is not nested inside another button.
    await expect(dropdown.locator('xpath=self::button')).toHaveCount(0);

    await toggle.click();

    await expect(menu).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(menu).toHaveAttribute('role', 'menu');
    await expect(menu).toHaveAccessibleName('More options');
    const menuId = await menu.evaluate((element) => element.id);
    expect(menuId).toBeTruthy();
    await expect(toggle).toHaveAttribute('aria-controls', menuId ?? '');
    await expect(menu.getByTestId('row-delete')).toHaveAttribute('role', 'menuitem');
    await expect(menu.getByRole('menuitem', { name: /Delete/ })).toBeVisible();
    await expectNoAxeViolations(
      adminPage,
      `[data-testid="repo-row-${repo.name}"] [data-testid="row-menu"]`,
    );
  });

  test('A11Y-02: Escape closes the row menu and returns focus to its toggle', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const dropdown = repos.list.inRow(repo.name, 'row-menu');
    const toggle = dropdown.getByTestId('dropdown-toggle');

    const menu = await repos.list.openRowMenu(repo.name);
    await menu.getByTestId('row-delete').focus();
    await adminPage.keyboard.press('Escape');

    await expect(menu).toHaveCount(0);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).toBeFocused();
    // Escape on a closed menu does nothing to the page.
    await adminPage.keyboard.press('Escape');
    await expect(repos.row(repo.name)).toBeVisible();
  });

  test('A11Y-02: a click outside closes the row menu, and so does choosing an item', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const toggle = repos.list.inRow(repo.name, 'row-menu').getByTestId('dropdown-toggle');

    const menu = await repos.list.openRowMenu(repo.name);
    await repos.title.click();
    await expect(menu).toHaveCount(0);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');

    const reopened = await repos.list.openRowMenu(repo.name);
    await reopened.getByTestId('row-delete').click();
    await repos.dangerModal.expectOpen('Delete Repository');
    await expect(reopened).toHaveCount(0);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await repos.dangerModal.cancel();
  });

  test.describe('toasts', () => {
    /** Loads the repository list with the NPM `info` call failing, which raises ONE error toast. */
    async function raiseErrorToast(page: Page) {
      await page.clock.install();
      await page.route(NPM_INFO_URL, (route) =>
        route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }),
      );
      const repos = new RepositoriesPage(page);
      await page.goto('/repositories');
      const toast = repos.toasts.error('Server error');
      await expect(toast).toBeVisible();
      return { repos, toast };
    }

    test('A11Y-03: the stack is a polite live region, an error toast is an alert, its close button is named', async ({
      adminPage,
    }) => {
      const { repos, toast } = await raiseErrorToast(adminPage);

      await expect(repos.toasts.stack).toHaveAttribute('role', 'status');
      await expect(repos.toasts.stack).toHaveAttribute('aria-live', 'polite');
      await expect(toast).toHaveAttribute('role', 'alert');
      await expect(toast.getByRole('button', { name: 'Dismiss notification' })).toBeVisible();
      await expectNoAxeViolations(adminPage, '[data-testid="toast-stack"]');

      await toast.getByTestId('toast-close').click();
      await expect(toast).toHaveCount(0);
      // The live region itself stays for the next message.
      await expect(repos.toasts.stack).toHaveAttribute('role', 'status');
    });

    test('A11Y-03: an error toast outlives the old 3 s and is gone after its 7 s', async ({
      adminPage,
    }) => {
      const { toast } = await raiseErrorToast(adminPage);

      await adminPage.clock.fastForward(4000);
      await expect(toast).toBeVisible();

      await adminPage.clock.fastForward(4000);
      await expect(toast).toHaveCount(0);
    });

    test('A11Y-03: an error toast stays while hovered, and expires once the pointer leaves', async ({
      adminPage,
    }) => {
      const { toast } = await raiseErrorToast(adminPage);

      await toast.hover();
      await adminPage.clock.fastForward(30_000);
      await expect(toast).toBeVisible();

      await adminPage.mouse.move(0, 0);
      await adminPage.clock.fastForward(8000);
      await expect(toast).toHaveCount(0);
    });

    test('A11Y-03: an error toast stays while focus is inside it', async ({ adminPage }) => {
      const { toast } = await raiseErrorToast(adminPage);

      await toast.getByTestId('toast-close').focus();
      await adminPage.clock.fastForward(30_000);
      await expect(toast).toBeVisible();

      await toast.getByTestId('toast-close').blur();
      await adminPage.clock.fastForward(8000);
      await expect(toast).toHaveCount(0);
    });
  });

  test('A11Y-04: pagination is a named navigation with aria-current on the current page', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    for (let count = 0; count < REPO_PAGE_SIZE + 1; count++) {
      await seeder.createRepo(RepoType.MAVEN);
    }
    await repos.goto();
    await repos.search(`e2e-${seeder.runId}-`);
    await expect(repos.rows()).toHaveCount(REPO_PAGE_SIZE);

    const nav = adminPage.getByRole('navigation', { name: 'Pagination' });
    await expect(nav).toBeVisible();
    await expect(repos.pagination.root).toHaveAttribute('aria-label', 'Pagination');
    await expect(repos.pagination.page(1)).toHaveAttribute('aria-current', 'page');
    await expect(repos.pagination.page(2)).not.toHaveAttribute('aria-current');
    await expectNoAxeViolations(adminPage, '[data-testid="pagination"]');
    // The arrows are named by the button, not by the image inside it.
    await expect(nav.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    await expect(nav.getByRole('button', { name: 'Next page' })).toBeEnabled();
    await expect(repos.pagination.prev.locator('img')).toHaveAttribute('alt', '');
    await expect(repos.pagination.next.locator('img')).toHaveAttribute('alt', '');

    await nav.getByRole('button', { name: 'Next page' }).click();

    await expect(repos.rows()).toHaveCount(1);
    await expect(repos.pagination.page(2)).toHaveAttribute('aria-current', 'page');
    await expect(repos.pagination.page(1)).not.toHaveAttribute('aria-current');
    await expect(repos.pagination.page(2)).toHaveAccessibleName('Page 2');
  });
});
