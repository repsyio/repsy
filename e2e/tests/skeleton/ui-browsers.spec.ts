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
 * RPS-1651: which tests the `ui-firefox` and `ui-webkit` projects select (`playwright.config.ts`), read from the
 * config by listing them in a child Playwright process, and the trust rule of `src/ui/browser-trust.ts`. No
 * stack and no browser: a list runs nothing.
 *
 * The two projects run the @smoke set, the @tls panel spec and the keyboard walkthrough (A11Y-13), and leave out
 * what is tagged `@chromium-only` (a CDP session, ...); `ui` runs everything. `REPSY_UI_BROWSER_GREP` widens the
 * two for one run.
 */
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';
import { execa } from 'execa';

import { browserTrustOptions } from '../../src/ui/browser-trust.js';

const HARNESS_ROOT = resolve(import.meta.dirname, '../..');

interface ListedSuite {
  title: string;
  specs?: { title: string; file: string; tags?: string[] }[];
  suites?: ListedSuite[];
}

interface Listed {
  title: string;
  tags: string[];
}

function flatten(suite: ListedSuite, into: Listed[] = []): Listed[] {
  for (const spec of suite.specs ?? []) {
    // The JSON reporter lists a tag without its `@`.
    into.push({
      title: spec.title,
      tags: (spec.tags ?? []).map((tag) => `@${tag.replace(/^@/, '')}`),
    });
  }
  for (const child of suite.suites ?? []) {
    flatten(child, into);
  }
  return into;
}

async function list(project: string, extraEnv: Record<string, string> = {}): Promise<Listed[]> {
  const { stdout } = await execa(
    'npx',
    ['playwright', 'test', '--project', project, '--list', '--reporter=json'],
    {
      cwd: HARNESS_ROOT,
      // A clean environment: nothing of this run's (a grep, a CI flag) leaks in.
      extendEnv: false,
      env: {
        PATH: process.env.PATH ?? '',
        HOME: process.env.HOME ?? '',
        REPSY_ADMIN_PASSWORD: 'listing-only',
        ...extraEnv,
      },
    },
  );
  return (JSON.parse(stdout) as { suites: ListedSuite[] }).suites.flatMap((s) => flatten(s));
}

const KEYBOARD_WALKTHROUGH = /^A11Y-13\b/;

test.describe('the ui-firefox and ui-webkit projects (RPS-1651)', () => {
  for (const project of ['ui-firefox', 'ui-webkit']) {
    test(`${project} selects @smoke, @tls and A11Y-13, and nothing tagged @chromium-only`, async () => {
      test.setTimeout(120_000);
      const listed = await list(project);

      expect(listed.length).toBeGreaterThan(20);
      for (const one of listed) {
        const selected =
          one.tags.includes('@smoke') ||
          one.tags.includes('@tls') ||
          KEYBOARD_WALKTHROUGH.test(one.title);
        expect(selected, `${one.title} is neither @smoke, @tls nor A11Y-13`).toBe(true);
        expect(one.tags, one.title).not.toContain('@chromium-only');
      }
      expect(listed.some((one) => KEYBOARD_WALKTHROUGH.test(one.title))).toBe(true);
      expect(listed.some((one) => one.tags.includes('@tls'))).toBe(true);
      expect(listed.some((one) => one.title.startsWith('UI login lands on the dashboard'))).toBe(
        true,
      );
    });
  }

  test('ui selects the whole suite, the @chromium-only tests included', async () => {
    test.setTimeout(120_000);
    const everything = await list('ui');
    const firefox = await list('ui-firefox');

    const chromiumOnly = everything.filter((one) => one.tags.includes('@chromium-only'));
    expect(chromiumOnly.length).toBeGreaterThan(0);
    expect(everything.length).toBeGreaterThan(firefox.length + chromiumOnly.length);
  });

  test('REPSY_UI_BROWSER_GREP widens the selection for one run, @chromium-only still out', async () => {
    test.setTimeout(120_000);
    const narrow = await list('ui-webkit');
    const wide = await list('ui-webkit', { REPSY_UI_BROWSER_GREP: '.' });

    expect(wide.length).toBeGreaterThan(narrow.length);
    expect(wide.some((one) => one.tags.includes('@chromium-only'))).toBe(false);
  });
});

test.describe('the certificate trust of the browser contexts (RPS-1651)', () => {
  test('a context ignores certificate errors when the runner was given the stack CA, and only then', () => {
    expect(browserTrustOptions({ REPSY_E2E_TLS_CA_FILE: '/tls/ca.pem' })).toEqual({
      ignoreHTTPSErrors: true,
    });
    expect(browserTrustOptions({})).toEqual({});
    expect(browserTrustOptions({ REPSY_E2E_TLS_CA_FILE: '' })).toEqual({});
  });
});
