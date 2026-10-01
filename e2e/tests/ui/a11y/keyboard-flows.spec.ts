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
 * A11Y-15..21 (RPS-1650, G23): what `a11y/keyboard.spec.ts` (A11Y-13, one long flow) and `a11y/dialogs.spec.ts`
 * (A11Y-05, the focus trap and Escape of every modal) do not cover, still with the keyboard only:
 *
 *  - A11Y-15: the tab order of the login form, and that a rejected submit keeps the focus in the form;
 *  - A11Y-16: the landmarks and page titles a screen reader jumps by (no skip link exists: the `main`,
 *    `banner` and `navigation` landmarks are the bypass mechanism);
 *  - A11Y-17: the tab order of the settings page follows the page from top to bottom, every control on it is
 *    a tab stop, and the fixed header never hides the focus (desktop; the phone is NAV-13);
 *  - A11Y-18: pagination and the sort and type selectors;
 *  - A11Y-19: a package row's menu and its delete confirmation;
 *  - A11Y-20: the create-user modal (Repsy OS only);
 *  - A11Y-21: the mobile menu: opened, entered and closed with the keyboard.
 */
import type { Locator, Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { target } from '../../../src/target.js';
import { obscuredFocus } from '../../../src/ui/layout.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';

const A11Y = '@a11y';

/** The `data-testid` of the focused element, or its tag name, or `body`. */
function focusedId(page: Page): Promise<string> {
  return page.evaluate(() => {
    const element = document.activeElement;
    return element?.getAttribute('data-testid') ?? element?.tagName.toLowerCase() ?? 'none';
  });
}

/** Presses Tab until `target` has the focus (at most `max` presses). */
async function tabTo(page: Page, target: Locator, max = 80): Promise<void> {
  for (let press = 0; press <= max; press++) {
    if (await target.evaluate((element) => element === document.activeElement)) {
      return;
    }
    await page.keyboard.press('Tab');
  }
  throw new Error(`Tab did not reach ${String(target)} within ${max} presses`);
}

/** Tabs `count` times and returns the focused test id after each press. */
async function tabOrder(page: Page, count: number): Promise<string[]> {
  const order: string[] = [];
  for (let press = 0; press < count; press++) {
    await page.keyboard.press('Tab');
    order.push(await focusedId(page));
  }
  return order;
}

test.describe('Keyboard: login and landmarks', { tag: A11Y }, () => {
  test('A11Y-15: the login form is tabbed username, password, the eye, Sign in, and Shift+Tab walks it back', async ({
    page,
  }) => {
    const login = new LoginPage(page);
    await login.goto();
    // Sign in is disabled until the form is valid, and a disabled button is not a tab stop.
    await expect(login.submit).toBeDisabled();
    await login.username.fill(env.adminUsername);
    await login.password.fill(env.adminPassword);
    await expect(login.submit).toBeEnabled();
    // Tab starts from the top of the document (a click on its corner moves the starting point there).
    await page.mouse.click(2, 2);

    // The header's logo comes first; the form's controls follow it in reading order.
    const order = await tabOrder(page, 6);
    const form = [
      `login-${target.ui.loginField}`,
      'login-password',
      'login-password-toggle',
      'login-submit',
    ];
    const at = form.map((id) => order.indexOf(id));
    expect(at, `the form's controls are all tab stops in ${JSON.stringify(order)}`).not.toContain(
      -1,
    );
    expect(at, 'and in reading order').toEqual([...at].sort((a, b) => a - b));

    await tabTo(page, login.submit);
    await page.keyboard.press('Shift+Tab');
    await expect(login.passwordToggle).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(login.password).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(login.username).toBeFocused();
  });

  test('A11Y-15: Space on the eye shows the password and Space again hides it, the focus stays on the eye', async ({
    page,
  }) => {
    const login = new LoginPage(page);
    await login.goto();
    await login.password.fill('secret');
    await tabTo(page, login.passwordToggle);
    await page.keyboard.press('Space');
    await expect(login.password).toHaveAttribute('type', 'text');
    await expect(login.passwordToggle).toBeFocused();
    await page.keyboard.press('Space');
    await expect(login.password).toHaveAttribute('type', 'password');
    await expect(login.passwordToggle).toBeFocused();
  });

  test('A11Y-16: every panel page has one main landmark, a banner and a navigation, and its own title', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pages: [string, string, RegExp][] = [
      ['/', 'the dashboard', /Dashboard/],
      ['/repositories', 'the repository list', /Repositories/],
      [`/${repo.name}/settings`, 'the settings page', /.+/],
      ['/profile', 'the profile', /Account/],
    ];
    const titles = new Set<string>();
    for (const [path, label, title] of pages) {
      await adminPage.goto(path);
      await expect(adminPage.getByRole('main')).toHaveCount(1);
      await expect(adminPage.getByRole('banner')).toHaveCount(1);
      await expect(
        adminPage.getByRole('navigation').first(),
        `${label} has a navigation`,
      ).toBeAttached();
      await expect(adminPage, `${label} is titled`).toHaveTitle(title);
      titles.add(await adminPage.title());
    }
    expect(titles.size, 'the pages are told apart by their titles').toBeGreaterThan(1);

    // A screen reader user leaves the header and the sidebar with the main landmark: the first tab stops
    // are the header and the sidebar and the main content follows them (no skip link needed to bypass them).
    await adminPage.goto('/repositories');
    await expect(adminPage.getByTestId('repo-title')).toBeVisible();
    await adminPage.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    const inMain = async (): Promise<boolean> =>
      adminPage.evaluate(
        () => document.querySelector('main')?.contains(document.activeElement) ?? false,
      );
    let leftHeader = false;
    for (let press = 0; press < 12 && !leftHeader; press++) {
      await adminPage.keyboard.press('Tab');
      leftHeader = await inMain();
    }
    expect(leftHeader, 'the main landmark is reached within twelve tab stops').toBe(true);
  });
});

