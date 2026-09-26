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
 * RPS-1619: two admins on one repo's settings page. The page loads the settings once, and every control used to
 * PUT the whole form it had loaded: admin B makes the repo private, admin A (whose page still says "public") flips
 * "allow override", and A's request carried the stale `privateRepo=false` and made the repo public again.
 *
 * Now a control sends only its own field(s). For every ordered pair of controls (X, Y) of a Maven repo, B flips X
 * in a second browser (its own session), then A, whose page was opened BEFORE that and never reloaded, flips Y:
 * the repo must store both changes.
 */
import { RepoType, UserRole } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import {
  comparable,
  seedPattern,
  SETTING_TOGGLES,
} from '../../../src/ui/pages/repo-settings/setting-toggles.js';
import { loginSession } from '../../../src/ui/session.js';

const SETTINGS = '@settings';

test.describe('Repository settings: two admins on one page', { tag: SETTINGS }, () => {
  const type = RepoType.MAVEN;

  for (const first of SETTING_TOGGLES) {
    for (const second of SETTING_TOGGLES) {
      if (first === second) {
        continue;
      }

      // @cloud-skip: needs a second seeded ADMIN account; Repsy Cloud has no user roles (collaborators instead).
      test(
        `SET-13 admin B flips ${first.title}, then admin A's stale page flips ${second.title}: both are stored`,
        { tag: ['@cloud-skip'] },
        async ({ adminPage, openUiPage, seeder, panelApi }) => {
          const repo = await seeder.createRepo(type, { privateRepo: true });
          await seeder.setSettings(repo.name, seedPattern(type));
          const initial = comparable(await panelApi.getSettings(repo.name));

          // Admin A opens the page and does not touch it.
          await first.prepare(adminPage, type);
          await second.prepare(adminPage, type);
          const pageA = new RepoSettingsPage(adminPage, repo.name);
          await pageA.goto();
          await second.expectShows(pageA, initial, type);

          // Admin B changes another setting of the same repo, in the UI.
          const adminB = await seeder.createUser({ role: UserRole.ADMIN });
          const pageB = new RepoSettingsPage(
            await openUiPage({ session: await loginSession(adminB.username, adminB.password) }),
            repo.name,
          );
          await first.prepare(pageB.page, type);
          await pageB.goto();
          await first.flip(pageB, type);
          await pageB.shell.toasts.expectSuccess(first.successToast(initial, type));
          const afterB = { ...initial, ...first.flipped(initial, type) };
          await expect
            .poll(async () => comparable(await panelApi.getSettings(repo.name)))
            .toEqual(afterB);

          // Admin A, still on the page loaded before that, changes theirs.
          await second.flip(pageA, type);
          await pageA.shell.toasts.expectSuccess(second.successToast(initial, type));

          // B's change survived, and A's was stored.
          const both = { ...afterB, ...second.flipped(initial, type) };
          await expect
            .poll(async () => comparable(await panelApi.getSettings(repo.name)))
            .toEqual(both);

          // A fresh page agrees with the store.
          await pageA.reload();
          await first.expectShows(pageA, both, type);
          await second.expectShows(pageA, both, type);
        },
      );
    }
  }
});
