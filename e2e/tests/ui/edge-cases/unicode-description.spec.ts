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

/**
 * RPS-1761, Unicode in repository descriptions and package READMEs (the OS half of the shared edge-case story;
 * the Cloud half is repsy-mono #1695).
 *
 * What the 500 limit counts, read from the code and then asserted:
 *  - the panel forms: `Validators.maxLength(500)` and the `n/500` counter (`description.validators.ts`) use JS
 *    `String.length`, UTF-16 code units;
 *  - the backend: `maxLength: 500` of `RepoCreateRequest`, `RepoDescriptionForm` and `DeployTokenForm`
 *    (`openapi-spec.yaml`) becomes `@Size(max = 500)` on a `String`, which Hibernate Validator also counts in
 *    UTF-16 units; the column is `varchar(500)` (V0001), which counts characters and so is never stricter.
 * So the limit is neither bytes (500 x "e-acute" is 1000 bytes of UTF-8 and fits) nor code points (an emoji
 * outside the BMP is two units: 250 fit, 251 do not).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { publishCrate } from '../../../src/seed/packages/cargo.js';
import { DESCRIPTION_MAX_TEXT, bulleted } from '../../../src/ui/credential-messages.js';
import { panelCall, repoDescription } from '../../../src/ui/edge-case-support.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoCreateModal } from '../../../src/ui/pages/repo-create-modal.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { uiRepoType } from '../../../src/ui/repo-types.js';
import { repoApiPath } from '../../../src/ui/routes.js';

const GRINNING = '\u{1F600}';
/** 250 emoji = 500 UTF-16 units = 1000 UTF-8 bytes = 250 code points. */
const EMOJI_AT_LIMIT = GRINNING.repeat(250);
const EMOJI_OVER_LIMIT = GRINNING.repeat(251);
/** 500 units, 500 code points, 1000 UTF-8 bytes. */
const ACCENTED_AT_LIMIT = 'é'.repeat(500);
/** Arabic and Hebrew (right to left), a ZWJ emoji sequence, a combining accent (NFD) and CJK in one line. */
const MIXED_SCRIPTS = 'English مرحبا بالعالم שלום עולם \u{1F3F3}️‍\u{1F308} é 日本語';