test.describe('Keyboard: the settings page', { tag: A11Y }, () => {
  test('A11Y-17: the tab order follows the page top to bottom, every control is a tab stop and the header never hides the focus', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER);
    await seeder.createToken(repo.name, { name: 'kb-order' });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expect(settings.tokens.rows).toHaveCount(1);

    // Record every element of the page's own column that takes the focus, and where it is on the page.
    await adminPage.evaluate(() => {
      const w = window as unknown as { __stops: { id: string; top: number }[] };
      w.__stops = [];
      document.addEventListener('focusin', (event) => {
        const element = event.target as HTMLElement;
        if (!element.closest('[data-testid="panel-content"]')) {
          return;
        }
        const box = element.getBoundingClientRect();
        w.__stops.push({
          id: element.getAttribute('data-testid') ?? element.tagName.toLowerCase(),
          top: Math.round(box.top + window.scrollY),
        });
      });
      (document.activeElement as HTMLElement | null)?.blur();
    });

    // Tab down the whole page, to its last control (Delete repository), checking each stop on the way.
    const hidden: string[] = [];
    const { deleteButton } = settings.deleteRepo;
    for (let press = 0; press < 150; press++) {
      await adminPage.keyboard.press('Tab');
      const problem = await obscuredFocus(adminPage);
      if (problem) {
        hidden.push(`Tab #${press + 1}: ${problem}`);
      }
      if (await deleteButton.evaluate((element) => element === document.activeElement)) {
        break;
      }
    }
    await expect(deleteButton, 'the last control of the page is reached by Tab').toBeFocused();
    expect(hidden, 'focus hidden behind another element').toEqual([]);

    const stops = await adminPage.evaluate(
      () => (window as unknown as { __stops: { id: string; top: number }[] }).__stops,
    );
    // Every kind of control of the page took the focus: none is skipped (tabindex -1, an overlay...).
    const reached = new Set(stops.map((stop) => stop.id));
    for (const id of ['token-create', 'row-rotate', 'row-revoke']) {
      expect(reached.has(id), `${id} is a tab stop`).toBe(true);
    }

    // In the page's own column the focus only ever moves down, or sideways in a row: it never jumps back
    // up the page (a control that comes before its heading in the DOM would).
    const tops = stops.map((stop) => stop.top);
    const backJumps = stops
      .map((stop, index) =>
        index > 0 && stop.top < tops[index - 1] - 40
          ? `${stop.id} (${stop.top} after ${tops[index - 1]})`
          : '',
      )
      .filter(Boolean);
    expect(backJumps, 'stops that jump back up the page').toEqual([]);
  });
});

