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
 * A11Y-08..10: list rows are links, not fake buttons (RPS-1266, part 4).
 *
 * Every list row (the repository list, the dashboard's recent activity and every protocol's package,
 * group, version, tag and manifest lists) used to be a `<div role="button" [routerLink]>` holding links
 * and a dropdown, which axe reports as `nested-interactive`. A row is now a plain container with ONE
 * real `<a class="row-link">` stretched over it (so the whole row still takes a mouse click), named
 * after the row's identifier, and its other controls (secondary links, the security badge, the action
 * menu) are siblings of that link. That gives a row middle/ctrl-click, "open in a new tab" and Tab +
 * Enter for free. The `javascript:void(0)` anchors that acted as buttons are real buttons.
 */
import type { Locator, Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import type { PackageProtocol } from '../../../src/seed/packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import {
  DESCRIPTORS,
  pageOf,
  protocolPages,
  type ProtocolListPage,
} from '../../../src/ui/pages/protocol.js';
import type { ListLevelName } from '../../../src/ui/pages/protocols/types.js';
import { RepositoriesPage, rowNames } from '../../../src/ui/pages/repositories.js';
import { UI_REPO_TYPES } from '../../../src/ui/repo-types.js';

const MAVEN_TYPE = UI_REPO_TYPES.find((type) => type.slug === 'maven')!;

const INTERACTIVE = 'a[href], button, input, select, textarea, [role="button"], [tabindex]';

interface RowFacts {
  role: string | null;
  tabindex: string | null;
  /** The stretched links of the row. */
  rowLinks: number;
  href: string | null;
  label: string | null;
  /** Interactive elements that sit inside another interactive element (must be none). */
  nested: string[];
  /** The row link covers the row's container, to the pixel. */
  covers: boolean;
  /** Elements the row still holds that behave like buttons but are not native ones. */
  fakeButtons: number;
}

/** What a row looks like to the accessibility tree; `row` is the element that carries the `-row-` test id. */
async function inspectRow(row: Locator): Promise<RowFacts> {
  return row.evaluate((element, selector) => {
    const links = Array.from(element.querySelectorAll<HTMLAnchorElement>('a.row-link'));
    const link = links[0];
    const nested = Array.from(element.querySelectorAll(selector))
      .filter((inner) => inner.parentElement?.closest(selector) !== null)
      .filter((inner) => element.contains(inner.parentElement?.closest(selector) ?? null))
      .map((inner) => inner.outerHTML.slice(0, 80));
    const box = element.getBoundingClientRect();
    const linkBox = link?.getBoundingClientRect();
    return {
      role: element.getAttribute('role'),
      tabindex: element.getAttribute('tabindex'),
      rowLinks: links.length,
      href: link?.getAttribute('href') ?? null,
      label: link?.getAttribute('aria-label') ?? null,
      nested,
      covers:
        linkBox !== undefined &&
        Math.abs(linkBox.width - box.width) <= 1 &&
        Math.abs(linkBox.height - box.height) <= (box.height > 0 ? 3 : 0),
      fakeButtons: element.querySelectorAll('[role="button"]').length,
    };
  }, INTERACTIVE);
}

async function expectRowIsLink(row: Locator, name: string): Promise<string> {
  await expect(row).toBeVisible();
  const facts = await inspectRow(row);
  expect(facts.role, 'the row is a container, not role=button').toBeNull();
  expect(facts.tabindex, 'the row itself is not a tab stop').toBeNull();
  expect(facts.fakeButtons, 'no role=button inside the row').toBe(0);
  expect(facts.rowLinks, 'exactly one stretched link').toBe(1);
  expect(facts.label, 'the link is named after the row').toBe(name);
  expect(facts.href, 'a real href').toBeTruthy();
  expect(facts.nested, 'no interactive element inside another').toEqual([]);
  expect(facts.covers, 'the link covers the whole row').toBe(true);
  await expect(row.locator('a.row-link')).toHaveAccessibleName(name);
  return facts.href ?? '';
}

/** Tab from `start` until `target` has focus (at most `max` presses): "Tab reaches the link". */
async function tabTo(page: Page, start: Locator, target: Locator, max = 40): Promise<void> {
  await start.focus();
  for (let press = 0; press < max; press++) {
    await page.keyboard.press('Tab');
    if (await target.evaluate((element) => element === document.activeElement)) {
      return;
    }
  }
  throw new Error(`Tab did not reach the row link within ${max} presses`);
}

const pathOf = (url: string): string => new URL(url, 'http://x').pathname;

