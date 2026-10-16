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
 * `/cli/auth` without a `redirect_uri` (RPS-1905, RPS-1906, RPS-2039; PAT-10..PAT-17): the authorize screen the Repsy
 * CLI opens with `?scopes=&state=`. The person authorizes and the page shows a TEMPORARY token (`rct-`, 24 hours) to
 * paste back; it is not a personal access token, so it is not listed and does not count against the 50 token limit.
 * Repsy Cloud runs these specs too: the sign-in detour differs (OS keeps a return URL, Cloud a hand-over), so PAT-10
 * asserts the END state only.
 *
 * The page must never create a token on its own: a GET is only a screen, PAT-12 reads the token through the API
 * (a temporary token is invisible in the list, so `whoAmI` of the secret is the witness). Each test has its OWN
 * account (`tokenUser`, see `access-token-fixtures.ts`).
 */
import { expect, test } from '../../../src/ui/access-token-fixtures.js';
import { whoAmI } from '../../../src/ui/access-token-api.js';
import { allowClipboard, copiedText } from '../../../src/ui/clipboard.js';
import { CliAuthPage } from '../../../src/ui/pages/access-tokens.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { accessTokensRoute, urlHasPath } from '../../../src/ui/routes.js';

const MAX_LIVE = 50;
const REPO_LABELS = ['Read repositories', 'Publish packages', 'Manage repositories'];

/** The query a CLI sends. */
function query(values: Record<string, string>): string {
  return `?${new URLSearchParams(values).toString()}`;
}

