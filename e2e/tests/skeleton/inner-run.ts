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
 * Runs `known-gap-inner/*.inner.ts` as an inner Playwright run of its own under a chosen `REPSY_TARGET`,
 * against the fake Repsy Cloud backend, and reports each test's outcome by title (RPS-1510, RPS-1481). Cases
 * that are meant to fail, or to be skipped, cannot sit in a green suite as tests, so their outcome is read
 * from the report of the run instead.
 */
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import { expect } from '@playwright/test';

import { BACKEND_MODULE_ENV } from '../../src/api/backend-registry.js';

const INNER_CONFIG = resolve(import.meta.dirname, 'known-gap-inner/playwright.inner.config.ts');
const FAKE_CLOUD_MODULE = resolve(import.meta.dirname, 'fake-cloud-panel-backend.ts');
const E2E_DIR = resolve(import.meta.dirname, '../..');
const PLAYWRIGHT_CLI = createRequire(import.meta.url).resolve('@playwright/test/cli');

export interface InnerResult {
  /** \`expected\`, \`unexpected\`, \`skipped\` or \`flaky\`: what Playwright judges the test to be. */
  status: string;
  annotations: string[];
  /** What the test was expected to do (\`failed\` for a pinned gap) and what it did. */
  expected: string;
  ran: string;
}

/** Runs the inner cases as `target` on the fake cloud backend (plus `extraEnv`) and returns each test's outcome by title. */
export function runInner(
  target: string,
  extraEnv: NodeJS.ProcessEnv = {},
  /** Extra arguments of `playwright test`: file filters (only the inner files that match), `--grep`. None: everything. */
  args: string[] = [],
): Map<string, InnerResult> {
  const dir = mkdtempSync(join(tmpdir(), 'repsy-known-gap-'));
  const report = join(dir, 'report.json');
  try {
    const env: NodeJS.ProcessEnv = {};
    for (const [name, value] of Object.entries(process.env)) {
      // The outer run's own worker/runner variables must not leak into a run of its own.
      if (
        !name.startsWith('PW_') &&
        !name.startsWith('PLAYWRIGHT_') &&
        !name.startsWith('REPSY_')
      ) {
        env[name] = value;
      }
    }
    Object.assign(env, {
      REPSY_TARGET: target,
      REPSY_ADMIN_PASSWORD: 'unused',
      [BACKEND_MODULE_ENV]: FAKE_CLOUD_MODULE,
      REPSY_INNER_REPORT: report,
      ...extraEnv,
    });
    const run = spawnSync(process.execPath, [PLAYWRIGHT_CLI, 'test', '-c', INNER_CONFIG, ...args], {
      cwd: E2E_DIR,
      env,
      encoding: 'utf8',
    });
    const parsed = JSON.parse(readFileSync(report, 'utf8')) as {
      suites: Suite[];
    };
    const results = new Map<string, InnerResult>();
    const collect = (suite: Suite, path: string[]): void => {
      for (const child of suite.suites ?? []) {
        collect(child, child.title.endsWith('.inner.ts') ? path : [...path, child.title]);
      }
      for (const spec of suite.specs ?? []) {
        const outcome = spec.tests[0];
        results.set([...path, spec.title].join(' > '), {
          status: outcome.status,
          annotations: outcome.annotations.map((a) => a.description ?? a.type),
          expected: outcome.expectedStatus,
          ran: outcome.results[0]?.status ?? 'none',
        });
      }
    };
    for (const suite of parsed.suites) {
      collect(suite, []);
    }
    expect(run.error, run.stderr).toBeUndefined();
    return results;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

interface Suite {
  title: string;
  suites?: Suite[];
  specs?: {
    title: string;
    tests: {
      status: string;
      expectedStatus: string;
      annotations: { type: string; description?: string }[];
      results: { status: string }[];
    }[];
  }[];
}
