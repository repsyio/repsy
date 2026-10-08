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
 * Personal access tokens in the panel (RPS-1905, RPS-2002; PAT-01..PAT-08): the section that lists, creates and
 * revokes them (the Settings page of Repsy OS, `accessTokensRoute()`), the one-time secret, the limit of 50, and
 * the password warning that links to it. Repsy Cloud runs these specs too, through the target layer.
 *
 * A token is only worth what the server does with it, so PAT-06 uses the secret against
 * `GET /api/profile/access-tokens/current` and expects 401 the moment the token is revoked in the UI.
 *
 * Every test has its OWN account (`tokenUser`): the list, "revoke all" and the limit are per user, so the
 * harness admin cannot be shared between parallel tests. Names are unique per run (`seeder.runId`).
 */
import { type AccessTokenScope, explicitScopes, whoAmI } from '../../../src/ui/access-token-api.js';
import { expect, test } from '../../../src/ui/access-token-fixtures.js';
import { allowClipboard, copiedText } from '../../../src/ui/clipboard.js';
import { AccessTokensPage, SettingsPage } from '../../../src/ui/pages/access-tokens.js';
import { ProfilePage } from '../../../src/ui/pages/profile.js';
import { settingsRoute, urlEndsWith } from '../../../src/ui/routes.js';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;
const MAX_LIVE = 50;

/** `YYYY-MM-DD` of today (UTC) plus `days`: the format and zone of the form's date input. */
function utcDate(days: number): string {
  return new Date(Date.now() + days * ONE_DAY_MS).toISOString().slice(0, 10);
}

test.describe('Access tokens: create', () => {
  test(
    'PAT-01 create with a name, scopes and an expiry: the secret is shown once, copy works, the row shows it',
    { tag: ['@smoke'] },
    async ({ tokenPage, tokenApi, seeder }) => {
      await allowClipboard(tokenPage.context());
      const tokens = new AccessTokensPage(tokenPage);
      const name = `pat-${seeder.runId}-create`;
      const expiry = utcDate(30);
      await tokens.goto();
      await expect(tokens.empty).toBeVisible();
      await expect(tokens.rows).toHaveCount(0);

      await tokens.openCreateModal();
      // No scope is ticked by default: the person chooses.
      await expect(tokens.createModal.scope('repo:read')).not.toBeChecked();
      await tokens.createModal.fill({
        name,
        scopes: ['repo:read', 'repo:write'],
        expirationDate: expiry,
      });
      await expect(tokens.createModal.submit).toBeEnabled();
      await tokens.createModal.submit.click();

      await tokens.createModal.expectClosed();
      await tokens.infoModal.expectOpen();
      const secret = await tokens.infoModal.secret();
      expect(secret).toMatch(/^rut-/);

      // The secret is masked until asked for, and the copy button puts the very value on the clipboard.
      await expect(tokens.infoModal.value).toHaveAttribute('type', 'password');
      await tokens.infoModal.reveal();
      await expect(tokens.infoModal.value).toHaveValue(secret);
      const copy = tokens.infoModal.copyValue.getByTestId('copy-button');
      await copy.click();
      await expect(copy).toHaveAttribute('data-copied', 'true');
      expect(await copiedText(tokenPage)).toBe(secret);

      await tokens.infoModal.close.click();
      await tokens.infoModal.expectClosed();

      // The row: the name, the scopes, never used, and an expiry (the date pipe is local, so the day is read
      // back from the API below).
      await expect(tokens.rows).toHaveCount(1);
      await expect(tokens.cell(name, 'row-name')).toContainText(name);
      // `profile:read` is on every token (implicit), so the row lists it next to the two that were asked for.
      await expect(tokens.cell(name, 'row-scopes')).toHaveText(
        'profile:read, repo:read, repo:write',
      );
      await expect(tokens.cell(name, 'row-last-used')).toHaveText('Never');
      await expect(tokens.cell(name, 'row-expires')).not.toBeEmpty();
      await expect(tokens.cell(name, 'row-expired')).toHaveCount(0);

      // Persisted as asked, and the secret is not part of what the server lists.
      const listed = await tokenApi.list();
      expect(listed).toHaveLength(1);
      expect(listed[0]).toMatchObject({ name });
      expect(explicitScopes(listed[0].scopes)).toEqual(['repo:read', 'repo:write']);
      expect(listed[0].expirationDate.slice(0, 10)).toBe(expiry);
      expect(JSON.stringify(listed)).not.toContain(secret);

      // Gone for good: after a reload the row is there and the secret is nowhere on the page.
      await tokenPage.reload();
      await tokens.expectLoaded();
      await expect(tokens.rows).toHaveCount(1);
      await tokens.infoModal.expectClosed();
      await expect(tokens.root).not.toContainText(secret);
    },
  );

  test('PAT-02 the form validates the name, the scopes and the expiry, and offers no scan scope', async ({
    tokenPage,
  }) => {
    const tokens = new AccessTokensPage(tokenPage);
    await tokens.goto();
    await tokens.openCreateModal();
    const form = tokens.createModal;

    // Nothing filled: no create.
    await expect(form.submit).toBeDisabled();

    // An empty name, once touched.
    await form.touch(form.name);
    await expect(form.nameError('required')).toBeVisible();
    await expect(form.submit).toBeDisabled();

    // A name of 81 characters (the most is 80).
    await form.name.fill('n'.repeat(81));
    await form.touch(form.name);
    await expect(form.nameError('maxlength')).toBeVisible();
    await expect(form.submit).toBeDisabled();

    // A valid name still needs a scope.
    await form.name.fill('n'.repeat(80));
    await expect(form.nameError('maxlength')).toHaveCount(0);
    await expect(form.scopesError).toBeVisible();
    await expect(form.submit).toBeDisabled();
    await form.scope('repo:read').check();
    await expect(form.scopesError).toHaveCount(0);
    await expect(form.submit).toBeEnabled();

    // An expiry past the maximum (a year out) is refused with one message, and recovers.
    await form.expiration.fill(utcDate(400));
    await expect(form.expirationError).toContainText(
      'Must be between tomorrow and one year from today',
    );
    await expect(form.submit).toBeDisabled();
    await form.expiration.fill(utcDate(30));
    await expect(form.expirationError).toHaveCount(0);
    await expect(form.submit).toBeEnabled();

    // The scopes on offer: the three repository scopes, no scan scope.
    await expect(form.offeredScopes).toHaveCount(3);
    await expect(form.scope('scan:read')).toHaveCount(0);
  });
});

