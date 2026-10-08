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
 * RPS-1638: the UI capability seam (`target.ui`, `src/target.ts`) that lets the Playwright `ui` suite run
 * against the panel of Repsy Cloud. No server and no browser: the route builders, the session keys and
 * the base URL are pure, and the `@cloud-skip` tagging is proven by listing the suite in a child
 * Playwright process, the way a Cloud run lists it.
 */
import { resolve } from 'node:path';

import { expect, test, type BrowserContext } from '@playwright/test';
import { execa } from 'execa';

import { env } from '../../src/env.js';
import type { PackageProtocol, PackageRef } from '../../src/seed/packages.js';
import { capabilitiesFor, target } from '../../src/target.js';
import { uiBaseUrlFrom } from '../../src/ui/base-url.js';
import { DESCRIPTORS } from '../../src/ui/pages/protocol.js';
import {
  accessTokensRoute,
  profileRoute,
  repoRoute,
  settingsRoute,
  urlEndsWith,
} from '../../src/ui/routes.js';
import { seedSession, type UiSession } from '../../src/ui/session.js';

const HARNESS_ROOT = resolve(import.meta.dirname, '../..');

/** Runs `body` as if `REPSY_TARGET` were `name`, with `REPSY_REPO_OWNER` = `owner`; both restored after. */
async function withUiTarget<T>(
  name: 'local' | 'cloud-remote',
  owner: string | undefined,
  body: () => Promise<T> | T,
): Promise<T> {
  const saved = { ui: target.ui, owner: env.repoOwner };
  target.ui = capabilitiesFor(name).ui;
  env.repoOwner = owner;
  try {
    return await body();
  } finally {
    target.ui = saved.ui;
    env.repoOwner = saved.owner;
  }
}

