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
 * The Deploy Tokens section of `/:repo/settings` (TOK-01..TOK-05): create (R/W and R/O, custom
 * username, expiration date), the one-time information modal, rotate and revoke behind the danger
 * modal, page size 3, the expiry colours and the form validation.
 *
 * A token is only worth what the server does with it, so besides the list (read back through the
 * panel API) TOK-03 and TOK-04 use the token on the repo PORT: `GET /<repo>/` with Basic auth, no
 * package-manager client, answers 200 for a live token and 401 for a revoked or rotated-away one.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import {
  DESCRIPTION_MAX_TEXT,
  REQUIRED_TEXT,
  USERNAME_TEXT,
  bulleted,
} from '../../../src/ui/credential-messages.js';
import type { SeededToken } from '../../../src/seed/seeder.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';
import { repoRootStatus } from '../../../src/ui/pages/repo-settings/readback.js';
import {
  EXPIRES_ERROR_CLASS,
  EXPIRES_WARNING_CLASS,
} from '../../../src/ui/pages/repo-settings/deploy-tokens.js';

const SETTINGS = '@settings';
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

/** `YYYY-MM-DD` of today (UTC) plus `days`, the format and zone of the form's date input. */
function utcDate(days: number): string {
  return new Date(Date.now() + days * ONE_DAY_MS).toISOString().slice(0, 10);
}

/** The seeded token called `name`; the specs look one up by a name read off the page. */
function seededByName(seeded: SeededToken[], name: string): SeededToken {
  const found = seeded.find((token) => token.name === name);
  if (!found) {
    throw new Error(`no seeded token called ${name}`);
  }
  return found;
}

/** `GET /api/repos/<repo>/deploy-tokens?page=<n>` (zero-based `n`), the list the section reads. */
function isTokenListRequest(url: URL, repoName: string, pageIndex: number): boolean {
  return (
    url.pathname.endsWith(`/api/repos/${repoName}/deploy-tokens`) &&
    url.searchParams.get('page') === String(pageIndex)
  );
}

