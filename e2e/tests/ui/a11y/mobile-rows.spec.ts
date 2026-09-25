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
 * A11Y-12: the list rows as cards at a phone's width (390x844, a touch screen), RPS-1315.
 *
 * Below `lg` every list swaps its table rows for cards that share the row markup (a `row-link-host`
 * with one stretched link and an action menu that is a sibling of the link). The desktop tests click
 * rows with a mouse; these TAP: the repository list, a package list and a version list (PyPI stands for
 * the protocol lists, all of which use the same card markup, and A11Y-11 taps the centre of one card per
 * protocol level). For each one, a tap on a card opens it, the card's menu opens without opening the
 * card, its items are on top of whatever follows the card (the next card's stretched link), an item does
 * its job, and opening a second card's menu closes the first. The open menu is scanned with axe too.
 */
import type { Locator } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { scanPage } from '../../../src/ui/a11y.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, pageOf, type ProtocolListPage } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';

const PHONE = { width: 390, height: 844 };

const pathOf = (url: string): string => new URL(url, 'http://x').pathname;

/** The card's stretched link target. */
const hrefOf = (card: Locator): Promise<string> =>
  card.locator('a.row-link').evaluate((link) => link.getAttribute('href') ?? '');

const menuOf = (card: Locator): Locator =>
  card.getByTestId('row-menu').getByTestId('dropdown-menu');
const toggleOf = (card: Locator): Locator =>
  card.getByTestId('row-menu').getByTestId('dropdown-toggle');

/**
 * The cards are taller than their menus, so no menu overhangs the next card by itself. This makes it: the
 * next card is pulled up (a negative margin on the card that follows) until it lies under the lower half
 * of the open menu, which is where a menu of a short card (or of the last card of a page that scrolls)
 * ends up in real life. `menu` belongs to the card whose next sibling is pulled.
 */
async function pullNextCardUnder(menu: Locator): Promise<() => Promise<void>> {
  const pulled = await menu.evaluate((element) => {
    const container = element.closest('[data-testid$="-cards"]');
    let card: Element | null = element;
    while (card && card.parentElement !== container) {
      card = card.parentElement;
    }
    const next = card?.nextElementSibling as HTMLElement | null;
    if (!next) {
      return false;
    }
    // Pull it up so that it starts a quarter of the menu's height above the menu's middle.
    const menuBox = element.getBoundingClientRect();
    const target = menuBox.bottom - menuBox.height * 0.75;
    next.style.marginTop = `${Math.round(target - next.getBoundingClientRect().top)}px`;
    // The next card now starts above the middle of the menu.
    return next.getBoundingClientRect().top < menuBox.bottom - menuBox.height / 2;
  });
  expect(pulled, 'the next card lies under the lower half of the menu').toBe(true);
  await menu.scrollIntoViewIfNeeded();
  return () =>
    menu.evaluate((element) => {
      const container = element.closest('[data-testid$="-cards"]');
      let card: Element | null = element;
      while (card && card.parentElement !== container) {
        card = card.parentElement;
      }
      (card?.nextElementSibling as HTMLElement | null)?.style.removeProperty('margin-top');
    });
}

/**
 * Every part of the open menu that a finger can reach is the menu: at the middle of each item and near
 * the menu's four corners the topmost element is inside the menu, not the next card's link or content.
 */
async function expectMenuOnTop(menu: Locator): Promise<void> {
  await menu.scrollIntoViewIfNeeded();
  const covered = await menu.evaluate((element) => {
    const box = element.getBoundingClientRect();
    const points = [
      ...Array.from(element.querySelectorAll('[role="menuitem"]')).map((item) => {
        const itemBox = item.getBoundingClientRect();
        return [itemBox.x + itemBox.width / 2, itemBox.y + itemBox.height / 2];
      }),
      [box.x + 3, box.y + 3],
      [box.right - 3, box.y + 3],
      [box.x + 3, box.bottom - 3],
      [box.right - 3, box.bottom - 3],
    ];
    return points
      .filter(([x, y]) => {
        const hit = document.elementFromPoint(x, y);
        return hit === null || !element.contains(hit);
      })
      .map(([x, y]) => `${Math.round(x)},${Math.round(y)}`);
  });
  expect(covered, 'points of the open menu that another element covers').toEqual([]);
}

test.describe('Phone-width cards (390x844)', { tag: '@a11y' }, () => {
  test.use({ viewport: PHONE, hasTouch: true });

  test('A11Y-12: repository cards: tap opens, the menu paints over the next card and Settings works', async ({
    adminPage,
    seeder,
  }, testInfo) => {
    const first = await seeder.createRepo(RepoType.MAVEN);
    const second = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(seeder.runId);
    await expect(repos.cards.locator('[data-testid^="repo-card-"]')).toHaveCount(2);
    // The newest first: `second` is above `first`.
    const top = repos.card(second.name);
    const next = repos.card(first.name);
    await expect(top).toBeVisible();
    await expect(next).toBeVisible();
    expect((await top.boundingBox())!.y).toBeLessThan((await next.boundingBox())!.y);

    // The menu opens on a tap of its toggle and does not open the card.
    await toggleOf(top).tap();
    const menu = menuOf(top);
    await expect(menu).toBeVisible();
    await expect(adminPage).toHaveURL(/\/repositories$/);
    await expect(menu.getByTestId('row-settings')).toBeVisible();
    const release = await pullNextCardUnder(menu);
    await expectMenuOnTop(menu);
    await release();
    await scanPage(adminPage, testInfo, 'repositories-mobile-menu-open');

    // A second card's menu closes the first (one menu at a time).
    await toggleOf(next).tap();
    await expect(menuOf(next)).toBeVisible();
    await expect(menu).toHaveCount(0);
    await expectMenuOnTop(menuOf(next));

    // The item of the first card does its job.
    await toggleOf(top).tap();
    await menu.getByTestId('row-settings').tap();
    await expect(adminPage).toHaveURL(new RegExp(`/${second.name}/settings$`));
  });

  test('A11Y-12: a tap on a repository card opens the repository', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const repos = new RepositoriesPage(adminPage);
    await repos.goto();
    await repos.search(repo.name);
    const card = repos.card(repo.name);
    await expect(card).toBeVisible();
    await expect(card.locator('a.row-link')).toHaveAccessibleName(repo.name);

    await card.tap();

    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}$`));
  });

  test('A11Y-12: package cards: tap opens, the menu paints over the next card and Delete asks first', async ({
    adminPage,
    seeder,
    seedPackages,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const [a, b] = await seedPackages(repo, 2);
    const list = pageOf(adminPage, DESCRIPTORS.pypi, 'list', repo.name, a) as ProtocolListPage;
    await list.goto();
    const cardA = list.card(a);
    const cardB = list.card(b);
    await expect(cardA).toBeVisible();
    await expect(cardB).toBeVisible();
    // Whichever is listed first is the upper card.
    const cards = list.cards.locator(`[data-testid^="${list.prefix}-card-"]`);
    await expect(cards).toHaveCount(2);
    const [upper, lower] = [cards.nth(0), cards.nth(1)];

    await toggleOf(upper).tap();
    await expect(menuOf(upper)).toBeVisible();
    await expect(adminPage).toHaveURL((url) => url.pathname === `/${repo.name}`);
    const release = await pullNextCardUnder(menuOf(upper));
    await expectMenuOnTop(menuOf(upper));
    await release();
    await scanPage(adminPage, testInfo, 'pypi-list-mobile-menu-open');

    await toggleOf(lower).tap();
    await expect(menuOf(lower)).toBeVisible();
    await expect(menuOf(upper)).toHaveCount(0);

    // Delete asks first: the dialog opens, Cancel leaves the package where it is.
    await menuOf(lower).getByTestId('row-delete').tap();
    await list.dangerModal.expectOpen('Delete Package');
    await list.dangerModal.cancelButton.tap();
    await list.dangerModal.expectClosed();
    await expect(lower).toBeVisible();

    // A tap on the card itself opens the package's latest version.
    const href = await hrefOf(upper);
    await upper.tap();
    await expect(adminPage).toHaveURL((url) => url.pathname === pathOf(href));
  });

  test('A11Y-12: version cards: tap opens, the menu paints over the next card and Delete asks first', async ({
    adminPage,
    seeder,
    seedVersions,
  }, testInfo) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const [older, newer] = await seedVersions(repo, ['1.0.0', '1.1.0']);
    const list = pageOf(
      adminPage,
      DESCRIPTORS.pypi,
      'versions',
      repo.name,
      older,
    ) as ProtocolListPage;
    await list.goto();
    const cardOlder = list.card(older);
    const cardNewer = list.card(newer);
    await expect(cardOlder).toBeVisible();
    await expect(cardNewer).toBeVisible();
    const cards = list.cards.locator(`[data-testid^="${list.prefix}-card-"]`);
    await expect(cards).toHaveCount(2);
    const [upper, lower] = [cards.nth(0), cards.nth(1)];

    await toggleOf(upper).tap();
    await expect(menuOf(upper)).toBeVisible();
    const release = await pullNextCardUnder(menuOf(upper));
    await expectMenuOnTop(menuOf(upper));
    await release();
    await scanPage(adminPage, testInfo, 'pypi-versions-mobile-menu-open');

    await toggleOf(lower).tap();
    await expect(menuOf(lower)).toBeVisible();
    await expect(menuOf(upper)).toHaveCount(0);

    await menuOf(lower).getByTestId('row-delete').tap();
    await list.dangerModal.expectOpen('Delete Release');
    await list.dangerModal.cancelButton.tap();
    await list.dangerModal.expectClosed();
    await expect(lower).toBeVisible();

    const href = await hrefOf(upper);
    await upper.tap();
    await expect(adminPage).toHaveURL((url) => url.pathname === pathOf(href));
  });
});
