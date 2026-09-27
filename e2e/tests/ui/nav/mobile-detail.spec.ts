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
 * NAV-10..13 (RPS-1650, G21): the pages `nav/mobile.spec.ts` and `a11y/mobile-rows.spec.ts` do not reach, at a
 * phone's width (390x844): the list, versions and detail pages of every protocol (also with a very long
 * identity), the repository settings page of every type, the modals, the Users and Profile pages and the
 * dashboard.
 *
 * What "fits" means is in `src/ui/layout.ts`: the page AND the layout's content column (which clips instead
 * of scrolling) are not wider than the viewport, nothing sticks out past it, a control is inside the viewport
 * and not covered when it is scrolled to, a modal is inside the viewport (or scrolls inside itself), and the
 * focus is never hidden behind the fixed header. Every page is scanned with axe too.
 */
import type { Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { scanPage } from '../../../src/ui/a11y.js';
import {
  expectModalFits,
  expectNoHorizontalOverflow,
  expectReachable,
  obscuredFocus,
} from '../../../src/ui/layout.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import type { OpenUiPageOptions } from '../../../src/ui/fixtures.js';
import type { UiSession } from '../../../src/ui/session.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { ProfilePage } from '../../../src/ui/pages/profile.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { UsersPage } from '../../../src/ui/pages/users.js';
import type {
  PackageProtocol,
  SeededPackage,
  SeedPackageOptions,
} from '../../../src/seed/packages.js';
import { UI_REPO_TYPES } from '../../../src/ui/repo-types.js';

const PHONE = { width: 390, height: 844 };
const MOBILE = '@mobile';

/** A name (or tag) that cannot wrap at a space: the classic way to push a phone layout wider than the screen. */
const LONG: Record<PackageProtocol, SeedPackageOptions> = {
  maven: { name: `com.e2e.${'g'.repeat(60)}:${'a'.repeat(60)}` },
  npm: { name: `e2e-long-${'a'.repeat(150)}`, scoped: false },
  docker: { version: `v${'9'.repeat(126)}` },
  pypi: { name: `e2e-long-${'a'.repeat(120)}` },
  cargo: { name: `e2e_long_${'a'.repeat(50)}` },
  nuget: { name: `e2e.long.${'a'.repeat(80)}` },
  helm: { name: `e2e-long-${'a'.repeat(50)}` },
  golang: { name: `example.com/e2e/${'a'.repeat(100)}` },
  ruby: { name: `e2e_long_${'a'.repeat(100)}` },
};

const PROTOCOLS = Object.keys(DESCRIPTORS) as PackageProtocol[];

/** Tabs through the page (at most `max` stops) and returns what was hidden behind another element. */
async function tabThrough(page: Page, max = 90): Promise<string[]> {
  const hidden: string[] = [];
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  for (let stop = 0; stop < max; stop++) {
    await page.keyboard.press('Tab');
    const problem = await obscuredFocus(page);
    if (problem) {
      hidden.push(`Tab #${stop + 1}: ${problem}`);
    }
  }
  return hidden;
}

test.describe('Phone width: package pages', { tag: MOBILE }, () => {
  for (const protocol of PROTOCOLS) {
    const descriptor = DESCRIPTORS[protocol];
    const type = RepoType[protocol.toUpperCase() as keyof typeof RepoType];

    /** Opens a repository with one package (`options` shapes its identity) at the phone's width. */
    const setup = async (
      openUiPage: (options?: OpenUiPageOptions) => Promise<Page>,
      adminSession: UiSession,
      seeder: { createRepo: (type: RepoType) => Promise<{ name: string; type: RepoType }> },
      seedPackage: (
        repo: { name: string; type: RepoType },
        options: SeedPackageOptions,
      ) => Promise<SeededPackage>,
      options: SeedPackageOptions,
    ) => {
      const page = await openUiPage({ session: adminSession, viewport: PHONE });
      const repo = await seeder.createRepo(type);
      const pkg = await seedPackage(repo, options);
      return { page, pkg, pages: protocolPages(page, descriptor, repo.name) };
    };

    for (const [variant, options] of [
      ['a package', {}],
      ['a package with a very long name or tag', LONG[protocol]],
    ] as const) {
      test(`NAV-10: ${protocol}: ${variant}: the list and the versions page fit, controls are reachable`, async ({
        openUiPage,
        adminSession,
        seeder,
        seedPackage,
      }) => {
        const { page, pkg, pages } = await setup(
          openUiPage,
          adminSession,
          seeder,
          seedPackage,
          options,
        );

        const list = pages.list();
        await list.goto();
        await expectNoHorizontalOverflow(page, `${protocol} list`);
        await expectReachable(page, list.card(pkg), `${protocol} list: the package card`);
        await expectReachable(page, list.configureButton, `${protocol} list: Configure`);
        await expectReachable(page, list.searchInput, `${protocol} list: the search box`);

        const versions = pages.versions(pkg);
        await versions.goto();
        await expectNoHorizontalOverflow(page, `${protocol} versions`);
        await expectReachable(page, versions.card(pkg), `${protocol} versions: the version card`);
      });
    }

    test(`NAV-10: ${protocol}: the detail page fits, its controls are reachable and axe is clean`, async ({
      openUiPage,
      adminSession,
      seeder,
      seedPackage,
    }, testInfo) => {
      const { page, pkg, pages } = await setup(openUiPage, adminSession, seeder, seedPackage, {});
      const detail = pages.detail(pkg);
      await detail.goto();
      await expect(detail.root).toBeVisible();
      await expectNoHorizontalOverflow(page, `${protocol} detail`);
      await expectReachable(page, detail.name, `${protocol} detail: the name`);
      await expectReachable(page, detail.copyButton.first(), `${protocol} detail: the copy button`);
      if (descriptor.levels.detail.delete) {
        await expectReachable(page, detail.deleteButton, `${protocol} detail: Delete`);
      }
      await scanPage(page, testInfo, `mobile-${protocol}-detail`);
    });

    // RPS-1670 (fixed here): the detail page's name, version and install text wrap or break inside their
    // flex boxes (min-w-0 on the flex container, break-all on the text), so a very long identity no
    // longer clips against the layout's `overflow-hidden` column.
    test(`NAV-10: ${protocol}: the detail page of a very long name or tag fits`, async ({
      openUiPage,
      adminSession,
      seeder,
      seedPackage,
    }, testInfo) => {
      const { page, pkg, pages } = await setup(
        openUiPage,
        adminSession,
        seeder,
        seedPackage,
        LONG[protocol],
      );
      const detail = pages.detail(pkg);
      await detail.goto();
      await expect(detail.root).toBeVisible();
      await expectNoHorizontalOverflow(page, `${protocol} detail (long identity)`);
      await scanPage(page, testInfo, `mobile-${protocol}-detail-long`);
    });
  }
});

test.describe('Phone width: repository settings', { tag: MOBILE }, () => {
  for (const repoType of UI_REPO_TYPES) {
    test(`NAV-11: ${repoType.label}: the settings page fits and every section is reachable`, async ({
      openUiPage,
      adminSession,
      seeder,
    }, testInfo) => {
      const page = await openUiPage({ session: adminSession, viewport: PHONE });
      const repo = await seeder.createRepo(repoType.type);
      // A token with a name that does not wrap: its row is the widest thing on the page.
      await seeder.createToken(repo.name, { name: `tok-${'x'.repeat(60)}` });
      const settings = new RepoSettingsPage(page, repo.name);
      await settings.goto();
      await expect(settings.tokens.rows).toHaveCount(1);

      await expectNoHorizontalOverflow(page, `${repoType.label} settings`);
      await expectReachable(page, settings.visibility.toggle, 'the visibility switch');
      await expectReachable(page, settings.info.renameInput, 'the rename field');
      await expectReachable(page, settings.info.descriptionInput, 'the description field');
      await expectReachable(page, settings.tokens.createButton, 'Create deploy token');
      await expectReachable(page, settings.deleteRepo.deleteButton, 'Delete repository');
      await scanPage(page, testInfo, `mobile-${repoType.slug}-settings`);
    });
  }

  test('NAV-11: the settings modals fit the phone: create token, the one-time token, the delete confirmation', async ({
    openUiPage,
    adminSession,
    seeder,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const settings = new RepoSettingsPage(page, repo.name);
    await settings.goto();
    const { tokens } = settings;

    await tokens.openCreateModal();
    await expectModalFits(page, tokens.createModal.root, 'the create-token modal', {
      'the name field': tokens.createModal.name,
      Create: tokens.createModal.submitButton,
      Cancel: tokens.createModal.cancelButton,
    });
    await tokens.createModal.create({ name: `tok-${'y'.repeat(60)}` });
    await tokens.infoModal.expectOpen();
    await expectModalFits(page, tokens.infoModal.root, 'the one-time token modal', {
      'the token': tokens.infoModal.token,
      Close: tokens.infoModal.closeButton,
    });
    await expectNoHorizontalOverflow(page, 'the one-time token modal');
    await page.keyboard.press('Escape');
    await tokens.infoModal.expectClosed();

    await settings.deleteRepo.deleteButton.scrollIntoViewIfNeeded();
    await settings.deleteRepo.deleteButton.click();
    await settings.shell.dangerModal.expectOpen('Delete Repository');
    await expectModalFits(page, settings.shell.dangerModal.root, 'the delete confirmation', {
      Cancel: settings.shell.dangerModal.cancelButton,
      Confirm: settings.shell.dangerModal.confirmButton,
    });
  });

  test('NAV-11: the create-repository modal fits the phone, with its type menu open', async ({
    openUiPage,
    adminSession,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const repos = new RepositoriesPage(page);
    await repos.goto();
    await expectNoHorizontalOverflow(page, 'the repository list');
    await repos.openCreateModal();
    await expectModalFits(page, repos.modal.root, 'the create-repository modal', {
      'the name field': repos.modal.nameInput,
      Create: repos.modal.submitButton,
      Cancel: repos.modal.cancelButton,
    });
    await repos.modal.typeToggle.click();
    await expectNoHorizontalOverflow(page, 'the create-repository modal with the type menu open');
    await expectModalFits(page, repos.modal.root, 'the create-repository modal (type menu open)');
  });
});

test.describe('Phone width: account pages', { tag: MOBILE }, () => {
  test('NAV-12: the dashboard and the profile fit the phone', async ({
    openUiPage,
    adminSession,
  }, testInfo) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await expectNoHorizontalOverflow(page, 'the dashboard');
    await scanPage(page, testInfo, 'mobile-dashboard');

    const profile = new ProfilePage(page);
    await profile.goto();
    await expectNoHorizontalOverflow(page, 'the profile');
    await expectReachable(page, profile.newPassword, 'the new password field');
    await expectReachable(page, profile.passwordSubmit, 'the password submit');
    await expectReachable(page, profile.username, 'the username field');
    await scanPage(page, testInfo, 'mobile-profile');
  });

  // @cloud-skip: the Users page exists on Repsy OS only.
  test(
    'NAV-12: the users page and its modals fit the phone',
    { tag: ['@cloud-skip'] },
    async ({ openUiPage, adminSession, seededUser }, testInfo) => {
      const page = await openUiPage({ session: adminSession, viewport: PHONE });
      const users = new UsersPage(page);
      await users.goto();
      await users.search(seededUser.username);
      await expectNoHorizontalOverflow(page, 'the users list');
      const card = page.getByTestId(`user-card-${seededUser.username}`);
      await expectReachable(page, card, 'the user card');
      await scanPage(page, testInfo, 'mobile-users');

      await users.openCreateModal();
      await expectModalFits(page, users.createModal.root, 'the create-user modal', {
        'the username field': users.createModal.username,
        Cancel: users.createModal.cancel,
      });
      await users.createModal.cancel.click();
      await expect(users.createModal.root).toHaveCount(0);

      // The card's menu is the phone's only way to Edit and Delete.
      const menu = card.getByTestId('row-menu');
      await menu.getByTestId('dropdown-toggle').click();
      await menu.getByTestId('row-edit').click();
      await expect(users.editModal.root).toBeVisible();
      await expectModalFits(page, users.editModal.root, 'the edit-user modal', {
        'the username field': users.editModal.username,
        Cancel: users.editModal.cancel,
      });
    },
  );
});

test.describe('Phone width: the fixed header never hides the focus', { tag: MOBILE }, () => {
  test('NAV-13: tabbing through a package detail page and the settings page keeps the focus visible', async ({
    openUiPage,
    adminSession,
    seeder,
    seedPackage,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    await protocolPages(page, DESCRIPTORS.npm, repo.name).detail(pkg).goto();
    expect(await tabThrough(page), 'the npm detail page').toEqual([]);

    const settings = new RepoSettingsPage(page, repo.name);
    await settings.goto();
    expect(await tabThrough(page), 'the settings page').toEqual([]);
  });
});
