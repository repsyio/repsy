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
 * SEC-01 (`@scanner`), row clicks with the scanner on (RPS-1337). The security badge of a scanned
 * version is a control of its own in the middle of a Maven versions or artifacts row, so the centre of
 * the row is not a free point any more. A click there opens the security modal (and must not navigate),
 * a click anywhere else on the row opens the row's page, and nothing covers the row (a tooltip, a popup
 * or the badge cell) so that a click does neither. Swept over every protocol the scanner supports, at
 * the levels that carry a badge, on the desktop rows and on the mobile cards. A11Y-11 does the same for
 * every protocol at every level, without a scanner (and with one, on the stub stack).
 */
import type { Locator, Page } from '@playwright/test';

import type { PackageProtocol, PackageRef } from '../../../src/seed/packages.js';
import { DESCRIPTORS, pageOf, type ProtocolListPage } from '../../../src/ui/pages/protocol.js';
import type { ListLevelName } from '../../../src/ui/pages/protocols/types.js';
import { rowLinkPoint } from '../../../src/ui/row-click.js';
import { SecurityModal, securityBadgeIn } from '../../../src/ui/pages/security.js';
import {
  SCANNER_TAG,
  expect,
  newestFinishedScan,
  scanPackageName,
  skipUnlessScannerOptedIn,
  test,
} from '../../../src/ui/scanner-fixtures.js';
import { SECURITY_CASES } from '../security/cases.js';

/** The levels of each scanner protocol whose rows carry a security badge, and open something. */
const BADGE_LEVELS: Record<string, readonly ListLevelName[]> = {
  maven: ['sublist', 'versions'],
  npm: ['list', 'versions'],
  pypi: ['list', 'versions'],
  docker: ['list', 'versions'],
};

const pathOf = (url: string): string => new URL(url, 'http://x').pathname;

/** Where the pointer lands at the centre of `row`: the badge (`badge`) or something else (`other`). */
async function centreLandsOn(row: Locator): Promise<'badge' | 'other'> {
  return row.evaluate((element) => {
    const box = element.getBoundingClientRect();
    const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
    return hit?.closest('[data-testid="security-badge"]') ? 'badge' : 'other';
  });
}

async function clickAt(page: Page, row: Locator, at: { x: number; y: number }): Promise<void> {
  const box = await row.boundingBox();
  if (box === null) {
    throw new Error('the row has no box');
  }
  await page.mouse.click(box.x + at.x, box.y + at.y);
}

test.describe('SEC-01 a click on a scanned row', { tag: SCANNER_TAG }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 120_000 });

  for (const { protocol, type } of SECURITY_CASES) {
    const descriptor = DESCRIPTORS[protocol as PackageProtocol];
    for (const level of BADGE_LEVELS[protocol]) {
      for (const [device, viewport] of [
        ['desktop', { width: 1440, height: 900 }],
        ['mobile', { width: 390, height: 844 }],
      ] as const) {
        if (device === 'mobile' && !descriptor.levels[level]?.mobileCards) {
          continue;
        }

        test(`${protocol} ${level} ${device === 'desktop' ? 'row' : 'card'}: the centre opens the row, or the security modal when the badge is there, and the rest of the row opens the row`, async ({
          adminPage,
          seeder,
          seedPackage,
          panelApi,
        }) => {
          await adminPage.setViewportSize(viewport);
          const repo = await seeder.createRepo(type);
          const pkg: PackageRef = await seedPackage(repo, {
            name: scanPackageName(protocol, seeder.runId, 'clean'),
          });
          await newestFinishedScan(panelApi, repo.name, pkg);

          const listPage = pageOf(adminPage, descriptor, level, repo.name, pkg) as ProtocolListPage;
          await listPage.goto();
          const row = device === 'desktop' ? listPage.row(pkg) : listPage.card(pkg);
          await expect(row).toBeVisible();
          // The scan is done, so the badge is what the row shows: wait for it, as it renders after the row.
          await expect(securityBadgeIn(row)).toBeVisible();
          const href = await row.locator('a.row-link').getAttribute('href');
          expect(href).toMatch(/^\//);
          const listPath = pathOf(adminPage.url());
          // The badge of a versions row opens the version's modal, the badge of a package row the package's.
          const modal = new SecurityModal(adminPage, level === 'versions' ? 'version' : 'package');

          // The centre of the row, like a user's mouse: one move, then press and release at once.
          const box = await row.boundingBox();
          const centre = await centreLandsOn(row);
          await clickAt(adminPage, row, { x: box!.width / 2, y: box!.height / 2 });
          if (centre === 'badge') {
            // The badge sits there on purpose: it opens its modal, and the row stays where it is.
            await modal.expectOpen();
            expect(pathOf(adminPage.url())).toBe(listPath);
            await modal.closeViaBackdrop();
          } else {
            await expect(adminPage).toHaveURL((url) => url.pathname === pathOf(href!));
            await adminPage.goBack();
            await listPage.expectLoaded();
            await expect(securityBadgeIn(row)).toBeVisible();
          }

          // Anywhere else on the row: the stretched link, whatever pops up when the pointer arrives.
          await clickAt(adminPage, row, await rowLinkPoint(row));
          await expect(modal.root).toHaveCount(0);
          await expect(adminPage).toHaveURL((url) => url.pathname === pathOf(href!));
        });
      }
    }
  }
});
