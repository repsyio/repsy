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
 * REPO-01..03: the create-repository modal, opened from the repository list. Every repository the
 * UI creates is named with `seeder.reserveRepoName()` and adopted BEFORE the submit, so a failure
 * half way still deletes it.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import {
  CREATE_MODAL_DEFAULT_TYPE,
  UI_REPO_TYPES,
  uiRepoType,
} from '../../../src/ui/repo-types.js';

test.describe('Create repository modal', () => {
  // REPO-01: one generated case per type; a new protocol only needs a row in UI_REPO_TYPES.
  for (const repoType of UI_REPO_TYPES) {
    test(
      `REPO-01 (${repoType.slug}): admin creates a ${repoType.label} repository`,
      { tag: repoType.smoke ? ['@smoke'] : [] },
      async ({ adminPage, seeder, panelApi }) => {
        const repos = new RepositoriesPage(adminPage);
        const name = seeder.reserveRepoName(repoType.type);
        seeder.adoptRepo(name);
        await repos.goto();

        const modal = await repos.openCreateModal();
        await modal.selectType(repoType);
        await modal.fillName(name);
        // Creating reloads the list (all nine types); the success toast lives 3 s, so it is asserted
        // straight after.
        await repos.afterInfoResponses(() => modal.submitButton.click());

        await repos.toasts.expectSuccess('Repository created successfully');
        await modal.expectClosed();
        await repos.search(name);
        await expect(repos.row(name)).toBeVisible();
        await expect(repos.visibility(name)).toHaveText('Private');
        // The cell shows the name cut to ten characters; the full name is in the hover popup.
        await expect(repos.list.inRow(name, 'row-name')).toContainText(name.slice(0, 8));
        await repos.list.inRow(name, 'row-name').hover();
        await expect(repos.list.inRow(name, 'row-name').getByTestId('tooltip-popup')).toHaveText(
          name,
        );
        await expect(repos.list.inRow(name, 'row-created')).toContainText('ago');
        await expect(repos.list.inRow(name, 'row-size')).toHaveText('0 B');
        const created = (await panelApi.listRepos(repoType.type)).find(
          (repo) => repo.name === name,
        );
        expect(created?.privateRepo).toBe(true);
      },
    );
  }

  test('REPO-01: a public repository with a description is created public', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const npm = uiRepoType(RepoType.NPM);
    const name = seeder.reserveRepoName(npm.type);
    seeder.adoptRepo(name);
    await repos.goto();

    const modal = await repos.openCreateModal();
    await expect(modal.visibilityLabel).toHaveText('Private');
    await expect(modal.visibilityToggle).not.toBeChecked();
    await modal.create({
      type: npm,
      name,
      description: 'Created by the UI e2e suite',
      isPrivate: false,
    });

    await repos.toasts.expectSuccess('Repository created successfully');
    await modal.expectClosed();
    await expect(async () => {
      expect((await panelApi.getSettings(name)).privateRepo).toBe(false);
    }).toPass();
    await repos.search(name);
    await expect(repos.visibility(name)).toHaveText('Public');
  });

  test("REPO-01: the modal defaults to Docker, or to the list's type filter", async ({
    adminPage,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const cargo = uiRepoType(RepoType.CARGO);
    await repos.goto();

    const modal = await repos.openCreateModal();
    await expect(modal.typeToggle).toHaveText(CREATE_MODAL_DEFAULT_TYPE.label);
    await modal.closeButton.click();
    await modal.expectClosed();

    await repos.selectType(cargo);
    await repos.openCreateModal();
    await expect(modal.typeToggle).toHaveText(cargo.label);
  });

  // RPS-1267: Cancel sits inside the form; it must be a plain button, and Enter in the name field
  // must send exactly one create request (the form's own submit, not a second key handler).
  test('REPO-01: Cancel closes the modal without a create request, Enter in the name creates once', async ({
    adminPage,
    seeder,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const maven = uiRepoType(RepoType.MAVEN);
    const name = seeder.reserveRepoName(maven.type);
    seeder.adoptRepo(name);
    const creates: string[] = [];
    adminPage.on('request', (request) => {
      if (request.method() === 'POST' && /\/api\/repos\/[A-Za-z]+$/.test(request.url())) {
        creates.push(request.url());
      }
    });
    await repos.goto();

    const modal = await repos.openCreateModal();
    await expect(modal.cancelButton).toHaveAttribute('type', 'button');
    await modal.fillName(name);
    await modal.cancelButton.click();
    await modal.expectClosed();
    // Give a stray submit the time it would need, then check nothing was sent.
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(creates).toEqual([]);

    await repos.openCreateModal();
    await expect(modal.nameInput).toHaveValue('');
    await modal.selectType(maven);
    await modal.fillName(name);
    await repos.afterInfoResponses(() => modal.nameInput.press('Enter'));
    await repos.toasts.expectSuccess('Repository created successfully');
    await modal.expectClosed();
    expect(creates).toHaveLength(1);
  });

  test('REPO-01: creating from the dashboard works too and lands on the list', async ({
    adminPage,
    seeder,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const repos = new RepositoriesPage(adminPage);
    const maven = uiRepoType(RepoType.MAVEN);
    const name = seeder.reserveRepoName(maven.type);
    seeder.adoptRepo(name);
    await dashboard.open({ admin: true });

    const modal = await dashboard.openCreateModal();
    await modal.selectType(maven);
    await modal.fillName(name);
    await repos.afterInfoResponses(() => modal.submitButton.click());

    await repos.toasts.expectSuccess('Repository created successfully');
    await expect(adminPage).toHaveURL('/repositories');
    await repos.search(name);
    await expect(repos.row(name)).toBeVisible();
  });

  test.describe('REPO-02: validation', () => {
    const invalidNames: Array<[string, string, 'maxlength' | 'pattern']> = [
      ['a name of 26 characters', 'a'.repeat(26), 'maxlength'],
      ['a leading dash', '-repo', 'pattern'],
      ['a space', 'my repo', 'pattern'],
      ['a dot', 'my.repo', 'pattern'],
      ['a slash', 'my/repo', 'pattern'],
      ['a non-ASCII letter', 'répo', 'pattern'],
    ];

    test('the empty name shows "required" and disables Create', async ({ adminPage }) => {
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const modal = await repos.openCreateModal();

      // The message only shows once the field was touched and left.
      await expect(modal.submitButton).toBeDisabled();
      await modal.fillName('x');
      await expect(modal.anyError()).toHaveCount(0);
      await modal.fillName('');

      await expect(modal.nameError('required')).toBeVisible();
      await expect(modal.nameError('required')).toContainText('Should not be empty');
      await expect(modal.submitButton).toBeDisabled();
    });

    for (const [title, value, validator] of invalidNames) {
      test(`${title} shows "${validator}" and disables Create`, async ({ adminPage }) => {
        const repos = new RepositoriesPage(adminPage);
        await repos.goto();
        const modal = await repos.openCreateModal();

        await modal.fillName(value);

        await expect(modal.nameError(validator)).toBeVisible();
        await expect(modal.anyError()).toHaveCount(1);
        await expect(modal.submitButton).toBeDisabled();
      });
    }

    test('25 characters, an underscore start and a dash inside are accepted', async ({
      adminPage,
    }) => {
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const modal = await repos.openCreateModal();

      await modal.fillName('_' + 'a-Z_9'.repeat(4) + '1234');
      await expect(modal.nameInput).toHaveValue(/^.{25}$/);
      await expect(modal.anyError()).toHaveCount(0);
      await expect(modal.submitButton).toBeEnabled();
    });

    test('a name reserved for the panel is refused', async ({ adminPage }) => {
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const modal = await repos.openCreateModal();

      await modal.fillName('login');

      await expect(modal.reservedNameError()).toBeVisible();
      await expect(modal.submitButton).toBeDisabled();
    });

    test('a description of exactly 500 characters is accepted', async ({ adminPage }) => {
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const modal = await repos.openCreateModal();

      await modal.fillName('valid_name');
      await modal.fillDescription('d'.repeat(500));

      await expect(modal.descriptionError()).toHaveCount(0);
      await expect(modal.submitButton).toBeEnabled();
    });

    test('a description over 500 characters shows "maxlength" and disables Create', async ({
      adminPage,
    }) => {
      test.fail(
        true,
        'RPS-1265: the description textarea has a maxlength="500" attribute, so typed or pasted ' +
          'text is silently cut at 500 and the ">500" error can never show',
      );
      const repos = new RepositoriesPage(adminPage);
      await repos.goto();
      const modal = await repos.openCreateModal();

      await modal.fillName('valid_name');
      // Real key presses, not fill(): fill() sets the value from script, which the maxlength
      // attribute does not limit, and would hide the defect.
      await modal.descriptionInput.click();
      await modal.descriptionInput.pressSequentially('d'.repeat(501));
      await modal.descriptionInput.blur();

      await expect(modal.descriptionError()).toBeVisible();
      await expect(modal.submitButton).toBeDisabled();
    });
  });

  test('REPO-03: a duplicate name shows the server error and keeps the modal open', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repos = new RepositoriesPage(adminPage);
    const maven = uiRepoType(RepoType.MAVEN);
    const existing = await seeder.createRepo(RepoType.MAVEN);
    await repos.goto();

    const modal = await repos.openCreateModal();
    await modal.selectType(maven);
    await modal.fillName(existing.name);
    await modal.submitButton.click();

    await repos.toasts.expectError('The repository exists. Please try another name.');
    await expect(repos.toasts.success()).toHaveCount(0);
    await modal.expectOpen();
    await expect(modal.nameInput).toHaveValue(existing.name);
    // The form is usable again (it is disabled while the request runs).
    await expect(modal.submitButton).toBeEnabled();
    const same = (await panelApi.listRepos(RepoType.MAVEN)).filter(
      (repo) => repo.name === existing.name,
    );
    expect(same).toHaveLength(1);
  });
});