test.describe('UI capabilities per target (RPS-1638)', () => {
  test('Repsy OS: /<repo> routes, /profile, a Users page, a username field, three storage keys', () => {
    for (const name of ['local', 'ci', 'remote'] as const) {
      const ui = capabilitiesFor(name).ui;

      expect(ui.repoRoute('r'), name).toBe('/r');
      expect(ui.repoRoute('r', 'settings'), name).toBe('/r/settings');
      expect(ui.repoRoute('r', 'g', 'a', '1.0'), name).toBe('/r/g/a/1.0');
      expect(ui.profilePath, name).toBe('/profile');
      expect(ui.settingsPath, name).toBe('/profile/settings');
      expect(ui.accessTokensPath, name).toBe('/profile/settings');
      expect(ui.hasUsersPage, name).toBe(true);
      expect(ui.loginField, name).toBe('username');
      expect(ui.sessionStorageKeys, name).toEqual({
        username: 'username',
        token: 'token',
        refreshToken: 'refresh-token',
      });
    }
  });

  test('Repsy Cloud: /<owner>/<repo> routes, /account, no Users page, a usernameOrEmail field, an email key', () =>
    withUiTarget('cloud-remote', 'acme', () => {
      for (const name of ['cloud-remote', 'cloud-local'] as const) {
        const ui = capabilitiesFor(name).ui;

        expect(ui.repoRoute('r'), name).toBe('/acme/r');
        expect(ui.repoRoute('r', 'settings'), name).toBe('/acme/r/settings');
        expect(ui.profilePath, name).toBe('/account');
        expect(ui.settingsPath, name).toBe('/settings');
        expect(ui.accessTokensPath, name).toBe('/account');
        expect(ui.hasUsersPage, name).toBe(false);
        expect(ui.loginField, name).toBe('usernameOrEmail');
        expect(ui.sessionStorageKeys, name).toEqual({
          username: 'username',
          token: 'token',
          refreshToken: 'refresh-token',
          email: 'email',
        });
      }
    }));

  test('the owner is read when a route is built, never at import: a missing one fails loudly then', () =>
    withUiTarget('cloud-remote', undefined, () => {
      // Reaching this point already proves that importing the harness needs no owner.
      expect(() => repoRoute('r')).toThrow('REPSY_REPO_OWNER');
      env.repoOwner = 'late';
      expect(repoRoute('r')).toBe('/late/r');
      // The Repsy OS routes never need one.
      expect(capabilitiesFor('local').ui.repoRoute('r')).toBe('/r');
    }));

  test('the short forms (`repoRoute`, `profileRoute`) follow the target they are called under', async () => {
    // Under `local` explicitly, not the run's target: this spec must pass on every target (RPS-1510).
    await withUiTarget('local', undefined, () => {
      expect(repoRoute('r', 'settings')).toBe('/r/settings');
      expect(profileRoute()).toBe('/profile');
      expect(settingsRoute()).toBe('/profile/settings');
      expect(accessTokensRoute()).toBe('/profile/settings');
    });

    await withUiTarget('cloud-remote', 'acme', () => {
      expect(repoRoute('r', 'settings')).toBe('/acme/r/settings');
      expect(profileRoute()).toBe('/account');
      expect(settingsRoute()).toBe('/settings');
      expect(accessTokensRoute()).toBe('/account');
    });
  });

  test('urlEndsWith matches a path at the end of a URL, whatever the base and the owner', () => {
    const settings = urlEndsWith('/r/settings');

    expect(settings.test('http://localhost:8080/r/settings')).toBe(true);
    expect(settings.test('https://app.repsy.io/acme/r/settings')).toBe(true);
    expect(settings.test('http://localhost:8080/r/settings/more')).toBe(false);
    expect(urlEndsWith('/r/a.b').test('http://h/r/aXb')).toBe(false);
  });

  test('the panel base URL: REPSY_UI_BASE_URL, then (Cloud only) REPSY_FRONTEND_BASE_URL, then the API', () => {
    const api = 'http://api.test:8080';
    const ui = 'http://ui.test:4200';
    const frontend = 'http://frontend.test:4200';

    // Repsy OS: the chain that always was; REPSY_FRONTEND_BASE_URL is not part of it.
    expect(uiBaseUrlFrom({}, false)).toBe('http://localhost:8080');
    expect(uiBaseUrlFrom({ REPSY_API_BASE_URL: api }, false)).toBe(api);
    expect(
      uiBaseUrlFrom({ REPSY_API_BASE_URL: api, REPSY_FRONTEND_BASE_URL: frontend }, false),
    ).toBe(api);
    expect(uiBaseUrlFrom({ REPSY_API_BASE_URL: api, REPSY_UI_BASE_URL: ui }, false)).toBe(ui);
    // Repsy Cloud serves the panel from another host than the API.
    expect(
      uiBaseUrlFrom({ REPSY_API_BASE_URL: api, REPSY_FRONTEND_BASE_URL: frontend }, true),
    ).toBe(frontend);
    expect(
      uiBaseUrlFrom(
        { REPSY_API_BASE_URL: api, REPSY_FRONTEND_BASE_URL: frontend, REPSY_UI_BASE_URL: ui },
        true,
      ),
    ).toBe(ui);
    // Unspecified, it follows REPSY_TARGET, as the Playwright config does.
    expect(uiBaseUrlFrom({ REPSY_TARGET: 'cloud-remote', REPSY_FRONTEND_BASE_URL: frontend })).toBe(
      frontend,
    );
    expect(uiBaseUrlFrom({ REPSY_TARGET: 'local', REPSY_FRONTEND_BASE_URL: frontend })).toBe(
      'http://localhost:8080',
    );
  });
});

/** A package of each protocol, in the shape its descriptor expects (maven `group:artifact`, npm scoped). */
const SAMPLES: Record<PackageProtocol, PackageRef> = {
  maven: { name: 'org.acme:lib', version: '1.0.0' },
  npm: { name: '@acme/lib', version: '1.0.0' },
  docker: { name: 'img', version: 'latest' },
  pypi: { name: 'lib', version: '1.0.0' },
  cargo: { name: 'lib', version: '1.0.0' },
  nuget: { name: 'lib', version: '1.0.0' },
  helm: { name: 'lib', version: '1.0.0' },
  golang: { name: 'example.com/acme/lib', version: 'v1.0.0' },
  ruby: { name: 'lib', version: '1.0.0' },
};