test.describe('Deploy tokens: create', { tag: SETTINGS }, () => {
  test(
    'TOK-01 a Read/Write token: the info modal shows the username and token once, copy works, the row says R/W',
    { tag: ['@smoke'] },
    async ({ adminPage, context, seeder, panelApi }) => {
      await context.grantPermissions(['clipboard-read', 'clipboard-write']);
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const name = `tok-rw-${seeder.runId}`;
      const settings = new RepoSettingsPage(adminPage, repo.name);
      const { tokens } = settings;
      await settings.goto();
      await expect(tokens.empty).toBeVisible();
      await expect(tokens.rows).toHaveCount(0);

      await tokens.openCreateModal();
      // Read/Write is the default access type, a year out the default expiry.
      await expect(tokens.createModal.access(false)).toBeChecked();
      await expect(tokens.createModal.access(true)).not.toBeChecked();
      await tokens.createModal.create({ name, description: 'created by the UI suite' });

      await settings.shell.toasts.expectSuccess('Deploy token created successfully.');
      await tokens.createModal.expectClosed();
      await tokens.infoModal.expectOpen();
      const { username, token } = await tokens.infoModal.values();
      expect(username).not.toBe('');
      expect(token).not.toBe('');

      // The secret is masked until asked for.
      await expect(tokens.infoModal.token).toHaveAttribute('type', 'password');
      await tokens.infoModal.toggleTokenVisibility();
      await expect(tokens.infoModal.tokenToggle).toHaveAttribute('aria-pressed', 'true');
      await expect(tokens.infoModal.token).toHaveAttribute('type', 'text');
      await expect(tokens.infoModal.token).toHaveValue(token);

      // Copy puts the very values on the clipboard and says so on the button.
      await tokens.infoModal.copyToken.click();
      await expect(tokens.infoModal.copyToken).toHaveAttribute('data-copied', 'true');
      expect(await adminPage.evaluate(() => navigator.clipboard.readText())).toBe(token);
      await tokens.infoModal.copyUsername.click();
      await expect(tokens.infoModal.copyUsername).toHaveAttribute('data-copied', 'true');
      expect(await adminPage.evaluate(() => navigator.clipboard.readText())).toBe(username);

      await tokens.infoModal.close();

      // The list row: R/W, the username, no expiry warning (a year away).
      await tokens.expectRowCount(1);
      await expect(tokens.cell(name, 'row-permission')).toHaveText('R/W');
      await tokens.expectCellText(name, 'row-username', username);
      await expect(tokens.cell(name, 'row-expires')).not.toHaveClass(EXPIRES_WARNING_CLASS);
      await expect(tokens.cell(name, 'row-expires')).not.toHaveClass(EXPIRES_ERROR_CLASS);

      // Persisted, and the secret is not part of what the server lists (it is shown once).
      const listed = await panelApi.listDeployTokens(repo.name);
      expect(listed).toHaveLength(1);
      expect(listed[0]).toMatchObject({ name, username, read_only: false });
      expect(JSON.stringify(listed)).not.toContain(token);

      // Gone for good: a reload shows the row and no modal, and the token is nowhere on the page.
      await settings.reload();
      await tokens.expectRowCount(1);
      await tokens.infoModal.expectClosed();
      await expect(settings.root).not.toContainText(token);
    },
  );

  test('TOK-02 a Read Only token with a custom username and a near expiry, and the expiry colours', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { tokens } = settings;

    // Two tokens the UI cannot make: one already expired, one far from expiring.
    const expired = await seeder.createToken(repo.name, {
      name: 'tok-expired',
      expirationDate: new Date(Date.now() - ONE_DAY_MS),
    });
    const distant = await seeder.createToken(repo.name, {
      name: 'tok-distant',
      expirationDate: new Date(Date.now() + 30 * ONE_DAY_MS),
    });
    await settings.goto();

    // The near one: a custom username, Read Only, three days out (inside the 7-day warning window).
    const name = 'tok-ro-soon';
    const username = `ro-${seeder.runId}`;
    const expirationDate = utcDate(3);
    await tokens.openCreateModal();
    await tokens.createModal.create({ name, username, readOnly: true, expirationDate });

    await settings.shell.toasts.expectSuccess('Deploy token created successfully.');
    const shown = await tokens.infoModal.values();
    expect(shown.username).toBe(username);
    await tokens.infoModal.close();

    await tokens.expectRowCount(3);
    await expect(tokens.cell(name, 'row-permission')).toHaveText('R/O');
    await tokens.expectCellText(name, 'row-username', username);
    await expect(tokens.cell(expired.name, 'row-permission')).toHaveText('R/W');

    // Warning inside 7 days, error once past, neither for a token that is far off.
    await expect(tokens.cell(name, 'row-expires')).toHaveClass(EXPIRES_WARNING_CLASS);
    await expect(tokens.cell(name, 'row-expires')).not.toHaveClass(EXPIRES_ERROR_CLASS);
    await expect(tokens.cell(expired.name, 'row-expires')).toHaveClass(EXPIRES_ERROR_CLASS);
    await expect(tokens.cell(expired.name, 'row-expires')).not.toHaveClass(EXPIRES_WARNING_CLASS);
    await expect(tokens.cell(distant.name, 'row-expires')).not.toHaveClass(EXPIRES_WARNING_CLASS);
    await expect(tokens.cell(distant.name, 'row-expires')).not.toHaveClass(EXPIRES_ERROR_CLASS);

    // The server kept the username, the access type and the chosen day.
    const stored = (await panelApi.listDeployTokens(repo.name)).find((t) => t.name === name);
    expect(stored).toMatchObject({ username, read_only: true });
    expect(stored?.expiration_date?.slice(0, 10)).toBe(expirationDate);
  });
});