test.describe('Access tokens: revoke', () => {
  test('PAT-03 revoke one token behind a confirmation: cancel keeps it, confirm removes only it', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    const keep = `pat-${seeder.runId}-keep`;
    const drop = `pat-${seeder.runId}-drop`;
    await tokenApi.create(keep);
    await tokenApi.create(drop);
    const tokens = new AccessTokensPage(tokenPage);
    await tokens.goto();
    await expect(tokens.rows).toHaveCount(2);

    await tokens.row(drop).getByTestId('row-revoke').click();
    await tokens.dangerModal.expectOpen('Revoke Access Token');
    await tokens.dangerModal.cancel();
    await tokens.dangerModal.expectClosed();
    await expect(tokens.rows).toHaveCount(2);

    await tokens.revoke(drop);
    await expect(tokens.rows).toHaveCount(1);
    await expect(tokens.row(keep)).toBeVisible();
    expect((await tokenApi.list()).map((t) => t.name)).toEqual([keep]);
  });

  test('PAT-04 revoke all removes every token and says how many', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    const count = 3;
    await tokenApi.createMany(count, `pat-${seeder.runId}-all`);
    const tokens = new AccessTokensPage(tokenPage);
    await tokens.goto();
    await expect(tokens.rows).toHaveCount(count);

    await tokens.revokeAllButton.click();
    await tokens.dangerModal.expectOpen('Revoke All Access Tokens');
    await tokens.dangerModal.confirm();

    await expect(tokens.revokeAllReport).toContainText(`${count} revoked.`);
    await expect(tokens.empty).toBeVisible();
    await expect(tokens.rows).toHaveCount(0);
    expect(await tokenApi.list()).toEqual([]);
  });
});

