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
 * RPS-1618 (G25, also listed in RPS-1650): a repo deleted, or renamed, by another session while its settings page
 * is open. The next save must say so (`Repository not found`, the answer is a 404) instead of failing silently, the
 * control must go back to what it showed (the repo stores nothing any more, and the page must not claim a change),
 * and nothing may throw in the page.
 *
 * Reloading such a page is a different story: an unknown repository route fires about ten parallel lookups, each
 * raising the same toast (a known bug of its own, see `repo-management.spec.ts`, SET-05), so it is not asserted here.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { errorToasts } from '../../../src/ui/page-errors.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import {
  comparable,
  seedPattern,
  togglesFor,
} from '../../../src/ui/pages/repo-settings/setting-toggles.js';

const SETTINGS = '@settings';
const type = RepoType.MAVEN;

test.describe('Repository settings: the repo is gone under an open page', { tag: SETTINGS }, () => {
  test.use({
    allowedPageErrors: errorToasts(
      'by design: the repo is deleted or renamed under the page and every case asserts the toast of the save that follows',
      'Repository not found',
    ),
  });

  for (const toggle of togglesFor(type)) {
    test(`SET-14 ${toggle.title}: a save after the repo was deleted says "Repository not found" and changes nothing`, async ({
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

      // Another admin deletes the repo; this page does not know.
      await panelApi.deleteRepo(repo.name);

      await toggle.flip(settings, type);

      await settings.shell.toasts.expectError('Repository not found');
      await expect(settings.shell.toasts.success()).toHaveCount(0);
      // The page did not claim the change, and it is still there to read.
      await toggle.expectShows(settings, before, type);
      await expect(settings.root).toBeVisible();
    });
  }

  test('SET-14 a save after the repo was renamed says "Repository not found" and does not touch the renamed repo', async ({
    adminPage,
    adminSession,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(type, { privateRepo: true });
    await seeder.setSettings(repo.name, seedPattern(type));
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    // Another admin renames it; the page still holds the old name. Tracked, so cleanup deletes it.
    const newName = seeder.reserveRepoName(type);
    seeder.adoptRepo(newName);
    const res = await fetch(`${env.apiBaseUrl}/api/repos/${encodeURIComponent(repo.name)}/name`, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${adminSession.token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name: newName }),
    });
    expect(res.status).toBe(200);
    const before = comparable(await panelApi.getSettings(newName));

    await settings.visibility.flip();

    await settings.shell.toasts.expectError('Repository not found');
    await expect(settings.shell.toasts.success()).toHaveCount(0);
    await settings.visibility.expectChecked(!before.privateRepo);
    expect(comparable(await panelApi.getSettings(newName))).toEqual(before);
  });
});
