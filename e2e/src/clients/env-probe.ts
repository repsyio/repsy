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
 * What a client's environment really looks like inside the process (RPS-1446): the "no `REPSY_*`
 * reaches the client" cells of every suite (`tests/<protocol>/sealed-env.spec.ts`, modelled on
 * `tests/npm-clients/sealed-env.spec.ts`) run `env` through `run()` with the environment the suite's
 * own builder (`cargoEnv`, `nugetEnv`, `gemEnv`, ...) produced, so the builder and `run()`'s
 * `extendEnv` handling are both on the path, and read back the NAMES that arrived (never a value:
 * a leak must not put a secret in a failure message).
 */
import { expect } from '@playwright/test';

import { run } from './exec.js';

/** Set in the test worker for the duration of a probe: neither name may ever reach a client. */
const SENTINELS = ['REPSY_RPS1446_SENTINEL', 'E2E_RPS1446_SENTINEL'];

/** What the runner containers set that must never reach a client (`docker-compose.runners.yml`). */
const RUNNER_VARIABLES = [
  'REPSY_ADMIN_PASSWORD',
  'REPSY_ADMIN_USERNAME',
  'REPSY_API_BASE_URL',
  'REPSY_REPO_BASE_URL',
  'REPSY_TARGET',
  'REPSY_E2E_RUN_ID',
];

/** The variables `env` printed, name to value. */
export async function probeEnv(
  env: NodeJS.ProcessEnv,
  cwd: string,
  label: string,
): Promise<Record<string, string>> {
  const previous = SENTINELS.map((name) => process.env[name]);
  for (const name of SENTINELS) {
    process.env[name] = 'leaked';
  }
  try {
    const result = await run('env', ['-0'], { cwd, env, label });
    expect(result.exitCode, `env: ${result.stderr}`).toBe(0);
    const seen: Record<string, string> = {};
    for (const entry of result.stdout.split('\0')) {
      const at = entry.indexOf('=');
      if (at > 0) {
        seen[entry.slice(0, at)] = entry.slice(at + 1);
      }
    }
    return seen;
  } finally {
    SENTINELS.forEach((name, index) => {
      const value = previous[index];
      if (value === undefined) {
        delete process.env[name];
      } else {
        process.env[name] = value;
      }
    });
  }
}

/**
 * The cell every suite runs: `seen` (from `probeEnv`) carries the invocation's `home`, a `PATH`
 * and the client's own `expected` variables, and none of the runner's (any `REPSY_*` name, the
 * sentinels, the runner variables above, Playwright's `FORCE_COLOR`).
 */
export function expectSealed(
  seen: Record<string, string>,
  home: string,
  expected: readonly string[] = [],
): void {
  const names = Object.keys(seen);
  expect(seen.HOME, 'the client runs in the invocation HOME').toBe(home);
  expect(names, 'the client finds its tools').toContain('PATH');
  for (const name of expected) {
    expect(names, `${name} is set for the client`).toContain(name);
  }
  expect(
    names.filter((name) => name.startsWith('REPSY_')),
    'no REPSY_* variable reaches the client',
  ).toEqual([]);
  for (const name of [...SENTINELS, ...RUNNER_VARIABLES, 'FORCE_COLOR']) {
    expect(names, `${name} leaked into the client`).not.toContain(name);
  }
}
