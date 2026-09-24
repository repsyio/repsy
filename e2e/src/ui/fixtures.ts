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
 * The Playwright `test` every UI spec imports: `scenarios/fixtures.ts`'s `test` (so `panelApi` and
 * `seeder` keep working, and `seeder` keeps its per-test run id and cleanup) extended with a
 * browser context that has the flake defaults applied, and logged-in pages.
 *
 *  - anonymous test: use the built-in `page`.
 *  - admin test:     use `adminPage` (the built-in `page`, already logged in as the harness admin).
 *  - USER test:      use `userPage` (a second context, logged in as a freshly seeded USER).
 *
 * Every login here is its OWN API login, per test: refresh tokens are single-use with family
 * revocation, so a token pair shared between tests (a worker-scoped fixture, a `storageState` file)
 * would be logged out mid-run by the first test whose page refreshes it. See `session.ts`.
 *
 * A spec needing more fixtures composes on top instead of editing this file: create
 * `src/ui/<area>-fixtures.ts` with `export const test = uiTest.extend<...>({...})`.
 */
import type { BrowserContext, Page } from '@playwright/test';

import { env } from '../env.js';
import { type SeededUser } from '../seed/seeder.js';
import { expect, test as base } from '../scenarios/fixtures.js';
import { applyUiDefaults } from './defaults.js';
import {
  assertAdminCredentialsUsableInUi,
  loginSession,
  seedSession,
  type UiSession,
} from './session.js';

/** Tag for a test that changes a password/username or deletes an account: it must not use `adminPage`. */
export const CREDENTIALS_TAG = '@credentials';

export interface OpenUiPageOptions {
  /** Log the page's context in as this session (from `loginSession()`); omitted = anonymous. */
  session?: UiSession;
  /** Overrides the project's 1440x900 viewport, e.g. `{ width: 390, height: 844 }` for mobile. */
  viewport?: { width: number; height: number };
}

export interface UiFixtures {
  /** A fresh API login as the harness admin, one per test. */
  adminSession: UiSession;
  /**
   * The built-in `page`, logged in as the harness admin before its first navigation. Refuses to run
   * in a test tagged `@credentials` (see `CREDENTIALS_TAG`): those tests use `userPage`.
   */
  adminPage: Page;
  /** A USER created through `seeder` for this test (deleted by it). */
  seededUser: SeededUser;
  /** A second `BrowserContext`, logged in as `seededUser`, with the flake defaults applied. */
  userPage: Page;
  /**
   * Opens an extra page in a NEW context (flake defaults applied, optionally logged in), closed
   * automatically after the test, before `seeder` cleans up. Contexts made this way get a trace and a
   * failure screenshot but no video (Playwright only records video for its own default context).
   */
  openUiPage: (options?: OpenUiPageOptions) => Promise<Page>;
}

export interface UiWorkerFixtures {
  /** Automatic: fails every test with a clear message if the admin credentials cannot log in through the UI. */
  uiPreflight: void;
}

/** Origins the page may talk to: the UI, the panel API and the repo protocol port. */
function allowedOrigins(baseURL: string | undefined): string[] {
  return [baseURL, env.apiBaseUrl, env.repoBaseUrl]
    .filter((url): url is string => Boolean(url))
    .map((url) => new URL(url).origin);
}

function originOf(baseURL: string | undefined): string {
  if (!baseURL) {
    throw new Error('The ui project has no baseURL (REPSY_UI_BASE_URL / REPSY_API_BASE_URL)');
  }
  return new URL(baseURL).origin;
}

export const test = base.extend<UiFixtures, UiWorkerFixtures>({
  uiPreflight: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      assertAdminCredentialsUsableInUi();
      await use();
    },
    { scope: 'worker', auto: true },
  ],

  // Overrides Playwright's built-in `context` (and so the built-in `page`) to apply the defaults.
  context: async ({ context, baseURL }, use) => {
    await applyUiDefaults(context, allowedOrigins(baseURL));
    await use(context);
  },

  // eslint-disable-next-line no-empty-pattern
  adminSession: async ({}, use) => {
    await use(await loginSession(env.adminUsername, env.adminPassword));
  },

  adminPage: async ({ page, context, adminSession, baseURL }, use, testInfo) => {
    if (testInfo.tags.includes(CREDENTIALS_TAG)) {
      throw new Error(
        `"${testInfo.title}" is tagged ${CREDENTIALS_TAG} (it changes a password/username or ` +
          'deletes an account) and must not use `adminPage`: the harness admin is what every run ' +
          'logs in with. Use `userPage` (a seeded user) instead.',
      );
    }
    await seedSession(context, originOf(baseURL), adminSession);
    await use(page);
  },

  seededUser: async ({ seeder }, use) => {
    await use(await seeder.createUser());
  },

  openUiPage: async ({ browser, baseURL }, use) => {
    const opened: BrowserContext[] = [];
    await use(async (options = {}) => {
      const context = await browser.newContext(
        options.viewport ? { viewport: options.viewport } : {},
      );
      opened.push(context);
      await applyUiDefaults(context, allowedOrigins(baseURL));
      if (options.session) {
        await seedSession(context, originOf(baseURL), options.session);
      }
      return context.newPage();
    });
    for (const context of opened) {
      await context.close();
    }
  },

  userPage: async ({ seededUser, openUiPage }, use) => {
    const session = await loginSession(seededUser.username, seededUser.password);
    await use(await openUiPage({ session }));
  },
});

export { expect };
