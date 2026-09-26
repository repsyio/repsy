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
 * RPS-1618 (N04): the read-back matrix. SET-02 flips Visibility and looks at three other fields; nothing looked at
 * the other controls, or at the Vulnerability Scanning and PGP fields. Here every control that a repo type shows
 * is flipped, from a known non-default pattern of every stored setting (`seedPattern`), and the WHOLE stored
 * settings object is compared with what it was: exactly the fields the control owns changed, nothing else moved.
 * A reload then shows the control at the stored value.
 *
 * Since RPS-1619 a control sends only its own field(s), so "nothing else moved" is also true when another admin
 * changed a field meanwhile (`concurrent-admins.spec.ts`).
 */
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import {
  comparable,
  expectSeeded,
  seedPattern,
  togglesFor,
} from '../../../src/ui/pages/repo-settings/setting-toggles.js';
import { UI_REPO_TYPES } from '../../../src/ui/repo-types.js';

const SETTINGS = '@settings';

test.describe('Repository settings: read-back matrix', { tag: SETTINGS }, () => {
  for (const { type } of UI_REPO_TYPES) {
    for (const toggle of togglesFor(type)) {
      test(`SET-12 ${toggle.title} on ${type}: only its own fields change`, async ({
        adminPage,
        seeder,
        panelApi,
      }) => {
        const repo = await seeder.createRepo(type, { privateRepo: true });
        await seeder.setSettings(repo.name, seedPattern(type));
        const before = comparable(await panelApi.getSettings(repo.name));
        expectSeeded(before, type);
        await toggle.prepare(adminPage, type);
        const settings = new RepoSettingsPage(adminPage, repo.name);
        await settings.goto();
        await toggle.expectShows(settings, before, type);

        await toggle.flip(settings, type);
        await settings.shell.toasts.expectSuccess(toggle.successToast(before, type));

        const after = { ...before, ...toggle.flipped(before, type) };
        await expect
          .poll(async () => comparable(await panelApi.getSettings(repo.name)))
          .toEqual(after);
        await toggle.expectShows(settings, after, type);

        await settings.reload();
        await toggle.expectShows(settings, after, type);
        expect(comparable(await panelApi.getSettings(repo.name))).toEqual(after);
      });
    }
  }
});
