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
 * `/cli/auth` as an authorize screen with a loopback `redirect_uri` (RPS-2039; PAT-18..PAT-37). The Repsy CLI opens
 * `/cli/auth?state=&redirect_uri=http://127.0.0.1:<port>/callback&scopes=`; the page asks the person, creates a
 * TEMPORARY token (`rct-`, 24 hours) only on Authorize, and POSTs it (form body, `token` and `state`) to the redirect
 * address. A stand-in listener (`LoopbackCallback`) plays the CLI. Repsy Cloud runs these specs too.
 *
 * A temporary token is not listed, so `tokenApi.list()` stays empty and `whoAmI(secret)` is the witness that a token
 * exists. What the browser cannot tell the page is whether the CLI listened (the post is a top-level navigation in a
 * NEW tab), so PAT-27 pins the fallback instead: the page always offers "Send again" and "Show the token to copy".
 *
 * PAT-33..PAT-37 cover what a temporary token may do: it manages personal tokens (`rp token create`), a personal
 * token does not, and a newer login replaces it.
 */
import { explicitScopes, TokenRoutesAs, whoAmI } from '../../../src/ui/access-token-api.js';
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

test.describe('/cli/auth authorize screen with a loopback redirect_uri', () => {
  test('PAT-18 the authorize screen shows the account, scopes and 24 hours, and creates nothing', async ({
    tokenPage,
    tokenApi,
    tokenUser,
    cliCallback,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(
      query({
        state: STATE,
        redirect_uri: cliCallback.url(),
        name: 'ignored-name',
        scopes: 'repo:read,repo:write,admin',
      }),
    );
    await tokenPage.reload();
    await cli.expectLoaded();

    await expect(cli.account).toHaveText(tokenUser.username);
    await expect(cli.scopeList).toContainText('Read repositories');
    await expect(cli.scopeList).toContainText('Publish packages');
    await expect(cli.scopeList).not.toContainText('Manage repositories');
    await expect(cli.scopeList).not.toContainText('admin');
    await expect(cli.expiry).toContainText('24 hours');
    await expect(cli.target).toHaveText(cliCallback.url());
    await expect(cli.approve).toBeEnabled();
    // No name to choose and nothing to tick: the old token form and its "revoke the older token" box are gone.
    await expect(tokenPage.locator('input')).toHaveCount(0);
    await expect(tokenPage.locator('body')).not.toContainText('ignored-name');
    // Opening and reloading is a GET: no token, and nothing sent to the listener.
    expect(await tokenApi.list()).toHaveLength(0);
    expect(cliCallback.requests).toHaveLength(0);
  });

  test(
    'PAT-19 Authorize creates a temporary token and posts it with the state unchanged, never in a URL',
    { tag: ['@smoke'] },
    async ({ tokenPage, tokenApi, tokenUser, cliCallback }) => {
      const cli = new CliAuthPage(tokenPage);
      await cli.goto(
        query({
          state: STATE,
          redirect_uri: cliCallback.url('/callback'),
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
      expect(secret).toMatch(/^rct-/);
      // The secret is in the body only: no URL parameter, no referrer.
      expect(request.url).not.toContain(secret);
      expect(request.headers.referer).toBeUndefined();

      // Not a personal access token: it is not in the list.
      expect(await tokenApi.list()).toHaveLength(0);
      const current = await whoAmI(secret);
      expect(current.status).toBe(200);
      expect(current.body?.username).toBe(tokenUser.username);
      expect(explicitScopes(current.body?.scopes ?? [])).toEqual(['repo:read', 'repo:write']);

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
    await cli.goto(query({ state, redirect_uri: cliCallback.url() }));

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
    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));

    await cli.decline.click();

    await expect(cli.declined).toContainText('No token was created');
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
      expect(await tokenApi.list()).toHaveLength(0);
      // eslint-disable-next-line playwright/no-wait-for-timeout -- proving a negative: give a wrong post time to arrive
      await tokenPage.waitForTimeout(300);
      expect(cliCallback.requests).toHaveLength(0);
    });
  }

  test('PAT-23 after a refused link the person can still get a token by hand, and nothing is posted', async ({
    tokenPage,
    cliCallback,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await tokenPage.goto(
      `/cli/auth${query({ redirect_uri: `http://example.com:${cliCallback.port}/callback`, state: STATE })}`,
    );
    await expect(cli.refused).toBeVisible();

    await cli.usePaste.click();
    // The same authorize screen, with no address to post to: the token is shown here instead.
    await expect(cli.approval).toBeVisible();
    await expect(cli.target).toHaveCount(0);
    await cli.approve.click();

    await expect(cli.token).toBeVisible();
    expect((await whoAmI((await cli.token.innerText()).trim())).status).toBe(200);
    expect(cliCallback.requests).toHaveLength(0);
  });

  test('PAT-24 a link with no redirect_uri is the paste flow: the same screen, the secret on screen after Authorize', async ({
    tokenPage,
    tokenApi,
  }) => {
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ state: STATE, scopes: 'repo:read' }));

    await expect(cli.approval).toBeVisible();
    await expect(cli.target).toHaveCount(0);
    await expect(cli.refused).toHaveCount(0);
    expect(await tokenApi.list()).toHaveLength(0);
    await cli.approve.click();
    await expect(cli.token).toBeVisible();
    await expect(cli.token).toHaveText(/^rct-/);
  });

  test('PAT-25 a new authorization ends the previous CLI token once the new one is delivered; personal tokens stay', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    const personal = await tokenApi.create(`pat-${seeder.runId}-bystander`);
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));
    await cli.approve.click();
    const [first] = await received(cliCallback);
    const firstSecret = first.form.get('token') ?? '';
    expect((await whoAmI(firstSecret)).status).toBe(200);

    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));
    await cli.approve.click();
    const [, second] = await received(cliCallback, 2);
    const secondSecret = second.form.get('token') ?? '';

    await expect(cli.revoked).toBeVisible();
    expect(secondSecret).not.toBe(firstSecret);
    expect((await whoAmI(firstSecret)).status).toBe(401);
    expect((await whoAmI(secondSecret)).status).toBe(200);
    expect((await whoAmI(personal.token)).status).toBe(200);
    expect(await tokenApi.list()).toHaveLength(1);
  });

  test('PAT-26 declining a second authorization leaves the first CLI token working', async ({
    tokenPage,
    cliCallback,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));
    await cli.approve.click();
    const [first] = await received(cliCallback);
    const firstSecret = first.form.get('token') ?? '';

    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));
    await cli.decline.click();

    await expect(cli.declined).toBeVisible();
    expect((await whoAmI(firstSecret)).status).toBe(200);
  });

  test('PAT-27 when nothing listens at the address the token is still created and can be copied by hand', async ({
    tokenPage,
  }) => {
    const gone = await LoopbackCallback.listen('127.0.0.1');
    const address = gone.url();
    await gone.close();
    const cli = new CliAuthPage(tokenPage);
    await cli.goto(query({ state: STATE, redirect_uri: address }));

    await cli.approve.click();

    // The post cannot be observed from the page: it says so and offers the fallback.
    await expect(cli.delivered).toBeVisible();
    await expect(cli.token).toHaveCount(0);
    await cli.reveal.click();
    const secret = (await cli.token.innerText()).trim();
    expect(secret).toMatch(/^rct-/);
    expect((await whoAmI(secret)).status).toBe(200);
  });

  test('PAT-28 at 50 personal tokens Authorize still works and the list does not change', async ({
    tokenPage,
    tokenApi,
    cliCallback,
    seeder,
  }) => {
    await tokenApi.createMany(50, `pat-${seeder.runId}-full`);
    const cli = new CliAuthPage(tokenPage);

    await cli.goto(query({ state: STATE, redirect_uri: cliCallback.url() }));
    await expect(cli.approve).toBeEnabled();
    await cli.approve.click();
    const [request] = await received(cliCallback);

    expect((await whoAmI(request.form.get('token') ?? '')).status).toBe(200);
    expect(await tokenApi.list()).toHaveLength(50);
  });

  test('PAT-29 the IPv6 loopback address is accepted; where the browser policy cannot name it the page falls back', async ({
    tokenPage,
    tokenApi,
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
      await cli.goto(query({ state: STATE, redirect_uri: v6!.url() }));
      await expect(cli.refused).toHaveCount(0);

      await cli.approve.click();

      /* eslint-disable playwright/no-conditional-in-test, playwright/no-conditional-expect -- the two products differ by design here */
      // Repsy OS sends a form-action CSP, which has no way to name [::1]: the browser refuses the post and the page
      // shows the token to copy. Repsy Cloud's panel host sends none, and the listener gets the post. Either way
      // the person is never left without a way to use the token.
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
        expect(request.form.get('token')).toMatch(/^rct-/);
      }
      /* eslint-enable playwright/no-conditional-in-test, playwright/no-conditional-expect */
      expect(await tokenApi.list()).toHaveLength(0);
    } finally {
      await v6?.close();
    }
  });

  test('PAT-30 signed out, the link goes through sign-in and comes back to the authorize screen with its parameters', async ({
    openUiPage,
    tokenUser,
    cliCallback,
  }) => {
    const page = await openUiPage({ allowLoopback: true });
    await page.goto(
      `/cli/auth${query({ state: STATE, redirect_uri: cliCallback.url(), scopes: 'repo:read' })}`,
    );
    const login = new LoginPage(page);
    await expect(login.submit).toBeVisible();

    await login.login(tokenUser.username, tokenUser.password);

    const cli = new CliAuthPage(page);
    await cli.expectLoaded();
    await expect(page).toHaveURL(urlHasPath('/cli/auth'));
    await expect(cli.target).toHaveText(cliCallback.url());
    await expect(cli.scopeList).toContainText('Read repositories');
    // Nothing was sent by signing in.
    expect(cliCallback.requests).toHaveLength(0);

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

test.describe('what a temporary CLI token may do', () => {
  /** Authorizes through the page with the paste flow and returns the secret. */
  async function authorize(cli: CliAuthPage, scopes: string): Promise<string> {
    await cli.goto(query({ scopes }));
    await cli.approve.click();
    await expect(cli.token).toBeVisible();
    return (await cli.token.innerText()).trim();
  }

  test('PAT-33 a temporary token creates, lists and revokes a personal token (rp token create)', async ({
    tokenPage,
    tokenApi,
    tokenUser,
    seeder,
  }) => {
    const cliToken = await authorize(new CliAuthPage(tokenPage), 'repo:read,repo:write');
    const as = new TokenRoutesAs(cliToken);
    const name = `pat-${seeder.runId}-from-cli`;

    const created = await as.create(name, ['repo:read']);

    expect(created.status).toBe(201);
    expect(created.body?.token).toMatch(/^rut-/);
    expect((await whoAmI(created.body!.token)).body?.username).toBe(tokenUser.username);
    const listed = await as.list();
    expect(listed.body?.map((t) => t.name)).toEqual([name]);
    // The same list through the login: the temporary token itself is not in it.
    expect((await tokenApi.list()).map((t) => t.name)).toEqual([name]);
    expect(await as.revoke(created.body!.id)).toBe(204);
    expect(await tokenApi.list()).toHaveLength(0);
  });

  test('PAT-34 a personal token cannot create, list or revoke personal tokens', async ({
    tokenApi,
    seeder,
  }) => {
    const personal = await tokenApi.create(`pat-${seeder.runId}-manager`, [
      'repo:read',
      'repo:write',
      'repo:manage',
    ]);
    const victim = await tokenApi.create(`pat-${seeder.runId}-victim`);
    const as = new TokenRoutesAs(personal.token);

    expect((await as.create('minted', ['repo:read'])).status).toBe(401);
    expect((await as.list()).status).toBe(401);
    expect(await as.revoke(victim.id)).toBe(401);
    expect(await tokenApi.list()).toHaveLength(2);
    // It still says who it is.
    expect((await whoAmI(personal.token)).status).toBe(200);
  });

  test('PAT-35 a temporary token cannot mint a temporary token or a personal token wider than itself', async ({
    tokenPage,
    tokenApi,
  }) => {
    const cliToken = await authorize(new CliAuthPage(tokenPage), 'repo:read');
    const as = new TokenRoutesAs(cliToken);

    expect(await as.createCliToken(['repo:read'])).toBe(401);
    expect((await as.create('wider', ['repo:read', 'repo:manage'])).status).toBe(400);
    expect(await tokenApi.list()).toHaveLength(0);
  });

  test('PAT-36 a temporary token is not shown in the Settings list of personal tokens', async ({
    tokenPage,
    tokenApi,
    seeder,
  }) => {
    await authorize(new CliAuthPage(tokenPage), 'repo:read');
    await tokenApi.create(`pat-${seeder.runId}-listed`);

    await tokenPage.goto(`/profile/settings`);

    await expect(tokenPage.getByText(`pat-${seeder.runId}-listed`)).toBeVisible();
    await expect(tokenPage.locator('body')).not.toContainText('Repsy CLI login');
  });

  test('PAT-37 a revoked or replaced temporary token is refused on every route at once', async ({
    tokenPage,
  }) => {
    const cli = new CliAuthPage(tokenPage);
    const first = await authorize(cli, 'repo:read');
    expect(await new TokenRoutesAs(first).list().then((r) => r.status)).toBe(200);

    const second = await authorize(cli, 'repo:read');

    expect(second).not.toBe(first);
    expect((await whoAmI(first)).status).toBe(401);
    expect((await new TokenRoutesAs(first).list()).status).toBe(401);
    expect((await new TokenRoutesAs(second).list()).status).toBe(200);
  });
});
