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
 * NET-01 (RPS-1402): the panel makes no request to a host outside the stack. It used to load Google Tag
 * Manager, Google Ads, GA4, the cdnjs Font Awesome stylesheet and Gravatar, which an air-gapped or
 * privacy-sensitive install cannot switch off.
 *
 * The UI suite's flake defaults already ABORT every such request (`applyUiDefaults`), so a page that
 * still asked for one would only lose its icon and the run would stay green. What this spec records is
 * the `request` event, which fires for an aborted request too: it is the list of hosts the browser was
 * asked to contact, blocked or not. `harness.spec.ts` keeps proving that the blocking itself works.
 */
import type { BrowserContext } from '@playwright/test';

import { env } from '../../src/env.js';
import { RepoType } from '../../src/api/panel-api.js';
import { expect, test } from '../../src/ui/fixtures.js';
import { DashboardPage } from '../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../src/ui/pages/login.js';
import { ProfilePage } from '../../src/ui/pages/profile.js';
import { RepositoriesPage } from '../../src/ui/pages/repositories.js';
import { RepoSettingsPage } from '../../src/ui/pages/repo-settings/page.js';
import { Shell } from '../../src/ui/pages/shell.js';
import { UsersPage } from '../../src/ui/pages/users.js';

/**
 * What the browser complained about while `context` was open, besides the hosts it contacted:
 * `csp` are the console errors of a blocked resource ("Refused to load ... Content Security Policy"),
 * `misserved` the fonts, stylesheets and scripts that came back as an HTML document. The panel's
 * static files are served next to a single-page-app fallback that answers HTML for a path it does not
 * recognise, so a bundled icon font that lands on such a path fails silently (a box instead of the
 * glyph) and only shows here (RPS-1445: `/media/remixicon-*.woff2` did).
 */
function recordPageProblems(context: BrowserContext): { csp: string[]; misserved: string[] } {
  const problems = { csp: [] as string[], misserved: [] as string[] };
  context.on('console', (message) => {
    if (/Content Security Policy/i.test(message.text())) {
      problems.csp.push(message.text());
    }
  });
  context.on('response', (response) => {
    const type = response.request().resourceType();
    if (
      ['font', 'stylesheet', 'script'].includes(type) &&
      (response.headers()['content-type'] ?? '').startsWith('text/html')
    ) {
      problems.misserved.push(`${type} ${response.url()}`);
    }
  });
  return problems;
}

/**
 * Records every http(s) request of `context` whose origin is not the UI, the panel API or the repo
 * protocol port. `request` fires for an aborted request too, so the list is what was ASKED for.
 */
function recordExternalRequests(context: BrowserContext, baseURL: string | undefined): string[] {
  const allowed = new Set(
    [baseURL, env.apiBaseUrl, env.repoBaseUrl]
      .filter((url): url is string => Boolean(url))
      .map((url) => new URL(url).origin),
  );
  const external: string[] = [];
  context.on('request', (request) => {
    const url = new URL(request.url());
    if ((url.protocol === 'http:' || url.protocol === 'https:') && !allowed.has(url.origin)) {
      external.push(request.url());
    }
  });
  return external;
}

test.describe('No third-party requests', { tag: '@net' }, () => {
  test('NET-01 the login page asks for nothing outside the stack', async ({
    page,
    context,
    baseURL,
  }) => {
    const external = recordExternalRequests(context, baseURL);
    const problems = recordPageProblems(context);

    const login = new LoginPage(page);
    await login.goto();
    await login.password.fill('anything');
    // The eye icon is a bundled remixicon glyph now: it has a box, so a real click works.
    await login.passwordToggle.click();
    // The toggle is the login page's only remixicon glyph, so the font is requested for it and no other.
    await page.evaluate(() => document.fonts.load('16px remixicon').catch(() => []));
    await page.evaluate(() => document.fonts.ready);
    const iconFont = await page.evaluate(() =>
      [...document.fonts]
        .filter((face) => face.family.includes('remixicon'))
        .map((face) => face.status),
    );

    expect(external).toEqual([]);
    expect(problems).toEqual({ csp: [], misserved: [] });
    expect(iconFont).toContain('loaded');
  });

  test('NET-01 the signed-in pages ask for nothing outside the stack', async ({
    adminPage,
    baseURL,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: false });
    const external = recordExternalRequests(adminPage.context(), baseURL);
    const problems = recordPageProblems(adminPage.context());

    await new DashboardPage(adminPage).goto();
    await new RepositoriesPage(adminPage).goto();
    await new RepoSettingsPage(adminPage, repo.name).goto();
    await new UsersPage(adminPage).goto();

    // The header shows the avatar on every one of these pages; it used to ask Gravatar for an image.
    await new ProfilePage(adminPage).goto();
    await expect(new Shell(adminPage).header.avatar).toBeVisible();

    expect(external).toEqual([]);
    expect(problems).toEqual({ csp: [], misserved: [] });
  });
});