test.describe('Deploy tokens: rotate, revoke and paging', { tag: SETTINGS }, () => {
  test('TOK-03 rotate shows a new token and kills the old one; revoke removes the row; page size is 3', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const created: SeededToken[] = [];
    for (const name of ['tok-a', 'tok-b', 'tok-c', 'tok-d']) {
      created.push(await seeder.createToken(repo.name, { name }));
    }
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { tokens } = settings;
    await settings.goto();

    // Four tokens, three per page.
    await tokens.expectRowCount(3);
    await expect(tokens.pagination.root).toBeVisible();
    await expect(tokens.numberedButton(2)).toBeVisible();
    await expect(tokens.numberedButton(3)).toHaveCount(0);
    const firstPage = await tokens.rowNames();
    await tokens.numberedButton(2).click();
    await expect.poll(() => tokens.rowNames()).toHaveLength(1);
    const secondPage = await tokens.rowNames();
    expect([...firstPage, ...secondPage].sort()).toEqual(['tok-a', 'tok-b', 'tok-c', 'tok-d']);
    await tokens.numberedButton(1).click();
    await expect.poll(() => tokens.rowNames()).toEqual(firstPage);

    // Rotate one token: cancel first (nothing changes), then confirm.
    const rotated = seededByName(created, firstPage[0]);
    await tokens.rotateButton(rotated.name).click();
    await settings.shell.dangerModal.expectOpen('Rotate Deploy Token');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    await tokens.infoModal.expectClosed();
    expect(await repoRootStatus(repo.name, rotated)).toBe(200);

    await tokens.rotateButton(rotated.name).click();
    await settings.shell.dangerModal.expectOpen('Rotate Deploy Token');
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Deploy token rotated successfully');
    const fresh = await tokens.infoModal.values();
    expect(fresh.username).toBe(rotated.username);
    expect(fresh.token).not.toBe('');
    expect(fresh.token).not.toBe(rotated.token);
    await tokens.infoModal.close();

    // The new secret works on the repo port, the old one no longer does.
    expect(await repoRootStatus(repo.name, fresh)).toBe(200);
    expect(await repoRootStatus(repo.name, rotated)).toBe(401);
    // Still the same four tokens.
    expect(await panelApi.listDeployTokens(repo.name)).toHaveLength(4);

    // Revoke another token on page 1: cancel first, then confirm; the fourth token moves up, so the
    // three that are left fit on one page and the pagination goes away.
    const revoked = seededByName(created, firstPage[1]);
    await tokens.revokeButton(revoked.name).click();
    await settings.shell.dangerModal.expectOpen('Delete Deploy Token');
    await settings.shell.dangerModal.cancel();
    await settings.shell.dangerModal.expectClosed();
    await expect(tokens.row(revoked.name)).toBeVisible();
    expect(await panelApi.listDeployTokens(repo.name)).toHaveLength(4);

    await tokens.revokeButton(revoked.name).click();
    await settings.shell.dangerModal.expectOpen('Delete Deploy Token');
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Deploy token revoked successfully');
    await expect(tokens.row(revoked.name)).toHaveCount(0);
    await tokens.expectRowCount(3);
    await expect(tokens.pagination.root).toBeHidden();
    await expect
      .poll(async () => (await panelApi.listDeployTokens(repo.name)).map((t) => t.name).sort())
      .toEqual(['tok-a', 'tok-b', 'tok-c', 'tok-d'].filter((n) => n !== revoked.name));
    expect(await repoRootStatus(repo.name, revoked)).toBe(401);
  });

  // RPS-1285. Revoking the only token on page 2 makes `revokeDeployToken`
  // refetch page 2 (now past the end, an empty list) and, in the same tick, page 1, because it tests
  // `deployTokens.length === 1` right after starting the first request. Whichever response lands LAST
  // wins, so when the empty page-2 answer is the slower one the section shows "Your list is empty"
  // while three tokens exist. The delay below makes that order certain instead of a coin toss.
  test.fail(
    'TOK-03 revoking the last token on page 2 shows the remaining tokens, whatever order the lists arrive in (RPS-1285)',
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      for (const name of ['tok-a', 'tok-b', 'tok-c', 'tok-d']) {
        await seeder.createToken(repo.name, { name });
      }
      const settings = new RepoSettingsPage(adminPage, repo.name);
      const { tokens } = settings;
      await settings.goto();
      await tokens.numberedButton(2).click();
      await expect.poll(() => tokens.rowNames()).toHaveLength(1);

      // Only the request for the (soon empty) second page is slowed down, from here on.
      const isSecondPageRequest = (url: URL) => isTokenListRequest(url, repo.name, 1);
      await adminPage.route(isSecondPageRequest, async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 1_500));
        await route.continue();
      });
      const slowAnswer = adminPage.waitForResponse((res) =>
        isSecondPageRequest(new URL(res.url())),
      );

      const [lastName] = await tokens.rowNames();
      await tokens.revokeButton(lastName).click();
      await settings.shell.dangerModal.confirm();
      await settings.shell.toasts.expectSuccess('Deploy token revoked successfully');

      // Wait for the late answer AND for the browser to paint it: the three tokens show up first
      // (the fast page-1 list), so a bare row count could pass on that transient state.
      await slowAnswer;
      await adminPage.evaluate(
        () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
      );
      await tokens.expectRowCount(3);
    },
  );
});

