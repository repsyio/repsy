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
 * `/cli/auth` (RPS-1905, RPS-1906; PAT-10..PAT-16): the page the Repsy CLI opens in a browser with
 * `?name=&scopes=&state=` so a person can create an access token for it and paste it back. Repsy Cloud runs
 * these specs too: the sign-in detour differs (OS keeps a return URL, Cloud a hand-over), so PAT-10 asserts the
 * END state only.
 *
 * The page must never create a token on its own: a GET is only a form, PAT-12 counts the tokens before and after.
 * Each test has its OWN account (`tokenUser`, see `access-token-fixtures.ts`); the names are unique per run.
 */
import { expect, test } from '../../../src/ui/access-token-fixtures.js';
import { allowClipboard, copiedText } from '../../../src/ui/clipboard.js';
import { CliAuthPage } from '../../../src/ui/pages/access-tokens.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { explicitScopes } from '../../../src/ui/access-token-api.js';
import { accessTokensRoute, urlHasPath } from '../../../src/ui/routes.js';

const MAX_LIVE = 50;
const NAME_MAX = 80;
const REPO_SCOPES = ['repo:read', 'repo:write', 'repo:manage'];

/** The query a CLI sends. */
function query(values: Record<string, string>): string {
  return `?${new URLSearchParams(values).toString()}`;
}

test.describe('/cli/auth', () => {
  test(
    'PAT-10 signed out, the link goes through sign-in and opens the page with the same name and scopes',
    { tag: ['@smoke'] },
    async ({ page, tokenUser }) => {
      await page.goto(
        `/cli/auth${query({ name: 'ci-box', scopes: 'repo:read,repo:write', state: 'abc' })}`,
      );
      // Where the visitor waits differs (OS keeps a return URL on `/`, Cloud a hand-over): only the login form counts.
      const login = new LoginPage(page);
      await expect(login.submit).toBeVisible();

      await login.login(tokenUser.username, tokenUser.password);

      const cli = new CliAuthPage(page);
      await cli.expectLoaded();
      await expect(page).toHaveURL(urlHasPath('/cli/auth'));
      await expect(cli.form.name).toHaveValue('ci-box');
      await expect(cli.form.scope('repo:read')).toBeChecked();
      await expect(cli.form.scope('repo:write')).toBeChecked();
      await expect(cli.form.scope('repo:manage')).not.toBeChecked();
    },
  );

  test('PAT-11 a scope the page does not offer is dropped; none left means the three repository scopes', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ scopes: 'repo:read,profile:read,scan:read,admin' }));
    await expect(cli.form.scope('repo:read')).toBeChecked();
    await expect(cli.form.scope('repo:write')).not.toBeChecked();
    await expect(cli.form.scope('repo:manage')).not.toBeChecked();
    await expect(cli.form.scope('scan:read')).toHaveCount(0);

    for (const scopes of ['scan:read,admin', 'profile:read', '']) {
      await cli.goto(query({ scopes }));
      for (const scope of REPO_SCOPES) {
        await expect(cli.form.scope(scope), `${scope} for scopes="${scopes}"`).toBeChecked();
      }
    }

    // No name asked for: the default one.
    await cli.goto(query({ scopes: 'repo:read' }));
    await expect(cli.form.name).toHaveValue('Repsy CLI');
  });

  test('PAT-12 opening the page creates nothing; Create makes one token and shows the secret once', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    await allowClipboard(tokenPage.context());
    const name = `pat-${seeder.runId}-cli`;
    const cli = new CliAuthPage(tokenPage);
    expect(await tokenApi.list()).toHaveLength(0);

    await cli.goto(query({ name, scopes: 'repo:read,repo:write', state: 'xyz' }));
    // A GET (a reload is another one) never creates a token.
    await tokenPage.reload();
    await cli.expectLoaded();
    expect(await tokenApi.list()).toHaveLength(0);

    await expect(cli.form.submit).toBeEnabled();
    await cli.form.submit.click();
    await expect(cli.token).toBeVisible();
    const secret = (await cli.token.innerText()).trim();
    expect(secret).toMatch(/^rut-/);
    await expect(
      tokenPage.getByText(
        /Copy this token and paste it into the terminal where rp is waiting\. It will not be shown again\./,
      ),
    ).toBeVisible();

    const copy = cli.copy.getByTestId('copy-button');
    await copy.click();
    await expect(copy).toHaveAttribute('data-copied', 'true');
    expect(await copiedText(tokenPage)).toBe(secret);

    const listed = await tokenApi.list();
    expect(listed).toHaveLength(1);
    expect(listed[0]).toMatchObject({ name });
    expect(explicitScopes(listed[0].scopes)).toEqual(['repo:read', 'repo:write']);

    // Shown once: after a reload the page is a form again and the secret is nowhere, and still one token.
    await tokenPage.reload();
    await cli.expectLoaded();
    await expect(cli.token).toHaveCount(0);
    await expect(tokenPage.locator('body')).not.toContainText(secret);
    expect(await tokenApi.list()).toHaveLength(1);
  });

  test('PAT-13 a name with markup is shown as text and never becomes an element', async ({
    tokenPage,
  }) => {
    const markup = '<img src=x onerror=alert(1)>';
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ name: markup, scopes: 'repo:read' }));
    await expect(cli.form.name).toHaveValue(markup);
    await cli.form.submit.click();

    // The created token's name is printed as text.
    await expect(cli.token).toBeVisible();
    await expect(tokenPage.locator('body')).toContainText(markup);
    await expect(tokenPage.locator('img[src="x"]')).toHaveCount(0);
  });

  test(`PAT-14 a name longer than ${NAME_MAX} characters is cut to ${NAME_MAX}`, async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ name: 'x'.repeat(NAME_MAX + 120), scopes: 'repo:read' }));

    await expect(cli.form.name).toHaveValue('x'.repeat(NAME_MAX));
    await expect(cli.form.submit).toBeEnabled();
  });

  test(`PAT-15 at ${MAX_LIVE} tokens that have not expired the page says so and does not create`, async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    await tokenApi.createMany(MAX_LIVE, `pat-${seeder.runId}-full`);
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ name: 'one-too-many', scopes: 'repo:read' }));

    await expect(cli.form.blocked).toContainText(`${MAX_LIVE} access tokens`);
    await expect(cli.form.submit).toBeDisabled();
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

  test('PAT-17 after the token is created, the link to manage tokens goes to the Settings page', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await cli.goto(query({ scopes: 'repo:read' }));
    await cli.form.submit.click();
    await expect(cli.token).toBeVisible();

    await expect(cli.manageLink).toHaveAttribute('href', accessTokensRoute() + '#access-tokens');
  });
});
