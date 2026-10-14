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
 * `/cli/auth` as an approval screen with a loopback `redirect_uri` (RPS-2039; PAT-18..PAT-29). The Repsy CLI opens
 * `/cli/auth?state=&redirect_uri=http://127.0.0.1:<port>/callback&name=&scopes=`; the page asks the person, creates
 * the token only on approval, and POSTs it (form body, `token` and `state`) to the redirect address. A stand-in
 * listener (`LoopbackCallback`) plays the CLI. Repsy Cloud runs these specs too.
 *
 * What the browser cannot tell the page is whether the CLI listened (the post is a top-level navigation in a NEW tab),
 * so PAT-26 pins the fallback instead: the page always offers "Send again" and "Show the token to copy".
 */
import { explicitScopes } from '../../../src/ui/access-token-api.js';
import { whoAmI } from '../../../src/ui/access-token-api.js';
import { expect, test } from '../../../src/ui/access-token-fixtures.js';
import { LoopbackCallback } from '../../../src/ui/loopback-callback.js';
import { CliAuthPage } from '../../../src/ui/pages/access-tokens.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { urlHasPath } from '../../../src/ui/routes.js';

const STATE = '0123456789abcdef0123456789abcdef';

function query(values: Record<string, string>): string {
  return `?${new URLSearchParams(values).toString()}`;
}

/** Waits until the listener has `count` requests (the post comes from another tab, a moment after the click). */
async function received(callback: LoopbackCallback, count = 1) {
  await expect
    .poll(() => callback.requests.length, { timeout: 15_000 })
    .toBeGreaterThanOrEqual(count);
  return callback.requests;
}

