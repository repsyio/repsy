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
 * The panel over Repsy's own https listener (RPS-1651): the UI suite runs against `https://localhost:8443` on the
 * stack `./run.sh local up --tls` starts (README.md "TLS stack"). Every other UI run is plain HTTP on
 * `localhost`, so nothing else proves that the panel boots, logs in and copies from a real https origin, that
 * it makes no plain-http request of its own, and that a browser that does not trust the certificate is refused.
 *
 * The browsers of the `ui`, `ui-firefox` and `ui-webkit` projects ignore certificate errors on such a stack
 * (`playwright.config.ts`, `src/ui/browser-trust.ts`): no browser trust store is fed by an environment variable.
 * The chain itself (signed by the throwaway CA, the SANs) is pinned by `tests/skeleton/tls-listeners.spec.ts`
 * with Node, and TLS-02 below is the control that the trust is what `ignoreHTTPSErrors` gives and not an
 * accident of the runner.
 *
 * Tagged `@tls`, not `@smoke`: the TLS leg of the nightly runs `@smoke|@tls` on the ui runners (and Firefox and WebKit
 * take `@tls` next to `@smoke`, `playwright.config.ts`), while the proxy leg, which also runs the ui runner's `@smoke`, must
 * not meet four skipped tests. It skips itself without the overlay, like the skeleton's TLS spec, so every other
 * run that selects it counts four skips.
 */
import { TRUST_VARIABLES } from '../../../src/clients/client-env.js';
import { env } from '../../../src/env.js';
import { optedIn } from '../../../src/stack-overlays.js';
import { RepoType } from '../../../src/api/panel-api.js';
import { copiedText, allowClipboard } from '../../../src/ui/clipboard.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import {
  countRefreshCalls,
  delayRefreshAnswers,
  expireAccessToken,
  setStoredSessionValue,
} from '../../../src/ui/session.js';
import { JWT_SHAPE, storedSession } from '../auth/stored-session.js';

/** The environment of this process without the variables a client reads its trusted CA from (`TRUST_VARIABLES`). */
function withoutTrustVariables(): Record<string, string> {
  const trust = new Set(TRUST_VARIABLES);
  return Object.fromEntries(
    Object.entries(process.env).filter(
      (entry): entry is [string, string] => entry[1] !== undefined && !trust.has(entry[0]),
    ),
  );
}

test.skip(!optedIn('tls'), 'needs the TLS overlay: REPSY_E2E_TLS=1 ./run.sh local up --tls');

