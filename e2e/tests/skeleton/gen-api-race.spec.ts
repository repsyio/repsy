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
 * RPS-1468: the runner entrypoint's API client generation is safe to start several times at once.
 * Every `run.sh test` regenerates the client in the bind mount all runners of a checkout share; it used
 * to `rm -rf` the directory and generate into it, so concurrent runners deleted what another was writing.
 * Now a matching stamp skips the work and the rest is generated into a temp directory under a lock and
 * swapped in. No server: each case starts `entrypoint.sh --gen-api` N times together against its own
 * output directory (REPSY_E2E_GEN_OUT) and reads what they did.
 */
import { existsSync } from 'node:fs';
import { mkdtemp, readdir, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import { expect, test } from '@playwright/test';
import { execa } from 'execa';

const HARNESS_ROOT = resolve(import.meta.dirname, '../..');
// Baked into the runner image at /app/entrypoint.sh; in a checkout it is runners/entrypoint.sh.
const ENTRYPOINT = existsSync(join(HARNESS_ROOT, 'entrypoint.sh'))
  ? join(HARNESS_ROOT, 'entrypoint.sh')
  : join(HARNESS_ROOT, 'runners', 'entrypoint.sh');
const PARALLEL = 8;

interface Outcome {
  exitCode: number | undefined;
  output: string;
}

/** Starts `entrypoint.sh --gen-api` `count` times at once, each in a clean environment (no runner variables). */
async function generateTogether(out: string, count: number): Promise<Outcome[]> {
  const env: NodeJS.ProcessEnv = {
    PATH: process.env.PATH ?? '',
    HOME: process.env.HOME ?? '',
    REPSY_E2E_GEN_OUT: out,
  };
  // A run against another backend's spec (Repsy Cloud's harness) names it the same way as the entrypoint.
  if (process.env.REPSY_E2E_OPENAPI_SPEC) {
    env.REPSY_E2E_OPENAPI_SPEC = process.env.REPSY_E2E_OPENAPI_SPEC;
  }
  const runs = Array.from({ length: count }, () =>
    execa('sh', [ENTRYPOINT, '--gen-api'], {
      cwd: HARNESS_ROOT,
      env,
      extendEnv: false,
      reject: false,
    }),
  );
  return (await Promise.all(runs)).map((result) => ({
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
  }));
}

const generatingRuns = (outcomes: Outcome[]): number =>
  outcomes.filter((outcome) => outcome.output.includes('Generating the API client')).length;

async function expectCompleteClient(out: string, parent: string): Promise<void> {
  expect(existsSync(join(out, 'index.ts')), 'the client has its index').toBe(true);
  expect(existsSync(join(out, 'services')), 'the client has its services').toBe(true);
  expect(await readFile(join(out, '.stamp'), 'utf8'), 'the stamp names the spec').toMatch(
    /^spec=[0-9a-f]{64} codegen=\d+\.\d+\.\d+ args=/,
  );
  expect(
    (await readdir(parent)).filter((name) => name.includes('.tmp.') || name.includes('.old.')),
    'no temp or old directory is left behind',
  ).toEqual([]);
}

test.describe('API client generation under concurrency (RPS-1468)', () => {
  let parent: string;
  let out: string;

  test.beforeEach(async () => {
    parent = await mkdtemp(join(tmpdir(), 'repsy-gen-api-'));
    out = join(parent, 'generated');
  });

  test.afterEach(async () => {
    await rm(parent, { recursive: true, force: true });
  });

  test(`${PARALLEL} generations started at once into a missing directory all succeed and generate once`, async () => {
    const outcomes = await generateTogether(out, PARALLEL);

    expect(outcomes.map((outcome) => outcome.exitCode)).toEqual(Array(PARALLEL).fill(0));
    expect(
      generatingRuns(outcomes),
      'exactly one runner generated, the others found it current',
    ).toBe(1);
    await expectCompleteClient(out, parent);
  });

  test(`${PARALLEL} generations started at once over a stale client all succeed and generate once`, async () => {
    await mkdir(join(out, 'services'), { recursive: true });
    await writeFile(join(out, 'services', 'old.ts'), 'export {};');
    await writeFile(join(out, '.stamp'), 'spec=stale');

    const outcomes = await generateTogether(out, PARALLEL);

    expect(outcomes.map((outcome) => outcome.exitCode)).toEqual(Array(PARALLEL).fill(0));
    expect(generatingRuns(outcomes)).toBe(1);
    expect(existsSync(join(out, 'services', 'old.ts')), 'the stale client was replaced').toBe(
      false,
    );
    await expectCompleteClient(out, parent);
  });

  test('a current client is left alone, and a changed stamp regenerates it', async () => {
    expect(generatingRuns(await generateTogether(out, 1))).toBe(1);
    await writeFile(join(out, 'marker.txt'), 'kept');

    const again = await generateTogether(out, 1);
    expect(again[0]?.exitCode).toBe(0);
    expect(generatingRuns(again), 'nothing to do').toBe(0);
    expect(existsSync(join(out, 'marker.txt')), 'the current directory was not touched').toBe(true);

    await writeFile(join(out, '.stamp'), 'spec=changed');
    const regenerated = await generateTogether(out, 1);
    expect(generatingRuns(regenerated)).toBe(1);
    expect(existsSync(join(out, 'marker.txt')), 'the regenerated directory is a fresh one').toBe(
      false,
    );
    await expectCompleteClient(out, parent);
  });
});