test.describe('Access tokens: the limit', () => {
  test('PAT-05 at 50 tokens that have not expired, create is disabled with the reason; revoking one frees it', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    const prefix = `pat-${seeder.runId}-lim`;
    await tokenApi.createMany(MAX_LIVE, prefix);
    const tokens = new AccessTokensPage(tokenPage);
    await tokens.goto();

    await expect(tokens.createButton).toBeDisabled();
    await expect(tokens.limit).toContainText(`${MAX_LIVE} access tokens that have not expired`);

    // The list is paged (5 a page), so the first page is enough to revoke one.
    const first = await tokens.rows.first().getByTestId('row-name').innerText();
    await tokens.revoke(first.trim());
    await expect(tokens.limit).toHaveCount(0);
    await expect(tokens.createButton).toBeEnabled();
  });
});

test.describe('Access tokens: the token works', () => {
  test('PAT-06 a created token identifies its owner; after revoking it in the UI the same call is refused', async ({
    tokenPage,
    tokenUser,
    seeder,
  }) => {
    const tokens = new AccessTokensPage(tokenPage);
    const name = `pat-${seeder.runId}-works`;
    await tokens.goto();
    await tokens.openCreateModal();
    await tokens.createModal.fill({ name, scopes: ['repo:read'] });
    await tokens.createModal.submit.click();
    await tokens.infoModal.expectOpen();
    const secret = await tokens.infoModal.secret();
    await tokens.infoModal.close.click();
    await tokens.infoModal.expectClosed();

    const live = await whoAmI(secret);
    expect(live.status).toBe(200);
    expect(live.body).toMatchObject({ username: tokenUser.username, name });
    expect(live.body?.scopes).toContain('repo:read' satisfies AccessTokenScope);
    expect(live.body?.scopes).not.toContain('repo:manage');

    // The row disappears only after the DELETE has answered, so the token is dead from here on.
    await tokens.revoke(name);
    expect((await whoAmI(secret)).status).toBe(401);
  });
});

// @cloud-skip: the password warning and the Settings page are reached through the OS profile page (`/profile`,
// `ProfilePage`); Cloud's account page has its own twin spec (RPS-2005).
test.describe(
  'Access tokens: the profile page and the Settings page',
  { tag: ['@cloud-skip'] },
  () => {
    test('PAT-07 the password warning shows only while tokens that have not expired exist, and links to them', async ({
      tokenPage,
      tokenApi,
      seeder,
    }) => {
      const profile = new ProfilePage(tokenPage);
      await profile.goto();
      await expect(tokenPage.getByTestId('profile-password-form')).toBeVisible();
      // No token, no warning.
      await expect(tokenPage.getByTestId('profile-password-tokens-warning')).toHaveCount(0);

      await tokenApi.createMany(2, `pat-${seeder.runId}-warn`);
      await profile.goto();
      const warning = tokenPage.getByTestId('profile-password-tokens-warning');
      await expect(warning).toContainText(
        'Your 2 access tokens stay valid after the password changes',
      );

      await warning.getByRole('link', { name: 'review them' }).click();
      await expect(tokenPage).toHaveURL(urlEndsWith(`${settingsRoute()}#access-tokens`));
      const tokens = new AccessTokensPage(tokenPage);
      await tokens.expectLoaded();
      await expect(tokens.rows).toHaveCount(2);
    });

    test('PAT-08 Settings is in the avatar menu, lists its sections, and the profile page has no token section', async ({
      tokenPage,
    }) => {
      const profile = new ProfilePage(tokenPage);
      await profile.goto();
      await expect(tokenPage.getByTestId('access-tokens-section')).toHaveCount(0);

      await profile.shell.openAvatarMenu();
      await profile.shell.header.settings.click();
      const settings = new SettingsPage(tokenPage);
      await expect(tokenPage).toHaveURL(urlEndsWith(settingsRoute()));
      await expect(settings.title).toBeVisible();
      await expect(settings.navEntry('access-tokens')).toHaveText('Access tokens');
      await settings.tokens.expectLoaded();
      await expect(settings.tokens.empty).toBeVisible();

      // The entry jumps to its section.
      await settings.navEntry('access-tokens').click();
      await expect(tokenPage).toHaveURL(/#access-tokens$/);
    });
  },
);