test.describe('/cli/auth approval with a loopback redirect_uri', () => {
  test('PAT-18 the approval screen shows the account, name, scopes and expiry, and creates nothing', async ({
    tokenPage,
    tokenApi,
    tokenUser,
    cliCallback,
    seeder,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    const name = `pat-${seeder.runId}-approval`;

    await cli.gotoApproval(
      query({
        state: STATE,
        redirect_uri: cliCallback.url(),
        name,
        scopes: 'repo:read,repo:write,admin',
      }),
    );
    await tokenPage.reload();
    await cli.expectApprovalLoaded();

    await expect(cli.account).toHaveText(tokenUser.username);
    await expect(cli.tokenName).toHaveText(name);
    await expect(cli.scopeList).toContainText('Read repositories');
    await expect(cli.scopeList).toContainText('Publish packages');
    await expect(cli.scopeList).not.toContainText('Manage repositories');
    await expect(cli.scopeList).not.toContainText('admin');
    // A year ahead, the longest the API allows.
    await expect(cli.expiry).toContainText(
      String(new Date(Date.now() + 365 * 86_400_000).getUTCFullYear()),
    );
    await expect(cli.target).toHaveText(cliCallback.url());
    await expect(cli.approve).toBeEnabled();
    await expect(cli.revokeOld).toHaveCount(0);
    // Opening and reloading is a GET: no token, and nothing sent to the listener.
    expect(await tokenApi.list()).toHaveLength(0);
    expect(cliCallback.requests).toHaveLength(0);
  });

  test(
    'PAT-19 Approve creates exactly one token and posts it with the state unchanged, never in a URL',
    { tag: ['@smoke'] },
    async ({ tokenPage, tokenApi, tokenUser, cliCallback, seeder }) => {
      const cli = new CliAuthPage(tokenPage);
      const name = `pat-${seeder.runId}-approve`;
      await cli.gotoApproval(
        query({
          state: STATE,
          redirect_uri: cliCallback.url('/callback'),
          name,
          scopes: 'repo:read,repo:write',
        }),
      );

      const opened = tokenPage.context().waitForEvent('page');
      await cli.approve.click();
      const [request] = await received(cliCallback);
      await opened;

      expect(request.method).toBe('POST');
      expect(request.url).toBe('/callback');
      expect(request.headers['content-type']).toContain('application/x-www-form-urlencoded');
      expect(request.form.get('state')).toBe(STATE);
      const secret = request.form.get('token') ?? '';
      expect(secret).toMatch(/^rut-/);
      // The secret is in the body only: no URL parameter, no referrer.
      expect(request.url).not.toContain(secret);
      expect(request.headers.referer).toBeUndefined();

      const listed = await tokenApi.list();
      expect(listed).toHaveLength(1);
      expect(listed[0]).toMatchObject({ name });
      expect(explicitScopes(listed[0].scopes)).toEqual(['repo:read', 'repo:write']);
      const current = await whoAmI(secret);
      expect(current.status).toBe(200);
      expect(current.body?.username).toBe(tokenUser.username);

      // This tab keeps no secret: not in its address, not on screen until asked for.
      await expect(cli.delivered).toBeVisible();
      expect(tokenPage.url()).not.toContain(secret);
      await expect(tokenPage.locator('body')).not.toContainText(secret);
      await expect(cli.token).toHaveCount(0);
      await expect(cli.resend).toBeVisible();
    },
  );

  test('PAT-20 a state with characters that need escaping comes back byte for byte', async ({
    tokenPage,
    cliCallback,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    const state = 'a b&c=d%20é+/?#x';
    await cli.gotoApproval(query({ state, redirect_uri: cliCallback.url() }));

    await cli.approve.click();
    const [request] = await received(cliCallback);

    expect(request.form.get('state')).toBe(state);
  });

  test('PAT-21 Decline creates no token and sends nothing', async ({
    tokenPage,
    tokenApi,
    cliCallback,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await cli.gotoApproval(query({ state: STATE, redirect_uri: cliCallback.url() }));

    await cli.decline.click();

    await expect(cli.declined).toContainText('No access token was created');
    await expect(cli.approve).toHaveCount(0);
    expect(await tokenApi.list()).toHaveLength(0);
    // eslint-disable-next-line playwright/no-wait-for-timeout -- proving a negative: give a wrong post time to arrive
    await tokenPage.waitForTimeout(500);
    expect(cliCallback.requests).toHaveLength(0);
  });

  const REFUSED = [
    ['a public host', 'http://example.com:PORT/callback'],
    ['a host that starts like loopback', 'http://127.0.0.1.evil.test:PORT/callback'],
    ['loopback as user info', 'http://127.0.0.1@evil.test:PORT/callback'],
    ['localhost', 'http://localhost:PORT/callback'],
    ['https', 'https://127.0.0.1:PORT/callback'],
    ['no port', 'http://127.0.0.1/callback'],
    ['a short IPv4 form', 'http://127.1:PORT/callback'],
    ['a decimal IPv4', 'http://2130706433:PORT/callback'],
    ['a fragment', 'http://127.0.0.1:PORT/callback#x'],
  ] as const;

  for (const [label, template] of REFUSED) {
    test(`PAT-22 a redirect_uri with ${label} is refused: no token, nothing sent`, async ({
      tokenPage,
      tokenApi,
      cliCallback,
    }) => {
      const cli = new CliAuthPage(tokenPage);
      await tokenPage.goto(
        `/cli/auth${query({ state: STATE, redirect_uri: template.replace('PORT', String(cliCallback.port)) })}`,
      );

      await expect(cli.refused).toBeVisible();
      await expect(cli.approve).toHaveCount(0);
      await expect(cli.form.root).toHaveCount(0);
      expect(await tokenApi.list()).toHaveLength(0);
      // eslint-disable-next-line playwright/no-wait-for-timeout -- proving a negative: give a wrong post time to arrive
      await tokenPage.waitForTimeout(300);
      expect(cliCallback.requests).toHaveLength(0);
    });
  }

  test('PAT-23 after a refused link the person can still create a token by hand, and nothing is posted', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    const name = `pat-${seeder.runId}-by-hand`;
    await tokenPage.goto(
      `/cli/auth${query({ redirect_uri: `http://example.com:${cliCallback.port}/callback`, name })}`,
    );
    await expect(cli.refused).toBeVisible();

    await cli.usePaste.click();
    await expect(cli.form.root).toBeVisible();
    await expect(cli.form.name).toHaveValue(name);
    await cli.form.submit.click();

    await expect(cli.token).toBeVisible();
    expect(await tokenApi.list()).toHaveLength(1);
    expect(cliCallback.requests).toHaveLength(0);
  });

  test('PAT-24 a link with no redirect_uri is the paste flow, state or not', async ({
    tokenPage,
    tokenApi,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ state: STATE, scopes: 'repo:read' }));

    await expect(cli.approval).toHaveCount(0);
    await expect(cli.refused).toHaveCount(0);
    expect(await tokenApi.list()).toHaveLength(0);
  });

  test('PAT-25 an older token with the same name is revoked after the new one is delivered, unless unticked', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    const name = `pat-${seeder.runId}-same`;
    const old = await tokenApi.create(name);
    const bystander = await tokenApi.create(`${name}-other`);
    const cli = new CliAuthPage(tokenPage);
    await cli.gotoApproval(query({ state: STATE, redirect_uri: cliCallback.url(), name }));

    await expect(cli.revokeOld).toBeChecked();
    await cli.approve.click();
    const [request] = await received(cliCallback);

    await expect(cli.revoked).toBeVisible();
    expect((await whoAmI(old.token)).status).toBe(401);
    expect((await whoAmI(bystander.token)).status).toBe(200);
    expect((await whoAmI(request.form.get('token') ?? '')).status).toBe(200);
    expect((await tokenApi.list()).map((t) => t.name).sort()).toEqual([name, `${name}-other`]);
  });

  test('PAT-26 with the box unticked the older token stays', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    const name = `pat-${seeder.runId}-keep`;
    const old = await tokenApi.create(name);
    const cli = new CliAuthPage(tokenPage);
    await cli.gotoApproval(query({ redirect_uri: cliCallback.url(), name }));

    await cli.revokeOld.uncheck();
    await cli.approve.click();
    await received(cliCallback);

    await expect(cli.delivered).toBeVisible();
    await expect(cli.revoked).toHaveCount(0);
    expect((await whoAmI(old.token)).status).toBe(200);
    expect(await tokenApi.list()).toHaveLength(2);
  });

  test('PAT-27 when nothing listens at the address the token is still created and can be copied by hand', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    const gone = await LoopbackCallback.listen('127.0.0.1');
    const address = gone.url();
    await gone.close();
    const name = `pat-${seeder.runId}-nobody`;
    const cli = new CliAuthPage(tokenPage);
    await cli.gotoApproval(query({ state: STATE, redirect_uri: address, name }));

    await cli.approve.click();

    // The post cannot be observed from the page: it says so and offers the fallback.
    await expect(cli.delivered).toBeVisible();
    await expect(cli.token).toHaveCount(0);
    await cli.reveal.click();
    const secret = (await cli.token.innerText()).trim();
    expect(secret).toMatch(/^rut-/);
    expect((await whoAmI(secret)).status).toBe(200);
    expect(await tokenApi.list()).toHaveLength(1);
  });

  test('PAT-28 at 50 live tokens Approve is blocked and nothing is created or sent', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    await tokenApi.createMany(50, `pat-${seeder.runId}-full`);
    const cli = new CliAuthPage(tokenPage);

    await cli.gotoApproval(
      query({ state: STATE, redirect_uri: cliCallback.url(), name: 'one-too-many' }),
    );

    await expect(cli.blocked).toContainText('50 access tokens');
    await expect(cli.approve).toBeDisabled();
    expect(await tokenApi.list()).toHaveLength(50);
    expect(cliCallback.requests).toHaveLength(0);
  });

  test('PAT-29 the IPv6 loopback address is accepted; where the browser policy cannot name it the page falls back', async ({
    tokenPage,
    tokenApi,
    seeder,
    pageErrors,
  }) => {
    pageErrors.allow(
      /form-action/,
      'Repsy OS sends form-action CSP that cannot name [::1]; the browser reports the refused post',
    );
    const v6 = await LoopbackCallback.listen('::1').catch(() => null);
    // eslint-disable-next-line playwright/no-skipped-test -- a machine without ::1 cannot run it
    test.skip(v6 === null, 'this machine has no IPv6 loopback');
    try {
      const cli = new CliAuthPage(tokenPage);
      const name = `pat-${seeder.runId}-v6`;
      await cli.gotoApproval(query({ state: STATE, redirect_uri: v6!.url(), name }));
      await expect(cli.refused).toHaveCount(0);

      await cli.approve.click();

      /* eslint-disable playwright/no-conditional-in-test, playwright/no-conditional-expect -- the two products differ by design here */
      // Repsy OS sends a form-action CSP, which has no way to name [::1]: the browser refuses the post and the page
      // shows the token to copy. Repsy Cloud's panel host sends none, and the listener gets the post. Either way
      // exactly one token exists and the person is never left without a way to use it.
      await expect
        .poll(async () => v6!.requests.length > 0 || (await cli.blockedByBrowser.isVisible()), {
          timeout: 15_000,
        })
        .toBe(true);
      if (await cli.blockedByBrowser.isVisible()) {
        expect(v6!.requests).toHaveLength(0);
        const secret = (await cli.token.innerText()).trim();
        expect((await whoAmI(secret)).status).toBe(200);
      } else {
        const [request] = await received(v6!);
        expect(request.method).toBe('POST');
        expect(request.form.get('state')).toBe(STATE);
        expect(request.form.get('token')).toMatch(/^rut-/);
      }
      /* eslint-enable playwright/no-conditional-in-test, playwright/no-conditional-expect */
      expect(await tokenApi.list()).toHaveLength(1);
    } finally {
      await v6?.close();
    }
  });

  test('PAT-30 signed out, the link goes through sign-in and comes back to the approval screen with its parameters', async ({
    openUiPage,
    tokenUser,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    const name = `pat-${seeder.runId}-login`;
    const page = await openUiPage({ allowLoopback: true });
    await page.goto(
      `/cli/auth${query({ state: STATE, redirect_uri: cliCallback.url(), name, scopes: 'repo:read' })}`,
    );
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();
    expect(await tokenApi.list()).toHaveLength(0);

    await login.login(tokenUser.username, tokenUser.password);

    const cli = new CliAuthPage(page);
    await cli.expectApprovalLoaded();
    await expect(page).toHaveURL(urlHasPath('/cli/auth'));
    await expect(cli.tokenName).toHaveText(name);
    await expect(cli.target).toHaveText(cliCallback.url());
    await expect(cli.scopeList).toContainText('Read repositories');
    // Nothing was created by signing in.
    expect(await tokenApi.list()).toHaveLength(0);

    await cli.approve.click();
    const [request] = await received(cliCallback);
    expect(request.form.get('state')).toBe(STATE);
  });

  test('PAT-31 signed out, a refused redirect_uri is still refused after sign-in', async ({
    page,
    tokenUser,
    tokenApi,
    cliCallback,
  }) => {
    await page.goto(
      `/cli/auth${query({ state: STATE, redirect_uri: `http://127.0.0.1.evil.test:${cliCallback.port}/callback` })}`,
    );
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();

    await login.login(tokenUser.username, tokenUser.password);

    const cli = new CliAuthPage(page);
    await expect(cli.refused).toBeVisible();
    await expect(cli.approve).toHaveCount(0);
    expect(await tokenApi.list()).toHaveLength(0);
  });
});