/** Every route a protocol descriptor builds for repository `repo`: its levels and its extra paths. */
function descriptorRoutes(protocol: PackageProtocol, repo: string): string[] {
  const descriptor = DESCRIPTORS[protocol];
  const sample = SAMPLES[protocol];
  const routes = Object.values(descriptor.levels).map((level) => level.path(repo, sample));
  for (const extra of Object.values(descriptor.extraPaths ?? {})) {
    routes.push(extra(repo));
  }
  return routes;
}

test.describe('the package page objects route through the seam (RPS-1638)', () => {
  const protocols = Object.keys(DESCRIPTORS) as PackageProtocol[];

  // Under `local` explicitly, not the run's target: this spec must pass on every target (RPS-1510).
  test('Repsy OS: every route of every descriptor is /<repo>...', () =>
    withUiTarget('local', undefined, () => {
      for (const protocol of protocols) {
        const routes = descriptorRoutes(protocol, 'the-repo');

        expect(routes.length, protocol).toBeGreaterThanOrEqual(3);
        for (const route of routes) {
          expect(route, protocol).toMatch(/^\/the-repo(\/|\?|$)/);
        }
      }
    }));

  test('Repsy Cloud: the same routes are /<owner>/<repo>..., with nothing else changed', async () => {
    const os = await withUiTarget('local', undefined, () =>
      Object.fromEntries(protocols.map((p) => [p, descriptorRoutes(p, 'the-repo')])),
    );

    await withUiTarget('cloud-remote', 'acme', () => {
      for (const protocol of protocols) {
        const cloud = descriptorRoutes(protocol, 'the-repo');

        for (const route of cloud) {
          expect(route, protocol).toMatch(/^\/acme\/the-repo(\/|\?|$)/);
        }
        // Dropping the owner gives exactly what the OS descriptor builds.
        expect(
          cloud.map((route) => route.replace('/acme', '')),
          protocol,
        ).toEqual(os[protocol]);
      }
    });
  });
});

interface StorageStub {
  local: Map<string, string>;
  session: Map<string, string>;
}

/**
 * Runs the init script `seedSession` registers, in a stub `window` with two Maps as its storages, as the
 * browser would for a page of `pageOrigin` (the session is seeded for `http://panel.test`). Returns what landed in `localStorage`.
 */
async function seedInto(
  session: UiSession,
  pageOrigin = 'http://panel.test',
): Promise<StorageStub> {
  const origin = 'http://panel.test';
  let script: ((arg: unknown) => void) | undefined;
  let arg: unknown;
  const context = {
    addInitScript: async (fn: (a: unknown) => void, a: unknown) => {
      script = fn;
      arg = a;
    },
  } as unknown as BrowserContext;
  await seedSession(context, origin, session);

  const stub: StorageStub = { local: new Map(), session: new Map() };
  const storage = (map: Map<string, string>) => ({
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => void map.set(key, value),
  });
  const globals = globalThis as unknown as { window?: unknown };
  const saved = globals.window;
  globals.window = {
    location: { origin: pageOrigin },
    localStorage: storage(stub.local),
    sessionStorage: storage(stub.session),
  };
  try {
    script!(arg);
  } finally {
    globals.window = saved;
  }
  return stub;
}

