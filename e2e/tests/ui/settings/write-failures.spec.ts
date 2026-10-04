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
 * RPS-1618: a settings control saves the moment it is touched, so it shows the new value first and the server's
 * answer later. When the answer is a failure, the control has to go back to what the repo really stores: the page
 * used to keep saying "private" for a repo that was public, or "scanning on" for one that scanned nothing.
 *
 * For every control (`setting-toggles.ts`) and each of a 500 and an aborted request: the error toast, the control
 * back at the stored value, the stored value unchanged (read through the API), a reload that agrees, and a second
 * try that works, so a failed save does not leave the control locked. A double click sends one request.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import {
  comparable,
  seedPattern,
  SETTING_TOGGLES,
  togglesFor,
} from '../../../src/ui/pages/repo-settings/setting-toggles.js';
import { escapeRegExp, repoApiPath } from '../../../src/ui/routes.js';
import { errorBody, fulfillJson, type ErrorResponse } from '../../../src/ui/stub-responses.js';

const SETTINGS = '@settings';

/** Maven has every control; NPM is the general form; NuGet has the release-aware form of a second type. */
const TYPES = [RepoType.MAVEN, RepoType.NPM, RepoType.NUGET] as const;

const FAILURES = [
  {
    mode: 'a 500',
    toast: 'Server error',
    fail: (route: import('@playwright/test').Route) =>
      fulfillJson<ErrorResponse>(route, 500, errorBody({ code: 'boom', detail: 'boom' })),
  },
  {
    mode: 'an aborted request',
    toast: 'Connection error',
    fail: (route: import('@playwright/test').Route) => route.abort('failed'),
  },
] as const;

const settingsUrl = (repoName: string): RegExp =>
  new RegExp(`${escapeRegExp(repoApiPath(repoName, 'settings'))}(\\?|$)`);

test.describe('Repository settings: a failed save', { tag: SETTINGS }, () => {
  test.use({
    allowedPageErrors: errorToasts(
      'by design: every case makes the settings PUT fail and asserts the toast it raises',
      'Server error',
      'Connection error',
    ),
  });

  for (const type of TYPES) {
    for (const toggle of togglesFor(type)) {
      for (const failure of FAILURES) {
        test(`SET-10 ${toggle.title} on ${type}: ${failure.mode} puts the control back and stores nothing`, async ({
          adminPage,
          seeder,
          panelApi,
        }) => {
          const repo = await seeder.createRepo(type, { privateRepo: true });
          await seeder.setSettings(repo.name, seedPattern(type));
          const before = comparable(await panelApi.getSettings(repo.name));
          await toggle.prepare(adminPage, type);
          const settings = new RepoSettingsPage(adminPage, repo.name);
          await settings.goto();
          await toggle.expectShows(settings, before, type);

          await adminPage.route(settingsUrl(repo.name), (route) =>
            route.request().method() === 'PUT' ? failure.fail(route) : route.fallback(),
          );
          await toggle.flip(settings, type);

          await settings.shell.toasts.expectError(failure.toast);
          await expect(settings.shell.toasts.success()).toHaveCount(0);
          // The control is back at what the repo stores, and the repo stores what it stored.
          await toggle.expectShows(settings, before, type);
          expect(comparable(await panelApi.getSettings(repo.name))).toEqual(before);

          // ... and so does the page after a reload.
          await adminPage.unroute(settingsUrl(repo.name));
          await settings.reload();
          await toggle.expectShows(settings, before, type);

          // A second try goes through: the control was not left locked by the failure.
          await toggle.flip(settings, type);
          await settings.shell.toasts.expectSuccess(toggle.successToast(before, type));
          const flipped = { ...before, ...toggle.flipped(before, type) };
          await expect
            .poll(async () => comparable(await panelApi.getSettings(repo.name)))
            .toEqual(flipped);
          await toggle.expectShows(settings, flipped, type);
        });
      }
    }
  }
});

test.describe('Repository settings: a double click', { tag: SETTINGS }, () => {
  for (const toggle of SETTING_TOGGLES) {
    test(`SET-11 ${toggle.title}: two quick clicks send one request and end consistent`, async ({
      adminPage,
      seeder,
      panelApi,
    }) => {
      const type = RepoType.MAVEN;
      const repo = await seeder.createRepo(type, { privateRepo: true });
      await seeder.setSettings(repo.name, seedPattern(type));
      const before = comparable(await panelApi.getSettings(repo.name));
      await toggle.prepare(adminPage, type);
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      // Hold the first save on the server side, so the second click meets the page mid-flight.
      const puts: string[] = [];
      let release!: () => void;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      await adminPage.route(settingsUrl(repo.name), async (route) => {
        if (route.request().method() !== 'PUT') {
          return route.fallback();
        }
        puts.push(route.request().postData() ?? '');
        await gate;
        return route.fallback();
      });

      await toggle.flipTwice(settings, type);
      release();

      await settings.shell.toasts.expectSuccess(toggle.successToast(before, type));
      const flipped = { ...before, ...toggle.flipped(before, type) };
      await expect
        .poll(async () => comparable(await panelApi.getSettings(repo.name)))
        .toEqual(flipped);
      await toggle.expectShows(settings, flipped, type);
      expect(puts).toHaveLength(1);
    });
  }
});
