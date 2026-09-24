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
 * The lower half of `/:repo/settings`: rename and description (SET-05), delete (SET-06), Docker
 * Orphan Layers (SET-07), Docker Untagged Manifests (SET-07b) and the Storage section (SET-09).
 * Rename, delete, orphan layers and untagged manifests all go through the danger modal; the
 * description saves without one.
 *
 * What a click PERSISTED is read back through the panel API, which is what a rename or a delete is
 * about: the old name stops resolving, the new one starts.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { rawGetManifest } from '../../../src/clients/docker-raw.js';
import { adminCredential, minimalPom, rawPut, versionDir } from '../../../src/clients/maven-raw.js';
import { DESCRIPTION_MAX_TEXT, bulleted } from '../../../src/ui/credential-messages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { RepoSettingsReadback } from '../../../src/ui/pages/repo-settings/readback.js';

const SETTINGS = '@settings';

test.describe('Repository settings: rename and description', { tag: SETTINGS }, () => {
  test('SET-05 renaming a repo moves the URL and breadcrumb, and the old URL 404s', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const newName = seeder.reserveRepoName(RepoType.NPM);
    // Tracked so cleanup deletes it; the original name is gone by then (a 404 is tolerated).
    seeder.adoptRepo(newName);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    // The form starts on the current name and refuses to rename to it.
    await expect(settings.info.renameInput).toHaveValue(repo.name);
    await expect(settings.info.renameSubmit).toBeDisabled();

    await settings.info.typeName(newName);
    await expect(settings.info.renameSubmit).toBeEnabled();

    // Cancelling the confirmation changes nothing.
    await settings.info.renameSubmit.click();
    await settings.shell.dangerModal.expectOpen('Rename Repository');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    expect((await panelApi.listAllRepos({ type: RepoType.NPM })).map((r) => r.name)).not.toContain(
      newName,
    );

    await settings.info.renameSubmit.click();
    await settings.shell.dangerModal.expectOpen('Rename Repository');
    await settings.shell.dangerModal.confirm();

    await settings.shell.toasts.expectSuccess('Repository renamed successfully');
    await expect(adminPage).toHaveURL(new RegExp(`/${newName}/settings$`));
    const renamed = new RepoSettingsPage(adminPage, newName);
    await renamed.expectLoaded();
    await expect(renamed.info.renameInput).toHaveValue(newName);
    await expect(adminPage.getByTestId('breadcrumb-item-1')).toContainText(newName);

    const names = (await panelApi.listAllRepos({ type: RepoType.NPM })).map((r) => r.name);
    expect(names).toContain(newName);
    expect(names).not.toContain(repo.name);
    expect((await panelApi.getSettings(newName)).privateRepo).toBe(true);

    // A fresh page load of the old address finds nothing (a same-session navigation could still
    // hit the SPA's cached repo lookup, so this reloads on purpose).
    await adminPage.goto(`/${repo.name}/settings`);
    await expect(adminPage.getByTestId('not-found')).toBeVisible();
    await expect(renamed.root).toHaveCount(0);
  });

  test('SET-05 the rename form validates the name', async ({ adminPage, seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const { info } = settings;

    await info.typeName('');
    await expect(info.renameError('required')).toBeVisible();
    await expect(info.renameSubmit).toBeDisabled();

    // Anything outside [a-zA-Z0-9_-], or starting with "-", is a pattern error, and the message says
    // what the rule is (the pattern and maxlength texts were once swapped; fixed on main, so this pins the right ones).
    for (const bad of ['has space', '-leading-dash', 'dot.name', 'slash/name']) {
      await info.typeName(bad);
      await expect(info.renameError('pattern'), bad).toBeVisible();
      await expect(info.renameError('pattern'), bad).toContainText('letters, numbers');
      await expect(info.renameSubmit, bad).toBeDisabled();
    }

    // 26 characters is one over the limit; 25 is fine.
    await info.typeName('a'.repeat(26));
    await expect(info.renameError('maxlength')).toBeVisible();
    await expect(info.renameError('maxlength')).toContainText('25 characters');
    await expect(info.renameSubmit).toBeDisabled();

    await info.typeName('a'.repeat(25));
    await expect(info.renameError('maxlength')).toHaveCount(0);
    await expect(info.renameError('pattern')).toHaveCount(0);
    await expect(info.renameSubmit).toBeEnabled();
  });

  test('SET-05 the description saves, resets, and is validated', async ({
    adminPage,
    seeder,
    adminSession,
  }) => {
    const readback = new RepoSettingsReadback(adminSession.token);
    const repo = await seeder.createRepo(RepoType.NPM, {
      privateRepo: true,
      description: 'the seeded description',
    });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const { info } = settings;

    await expect(info.descriptionInput).toHaveValue('the seeded description');
    // Nothing edited yet: nothing to save or reset.
    await expect(info.descriptionSave).toBeDisabled();
    await expect(info.descriptionReset).toBeDisabled();

    // Reset throws an edit away and leaves the stored text alone.
    await info.descriptionInput.fill('a draft nobody saves');
    await expect(info.descriptionSave).toBeEnabled();
    await info.descriptionReset.click();
    await expect(info.descriptionInput).toHaveValue('the seeded description');
    await expect(info.descriptionSave).toBeDisabled();
    expect((await readback.permissions(repo.name)).description).toBe('the seeded description');

    await info.descriptionInput.fill('a new description');
    await info.descriptionSave.click();
    await settings.shell.toasts.expectSuccess('Repository description updated successfully');
    await expect(info.descriptionSave).toBeDisabled();
    await expect
      .poll(async () => (await readback.permissions(repo.name)).description)
      .toBe('a new description');

    await settings.reload();
    await expect(info.descriptionInput).toHaveValue('a new description');

    // More than 500 characters is refused before anything is sent.
    await expect(info.descriptionInput).not.toHaveAttribute('maxlength', /.*/);
    await info.descriptionInput.fill('x'.repeat(501));
    await info.descriptionInput.blur();
    await expect(info.descriptionError).toHaveText(bulleted(DESCRIPTION_MAX_TEXT));
    await expect(info.descriptionCounter).toHaveText('501/500');
    await expect(info.descriptionSave).toBeDisabled();
    await info.descriptionInput.fill('x'.repeat(500));
    await expect(info.descriptionError).toHaveCount(0);
    await expect(info.descriptionCounter).toHaveText('500/500');
    await expect(info.descriptionSave).toBeEnabled();
  });
});