test.describe('/cli/auth', () => {
  test(
    'PAT-10 signed out, the link goes through sign-in and opens the authorize screen with the same scopes',
    { tag: ['@smoke'] },
    async ({ page, tokenUser }) => {
      await page.goto(`/cli/auth${query({ scopes: 'repo:read,repo:write', state: 'abc' })}`);
      // Where the visitor waits differs (OS keeps a return URL on `/`, Cloud a hand-over): only the login form counts.
      const login = new LoginPage(page);
      await expect(login.submit).toBeVisible();

      await login.login(tokenUser.username, tokenUser.password);

      const cli = new CliAuthPage(page);
      await cli.expectLoaded();
      await expect(page).toHaveURL(urlHasPath('/cli/auth'));
      await expect(cli.account).toHaveText(tokenUser.username);
      await expect(cli.scopeList).toContainText('Read repositories');
      await expect(cli.scopeList).toContainText('Publish packages');
      await expect(cli.scopeList).not.toContainText('Manage repositories');
    },
  );

  test('PAT-11 a scope the page does not offer is dropped; none left means every repository scope on offer', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ scopes: 'repo:read,profile:read,scan:read,admin' }));
    await expect(cli.scopeList).toContainText('Read repositories');
    await expect(cli.scopeList).not.toContainText('Publish packages');
    await expect(cli.scopeList).not.toContainText('Manage repositories');
    await expect(cli.scopeList).not.toContainText('scan:read');

    for (const scopes of ['scan:read,admin', 'profile:read', '']) {
      await cli.goto(query({ scopes }));
      for (const label of REPO_LABELS) {
        await expect(cli.scopeList, `${label} for scopes="${scopes}"`).toContainText(label);
      }
    }
  });

  test('PAT-12 opening the page creates nothing; Authorize shows one temporary token once, and it works', async ({
    tokenPage,
    tokenApi,
    tokenUser,
  }) => {
    await allowClipboard(tokenPage.context());
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ scopes: 'repo:read,repo:write', state: 'xyz' }));
    // A GET (a reload is another one) never creates a token.
    await tokenPage.reload();
    await cli.expectLoaded();
    await expect(cli.token).toHaveCount(0);

    await expect(cli.approve).toBeEnabled();
    await cli.approve.click();
    await expect(cli.token).toBeVisible();
    const secret = (await cli.token.innerText()).trim();
    expect(secret).toMatch(/^rct-/);
    await expect(
      tokenPage.getByText(/Paste this token into the terminal where rp is waiting\./),
    ).toBeVisible();

    const copy = cli.copy.getByTestId('copy-button');
    await copy.click();
    await expect(copy).toHaveAttribute('data-copied', 'true');
    expect(await copiedText(tokenPage)).toBe(secret);

    const current = await whoAmI(secret);
    expect(current.status).toBe(200);
    expect(current.body?.username).toBe(tokenUser.username);
    // Not a personal access token: invisible in the list.
    expect(await tokenApi.list()).toHaveLength(0);

    // Shown once: after a reload the page is the authorize screen again and the secret is nowhere.
    await tokenPage.reload();
    await cli.expectLoaded();
    await expect(cli.token).toHaveCount(0);
    await expect(tokenPage.locator('body')).not.toContainText(secret);
  });

  test('PAT-13 a name or markup in the link is ignored and never becomes an element', async ({
    tokenPage,
  }) => {
    const markup = '<img src=x onerror=alert(1)>';
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ name: markup, scopes: 'repo:read' }));

    // The token name is not asked for any more, so a name in the link appears nowhere.
    await expect(tokenPage.locator('body')).not.toContainText('onerror');
    await expect(tokenPage.locator('img[src="x"]')).toHaveCount(0);
    await cli.approve.click();
    await expect(cli.token).toBeVisible();
    await expect(tokenPage.locator('body')).not.toContainText('onerror');
    await expect(tokenPage.locator('img[src="x"]')).toHaveCount(0);
  });

  test('PAT-14 the screen says the token is valid for 24 hours, and the server agrees', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await cli.goto(query({ scopes: 'repo:read' }));
    await expect(cli.expiry).toContainText('24 hours');
    const before = Date.now();

    await cli.approve.click();
    await expect(cli.token).toBeVisible();

    const current = await whoAmI((await cli.token.innerText()).trim());
    const lifetime = new Date(current.body?.expirationDate ?? 0).getTime() - before;
    expect(lifetime).toBeGreaterThan(24 * 3_600_000 - 60_000);
    expect(lifetime).toBeLessThan(24 * 3_600_000 + 60_000);
  });

  test(`PAT-15 at ${MAX_LIVE} personal tokens the authorize screen still works, and the list is unchanged`, async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    await tokenApi.createMany(MAX_LIVE, `pat-${seeder.runId}-full`);
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ scopes: 'repo:read' }));
    await expect(cli.approve).toBeEnabled();
    await cli.approve.click();

    await expect(cli.token).toBeVisible();
    expect((await whoAmI((await cli.token.innerText()).trim())).status).toBe(200);
    expect(await tokenApi.list()).toHaveLength(MAX_LIVE);
  });
});

// @cloud-skip: the headers come from the API port, which serves the OS panel (`SecurityHeadersFilter`); Cloud's
// frontend is served by its own host and its headers are not verified here. The manage link: Cloud's page has no
// `cli-auth-manage` id yet (RPS-2005).
test.describe('/cli/auth on Repsy OS', { tag: ['@cloud-skip'] }, () => {
  test('PAT-16 the page cannot be framed: X-Frame-Options DENY and CSP frame-ancestors none', async ({
    page,
  }) => {
    const response = await page.goto('/cli/auth');

    expect(response, 'the document answer').not.toBeNull();
    const headers = response!.headers();
    expect(headers['x-frame-options']?.toUpperCase()).toBe('DENY');
    expect(headers['content-security-policy']).toContain("frame-ancestors 'none'");
  });

  test('PAT-17 after the token is shown, the link to create a long-lived token goes to the Settings page', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await cli.goto(query({ scopes: 'repo:read' }));
    await cli.approve.click();
    await expect(cli.token).toBeVisible();

    await expect(cli.manageLink).toHaveAttribute('href', accessTokensRoute() + '#access-tokens');
  });
});