test.describe('Deploy tokens: on the repo port', { tag: SETTINGS }, () => {
  test('TOK-04 a token made in the UI works on the repo port and stops working once revoked', async ({
    adminPage,
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const name = 'tok-wire';
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { tokens } = settings;
    await settings.goto();

    // The repo is private: without a credential the port says 401, so a 200 below is the token's.
    expect(await repoRootStatus(repo.name)).toBe(401);

    await tokens.openCreateModal();
    await tokens.createModal.create({ name });
    await settings.shell.toasts.expectSuccess('Deploy token created successfully.');
    const credential = await tokens.infoModal.values();
    await tokens.infoModal.close();

    expect(await repoRootStatus(repo.name, credential)).toBe(200);
    // The right username with a wrong secret is still refused.
    expect(await repoRootStatus(repo.name, { ...credential, token: `${credential.token}x` })).toBe(
      401,
    );

    await tokens.revokeButton(name).click();
    await settings.shell.dangerModal.expectOpen('Delete Deploy Token');
    await settings.shell.dangerModal.confirm();
    await settings.shell.toasts.expectSuccess('Deploy token revoked successfully');
    await expect(tokens.row(name)).toHaveCount(0);
    await expect(tokens.empty).toBeVisible();

    expect(await repoRootStatus(repo.name, credential)).toBe(401);
    expect(await panelApi.listDeployTokens(repo.name)).toEqual([]);
  });
});

test.describe('Deploy tokens: the create form', { tag: SETTINGS }, () => {
  test('TOK-05 name is required and at most 80 characters, the username follows its pattern', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const settings = new RepoSettingsPage(adminPage, repo.name);
    const { tokens } = settings;
    await settings.goto();
    const modal = await tokens.openCreateModal();

    // An untouched form is not submittable, but says nothing yet.
    await expect(modal.submitButton).toBeDisabled();
    await expect(modal.nameError('required')).toHaveCount(0);

    await modal.touch(modal.name);
    await expect(modal.nameError('required')).toHaveText(bulleted(REQUIRED_TEXT));
    await expect(modal.submitButton).toBeDisabled();

    await modal.name.fill('a'.repeat(81));
    await expect(modal.nameError('maxlength')).toBeVisible();
    await expect(modal.nameError('required')).toHaveCount(0);
    await expect(modal.submitButton).toBeDisabled();

    await modal.name.fill('a'.repeat(80));
    await expect(modal.nameError('maxlength')).toHaveCount(0);
    await expect(modal.submitButton).toBeEnabled();

    // The username is optional, but when given it is 3-25 of [a-z0-9_-].
    await modal.username.fill('ab');
    await modal.username.blur();
    // The same sentences as the create-user modal and the profile (RPS-1265).
    await expect(modal.usernameError('minlength')).toHaveText(bulleted(USERNAME_TEXT.minlength));
    await expect(modal.submitButton).toBeDisabled();

    for (const bad of ['Has Space', 'UPPER', 'dot.name', 'semi;colon']) {
      await modal.username.fill(bad);
      await expect(modal.usernameError('pattern'), bad).toHaveText(bulleted(USERNAME_TEXT.pattern));
      await expect(modal.submitButton, bad).toBeDisabled();
    }

    await modal.username.fill('a'.repeat(26));
    await expect(modal.usernameError('maxlength')).toHaveText(bulleted(USERNAME_TEXT.maxlength));
    await expect(modal.submitButton).toBeDisabled();

    await modal.username.fill('ok_user-1');
    await expect(modal.usernameError('pattern')).toHaveCount(0);
    await expect(modal.usernameError('maxlength')).toHaveCount(0);
    await expect(modal.usernameError('minlength')).toHaveCount(0);
    await expect(modal.submitButton).toBeEnabled();

    // The description is capped at 500: a counter and a message, and no maxlength attribute that would
    // cut the text (RPS-1265).
    await expect(modal.description).not.toHaveAttribute('maxlength', /.*/);
    await modal.description.fill('x'.repeat(501));
    await modal.description.blur();
    await expect(modal.descriptionError()).toHaveText(bulleted(DESCRIPTION_MAX_TEXT));
    await expect(modal.descriptionCounter()).toHaveText('501/500');
    await expect(modal.submitButton).toBeDisabled();
    await modal.description.fill('x'.repeat(500));
    await expect(modal.descriptionError()).toHaveCount(0);
    await expect(modal.descriptionCounter()).toHaveText('500/500');
    await expect(modal.submitButton).toBeEnabled();

    // Cancel closes without creating anything.
    await modal.cancelButton.click();
    await modal.expectClosed();
    await expect(tokens.empty).toBeVisible();
  });

  // RPS-1266: `#name` and `#description` (and `#username`) exist twice on this page, once in the
  // rename / description form of Repository Info and once in the create-token modal, so a
  // `<label for>` or `getByLabel('Name')` resolves to the wrong control. Ids must be unique per
  // document; expected to fail until the components get scoped ids.
  test.fail(
    'TOK-05 the create-token modal does not reuse the ids of the rename form (RPS-1266)',
    async ({ adminPage, seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      await settings.tokens.openCreateModal();

      await expect(adminPage.locator('#name')).toHaveCount(1);
      await expect(adminPage.locator('#description')).toHaveCount(1);
    },
  );
});
