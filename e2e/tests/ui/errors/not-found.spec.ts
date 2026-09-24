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
 * ERR-04: the not-found page (`/not-found`, where an unknown path ends up) in the panel layout, as an
 * anonymous visitor and as a signed-in admin and USER, at phone width (390x844) and at desktop width.
 * Without a session the layout has no sidebar, so it also has no burger and asks for no profile;
 * with one, the burger opens the mobile sidebar here just as on every other page.
 */
import type { Page } from '@playwright/test';

import { expect, test } from '../../../src/ui/fixtures.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { loginSession } from '../../../src/ui/session.js';

const PHONE = { width: 390, height: 844 };
const DESKTOP = { width: 1440, height: 900 };
const PROFILE_URL = /\/api\/profile(\?|$)/;

/** Collects the profile requests a page makes from now on. */
function watchProfileRequests(page: Page): string[] {
  const seen: string[] = [];
  page.on('request', (request) => {
    if (PROFILE_URL.test(request.url())) {
      seen.push(request.url());
    }
  });
  return seen;
}

test.describe('Not-found page', () => {
  for (const [name, viewport] of [
    ['phone', PHONE],
    ['desktop', DESKTOP],
  ] as const) {
    test(`ERR-04: an anonymous visitor at ${name} width sees the 404 without a sidebar, a burger or a profile request`, async ({
      openUiPage,
    }) => {
      const page = await openUiPage({ viewport });
      const profileRequests = watchProfileRequests(page);
      const shell = new Shell(page);

      await page.goto('/not-found');

      await expect(page.getByTestId('not-found')).toBeVisible();
      await expect(page.getByTestId('not-found-code')).toHaveText('404');
      await expect(shell.header.root).toBeVisible();
      await expect(shell.header.burger).toHaveCount(0);
      await expect(shell.sidebar.root).toHaveCount(0);
      await expect(shell.mobileSidebar.root).toHaveCount(0);

      // The sidebar asks for the profile as it renders, i.e. before the 404 above is on screen.
      await page.waitForLoadState('load');
      expect(profileRequests).toEqual([]);

      // Nothing to do with a session is offered: "Go to Home" leads to the login form.
      await page.getByTestId('not-found-home').click();
      await expect(page.getByTestId('login-form')).toBeVisible();
    });
  }

  test('ERR-04: an admin at phone width opens the mobile sidebar on the 404 page and leaves through it', async ({
    openUiPage,
    adminSession,
  }) => {
    const page = await openUiPage({ session: adminSession, viewport: PHONE });
    const shell = new Shell(page);

    await page.goto('/not-found');

    await expect(page.getByTestId('not-found')).toBeVisible();
    await expect(shell.sidebar.root).toBeHidden();
    await expect(shell.header.burger).toBeVisible();
    await expect(shell.mobileSidebar.root).toHaveCount(0);

    await shell.header.burger.click();
    await expect(shell.mobileSidebar.root).toBeVisible();
    await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'true');
    await expect(shell.mobileSidebar.link('users')).toBeVisible();

    await shell.mobileSidebar.link('repositories').click();
    await expect(page).toHaveURL(/\/repositories$/);
    await expect(shell.mobileSidebar.root).toHaveCount(0);
    await expect(shell.header.burger).toHaveAttribute('aria-expanded', 'false');
  });

  test('ERR-04: a USER at phone width gets the same burger, without Users and Security', async ({
    openUiPage,
    seededUser,
  }) => {
    const session = await loginSession(seededUser.username, seededUser.password);
    const page = await openUiPage({ session, viewport: PHONE });
    const shell = new Shell(page);

    await page.goto('/not-found');

    await expect(page.getByTestId('not-found')).toBeVisible();
    await shell.header.burger.click();
    await expect(shell.mobileSidebar.link('repositories')).toBeVisible();
    await expect(shell.mobileSidebar.link('users')).toHaveCount(0);
    await expect(shell.mobileSidebar.link('security')).toHaveCount(0);
    await shell.mobileSidebar.close.click();
    await expect(shell.mobileSidebar.root).toHaveCount(0);
  });

  test('ERR-04: a signed-in visitor at desktop width has the sidebar and no burger on the 404 page', async ({
    adminPage,
  }) => {
    const shell = new Shell(adminPage);

    await adminPage.goto('/not-found');

    await expect(adminPage.getByTestId('not-found')).toBeVisible();
    await expect(shell.sidebar.root).toBeVisible();
    await expect(shell.header.burger).toBeHidden();
  });
});