test.describe('the session is seeded under the keys of the target (RPS-1638)', () => {
  const session: UiSession = {
    username: 'alice',
    token: 'access',
    refreshToken: 'refresh',
    email: 'alice@example.test',
  };

  // Under `local` explicitly, not the run's target: this spec must pass on every target (RPS-1510).
  test('Repsy OS: three keys, and the email of a login answer is not stored', () =>
    withUiTarget('local', undefined, async () => {
      const seeded = await seedInto(session);

      expect([...seeded.local.entries()].sort()).toEqual([
        ['refresh-token', 'refresh'],
        ['token', 'access'],
        ['username', 'alice'],
      ]);
    }));

  test('Repsy Cloud: the same three keys plus the email', () =>
    withUiTarget('cloud-remote', 'acme', async () => {
      const seeded = await seedInto(session);

      expect(Object.fromEntries(seeded.local)).toEqual({
        username: 'alice',
        token: 'access',
        'refresh-token': 'refresh',
        email: 'alice@example.test',
      });
      // A login answer without an email leaves the key out.
      const without = await seedInto({ ...session, email: undefined });
      expect(without.local.has('email')).toBe(false);
    }));

  test('a page of another origin is left alone', async () => {
    const seeded = await seedInto(session, 'http://elsewhere.test');

    expect(seeded.local.size).toBe(0);
  });
});

/** What `playwright test --list --reporter=json` says about the `ui` project, by tag. */
interface Listed {
  file: string;
  line: number;
  title: string;
  tags: string[];
}

interface ListedSuite {
  title: string;
  specs?: { title: string; file: string; line: number; tags?: string[] }[];
  suites?: ListedSuite[];
}

function flatten(suite: ListedSuite, into: Listed[] = []): Listed[] {
  for (const spec of suite.specs ?? []) {
    into.push({ file: spec.file, line: spec.line, title: spec.title, tags: spec.tags ?? [] });
  }
  for (const child of suite.suites ?? []) {
    flatten(child, into);
  }
  return into;
}

/** Lists the `ui` project in a child process, as a Cloud run does: `REPSY_TARGET=cloud-remote`, no OS variable. */
async function listUi(extraArgs: string[]): Promise<Listed[]> {
  const { stdout } = await execa(
    'npx',
    ['playwright', 'test', '--project', 'ui', '--list', '--reporter=json', ...extraArgs],
    {
      cwd: HARNESS_ROOT,
      // A clean environment: nothing of this run's (a password, an owner, a CI flag) leaks in.
      extendEnv: false,
      env: {
        PATH: process.env.PATH ?? '',
        HOME: process.env.HOME ?? '',
        REPSY_TARGET: 'cloud-remote',
      },
    },
  );
  const report = JSON.parse(stdout) as { suites: ListedSuite[] };
  return report.suites.flatMap((suite) => flatten(suite));
}

// @cloud-skip: it lists the harness through its own Playwright config, which a Cloud consumer does not have.
test.describe('the ui project on a Cloud target (RPS-1638)', { tag: ['@cloud-skip'] }, () => {
  test('loads without any OS variable, and --grep-invert @cloud-skip lists exactly the untagged tests', async () => {
    test.setTimeout(120_000);
    const all = await listUi([]);
    const portable = await listUi(['--grep-invert', '@cloud-skip']);
    const skipped = all.filter((t) => t.tags.includes('cloud-skip'));
    const untagged = all.filter((t) => !t.tags.includes('cloud-skip'));

    // Something is tagged, and something is left to run.
    expect(skipped.length).toBeGreaterThan(0);
    expect(untagged.length).toBeGreaterThan(0);
    // The inverted list is the full list minus the tagged tests, test for test.
    const key = (t: Listed) => `${t.file}:${t.line}:${t.title}`;
    expect(portable.map(key).sort()).toEqual(untagged.map(key).sort());
    expect(portable.some((t) => t.tags.includes('cloud-skip'))).toBe(false);
    // The tests that need a seeded USER or the Users page are among the tagged.
    const skippedFiles = new Set(skipped.map((t) => t.file));
    expect(skippedFiles).toContain('ui/users/users-create.spec.ts');
    expect(skippedFiles).toContain('ui/profile/profile.spec.ts');
    // The portable core is not: package pages, repositories, settings.
    const portableFiles = new Set(portable.map((t) => t.file));
    expect(portableFiles).toContain('ui/packages/npm.spec.ts');
    expect(portableFiles).toContain('ui/repositories/create.spec.ts');
    expect(portableFiles).toContain('ui/settings/repo-management.spec.ts');
  });
});