test.describe('List rows are links', { tag: '@a11y' }, () => {
  test('A11Y-08: a repository row is a container with one link, reachable by Tab and opened by Enter', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const row = repos.list.row(repo.name);
    const href = await expectRowIsLink(row, repo.name);
    expect(href).toBe(`/${repo.name}`);
    // The dropdown toggle is a sibling of the link (a second, separate tab stop), not inside it.
    await expect(row.getByRole('button', { name: 'More options' })).toHaveCount(1);
    await expect(row.locator('a.row-link *')).toHaveCount(0);

    const link = row.locator('a.row-link');
    await tabTo(adminPage, repos.searchInput, link);
    await expect(link).toBeFocused();
    await adminPage.keyboard.press('Enter');
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
  });

  test('A11Y-08: a click anywhere on a repository row opens it, a modified click is left to the browser', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    // Wait for the filter to settle: a row that moves under the pointer swallows the click.
    await expect(repos.list.rows()).toHaveCount(1);
    const row = repos.list.row(repo.name);

    // What the page does with a click event once every handler has run: an SPA navigation prevents
    // the default; a ctrl/meta/shift/middle click must NOT, so the browser opens a new tab or window
    // itself (the headless "page" event of a background tab is too unreliable to wait for).
    await adminPage.evaluate(() => {
      const seen: boolean[] = [];
      (window as unknown as { __clicks: boolean[] }).__clicks = seen;
      window.addEventListener('click', (event) => {
        setTimeout(() => seen.push(event.defaultPrevented));
      });
    });
    const handled = () =>
      adminPage.evaluate(() => (window as unknown as { __clicks: boolean[] }).__clicks);

    // A ctrl-click on the row's centre (plain text under the link) stays on the list.
    await row.click({ modifiers: ['ControlOrMeta'] });
    await expect.poll(handled).toEqual([false]);
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await expect(row.locator('a.row-link')).toHaveAttribute('href', `/${repo.name}`);
    // The row link is a plain link (no target, no download): "open in a new tab" is the browser's.
    await expect(row.locator('a.row-link')).not.toHaveAttribute('target', /.+/);

    // A plain click on empty space in the row, near its left edge, opens the repository in this tab.
    await row.click({ position: { x: 3, y: 3 } });
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
    await expect.poll(async () => (await handled()).at(-1)).toBe(true);
  });

  test('A11Y-08: the row menu still works, and an open menu paints over the next row', async ({
    adminPage,
    seeder,
  }) => {
    await seeder.createRepo(RepoType.MAVEN);
    await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(seeder.runId);
    await expect(repos.list.rows()).toHaveCount(2);
    const [top] = await rowNames(repos.list.rows());

    const menu = await repos.list.openRowMenu(top);
    const item = menu.getByTestId('row-settings');
    await expect(item).toBeVisible();
    // The element at the item's centre is the item (or a child of it), not the next row's link.
    const hit = await item.evaluate((element) => {
      const box = element.getBoundingClientRect();
      const found = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
      return found !== null && element.contains(found);
    });
    expect(hit).toBe(true);
    // Opening the menu did not open the row.
    await expect(adminPage).toHaveURL(/\/repositories$/);

    await item.click({ timeout: 3_000 });
    await expect(adminPage).toHaveURL(new RegExp(`/${top}/settings$`));
  });

  test('A11Y-08: the dashboard recent-activity rows are links', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const dashboard = new DashboardPage(adminPage);
    await dashboard.goto();
    await expect(dashboard.recentActivity).toBeVisible();
    const row = dashboard.recentRow(repo.name);
    const href = await expectRowIsLink(row, repo.name);
    expect(pathOf(href)).toBe(`/${repo.name}`);
    await row.click();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
  });

  test('A11Y-09: the dashboard count rows are buttons, not javascript: anchors', async ({
    adminPage,
    seeder,
  }) => {
    await seeder.createRepo(RepoType.MAVEN);
    const dashboard = new DashboardPage(adminPage);
    await dashboard.goto();
    await expect(dashboard.recentActivity).toBeVisible();
    await expect(adminPage.locator('a[href^="javascript:"]')).toHaveCount(0);
    const row = dashboard.countRow(MAVEN_TYPE);
    await expect(row).toHaveJSProperty('tagName', 'BUTTON');
    await row.click();
    await expect(adminPage).toHaveURL(/\/repositories$/);
  });

  // The list levels that have clickable rows, per protocol (the descriptor's `rowOpens`).
  const LEVELS: readonly ListLevelName[] = ['list', 'sublist', 'versions', 'manifests'];
  for (const protocol of Object.keys(DESCRIPTORS) as PackageProtocol[]) {
    const descriptor = DESCRIPTORS[protocol];
    const repoType = RepoType[protocol.toUpperCase() as keyof typeof RepoType];
    const clickable = LEVELS.filter((level) => descriptor.levels[level]?.rowOpens);

    for (const level of clickable) {
      test(`A11Y-10: ${protocol} ${level} rows are containers with one named link`, async ({
        adminPage,
        seeder,
        seedPackage,
      }) => {
        const repo = await seeder.createRepo(repoType);
        const pkg = await seedPackage(repo);
        const listPage = pageOf(adminPage, descriptor, level, repo.name, pkg) as ProtocolListPage;
        await listPage.goto();
        const row = listPage.row(pkg);
        const href = await expectRowIsLink(row, listPage.keyOf(pkg));

        // Enter on the focused link opens the row's page, like a click on the row would.
        await row.locator('a.row-link').focus();
        await adminPage.keyboard.press('Enter');
        await expect(adminPage).toHaveURL((url) => url.pathname === pathOf(href));
      });
    }
  }

  test('A11Y-10: the maven file browser has real buttons, no javascript: anchors and no key that navigates but Enter and Space', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    await seedPackage(repo);
    const pages = protocolPages(adminPage, DESCRIPTORS.maven, repo.name);
    await adminPage.goto(pages.extraPath('browser'));
    const grid = adminPage.getByTestId('maven-browser-grid');
    await expect(grid).toBeVisible();

    await expect(adminPage.locator('a[href^="javascript:"]')).toHaveCount(0);
    await expect(adminPage.getByTestId('maven-browser-open-native')).toHaveJSProperty(
      'tagName',
      'BUTTON',
    );
    const open = grid.getByTestId('row-open').first();
    await expect(open).toHaveJSProperty('tagName', 'BUTTON');
    await expect(open).toHaveAccessibleName(/^Open /);

    // Tab moves on without opening anything; Enter opens the directory.
    const crumbs = adminPage.getByTestId('maven-browser-path');
    const before = ((await crumbs.textContent()) ?? '').replace(/\s+/g, ' ').trim();
    await open.focus();
    await adminPage.keyboard.press('Tab');
    await expect(crumbs).toHaveText(before);
    await open.focus();
    await adminPage.keyboard.press('Enter');
    await expect(crumbs).not.toHaveText(before);
  });
});
