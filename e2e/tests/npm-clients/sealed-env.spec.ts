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
 * The sealed environment is an allow-list (RPS-1364). `config.ts`'s `sealedEnv` builds a minimal
 * environment for every npm-family client, but `execa` merges its `env` over the runner's own
 * `process.env` unless told not to, so the runner's variables (`REPSY_ADMIN_PASSWORD`, `REPSY_*`,
 * Playwright's `FORCE_COLOR`, the image's `YARN_VERSION` and `NPM_CLIENTS_*`) used to reach every
 * client and the scripts it runs. `runSealed` passes `extendEnv: false`; these cells pin it.
 *
 * Each client runs `node -p` as a package script through its own command path (`npm run`, `pnpm run`,
 * `yarn run`, `bun run`, the same `runNpm`/`runPnpm`/`runYarn`/`execYarn`/`bunExec` the suite's
 * commands use), and the script prints the NAMES of its environment (never a value: a leak must not
 * put a secret in a failure message). A sentinel variable set in the test worker is the generic proof;
 * the named variables are the ones that actually leaked.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { run } from '../../src/clients/exec.js';
import type { ClientCtx, ClientId } from '../../src/clients/npm-family/client.js';
import { bunExec } from '../../src/clients/npm-family/bun-client.js';
import { runNpm } from '../../src/clients/npm-family/npm-client.js';
import { runPnpm } from '../../src/clients/npm-family/pnpm-client.js';
import { runSealed } from '../../src/clients/npm-family/config.js';
import { ENABLED_CLIENTS } from '../../src/clients/npm-family/registry.js';
import { execYarn } from '../../src/clients/npm-family/yarn-berry-client.js';
import { runYarn } from '../../src/clients/npm-family/yarn-classic-client.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const SENTINEL = 'REPSY_RPS1364_SENTINEL';

/** What the runner container sets that must never reach a client (see the header). */
const RUNNER_VARIABLES = ['REPSY_ADMIN_PASSWORD', 'FORCE_COLOR', 'YARN_VERSION'];

/** The script every client runs: the names of its environment and its HOME, as one JSON line. */
const PROBE_SCRIPT =
  'node -p "JSON.stringify({home:process.env.HOME,keys:Object.keys(process.env)})" --';

const EXEC: Record<
  ClientId,
  (ctx: ClientCtx, label: string, args: readonly string[]) => ReturnType<typeof runNpm>
> = {
  npm: (ctx, label, args) => runNpm(ctx, label, args),
  pnpm: (ctx, label, args) => runPnpm(ctx, label, args),
  'yarn-classic': (ctx, label, args) => runYarn(ctx, label, args),
  'yarn-berry': (ctx, label, args) => execYarn(ctx, label, args),
  bun: (ctx, label, args) => bunExec(ctx, label, args),
};

/** Commands a client needs before `run` works: berry refuses a script of a workspace its lockfile lacks. */
const PRIME: Partial<Record<ClientId, string[][]>> = { 'yarn-berry': [['install']] };

async function withSentinel<T>(body: () => Promise<T>): Promise<T> {
  process.env[SENTINEL] = 'leaked';
  try {
    return await body();
  } finally {
    delete process.env[SENTINEL];
  }
}

test.describe('the npm-family environment is an allow-list (RPS-1364)', () => {
  for (const client of ENABLED_CLIENTS) {
    test(
      `${client.label}: no runner variable reaches the client or its scripts`,
      { tag: [client.tag, '@sealed-env'] },
      async () => {
        const ctx = await client.prepare('sealed-env', []);
        await fs.writeFile(
          path.join(ctx.work, 'package.json'),
          JSON.stringify({
            name: 'e2e-sealed-env',
            version: '0.0.0',
            private: true,
            scripts: { probe: PROBE_SCRIPT },
          }),
        );

        for (const args of PRIME[client.id] ?? []) {
          const primed = await EXEC[client.id](ctx, `${client.id}-prime`, args);
          expect(primed.exitCode, primed.stdout).toBe(0);
        }

        const probed = await withSentinel(() =>
          EXEC[client.id](ctx, `${client.id}-probe`, ['run', 'probe']),
        );
        expect(probed.exitCode, `${client.label} run probe: ${probed.stderr}`).toBe(0);

        const line = /^\{"home".*\}$/m.exec(probed.stdout)?.[0];
        expect(line, `the probe printed no JSON line:\n${probed.stdout}`).toBeTruthy();
        const seen = JSON.parse(line ?? '{}') as { home: string; keys: string[] };

        expect(seen.home, 'the client runs in the invocation HOME').toBe(ctx.home);
        expect(seen.keys, 'HTTP_PROXY is the dead loopback one: the seal holds').toContain(
          'HTTP_PROXY',
        );
        expect(seen.keys).not.toContain(SENTINEL);
        expect(seen.keys.filter((key) => key.startsWith('REPSY_'))).toEqual([]);
        expect(seen.keys.filter((key) => key.startsWith('NPM_CLIENTS_'))).toEqual([]);
        for (const name of RUNNER_VARIABLES) {
          expect(seen.keys, `${name} leaked into ${client.label}`).not.toContain(name);
        }
      },
    );
  }

  test(
    'runSealed and run() with an env give the child only that env; extendEnv: true still merges',
    { tag: ['@sealed-env'] },
    async () => {
      const script = `process.stdout.write(process.env.${SENTINEL} ?? 'absent')`;
      const node = process.execPath;
      await withSentinel(async () => {
        const cwd = process.cwd();
        const sealed = await runSealed(node, ['-e', script], { cwd, env: {} });
        expect(sealed.exitCode, sealed.stderr).toBe(0);
        expect(sealed.stdout).toBe('absent');

        // Since RPS-1446 run() seals an explicit env by default too (every client suite builds its env
        // with clientEnv); the merge is opt-in.
        const explicit = await run(node, ['-e', script], { cwd, env: {} });
        expect(explicit.stdout).toBe('absent');
        const merged = await run(node, ['-e', script], { cwd, env: {}, extendEnv: true });
        expect(merged.stdout).toBe('leaked');
      });
    },
  );
});
