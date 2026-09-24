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
 * The package-page scenario template (RPS-1256), the UI counterpart of `scenarios/loop.ts`: ONE
 * function, `registerPackageScenarios(descriptor, options?)`, registers the scenarios PKG-<proto>-01..06
 * for the protocol the descriptor (`pages/protocols/<proto>.ts`) describes, and a spec is one line:
 *
 * ```ts
 * registerPackageScenarios(DESCRIPTORS.maven, { knownFailures: { '05-mobile-list': 'RPS-1262' } });
 * ```
 *
 * Everything that differs between protocols is DATA in the descriptor (routes, row keys, search terms,
 * sort options, dialog titles, where a detail delete lands, whether the last version removes the
 * package, which levels have a search box, a pager, mobile cards, a delete, where the repo URL shows,
 * the Configure modal's texts), so the template never asks "which protocol is this". A protocol that
 * lacks a feature registers the scenario that asserts its ABSENCE (`search: null` -> "has no search
 * box"), never a skipped one.
 *
 * | ID  | Scenario                                                                              | Tag    |
 * | --- | ------------------------------------------------------------------------------------- | ------ |
 * | 01  | seeded package on the list; row -> versions -> detail; install snippet; copy button   | @smoke |
 * | 02  | search (list and versions), sort, pagination with more than one page of packages      |        |
 * | 03  | empty repo shows the empty list                                                       |        |
 * | 04  | delete: a version from the detail page, the last version, list row, versions row, sublist row |  |
 * | 05  | USER: no Settings, no row dropdown, no Delete, on desktop rows and on every mobile card list |  |
 * | 06  | Configure modal (repo name, password placeholder) and its deploy-token variant        |        |
 *
 * Product bugs are pinned, not skipped: `options.knownFailures` maps a step key (`PackageScenarioKey`)
 * to its reason and Jira key, and that step runs under `test.fail`, so it must still fail and turns red
 * the day the bug is fixed. Titles carry the reason.
 *
 * The scenarios assert what the descriptor RECORDS, including recorded quirks (RPS-1288), never a
 * wished-for behaviour: a quirk that is a bug is a `knownFailures` entry, a quirk that is a product
 * decision is asserted as it is.
 */
import type { Locator, Page } from '@playwright/test';

import { RepoType } from '../api/panel-api.js';
import { env } from '../env.js';
import type { PackageRef, SeededPackage } from '../seed/packages.js';
import { expect, test } from './package-fixtures.js';
import {
  type DeleteAffordance,
  type LevelName,
  pageOf,
  type ProtocolDescriptor,
  type ProtocolListPage,
  type ProtocolPage,
  protocolPages,
  VersionDetailPage,
  type VersionsPage,
} from './pages/protocol.js';
import type { ConfigureModal, ListLevelName } from './pages/protocols/types.js';
import { RepoSettingsPage } from './pages/repo-settings/page.js';
import { loginSession } from './session.js';

/** The steps a `knownFailures` entry may name. */
export type PackageScenarioKey =
  | '01'
  | '02-search'
  | '02-versions-search'
  | '02-sort'
  | '02-pagination'
  | '03'
  | '04-detail'
  | '04-last-version'
  | '04-list-row'
  | '04-versions-row'
  | '04-sublist-row'
  | '05-desktop'
  | '05-mobile-list'
  | '05-mobile-sublist'
  | '05-mobile-versions'
  | '06-configure'
  | '06-deploy-token';

export interface PackageScenarioOptions {
  /** Steps that fail on the product today, by key, with the Jira key and a reason: `'RPS-1262: ...'`. */
  knownFailures?: Readonly<Partial<Record<PackageScenarioKey, string>>>;
}

const MOBILE_VIEWPORT = { width: 390, height: 844 };
const DEFAULT_CONFIGURE: ConfigureModal = {
  title: 'Configuration',
  deployTokenTitle: 'Deploy Token Usage',
  contains: (repoName) => [repoName],
  passwordMarker: 'YOUR_PASSWORD',
  deployTokenMarker: 'YOUR_DEPLOY_TOKEN',
};

function repoTypeOf(descriptor: ProtocolDescriptor): RepoType {
  return RepoType[descriptor.protocol.toUpperCase() as keyof typeof RepoType];
}

export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * A URL ending in `path` (query string included), whatever the base URL is. A version row's own link
 * appends `#security` (cargo, Go, ...), so an optional fragment is accepted.
 */
function endsWith(path: string): RegExp {
  return new RegExp(`${escapeRegExp(path)}(#[\\w-]+)?$`);
}

/** A page a row click opened, narrowed to the version detail (a wrong descriptor fails here, loudly). */
export function asDetailPage(opened: ProtocolPage): VersionDetailPage {
  if (!(opened instanceof VersionDetailPage)) {
    throw new Error(`expected the version detail page, got ${opened.constructor.name}`);
  }
  return opened;
}

/** The raw keys of the desktop rows on screen, in DOM order (`pkg-list-row-<key>` minus its prefix). */
export async function rowKeys(list: ProtocolListPage): Promise<string[]> {
  const prefix = `${list.prefix}-row-`;
  const ids = await list
    .rows()
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid') ?? ''));
  return ids.map((id) => id.slice(prefix.length));
}

/** The pager button of page `n` (a locator: `Pagination.page(n)` trips `playwright/prefer-locator`). */
function pagerButton(list: ProtocolListPage, n: number): Locator {
  return list.pagination.root.getByTestId(`pagination-page-${n}`);
}

/** The Configure modal every list page opens (`config-modal-*`). */
export class ConfigModal {
  readonly root: Locator;
  readonly title: Locator;
  readonly closeButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('config-modal');
    this.title = page.getByTestId('config-modal-title');
    this.closeButton = page.getByTestId('config-modal-close');
  }
}

/** A row click (or any navigation) landed on the page the descriptor says: its URL and its content. */
async function expectOnPage(page: Page, opened: ProtocolPage, target: PackageRef): Promise<void> {
  await expect(page).toHaveURL(endsWith(opened.path()));
  if (opened instanceof VersionDetailPage) {
    await opened.expectLoaded();
    await expect(opened.root).toBeVisible();
  } else {
    await opened.expectLoaded();
    await opened.expectRow(target);
  }
}

/** The page a delete lands on, when the descriptor has verified it. */
async function expectLandedOn(
  page: Page,
  descriptor: ProtocolDescriptor,
  affordance: DeleteAffordance & { landsOn: LevelName | 'unverified' },
  repoName: string,
  target: PackageRef,
): Promise<void> {
  if (affordance.landsOn === 'unverified') {
    return;
  }
  const lands = pageOf(page, descriptor, affordance.landsOn, repoName, target);
  await expect(page).toHaveURL(endsWith(lands.path()));
}

function sorted(keys: readonly string[]): string[] {
  return [...keys].sort();
}

/** Registers PKG-<proto>-01..06 for one protocol. See the file comment. */
export function registerPackageScenarios(
  descriptor: ProtocolDescriptor,
  options: PackageScenarioOptions = {},
): void {
  const protocol = descriptor.protocol;
  const type = repoTypeOf(descriptor);
  const { list: listLevel, sublist, versions: versionsLevel, detail } = descriptor.levels;
  const configure = descriptor.configure ?? {
    ...DEFAULT_CONFIGURE,
    title: `${descriptor.label} ${DEFAULT_CONFIGURE.title}`,
  };

  /** `PKG-maven-02 ...`, with the pinned bug's reason appended when this step is a known failure. */
  const title = (n: string, text: string, key?: PackageScenarioKey): string => {
    const reason = key ? options.knownFailures?.[key] : undefined;
    return `PKG-${protocol}-${n} ${text}${reason ? ` [known failure: ${reason}]` : ''}`;
  };
  /** Runs first in a step's body: a known failure must fail, and passing is an error. */
  const pin = (key: PackageScenarioKey): void => {
    const reason = options.knownFailures?.[key];
    if (reason) {
      test.fail(true, reason);
    }
  };

  test.describe(`${descriptor.label} package pages`, { tag: '@packages' }, () => {
    // 01 -----------------------------------------------------------------------------------------
    test(
      title(
        '01',
        'a seeded package shows on the list, opens its versions and detail, and the install snippet copies',
        '01',
      ),
      { tag: ['@smoke'] },
      async ({ adminPage, context, seeder, seedPackage }) => {
        pin('01');
        await context.grantPermissions(['clipboard-read', 'clipboard-write']);
        const repo = await seeder.createRepo(type);
        const pkg = await seedPackage(repo);
        const pages = protocolPages(adminPage, descriptor, repo.name);

        const list = pages.list();
        await list.goto();
        await list.expectRow(pkg);

        // The row click goes where the descriptor says (the latest detail, the scope, the tags, ...).
        await expectOnPage(adminPage, await list.openRow(pkg), pkg);

        // Versions: through the row's own link where it has one, through the row itself (docker) otherwise.
        await list.goto();
        let versions: VersionsPage;
        if (listLevel.rowLinks.versions) {
          versions = await list.openLink(pkg, 'versions');
        } else if (listLevel.rowOpens === 'versions') {
          versions = (await list.openRow(pkg)) as VersionsPage;
        } else {
          throw new Error(`${protocol}: the descriptor gives no way from the list to the versions`);
        }
        await expectOnPage(adminPage, versions, pkg);

        // From the version's row to its detail.
        const opened = asDetailPage(await versions.openRow(pkg));
        await expectOnPage(adminPage, opened, pkg);
        await expect(opened.install).toBeVisible();

        // The install snippet is the descriptor's, and the stack's repo URL shows where it says.
        const parts = detail.installContains(repo.name, pkg);
        for (const part of parts) {
          await expect(opened.installText).toContainText(part);
        }
        const host = new URL(env.repoBaseUrl).host;
        if (detail.repoUrlIn === 'install') {
          await expect(opened.installText).toContainText(host);
        } else if (detail.repoUrlIn.startsWith('snippet:')) {
          await expect(opened.snippet(detail.repoUrlIn.slice('snippet:'.length))).toContainText(
            host,
          );
        }

        // The copy button says so and puts the snippet on the clipboard.
        await opened.copyButton.click();
        await expect(opened.copyButton).toHaveAttribute('data-copied', 'true');
        const clipboard = await adminPage.evaluate(() => navigator.clipboard.readText());
        for (const part of parts) {
          expect(clipboard).toContain(part);
        }
      },
    );

    // 02 -----------------------------------------------------------------------------------------
    if (listLevel.search) {
      const search = listLevel.search;
      test(
        title('02', `search finds a package by its ${search.placeholder}`, '02-search'),
        async ({ adminPage, seeder, seedPackages }) => {
          pin('02-search');
          const repo = await seeder.createRepo(type);
          const seeded = await seedPackages(repo, 3);
          const list = protocolPages(adminPage, descriptor, repo.name).list();
          await list.goto();
          await expect(list.searchInput).toHaveAttribute('placeholder', search.placeholder);
          await expect(list.rows()).toHaveCount(3);

          await list.search(`zzz-no-such-${seeder.runId}`);
          await expect(list.emptyList.root).toBeVisible();
          await expect(list.rows()).toHaveCount(0);

          // What the search matches is the descriptor's term, which is not always the row key.
          const target = seeded[1];
          const term = search.term(target).toLowerCase();
          const expected = seeded
            .filter((pkg) => search.term(pkg).toLowerCase().includes(term))
            .map((pkg) => list.keyOf(pkg));
          await list.searchFor(target);
          await list.expectRow(target);
          await expect.poll(async () => sorted(await rowKeys(list))).toEqual(sorted(expected));

          await list.search('');
          await expect(list.rows()).toHaveCount(3);
        },
      );
    } else {
      test(
        title('02', 'the list has no search box', '02-search'),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('02-search');
          const repo = await seeder.createRepo(type);
          await seedPackage(repo);
          const list = protocolPages(adminPage, descriptor, repo.name).list();
          await list.goto();
          await expect(list.searchBox).toHaveCount(0);
        },
      );
    }

    if (versionsLevel.search) {
      const search = versionsLevel.search;
      test(
        title('02', `search finds a version by its ${search.placeholder}`, '02-versions-search'),
        async ({ adminPage, seeder, seedVersions }) => {
          pin('02-versions-search');
          const repo = await seeder.createRepo(type);
          const seeded = await seedVersions(repo, ['1.0.0', '2.0.0', '3.0.0']);
          const versions = protocolPages(adminPage, descriptor, repo.name).versions(seeded[0]);
          await versions.goto();
          await expect(versions.searchInput).toHaveAttribute('placeholder', search.placeholder);
          await expect(versions.rows()).toHaveCount(3);

          await versions.search(`zzz-no-such-${seeder.runId}`);
          await expect(versions.emptyList.root).toBeVisible();

          const target = seeded[1];
          const term = search.term(target);
          const expected = seeded
            .filter((pkg) => search.term(pkg).includes(term))
            .map((pkg) => versions.keyOf(pkg));
          await versions.searchFor(target);
          await versions.expectRow(target);
          await expect.poll(async () => sorted(await rowKeys(versions))).toEqual(sorted(expected));
        },
      );
    } else {
      test(
        title('02', 'the versions page has no search box', '02-versions-search'),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('02-versions-search');
          const repo = await seeder.createRepo(type);
          const pkg = await seedPackage(repo);
          const versions = protocolPages(adminPage, descriptor, repo.name).versions(pkg);
          await versions.goto();
          await expect(versions.searchBox).toHaveCount(0);
        },
      );
    }

    if (listLevel.sort) {
      const options_ = listLevel.sort;
      test(
        title('02', `sort offers ${options_.join(', ')} and orders the list by each`, '02-sort'),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('02-sort');
          const repo = await seeder.createRepo(type);
          // One after the other, so "Oldest" and "Newest" have an order to show.
          const seeded: SeededPackage[] = [];
          for (const index of [1, 2, 3]) {
            seeded.push(await seedPackage(repo, { index }));
          }
          const list = protocolPages(adminPage, descriptor, repo.name).list();
          await list.goto();
          const keys = seeded.map((pkg) => list.keyOf(pkg));
          const expected: Record<string, string[]> = {
            Newest: [...keys].reverse(),
            Oldest: keys,
            'Name (A-Z)': sorted(keys),
            'Name (Z-A)': sorted(keys).reverse(),
          };
          const order = (option: string): string[] => {
            const keysOf = expected[option];
            if (!keysOf) {
              throw new Error(`${protocol}: no expected order for the sort option "${option}"`);
            }
            return keysOf;
          };

          // The first option is the default.
          await expect.poll(() => rowKeys(list)).toEqual(order(options_[0]));
          for (const option of [...options_.slice(1), options_[0]]) {
            await list.sortBy(option);
            await expect.poll(() => rowKeys(list)).toEqual(order(option));
          }
        },
      );
    } else {
      test(
        title('02', 'the list has no sort', '02-sort'),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('02-sort');
          const repo = await seeder.createRepo(type);
          await seedPackage(repo);
          const list = protocolPages(adminPage, descriptor, repo.name).list();
          await list.goto();
          await expect(list.sort).toHaveCount(0);
        },
      );
    }

    test(
      title(
        '02',
        listLevel.pagination
          ? 'twelve packages page at ten per page'
          : 'twelve packages all show on one page, there is no pager',
        '02-pagination',
      ),
      async ({ adminPage, seeder, seedPackage }) => {
        pin('02-pagination');
        const repo = await seeder.createRepo(type);
        // One after the other, not `seedPackages` (four at a time): packages published in the same
        // instant tie on the sort key, and the pager then has no stable order to cut pages from
        // (embedded H2 showed one package on both pages and another on none; RPS-1298, not fixed here).
        const seeded: SeededPackage[] = [];
        for (let index = 1; index <= 12; index += 1) {
          seeded.push(await seedPackage(repo, { index }));
        }
        const list = protocolPages(adminPage, descriptor, repo.name).list();
        await list.goto();
        const all = sorted(seeded.map((pkg) => list.keyOf(pkg)));

        if (!listLevel.pagination) {
          await expect(list.rows()).toHaveCount(12);
          await expect(list.pagination.root).toHaveCount(0);
          return;
        }

        await expect(list.rows()).toHaveCount(10);
        await expect(list.pagination.root).toBeVisible();
        const firstPage = await rowKeys(list);
        await pagerButton(list, 2).click();
        await expect(list.rows()).toHaveCount(2);
        const secondPage = await rowKeys(list);
        // Two pages that together are exactly the twelve, with nothing on both.
        expect(sorted([...firstPage, ...secondPage])).toEqual(all);

        await list.pagination.prev.click();
        await expect(list.rows()).toHaveCount(10);
        expect(await rowKeys(list)).toEqual(firstPage);
        await list.pagination.next.click();
        await expect(list.rows()).toHaveCount(2);
        expect(await rowKeys(list)).toEqual(secondPage);
      },
    );

    // 03 -----------------------------------------------------------------------------------------
    test(
      title('03', 'a repository without packages shows the empty list', '03'),
      async ({ adminPage, seeder }) => {
        pin('03');
        const repo = await seeder.createRepo(type);
        const list = protocolPages(adminPage, descriptor, repo.name).list();
        await list.goto();
        await expect(list.emptyList.root).toBeVisible();
        await expect(list.rows()).toHaveCount(0);
        // The page is still usable: the toolbar's buttons are there for an admin.
        await expect(list.configureButton).toBeVisible();
        await expect(list.settingsButton).toBeVisible();
      },
    );

    // 04 -----------------------------------------------------------------------------------------
    const detailDelete = detail.delete;
    if (detailDelete) {
      test(
        title(
          '04',
          'deleting a version from its detail page toasts and lands where the panel says',
          '04-detail',
        ),
        async ({ adminPage, seeder, seedVersions }) => {
          pin('04-detail');
          const repo = await seeder.createRepo(type);
          const [first, second] = await seedVersions(repo, ['1.0.0', '2.0.0']);
          const pages = protocolPages(adminPage, descriptor, repo.name);

          const page = pages.detail(first);
          await page.goto();
          await page.delete(); // the dialog title and the toast are the descriptor's
          await expectLandedOn(adminPage, descriptor, detailDelete, repo.name, first);

          // Only that version is gone.
          const versions = pages.versions(second);
          await versions.goto();
          await versions.expectNoRow(first);
          await versions.expectRow(second);
        },
      );

      test(
        title(
          '04',
          descriptor.lastVersionRemovesPackage === false
            ? 'deleting the last version keeps the package listed'
            : 'deleting the last version removes the package',
          '04-last-version',
        ),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('04-last-version');
          const repo = await seeder.createRepo(type);
          const pkg = await seedPackage(repo);
          const pages = protocolPages(adminPage, descriptor, repo.name);

          const page = pages.detail(pkg);
          await page.goto();
          await page.delete();
          await expectLandedOn(
            adminPage,
            descriptor,
            { ...detailDelete, landsOn: detailDelete.landsOnLast ?? detailDelete.landsOn },
            repo.name,
            pkg,
          );

          const list = pages.list();
          await list.goto();
          if (descriptor.lastVersionRemovesPackage === true) {
            await expect(list.emptyList.root).toBeVisible();
            await list.expectNoRow(pkg);
          } else if (descriptor.lastVersionRemovesPackage === false) {
            // Recorded (RPS-1288): the package stays listed with nothing in it.
            await list.expectRow(pkg);
          } else {
            test.skip(
              true,
              `${protocol}: lastVersionRemovesPackage is unverified in the descriptor`,
            );
          }
        },
      );
    }

    const listDelete = listLevel.rowDelete;
    if (listDelete) {
      test(
        title(
          '04',
          `deleting a package from the list (${listDelete.dialogTitle}) removes only that one`,
          '04-list-row',
        ),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('04-list-row');
          const repo = await seeder.createRepo(type);
          const first = await seedPackage(repo, { index: 1 });
          const second = await seedPackage(repo, { index: 2 });
          const list = protocolPages(adminPage, descriptor, repo.name).list();
          await list.goto();
          await list.expectRow(first);
          await list.expectRow(second);

          // Cancelling deletes nothing.
          const dialog = await list.openDeleteDialog(second);
          await dialog.cancel();
          await dialog.expectClosed();
          await list.expectRow(second);

          await list.deleteRow(second); // menu, Delete, confirm, the descriptor's dialog title and toast
          await list.expectNoRow(second);
          await list.expectRow(first);

          // The server agrees, not just the page.
          await list.goto();
          await list.expectRow(first);
          await list.expectNoRow(second);
        },
      );
    }

    const versionsDelete = versionsLevel.rowDelete;
    if (versionsDelete) {
      test(
        title(
          '04',
          `deleting a version from the versions page (${versionsDelete.dialogTitle}) removes only that one`,
          '04-versions-row',
        ),
        async ({ adminPage, seeder, seedVersions }) => {
          pin('04-versions-row');
          const repo = await seeder.createRepo(type);
          const [first, second] = await seedVersions(repo, ['1.0.0', '2.0.0']);
          const versions = protocolPages(adminPage, descriptor, repo.name).versions(first);
          await versions.goto();
          await versions.expectRow(first);
          await versions.expectRow(second);

          await versions.deleteRow(first);
          await versions.expectNoRow(first);
          await versions.expectRow(second);

          await versions.goto();
          await versions.expectNoRow(first);
          await versions.expectRow(second);
        },
      );
    }

    if (sublist?.rowDelete && sublist.siblingName) {
      const sublistDelete = sublist.rowDelete;
      const siblingName = sublist.siblingName;
      test(
        title(
          '04',
          `deleting from the group page (${sublistDelete.dialogTitle}) removes only that one`,
          '04-sublist-row',
        ),
        async ({ adminPage, seeder, seedPackage }) => {
          pin('04-sublist-row');
          const repo = await seeder.createRepo(type);
          const first = await seedPackage(repo);
          const sibling = await seedPackage(repo, { name: siblingName(first, 1) });
          const group = protocolPages(adminPage, descriptor, repo.name).sublist(first);
          await group.goto();
          await group.expectRow(first);
          await group.expectRow(sibling);

          await group.deleteRow(first);
          await group.expectNoRow(first);
          await group.expectRow(sibling);
        },
      );
    }

    // 05 -----------------------------------------------------------------------------------------
    /** The levels that have a delete and a mobile card list, in the order the user reaches them. */
    const cardLevels = (['list', 'sublist', 'versions'] as const).filter((name) => {
      const level = descriptor.levels[name];
      return level?.rowDelete && level.mobileCards;
    });

    test(
      title(
        '05',
        'a USER sees no Settings, no row dropdown and no Delete, an admin does',
        '05-desktop',
      ),
      async ({ adminPage, userPage, seeder, seedPackage }) => {
        pin('05-desktop');
        const repo = await seeder.createRepo(type);
        const pkg = await seedPackage(repo);
        const admin = protocolPages(adminPage, descriptor, repo.name);
        const user = protocolPages(userPage, descriptor, repo.name);
        const names = (['list', 'sublist', 'versions'] as const).filter(
          (name) => descriptor.levels[name],
        );
        const at = (pages: typeof admin, name: ListLevelName): ProtocolListPage =>
          name === 'list'
            ? pages.list()
            : name === 'sublist'
              ? pages.sublist(pkg)
              : pages.versions(pkg);

        for (const name of names) {
          // The control first: the admin gets all of it on the very same page, so "absent" means removed.
          const asAdmin = at(admin, name);
          await asAdmin.goto();
          await asAdmin.expectRow(pkg);
          await expect(asAdmin.settingsButton).toBeVisible();
          if (descriptor.levels[name]?.rowDelete) {
            await expect(asAdmin.inRow(pkg, 'row-menu')).toBeVisible();
          }

          const asUser = at(user, name);
          await asUser.goto();
          await asUser.expectRow(pkg);
          await expect(asUser.settingsButton).toHaveCount(0);
          await expect(asUser.inRow(pkg, 'row-menu')).toHaveCount(0);
          // Reading and configuring stay available.
          await expect(asUser.configureButton).toBeVisible();
        }

        // The detail page: the Delete button is the admin's alone.
        const adminDetail = admin.detail(pkg);
        await adminDetail.goto();
        if (detail.delete) {
          await expect(adminDetail.deleteButton).toBeVisible();
        }
        const userDetail = user.detail(pkg);
        await userDetail.goto();
        await expect(userDetail.install).toBeVisible();
        await expect(userDetail.deleteButton).toHaveCount(0);
      },
    );

    for (const name of cardLevels) {
      const key = `05-mobile-${name}` as PackageScenarioKey;
      test(
        title('05', `a USER sees no Delete on the mobile ${name} cards, an admin does`, key),
        async ({ openUiPage, adminSession, seededUser, seeder, seedVersions }) => {
          pin(key);
          const repo = await seeder.createRepo(type);
          // Two versions, so the versions page has more than one card to check.
          const [pkg] = await seedVersions(repo, ['1.0.0', '2.0.0']);
          const userSession = await loginSession(seededUser.username, seededUser.password);
          const asAdmin = await openUiPage({ session: adminSession, viewport: MOBILE_VIEWPORT });
          const asUser = await openUiPage({ session: userSession, viewport: MOBILE_VIEWPORT });
          const at = (page: Page): ProtocolListPage => {
            const pages = protocolPages(page, descriptor, repo.name);
            return name === 'list'
              ? pages.list()
              : name === 'sublist'
                ? pages.sublist(pkg)
                : pages.versions(pkg);
          };

          const adminList = at(asAdmin);
          await adminList.goto();
          await expect(adminList.card(pkg)).toBeVisible();
          await expect(adminList.card(pkg).getByTestId('row-menu')).toBeVisible();

          const userList = at(asUser);
          await userList.goto();
          await expect(userList.card(pkg)).toBeVisible();
          await expect(userList.card(pkg).getByTestId('row-menu')).toHaveCount(0);
        },
      );
    }

    // 06 -----------------------------------------------------------------------------------------
    test(
      title(
        '06',
        `the Configure modal names the repository${configure.passwordMarker ? ` and says ${configure.passwordMarker}` : ''}`,
        '06-configure',
      ),
      async ({ adminPage, seeder }) => {
        pin('06-configure');
        const repo = await seeder.createRepo(type);
        const list = protocolPages(adminPage, descriptor, repo.name).list();
        await list.goto();
        const modal = new ConfigModal(adminPage);
        await expect(modal.root).toHaveCount(0);

        await list.configureButton.click();
        await expect(modal.root).toBeVisible();
        await expect(modal.title).toHaveText(configure.title);
        for (const text of configure.contains(repo.name, `${env.repoBaseUrl}/${repo.name}`)) {
          await expect(modal.root).toContainText(text);
        }
        if (configure.passwordMarker) {
          await expect(modal.root).toContainText(configure.passwordMarker);
        }
        // The password variant is not the deploy-token one.
        if (configure.deployTokenMarker) {
          await expect(modal.root).not.toContainText(configure.deployTokenMarker);
        }

        await modal.closeButton.click();
        await expect(modal.root).toHaveCount(0);
      },
    );

    test(
      title(
        '06',
        configure.deployTokenMarker
          ? `the deploy-token variant of the Configure modal says ${configure.deployTokenMarker}`
          : 'the deploy-token variant of the Configure modal has its own title and names the repository',
        '06-deploy-token',
      ),
      async ({ adminPage, seeder }) => {
        pin('06-deploy-token');
        const repo = await seeder.createRepo(type);
        const token = await seeder.createToken(repo.name);
        const settings = new RepoSettingsPage(adminPage, repo.name);
        await settings.goto();
        await settings.tokens.configureButton(token.name).click();

        const modal = new ConfigModal(adminPage);
        await expect(modal.root).toBeVisible();
        await expect(modal.title).toHaveText(configure.deployTokenTitle);
        await expect(modal.root).toContainText(repo.name);
        if (configure.deployTokenMarker) {
          await expect(modal.root).toContainText(configure.deployTokenMarker);
        }
        if (configure.passwordMarker) {
          await expect(modal.root).not.toContainText(configure.passwordMarker);
        }
      },
    );
  });
}