test.describe('Repository settings: delete', { tag: SETTINGS }, () => {
  test(
    'SET-06 deleting a repo asks first, then lands on /repositories with a toast',
    { tag: ['@smoke'] },
    async ({ adminPage, seeder, panelApi }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      const repoNames = async () =>
        (await panelApi.listAllRepos({ type: RepoType.NPM })).map((r) => r.name);

      // Cancel: the modal closes, the repo and the page stay.
      await settings.deleteRepo.deleteButton.click();
      await settings.shell.dangerModal.expectOpen('Delete Repository');
      await settings.shell.dangerModal.cancel();
      await settings.shell.dangerModal.expectClosed();
      await expect(settings.root).toBeVisible();
      expect(await repoNames()).toContain(repo.name);

      // Confirm.
      await settings.deleteRepo.deleteButton.click();
      await settings.shell.dangerModal.expectOpen('Delete Repository');
      await settings.shell.dangerModal.confirm();

      await settings.shell.toasts.expectSuccess('Repository deleted successfully');
      await expect(adminPage).toHaveURL(/\/repositories$/);
      await expect(settings.root).toHaveCount(0);
      await expect.poll(repoNames).not.toContain(repo.name);
    },
  );
});

test.describe('Repository settings: orphan layers', { tag: SETTINGS }, () => {
  test('SET-07 the Docker Orphan Layers action confirms, calls the API and toasts', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();

    await settings.orphanLayers.delete();
    await settings.shell.dangerModal.expectOpen('Delete Orphan Layers');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();

    await settings.orphanLayers.delete();
    await settings.shell.dangerModal.expectOpen('Delete Orphan Layers');
    const response = adminPage.waitForResponse(
      (res) =>
        res.request().method() === 'DELETE' &&
        res.url().includes(`/api/docker/images/blobs/${repo.name}/orphan-layers`),
    );
    await settings.shell.dangerModal.confirm();

    expect((await response).ok()).toBe(true);
    await settings.shell.toasts.expectSuccess('Orphan layers deleted successfully');
    await expect(settings.orphanLayers.deleteButton).toBeEnabled();
  });

  // RPS-1216: deleting or overriding a tag only moves the tag pointer; the previous manifest stays stored and
  // pullable by its digest until this action removes it (the backend's manual cleanup, no automatic GC).
  test('SET-07b the Docker Untagged Manifests action deletes the manifests no tag points to, and only those', async ({
    adminPage,
    seeder,
    seedVersions,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const [gone, kept] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const admin = adminCredential();
    const pull = async (image: typeof gone, ref: string) =>
      (await rawGetManifest(repo.name, admin, image.name, ref)).status;

    // Deleting a tag only removes the pointer: the manifest is still pullable by digest.
    await panelApi.deleteDockerTag(repo.name, gone.name, gone.version);
    expect(await pull(gone, gone.version)).toBe(404);
    expect(await pull(gone, gone.extra['digest'])).toBe(200);

    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    // The section says what it does and how it differs from Orphan Layers.
    await expect(settings.untaggedManifests.root).toContainText('no tag points to');
    await expect(settings.untaggedManifests.root).toContainText('pullable by digest');

    // Cancelling the confirmation changes nothing.
    await settings.untaggedManifests.delete();
    await settings.shell.dangerModal.expectOpen('Delete Untagged Manifests');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    expect(await pull(gone, gone.extra['digest'])).toBe(200);

    await settings.untaggedManifests.delete();
    await settings.shell.dangerModal.expectOpen('Delete Untagged Manifests');
    const response = adminPage.waitForResponse(
      (res) =>
        res.request().method() === 'DELETE' &&
        res.url().includes(`/api/docker/images/manifests/${repo.name}/untagged`),
    );
    await settings.shell.dangerModal.confirm();

    const answer = await response;
    expect(answer.ok()).toBe(true);
    const result = (await answer.json()) as {
      data: { deletedManifests: number; orphanLayersScheduled: number };
    };
    expect(result.data.deletedManifests).toBe(1);
    // The layers only that manifest used (its config and layer blobs) are freed with it.
    expect(result.data.orphanLayersScheduled).toBeGreaterThan(0);
    await settings.shell.toasts.expectSuccess(
      /^Deleted 1 untagged manifest and \d+ unused layers? \(/,
    );
    await expect(settings.untaggedManifests.deleteButton).toBeEnabled();

    // The untagged manifest is gone for good; the tagged one is untouched.
    expect(await pull(gone, gone.extra['digest'])).toBe(404);
    expect(await pull(kept, kept.version)).toBe(200);
    expect(await pull(kept, kept.extra['digest'])).toBe(200);

    // A second run has nothing left to delete.
    await settings.untaggedManifests.delete();
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('No untagged manifests to delete');
  });

  // RPS-1286: every section used to carry an invisible 100 px top padding (an anchor offset done with a
  // negative margin) that covered the lower part of the section above, so the corners of a button near
  // a section's end hit the next section. The offset is `scroll-margin-top` now.
  test('SET-07 no settings section covers its neighbour, and a section link scrolls to its section', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    await expect(settings.orphanLayers.deleteButton).toBeVisible();
    await expect(settings.untaggedManifests.deleteButton).toBeVisible();

    // Every visible button inside a section: each corner hits the button itself or its own section.
    const covered = await adminPage.evaluate(() => {
      const problems: string[] = [];
      for (const button of document.querySelectorAll<HTMLElement>('section[id] button')) {
        const box = button.getBoundingClientRect();
        if (box.width === 0 || box.height === 0) {
          continue;
        }
        button.scrollIntoView({ block: 'center' });
        const section = button.closest('section');
        const moved = button.getBoundingClientRect();
        for (const [x, y] of [
          [moved.left + 3, moved.top + 3],
          [moved.right - 3, moved.top + 3],
          [moved.left + 3, moved.bottom - 3],
          [moved.right - 3, moved.bottom - 3],
        ]) {
          const hit = document.elementFromPoint(x, y);
          if (hit === null || !(button.contains(hit) || section?.contains(hit))) {
            problems.push(
              `${button.textContent?.trim()} at ${x},${y} hits ${hit?.tagName}#${hit?.id}`,
            );
          }
        }
      }
      return problems;
    });
    expect(covered).toEqual([]);

    // A section's self-link scrolls to it, below the fixed header.
    await settings.orphanLayers.link.click();
    await expect(adminPage).toHaveURL(/#delete-orphan-layers$/);
    const headerBottom = await settings.shell.header.root.evaluate(
      (el) => el.getBoundingClientRect().bottom,
    );
    await expect
      .poll(() => settings.orphanLayers.root.evaluate((el) => el.getBoundingClientRect().top))
      .toBeGreaterThanOrEqual(headerBottom);
  });
});

test.describe('Repository settings: storage', { tag: SETTINGS }, () => {
  test('SET-09 Storage shows the disk used, more than 0 after a package is stored', async ({
    adminPage,
    seeder,
    adminSession,
  }) => {
    const readback = new RepoSettingsReadback(adminSession.token);
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);

    await settings.goto();
    await expect(settings.storage.diskUsed).toHaveText('0 B');

    // A real deploy over the repo port: a POM is a package file the repo now has to store.
    const groupId = `io.repsy.e2e.${seeder.runId}`;
    const pomPath = `${versionDir(groupId, 'storage', '1.0')}/storage-1.0.pom`;
    const put = await rawPut(
      repo.name,
      adminCredential(),
      pomPath,
      minimalPom(groupId, 'storage', '1.0'),
      'application/octet-stream',
    );
    expect(put.status, 'seed PUT of the POM').toBe(200);
    await expect.poll(() => readback.diskUsedBytes(repo.name)).toBeGreaterThan(0);

    await settings.reload();
    await expect(settings.storage.diskUsed).not.toHaveText('0 B');
    await expect(settings.storage.diskUsed).toHaveText(/^\d+(\.\d+)?\s*(B|KB|MB|GB)$/);
  });
});