// @cloud-skip: Repsy Cloud has its own copy in repsy-cloud/e2e/ui-cloud/edge-cases/unicode-description.spec.ts (#1695).
test.describe('RPS-1761 Unicode: what the 500 limit counts', { tag: ['@cloud-skip'] }, () => {
  test('the create modal accepts 250 emoji (500 units, 1000 bytes) and stores them unchanged', async ({
    adminPage,
    adminSession,
    seeder,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(name);
    await adminPage.goto('/repositories');
    await adminPage.getByTestId('repo-create').first().click();
    const modal = new RepoCreateModal(adminPage);
    await modal.expectOpen();
    await modal.selectType(uiRepoType(RepoType.NPM));
    await modal.fillName(name);
    await modal.fillDescription(EMOJI_AT_LIMIT);

    await expect(modal.descriptionCounter()).toHaveText('500/500');
    await expect(modal.descriptionError()).toBeHidden();
    await expect(modal.submitButton).toBeEnabled();

    const created = adminPage.waitForResponse(
      (res) => new URL(res.url()).pathname === '/api/repos' && res.request().method() === 'POST',
    );
    await modal.submitButton.click();
    expect((await created).status()).toBe(201);
    await modal.expectClosed();
    expect(await repoDescription(adminSession.token, name)).toBe(EMOJI_AT_LIMIT);
  });

  test('the create modal rejects 251 emoji (502 units, only 251 code points): counter, message, Create disabled', async ({
    adminPage,
    seeder,
  }) => {
    const name = seeder.reserveRepoName(RepoType.NPM);
    await adminPage.goto('/repositories');
    await adminPage.getByTestId('repo-create').first().click();
    const modal = new RepoCreateModal(adminPage);
    await modal.expectOpen();
    await modal.selectType(uiRepoType(RepoType.NPM));
    await modal.fillName(name);
    await modal.fillDescription(EMOJI_OVER_LIMIT);

    await expect(modal.descriptionCounter()).toHaveText('502/500');
    await expect(modal.descriptionError()).toHaveText(bulleted(DESCRIPTION_MAX_TEXT));
    await expect(modal.submitButton).toBeDisabled();
  });

  test('the API counts units as well: 500 accented letters (1000 bytes) and 250 emoji are stored, 251 emoji is a 400', async ({
    adminSession,
    seeder,
  }) => {
    const token = adminSession.token;
    const accented = seeder.reserveRepoName(RepoType.NPM);
    const emoji = seeder.reserveRepoName(RepoType.NPM);
    const refused = seeder.reserveRepoName(RepoType.NPM);
    seeder.adoptRepo(accented);
    seeder.adoptRepo(emoji);

    const create = (name: string, description: string) =>
      panelCall(token, 'POST', '/api/repos', {
        type: RepoType.NPM,
        name,
        description,
        privateRepo: true,
      });

    expect((await create(accented, ACCENTED_AT_LIMIT)).status, 'bytes are not counted').toBe(201);
    expect(await repoDescription(token, accented)).toBe(ACCENTED_AT_LIMIT);
    expect((await create(emoji, EMOJI_AT_LIMIT)).status).toBe(201);
    expect(await repoDescription(token, emoji)).toBe(EMOJI_AT_LIMIT);
    expect((await create(refused, EMOJI_OVER_LIMIT)).status, 'code points are not counted').toBe(
      400,
    );
    expect(await repoDescription(token, refused)).toBeUndefined();

    const patch = (description: string) =>
      panelCall(token, 'PATCH', repoApiPath(emoji), { description });
    expect((await patch(EMOJI_OVER_LIMIT)).status).toBe(400);
    expect(await repoDescription(token, emoji)).toBe(EMOJI_AT_LIMIT);
    expect((await patch(ACCENTED_AT_LIMIT)).status).toBe(200);
    expect(await repoDescription(token, emoji)).toBe(ACCENTED_AT_LIMIT);
  });

  test('the deploy-token description counts units too: 251 emoji is 502/500 and blocks Create', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const settings = new RepoSettingsPage(adminPage, repo.name);
    await settings.goto();
    const modal = await settings.tokens.openCreateModal();
    await modal.fill({ name: 'unicode-description', description: EMOJI_AT_LIMIT });
    await expect(modal.descriptionCounter()).toHaveText('500/500');
    await expect(modal.submitButton).toBeEnabled();
    await modal.fill({ description: EMOJI_OVER_LIMIT });
    await expect(modal.descriptionCounter()).toHaveText('502/500');
    await modal.description.blur();
    await expect(modal.descriptionError()).toHaveText(bulleted(DESCRIPTION_MAX_TEXT));
    await expect(modal.submitButton).toBeDisabled();
  });
});

// @cloud-skip: see above.
test.describe(
  'RPS-1761 Unicode: scripts and symbols in descriptions and READMEs',
  { tag: ['@cloud-skip'] },
  () => {
    test('Arabic, Hebrew, a ZWJ emoji, a combining accent and CJK survive an edit on the settings page and a reload', async ({
      adminPage,
      adminSession,
      seeder,
    }) => {
      const repo = await seeder.createRepo(RepoType.NPM, {
        description: 'plain ascii to start with',
      });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      const { info } = settings;
      await info.descriptionInput.fill(MIXED_SCRIPTS);
      await expect(info.descriptionCounter).toHaveText(`${MIXED_SCRIPTS.length}/500`);
      await expect(info.descriptionSave).toBeEnabled();
      await info.descriptionSave.click();
      await expect.poll(() => repoDescription(adminSession.token, repo.name)).toBe(MIXED_SCRIPTS);

      await settings.reload();
      await expect(info.descriptionInput).toHaveValue(MIXED_SCRIPTS);
    });

    test('a crate README in Arabic, Hebrew, CJK and emoji renders as written', async ({
      adminPage,
      seeder,
    }) => {
      const repo = await seeder.createRepo(RepoType.CARGO);
      const title = 'Ünïcödé 日本語 \u{1F389}';
      const arabic = 'مرحبا بالعالم';
      const hebrew = 'שלום עולם';
      const readme = `# ${title}\n\n${arabic}\n\n${hebrew}\n`;
      const pkg = await publishCrate(repo.name, `e2e_${seeder.runId}_uni`, '1.0.0', { readme });
      const detail = protocolPages(adminPage, DESCRIPTORS.cargo, repo.name).detail(pkg);
      await detail.goto();

      await expect(detail.readme).toBeVisible();
      await expect(detail.readme.getByRole('heading', { name: title, level: 1 })).toBeVisible();
      await expect(detail.readme).toContainText(arabic);
      await expect(detail.readme).toContainText(hebrew);
    });
  },
);
