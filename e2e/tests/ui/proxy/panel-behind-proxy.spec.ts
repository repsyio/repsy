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
 * The panel behind a reverse proxy (RPS-1651), on the stack `./run.sh local up --proxy` starts (README.md "Reverse
 * proxy stack"): nginx terminates TLS on its own ports and forwards to Repsy's plain HTTP ports with the
 * `X-Forwarded-*` headers the main README's "Reverse Proxy" section asks for. Every other UI run reaches Repsy
 * on its own ports, so nothing else proves what the panel does when its public origin (another scheme, another
 * port) is not the one Repsy listens on.
 *
 * What is pinned:
 *  - the panel is loaded through the proxy (its `Server` header is nginx's, the origin is the proxy's https
 *    one) and every request it makes after the login goes to that origin, none to Repsy's own port;
 *  - the panel's install snippets and the text their copy button copies name the PUBLIC repository URL
 *    (`REPO_BASE_URL`, README.md "Reverse Proxy") and never Repsy's internal address, for all nine formats.
 *
 * NOT covered, because it does not exist: a subpath. `<base href="/">` and absolute `/assets/...` script URLs in
 * `index.html` tie the panel to the root of its host, and Repsy has no context path setting. A proxy that
 * serves the panel under `/repsy/` gets a blank page; the README does not offer it either. If Repsy ever
 * supports one, this spec is where its test goes.
 *
 * The wire side (the URLs Repsy builds from `X-Forwarded-*`) is `tests/api/reverse-proxy.spec.ts`. Skipped without
 * the overlay (`REPSY_E2E_PROXY=1`); `@smoke` is on none of it, the leg runs `@smoke` and `@proxy` of the ui runner.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { env } from '../../../src/env.js';
import { optedIn } from '../../../src/stack-overlays.js';
import { allowClipboard, copiedText } from '../../../src/ui/clipboard.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

test.skip(
  !optedIn('proxy'),
  'needs the proxy overlay: REPSY_E2E_PROXY=1 ./run.sh local up --proxy',
);

const publicRepo = new URL(env.repoBaseUrl);
const publicApi = new URL(env.apiBaseUrl);
const internalRepo = new URL(env.plainRepoBaseUrl);
const internalApi = new URL(env.plainApiBaseUrl);

test.describe('The panel behind a reverse proxy', { tag: ['@proxy'] }, () => {
  test('PX-01 the panel is served by the proxy on its https origin and asks only that origin for anything', async ({
    page,
  }) => {
    // The public and the internal addresses are two different origins on this stack.
    expect(publicApi.origin).not.toBe(internalApi.origin);
    expect(publicApi.protocol).toBe('https:');
    expect(internalApi.protocol).toBe('http:');

    const requested: string[] = [];
    page.on('request', (request) => requested.push(request.url()));
    const response = await page.goto('/login');
    expect(response?.status()).toBe(200);
    expect(response?.headers()['server']).toMatch(/nginx/i);
    expect(new URL(page.url()).origin).toBe(publicApi.origin);

    await new LoginPage(page).login(env.adminUsername, env.adminPassword);
    await new DashboardPage(page).expectLoaded();

    const panelRequests = requested.filter((url) => /^https?:/.test(url));
    expect(panelRequests.filter((url) => !url.startsWith(`${publicApi.origin}/`))).toEqual([]);
    expect(panelRequests.some((url) => url.startsWith(`${publicApi.origin}/api/`))).toBe(true);
    // Repsy's own port answers the same page, but nothing of the panel points at it.
    expect(panelRequests.some((url) => url.startsWith(internalApi.origin))).toBe(false);
  });

  for (const descriptor of Object.values(DESCRIPTORS)) {
    test(`PX-02 ${descriptor.protocol}: the install snippet and its copy name the public repository URL, not Repsy's own`, async ({
      adminPage,
      context,
      seeder,
      seedPackage,
    }) => {
      await allowClipboard(context);
      const repo = await seeder.createRepo(
        RepoType[descriptor.protocol.toUpperCase() as keyof typeof RepoType],
      );
      const pkg = await seedPackage(repo);
      const detail = protocolPages(adminPage, descriptor, repo.name).detail(pkg);

      await detail.goto();
      await expect(detail.install).toBeVisible();
      const where = descriptor.levels.detail.repoUrlIn;
      const snippet =
        where === 'install' ? detail.installText : detail.snippet(where.slice('snippet:'.length));
      // The URL the stack was configured with is the proxy's: its host and port, https.
      await expect(snippet).toContainText(publicRepo.host);

      const page = await detail.root.textContent();
      expect(page).not.toContain(internalRepo.host);
      expect(page).not.toContain(internalApi.host);

      await detail.copyButton.click();
      await expect(detail.copyButton).toHaveAttribute('data-copied', 'true');
      const copied = await copiedText(adminPage);
      expect(copied).not.toContain(internalRepo.host);
    });
  }
});
