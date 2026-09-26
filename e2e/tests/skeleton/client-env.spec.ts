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

import { expect, test } from '@playwright/test';

import { BASE_VARIABLES, clientEnv } from '../../src/clients/client-env.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';

/**
 * `clientEnv` and `run()`'s environment handling (RPS-1446), which every client suite relies on. The
 * per-suite "no REPSY_* reaches the client" cells are `tests/<protocol>/sealed-env.spec.ts`. No
 * stack is needed.
 */
const SENTINEL = 'E2E_RPS1446_SKELETON_SENTINEL';

const PRINT_SENTINEL = `process.stdout.write(process.env.${SENTINEL} ?? 'absent')`;

async function withSentinel<T>(body: () => Promise<T>): Promise<T> {
  process.env[SENTINEL] = 'leaked';
  try {
    return await body();
  } finally {
    delete process.env[SENTINEL];
  }
}

test("clientEnv > HOME, the copied runner variables and the client's own settings, nothing else", () => {
  process.env.E2E_RPS1446_INHERITED = '/opt/rustup';
  try {
    const built = clientEnv('/tmp/h', { CARGO_HOME: '/tmp/h/cargo', LANG: 'override' }, [
      'E2E_RPS1446_INHERITED',
    ]);
    expect(built.HOME).toBe('/tmp/h');
    expect(built.CARGO_HOME).toBe('/tmp/h/cargo');
    expect(built.E2E_RPS1446_INHERITED, 'an inherited variable is copied when set').toBe(
      '/opt/rustup',
    );
    expect(built.LANG, "the client's own setting wins").toBe('override');
    expect(built.PATH).toBeTruthy();
    const allowed = new Set(['HOME', 'CARGO_HOME', 'E2E_RPS1446_INHERITED', ...BASE_VARIABLES]);
    for (const name of Object.keys(built)) {
      expect(allowed.has(name), `${name} is not on the allow-list`).toBe(true);
    }
    expect(clientEnv('/tmp/h', {}, ['NOT_SET_ANYWHERE_RPS1446'])).not.toHaveProperty(
      'NOT_SET_ANYWHERE_RPS1446',
    );
  } finally {
    delete process.env.E2E_RPS1446_INHERITED;
  }
});

test("run > an explicit env is the child's whole env, no env inherits the runner's", async () => {
  const { home, work } = await isolatedWorkDir('skeleton-run-env');
  const node = process.execPath;
  await withSentinel(async () => {
    const sealed = await run(node, ['-e', PRINT_SENTINEL], { cwd: work, env: clientEnv(home) });
    expect(sealed.stdout, "a clientEnv child does not see the runner's variables").toBe('absent');

    const inherited = await run(node, ['-e', PRINT_SENTINEL], { cwd: work });
    expect(inherited.stdout, 'a harness tool without an env still inherits').toBe('leaked');

    const merged = await run(node, ['-e', PRINT_SENTINEL], {
      cwd: work,
      env: clientEnv(home),
      extendEnv: true,
    });
    expect(merged.stdout, 'extendEnv: true is the explicit opt-in to the merge').toBe('leaked');
  });
});

test('run > refuses an env that carries a REPSY_* variable', async () => {
  const { work } = await isolatedWorkDir('skeleton-run-refuse');
  await expect(
    run(process.execPath, ['-e', ''], {
      cwd: work,
      env: { PATH: process.env.PATH, REPSY_ADMIN_PASSWORD: 'x' },
    }),
  ).rejects.toThrow(/REPSY_ADMIN_PASSWORD/);
});
