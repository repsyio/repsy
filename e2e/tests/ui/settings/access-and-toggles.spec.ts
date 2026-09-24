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
 * `/:repo/settings`, the part of the page that gates and switches the repo: who may open it (SET-01),
 * the Visibility and Package Override toggles (SET-02, SET-03), which sections each repo type gets
 * (SET-03) and the Maven/NuGet Version Allowance selector (SET-04).
 *
 * The three controls PUT the settings the moment they are touched and ask for no confirmation, so
 * every test reads the setting back through the panel API (`panelApi.getSettings`) instead of
 * trusting the control to show its own state, and re-reads it after a reload.
 */
import { RepoType, UserRole } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { repoRootStatus } from '../../../src/ui/pages/repo-settings/readback.js';
import { loginSession } from '../../../src/ui/session.js';

const SETTINGS = '@settings';

test.describe('Repository settings: access', { tag: SETTINGS }, () => {
  test(
    'SET-01 a USER who opens /<repo>/settings is redirected to /repositories',
    { tag: ['@smoke'] },
    async ({ userPage, seeder }) => {
      // A public and a private repo: the USER may read the first and not the second, and neither
      // may be managed by them.
      const publicRepo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: false });
      const privateRepo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });

      for (const repo of [publicRepo, privateRepo]) {
        const settings = new RepoSettingsPage(userPage, repo.name);
        await userPage.goto(settings.path);

        await expect(userPage).toHaveURL(/\/repositories$/);
        await expect(settings.root).toHaveCount(0);
        await expect(settings.tokens.root).toHaveCount(0);
      }
    },
  );

  test('SET-01 an admin gets the page for the same repo', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);

    await settings.goto();

    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/settings$`));
    await expect(settings.tokens.root).toBeVisible();
    await expect(settings.deleteRepo.deleteButton).toBeVisible();
  });

  test('SET-01 the role decides, not the account: a second ADMIN gets the page', async ({
    openUiPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const admin = await seeder.createUser({ role: UserRole.ADMIN });
    const page = await openUiPage({ session: await loginSession(admin.username, admin.password) });
    const settings = new RepoSettingsPage(page, repo.name);

    await settings.goto();

    await expect(settings.root).toBeVisible();
  });
});

test.describe('Repository settings: visibility', { tag: SETTINGS }, () => {
  test(
    'SET-02 the Visibility toggle flips Private <-> Public and persists',
    { tag: ['@smoke'] },
    async ({ adminPage, seeder, panelApi }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      await seeder.setSettings(repo.name, {
        allowOverride: false,
        releases: true,
        snapshots: false,
      });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      // Private to begin with: unchecked, and the repo port refuses an anonymous reader.
      await settings.visibility.expectChecked(false);
      await expect(settings.visibility.label).toHaveText('Private');
      expect((await panelApi.getSettings(repo.name)).privateRepo).toBe(true);
      expect(await repoRootStatus(repo.name)).toBe(401);

      await settings.visibility.flip();
      await settings.shell.toasts.expectSuccess('Repository visibility has changed as public');
      await settings.visibility.expectChecked(true);
      await expect(settings.visibility.label).toHaveText('Public');
      await expect
        .poll(async () => (await panelApi.getSettings(repo.name)).privateRepo)
        .toBe(false);

      // The toggle PUTs the whole form: nothing else on the repo may have moved with it.
      const afterPublic = await panelApi.getSettings(repo.name);
      expect(afterPublic).toMatchObject({ allowOverride: false, releases: true, snapshots: false });
      // Public means the repo port serves an anonymous reader, so this is not just a stored flag.
      expect(await repoRootStatus(repo.name)).toBe(200);

      // It survives a reload, i.e. the page reads it back from the server.
      await settings.reload();
      await settings.visibility.expectChecked(true);

      await settings.visibility.flip();
      await settings.shell.toasts.expectSuccess('Repository visibility has changed as private');
      await settings.visibility.expectChecked(false);
      await expect.poll(async () => (await panelApi.getSettings(repo.name)).privateRepo).toBe(true);
      expect(await repoRootStatus(repo.name)).toBe(401);

      await settings.reload();
      await settings.visibility.expectChecked(false);
      await expect(settings.visibility.label).toHaveText('Private');
    },
  );

  // RPS-1261: the two help texts of the section used to describe the OPPOSITE of the toggle ("When
  // active, only authorized users can access the repository" while "active" (checked) is labelled
  // Public; the dynamic hint told a private repo to "Deactivate it for private visibility"). With the
  // repo Public the section may not claim that only authorised users get in, and each hint names the
  // switch that leads to the OTHER state.
  test('SET-02 the Visibility help text does not contradict a Public repo (RPS-1261)', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: false });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    await settings.visibility.expectChecked(true);
    await expect(settings.visibility.root).not.toContainText('only authorized users can access');
    await expect(settings.visibility.hint).toHaveText(
      'Turn it off to restrict access to authorized users.',
    );

    await settings.visibility.flip();
    await settings.shell.toasts.expectSuccess('Repository visibility has changed as private');
    await expect(settings.visibility.hint).toHaveText('Turn it on to make the repository public.');
  });
});

test.describe('Repository settings: package override', { tag: SETTINGS }, () => {
  for (const repoType of [RepoType.MAVEN, RepoType.NUGET, RepoType.NPM]) {
    test(`SET-03 the Package Override toggle persists (${repoType})`, async ({
      adminPage,
      seeder,
      panelApi,
    }) => {
      const repo = await seeder.createRepo(repoType, { privateRepo: true });
      await seeder.setSettings(repo.name, { allowOverride: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      await settings.packageOverride.expectChecked(true);
      await expect(settings.packageOverride.label).toHaveText('Allow');

      await settings.packageOverride.flip();
      await settings.shell.toasts.expectSuccess('Package override is now blocked');
      await expect(settings.packageOverride.label).toHaveText('Deny');
      await expect
        .poll(async () => (await panelApi.getSettings(repo.name)).allowOverride)
        .toBe(false);
      // Only the override moved.
      expect((await panelApi.getSettings(repo.name)).privateRepo).toBe(true);

      await settings.reload();
      await settings.packageOverride.expectChecked(false);

      await settings.packageOverride.flip();
      await settings.shell.toasts.expectSuccess('Package override is now allowed');
      await expect
        .poll(async () => (await panelApi.getSettings(repo.name)).allowOverride)
        .toBe(true);

      await settings.reload();
      await settings.packageOverride.expectChecked(true);
    });
  }

  // RPS-1261: same contradiction as Visibility. The fixed line said "When active, package override
  // (uploading same version again) will be blocked" while active (checked) is labelled Allow.
  test('SET-03 the Package Override help text does not contradict Allow (RPS-1261)', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    await seeder.setSettings(repo.name, { allowOverride: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    await settings.packageOverride.expectChecked(true);
    await expect(settings.packageOverride.root).not.toContainText('will be blocked');
  });
});

/**
 * Which sections `/:repo/settings` renders per repo type (`repository-settings.component.html`):
 * Visibility, Deploy Tokens, Storage, Repository Info and Delete for all nine; Package Override for
 * all but Cargo and Go; Version Allowance for Maven and NuGet; PGP key stores for Maven; Untagged
 * Manifests and Orphan Layers for Docker. The Vulnerability Scanning section depends on a scanner and is left out.
 */
const SECTIONS_BY_TYPE: Record<
  RepoType,
  { override: boolean; allowance: boolean; pgp: boolean; orphan: boolean; untagged: boolean }
> = {
  [RepoType.MAVEN]: { override: true, allowance: true, pgp: true, orphan: false, untagged: false },
  [RepoType.NPM]: { override: true, allowance: false, pgp: false, orphan: false, untagged: false },
  [RepoType.PYPI]: { override: true, allowance: false, pgp: false, orphan: false, untagged: false },
  [RepoType.DOCKER]: { override: true, allowance: false, pgp: false, orphan: true, untagged: true },
  [RepoType.CARGO]: {
    override: false,
    allowance: false,
    pgp: false,
    orphan: false,
    untagged: false,
  },
  [RepoType.NUGET]: { override: true, allowance: true, pgp: false, orphan: false, untagged: false },
  [RepoType.GOLANG]: {
    override: false,
    allowance: false,
    pgp: false,
    orphan: false,
    untagged: false,
  },
  [RepoType.HELM]: { override: true, allowance: false, pgp: false, orphan: false, untagged: false },
  [RepoType.RUBY]: { override: true, allowance: false, pgp: false, orphan: false, untagged: false },
};

test.describe('Repository settings: sections per repo type', { tag: SETTINGS }, () => {
  for (const [repoType, expected] of Object.entries(SECTIONS_BY_TYPE) as [
    RepoType,
    (typeof SECTIONS_BY_TYPE)[RepoType],
  ][]) {
    test(`SET-03 the sections a ${repoType} repo gets`, async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(repoType, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      // Always there.
      await expect(settings.visibility.root).toBeVisible();
      await expect(settings.tokens.root).toBeVisible();
      await expect(settings.storage.root).toBeVisible();
      await expect(settings.info.root).toBeVisible();
      await expect(settings.deleteRepo.root).toBeVisible();

      // Type dependent. `toHaveCount` (not `toBeHidden`) so an absent section and a hidden one differ.
      await expect(settings.packageOverride.root).toHaveCount(expected.override ? 1 : 0);
      await expect(settings.allowance.root).toHaveCount(expected.allowance ? 1 : 0);
      await expect(settings.pgp.root).toHaveCount(expected.pgp ? 1 : 0);
      await expect(settings.orphanLayers.root).toHaveCount(expected.orphan ? 1 : 0);
      await expect(settings.untaggedManifests.root).toHaveCount(expected.untagged ? 1 : 0);
    });
  }
});

test.describe('Repository settings: version allowance', { tag: SETTINGS }, () => {
  test('SET-04 Maven: all packages / snapshots / releases persist', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    await seeder.setSettings(repo.name, { releases: true, snapshots: true, allowOverride: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    await expect(settings.allowance.toggle).toHaveText(/All packages/i);
    expect(await settings.allowance.options()).toEqual(['all packages', 'snapshots', 'releases']);

    const expectStored = async (releases: boolean, snapshots: boolean) => {
      await expect
        .poll(async () => {
          const stored = await panelApi.getSettings(repo.name);
          return { releases: stored.releases, snapshots: stored.snapshots };
        })
        .toEqual({ releases, snapshots });
    };

    await settings.allowance.choose('snapshots');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to snapshots');
    await expect(settings.allowance.toggle).toHaveText(/Snapshots/);
    await expectStored(false, true);

    await settings.allowance.choose('releases');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to releases');
    await expectStored(true, false);

    // The selection is read back from the server, not remembered by the page.
    await settings.reload();
    await expect(settings.allowance.toggle).toHaveText(/Releases/);

    await settings.allowance.choose('all packages');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to all packages');
    await expectStored(true, true);

    // The other settings were carried along untouched.
    expect(await panelApi.getSettings(repo.name)).toMatchObject({
      privateRepo: true,
      allowOverride: true,
    });
  });

  test('SET-04 NuGet: all packages / pre-release / stable persist', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
    await seeder.setSettings(repo.name, { releases: true, snapshots: true, allowOverride: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    expect(await settings.allowance.options()).toEqual(['all packages', 'pre-release', 'stable']);

    const expectStored = async (releases: boolean, snapshots: boolean) => {
      await expect
        .poll(async () => {
          const stored = await panelApi.getSettings(repo.name);
          return { releases: stored.releases, snapshots: stored.snapshots };
        })
        .toEqual({ releases, snapshots });
    };

    // NuGet maps pre-release to `snapshots` and stable to `releases`.
    await settings.allowance.choose('pre-release');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to pre-release');
    await expectStored(false, true);

    await settings.allowance.choose('stable');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to stable');
    await expectStored(true, false);

    await settings.reload();
    await expect(settings.allowance.toggle).toHaveText(/Stable/);

    await settings.allowance.choose('all packages');
    await settings.shell.toasts.expectSuccess('Version allowance has changed to all packages');
    await expectStored(true, true);
  });
});