test.describe('The panel over https', { tag: ['@tls'] }, () => {
  test('TLS-01 the panel is a secure https origin: it serves HSTS, logs in and asks for nothing over plain http', async ({
    page,
  }) => {
    const requested: string[] = [];
    page.on('request', (request) => requested.push(request.url()));

    const login = new LoginPage(page);
    const response = await page.goto('/login');
    expect(response?.status()).toBe(200);
    // The overlay sets APP_HSTS_MAX_AGE, and Repsy sends it on a secure request only (README.md "Security headers").
    expect(response?.headers()['strict-transport-security']).toBe('max-age=31536000');

    const origin = new URL(page.url());
    expect(origin.protocol).toBe('https:');
    expect(origin.origin).toBe(new URL(env.apiBaseUrl).origin);
    // Not the plain listener next to it: another port.
    expect(origin.origin).not.toBe(new URL(env.plainApiBaseUrl).origin);
    expect(
      await page.evaluate(() => ({
        secure: window.isSecureContext,
        webLocks: 'locks' in navigator && Boolean(navigator.locks),
        clipboard: 'clipboard' in navigator && Boolean(navigator.clipboard),
      })),
    ).toEqual({ secure: true, webLocks: true, clipboard: true });

    await login.login(env.adminUsername, env.adminPassword);
    await new DashboardPage(page).expectLoaded();

    // Everything the panel asked for after the login came from the https origin (a plain-http URL of its own, an
    // absolute one built from a wrong scheme, would have been aborted as a third-party request and failed the
    // test through the page-error guard as well).
    const panelRequests = requested.filter((url) => /^https?:/.test(url));
    expect(panelRequests.length).toBeGreaterThan(0);
    expect(panelRequests.filter((url) => !url.startsWith(`${origin.origin}/`))).toEqual([]);
    expect(panelRequests.some((url) => url.startsWith(`${origin.origin}/api/`))).toBe(true);
  });

  test('TLS-02 a browser that does not trust the certificate is refused (the control)', async ({
    browserName,
    playwright,
  }) => {
    // A browser of this process would not do: `run.sh` gives the runner SSL_CERT_FILE and NODE_EXTRA_CA_CERTS, and
    // WebKit (GnuTLS) trusts the CA through the first one, so that ignoreHTTPSErrors is not what lets it in. This
    // one is started without any trust variable and its context does not ignore errors.
    const untrusting = await playwright[browserName].launch({ env: withoutTrustVariables() });
    try {
      const page = await (await untrusting.newContext({ ignoreHTTPSErrors: false })).newPage();
      await expect(page.goto(env.apiBaseUrl)).rejects.toThrow(/cert|SSL|SEC_ERROR|secure/i);
    } finally {
      await untrusting.close();
    }
  });

  test('TLS-03 the install snippet copies with navigator.clipboard, which a secure context has', async ({
    adminPage,
    context,
    seeder,
    seedPackage,
  }) => {
    await allowClipboard(context);
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, DESCRIPTORS.npm, repo.name).detail(pkg);

    await detail.goto();
    await expect(detail.install).toBeVisible();
    expect(await adminPage.evaluate(() => typeof navigator.clipboard?.writeText)).toBe('function');
    await detail.copyButton.click();
    await expect(detail.copyButton).toHaveAttribute('data-copied', 'true');
    const copied = await copiedText(adminPage);
    expect(copied).toContain(pkg.name);
    // The snippet names the https repo URL of the stack, the one the panel was configured with.
    await expect(detail.snippet('npmrc')).toContainText(new URL(env.repoBaseUrl).host);
  });

  // The two-tab refresh lock (RPS-1621) has two implementations: Web Locks in a secure context, a localStorage
  // entry without them (a plain-HTTP install on a LAN address). tests/ui/auth/multi-tab.spec.ts runs the first on
  // `localhost`, which is a secure context only by exception; here it is a real https origin.
  //
  // RPS-1672: used to be pinned to fail on Firefox for the same reason as multi-tab.spec.ts's Web Locks
  // variant (see the comment there) -- Firefox's asynchronous `localStorage` replication between tabs, now
  // covered by `AuthService`'s BroadcastChannel hand-over.
  test('TLS-04 two tabs whose access tokens expire together make one refresh between them (Web Locks, https)', async ({
    adminPage,
  }) => {
    const tabA = adminPage;
    const tabB = await tabA.context().newPage();
    await new DashboardPage(tabA).goto();
    await new DashboardPage(tabB).goto();
    for (const tab of [tabA, tabB]) {
      expect(new URL(tab.url()).protocol).toBe('https:');
      expect(await tab.evaluate(() => 'locks' in navigator && Boolean(navigator.locks))).toBe(true);
    }
    const before = await storedSession(tabA);
    expect(before.refreshToken).toMatch(JWT_SHAPE);
    const stale = `${before.token}-stale`;
    await setStoredSessionValue(tabA, 'token', stale);
    // Both tabs boot with the same expired access token and the same refresh token; the refresh answers are
    // held back so that the two refreshes overlap for certain: the second would spend the first one's token.
    const refreshCalls = [countRefreshCalls(tabA), countRefreshCalls(tabB)];
    for (const tab of [tabA, tabB]) {
      await expireAccessToken(tab, stale);
      await delayRefreshAnswers(tab, 500);
    }

    await Promise.all([tabA.reload(), tabB.reload()]);

    await new DashboardPage(tabA).expectLoaded();
    await new DashboardPage(tabB).expectLoaded();
    await expect
      .poll(async () => (await storedSession(tabA)).refreshToken, { timeout: 15_000 })
      .not.toBe(before.refreshToken);
    expect(refreshCalls[0].count + refreshCalls[1].count).toBe(1);
    expect(await storedSession(tabB)).toEqual(await storedSession(tabA));
    // The family is alive: both tabs still load their data with the rotated pair.
    await tabA.reload();
    await new DashboardPage(tabA).expectLoaded();
    await tabB.reload();
    await new DashboardPage(tabB).expectLoaded();
  });
});