test.describe('Keyboard: lists and menus', { tag: A11Y }, () => {
  test('A11Y-18: pagination is used with Tab and Enter: a page number, then Previous', async ({
    adminPage,
    seeder,
    seedPackages,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await seedPackages(repo, 12);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    await expect(list.rows()).toHaveCount(10);

    await tabTo(adminPage, list.pagination.page(2));
    await adminPage.keyboard.press('Enter');
    await expect(list.rows()).toHaveCount(2);
    await expect(list.pagination.page(2)).toHaveAttribute('aria-current', 'page');

    await tabTo(adminPage, list.pagination.prev);
    await adminPage.keyboard.press('Enter');
    await expect(list.rows()).toHaveCount(10);
    await expect(list.pagination.page(1)).toHaveAttribute('aria-current', 'page');
  });

  // RPS-1669 (fixed): the list and pager now stay mounted through a reload (a busy overlay stands in
  // for the old full-block spinner), so the button that was pressed keeps the focus.
  test('A11Y-18: after a page change the focus is still in the pager', async ({
    adminPage,
    seeder,
    seedPackages,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await seedPackages(repo, 12);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    await tabTo(adminPage, list.pagination.page(2));
    await adminPage.keyboard.press('Enter');
    await expect(list.pagination.page(2)).toHaveAttribute('aria-current', 'page');
    const inPager = await list.pagination.root.evaluate((pager) =>
      pager.contains(document.activeElement),
    );
    expect(inPager, 'the focus stays inside the pagination after a page change').toBe(true);
  });

  test('A11Y-18: the sort menu opens with Enter, an option is chosen with Enter and the menu closes', async ({
    adminPage,
    seeder,
    seedPackages,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    await seedPackages(repo, 3);
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    const toggle = list.sort.getByTestId('sort-selector-toggle');
    await tabTo(adminPage, toggle);
    await adminPage.keyboard.press('Enter');
    await expect(list.sortMenu).toBeVisible();
    await adminPage.keyboard.press('Tab');
    const first = await focusedId(adminPage);
    expect(first, 'Tab enters the open menu').toMatch(/^sort-option-/);
    await adminPage.keyboard.press('Tab');
    const second = await focusedId(adminPage);
    expect(second).toMatch(/^sort-option-/);
    await adminPage.keyboard.press('Enter');
    await expect(list.sortMenu).toBeHidden();
    await expect(toggle).toContainText(second.replace('sort-option-', ''));
  });

  test('A11Y-18: the repository type selector is chosen with the keyboard and filters the list', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await tabTo(adminPage, repos.typeFilterToggle);
    await repos.afterListResponse(
      async () => {
        await adminPage.keyboard.press('Enter');
        const option = repos.typeFilterToggle
          .page()
          .getByTestId(`selector-option-${uiRepoType(RepoType.CARGO).slug}`);
        await tabTo(adminPage, option);
        await adminPage.keyboard.press('Enter');
      },
      { type: uiRepoType(RepoType.CARGO), page: 0 },
    );
    await expect(repos.typeFilterToggle).toContainText('Cargo');
    await repos.search(repo.name);
    await expect(repos.row(repo.name)).toBeVisible();
  });

  test('A11Y-19: a package row is deleted with the keyboard: row menu, Delete, Confirm', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const gone = await seedPackage(repo, { index: 1 });
    const kept = await seedPackage(repo, { index: 2 });
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    const menu = list.desktop.inRow(list.keyOf(gone), 'row-menu');

    await tabTo(adminPage, menu.getByTestId('dropdown-toggle'));
    await adminPage.keyboard.press('Enter');
    await expect(menu.getByTestId('dropdown-menu')).toBeVisible();
    await adminPage.keyboard.press('Tab');
    await expect(menu.getByTestId('row-delete')).toBeFocused();
    await adminPage.keyboard.press('Enter');
    await list.dangerModal.expectOpen(
      DESCRIPTORS.npm.levels.list.rowDelete?.dialogTitle ?? 'Delete',
    );
    await expect(list.dangerModal.cancelButton).toBeFocused();
    await tabTo(adminPage, list.dangerModal.confirmButton);
    await adminPage.keyboard.press('Enter');
    await list.toasts.expectSuccess(
      DESCRIPTORS.npm.levels.list.rowDelete?.successToast ?? /deleted/i,
    );
    await list.expectNoRow(gone);
    await list.expectRow(kept);
  });

  // RPS-1669 (fixed): the deleted row's menu button really is gone, but the list now reclaims the focus
  // (the next row's menu, the pager, or a tabbable fallback) once the reload's new rows are in.
  test('A11Y-19: after a row is deleted the focus is on something in the page', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const gone = await seedPackage(repo, { index: 1 });
    await seedPackage(repo, { index: 2 });
    const list = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).list();
    await list.goto();
    const menu = list.desktop.inRow(list.keyOf(gone), 'row-menu');
    await tabTo(adminPage, menu.getByTestId('dropdown-toggle'));
    await adminPage.keyboard.press('Enter');
    await adminPage.keyboard.press('Tab');
    await adminPage.keyboard.press('Enter');
    await tabTo(adminPage, list.dangerModal.confirmButton);
    await adminPage.keyboard.press('Enter');
    await list.expectNoRow(gone);
    expect(await focusedId(adminPage), 'the focus after the row is gone').not.toBe('body');
  });

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'A11Y-20: a user is created with the keyboard: Create, name, passwords, Enter',
    { tag: ['@cloud-skip'] },
    async ({ adminPage, seeder, panelApi }) => {
      const username = `kb-${seeder.runId}`.slice(0, 24);
      const users = new UsersPage(adminPage);
      await users.goto();
      await tabTo(adminPage, users.createButton);
      await adminPage.keyboard.press('Enter');
      await expect(users.createModal.root).toBeVisible();
      await expect(users.createModal.username).toBeFocused();
      await adminPage.keyboard.type(username);
      await adminPage.keyboard.press('Tab');
      await expect(users.createModal.password).toBeFocused();
      await adminPage.keyboard.type(env.adminPassword);
      await tabTo(adminPage, users.createModal.confirmPassword);
      await adminPage.keyboard.type(env.adminPassword);
      await expect(users.createModal.submit).toBeEnabled();
      await adminPage.keyboard.press('Enter');
      await users.shell.toasts.expectSuccess(/created/i);
      await expect(users.createModal.root).toHaveCount(0);
      const created = (await panelApi.listUsers({ q: username, size: 10 })).find(
        (user) => user.username === username,
      );
      expect(created, 'the user exists').toBeDefined();
      if (created) {
        seeder.adoptUser(created.id);
      }
      // Focus returns to the button that opened the modal.
      await expect(users.createButton).toBeFocused();
    },
  );
});

test.describe('Keyboard: the mobile menu', { tag: A11Y }, () => {
  // RPS-1669 (fixed): the drawer now uses the same `appDialog` focus-trap contract as the app's modals
  // (A11Y-05/A11Y-13): focus moves in on open, Escape closes it, and the focus returns to the burger.
  test('A11Y-21: the burger opens the menu with Enter, focus moves into it, Escape closes it and gives the focus back', async ({
    openUiPage,
    adminSession,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: { width: 390, height: 844 } });
    const shell = new Shell(page);
    await page.goto('/repositories');
    await expect(new RepositoriesPage(page).title).toBeVisible();
    await tabTo(page, shell.header.burger);
    await page.keyboard.press('Enter');
    await expect(shell.mobileSidebar.root).toBeVisible();
    await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'true');

    // The keyboard user is taken into the menu, not left on the burger behind it.
    await expect
      .poll(() =>
        shell.mobileSidebar.root.evaluate((menu) => menu.contains(document.activeElement)),
      )
      .toBe(true);
    await page.keyboard.press('Escape');
    await expect(shell.mobileSidebar.root).toHaveCount(0);
    await expect(shell.header.burger).toBeFocused();
  });
});
