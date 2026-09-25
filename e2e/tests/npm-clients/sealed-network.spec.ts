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
 * The network seal (RPS-1330), proven for every client the runner image installs: a client whose
 * registry is deliberately misconfigured to the real public registry (registry.npmjs.org, which the
 * container CAN reach unsealed -- probed) fails at once with a connection error to the dead proxy
 * (`127.0.0.1:9`, `config.ts`'s `DEAD_PROXY_URL`) instead of reaching the internet, hanging until a
 * timeout, or -- worst -- succeeding and letting a test pass on a public package. A package that
 * came back would show as a `left-pad` version in the output, which each case rules out too.
 *
 * Probed while writing this (each a real client run in the sealed environment):
 *  - npm, bun and yarn classic honour `HTTP_PROXY`/`NO_PROXY`. npm retries a refused connection
 *    with a 10 s and then 60 s back-off unless `--fetch-retries=0`; yarn classic retries three times
 *    first (about 12 s), which no flag shortens.
 *  - yarn berry ignores the proxy environment entirely (it reached registry.npmjs.org with the
 *    environment seal alone), so its seal is `.yarnrc.yml`'s `httpProxy`/`httpsProxy` plus a direct
 *    connection per registry host (`networkSettings`), and `httpRetry: 0` makes it fail at once.
 *  - pnpm 12 (a native binary) honours the environment too, but retries with backoff for 70 s unless
 *    its fetch retry settings are lowered (`pnpm-workspace.yaml`), so the case sets them.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { CLIENT_BINARIES, type ClientId } from '../../src/clients/npm-family/client.js';
import {
  DEAD_PROXY_URL,
  sealedEnv,
  writeYarnBerryRc,
} from '../../src/clients/npm-family/config.js';
import { INSTALLED_CLIENTS } from '../../src/clients/npm-family/registry.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const PUBLIC_REGISTRY = 'https://registry.npmjs.org/';

interface SealCase {
  args: string[];
  /** What the failure says: a refused connection to the dead proxy. */
  refused: RegExp;
  /** Per-client set-up in the case's home/work directory, and extra environment. */
  setUp?: (home: string, work: string) => Promise<void>;
  extraEnv?: NodeJS.ProcessEnv;
}

const CASES: Record<ClientId, SealCase> = {
  npm: {
    // npm retries a refused connection twice with a 10 s and then 60 s back-off by default.
    args: ['view', 'left-pad', 'version', '--registry', PUBLIC_REGISTRY, '--fetch-retries=0'],
    refused: /ECONNREFUSED 127\.0\.0\.1:9\b/,
  },
  pnpm: {
    args: ['add', 'left-pad', '--registry', PUBLIC_REGISTRY, '--ignore-scripts'],
    refused: /error sending request|tunnel error|Connect/,
    setUp: async (_home, work) => {
      await fs.writeFile(
        path.join(work, 'pnpm-workspace.yaml'),
        'fetchRetries: 0\nfetchRetryMintimeout: 100\nfetchRetryMaxtimeout: 200\n',
      );
    },
  },
  'yarn-classic': {
    // `yarn info` exits 0 even when it cannot reach the registry; `add` fails properly.
    args: ['add', 'left-pad', '--registry', PUBLIC_REGISTRY, '--non-interactive'],
    refused: /ECONNREFUSED 127\.0\.0\.1:9\b|trouble with your network connection/,
    extraEnv: { NODE_OPTIONS: '--no-deprecation', YARN_IGNORE_PATH: '1' },
  },
  'yarn-berry': {
    args: ['npm', 'info', 'left-pad', '--fields', 'version'],
    refused: /ECONNREFUSED 127\.0\.0\.1:9\b/,
    extraEnv: { YARN_IGNORE_PATH: '1' },
    setUp: async (home, work) => {
      // Berry takes the nearest lockfile as its project root.
      await fs.writeFile(path.join(work, 'yarn.lock'), '');
      const rc = path.join(work, '.yarnrc.yml');
      await writeYarnBerryRc(rc, [], { cacheFolder: path.join(home, 'yarn-cache') });
      await fs.appendFile(rc, `npmRegistryServer: "${PUBLIC_REGISTRY}"\n`);
    },
  },
  bun: {
    args: ['pm', 'view', 'left-pad', 'version', '--registry', PUBLIC_REGISTRY],
    refused: /ConnectionRefused|failed to send/,
  },
};

test.describe('network seal: a misconfigured registry fails fast, never reaching the internet', () => {
  for (const client of INSTALLED_CLIENTS) {
    test(
      `${client.label} against ${PUBLIC_REGISTRY}`,
      { tag: [client.tag, '@sealed'] },
      async () => {
        const sealCase = CASES[client.id];
        const { home, work } = await isolatedWorkDir(`npmc-seal-${client.id}`);
        await fs.writeFile(
          path.join(work, 'package.json'),
          '{"name":"seal","version":"0.0.0","license":"MIT"}\n',
        );
        await sealCase.setUp?.(home, work);

        const started = Date.now();
        const result = await run(CLIENT_BINARIES[client.id], sealCase.args, {
          cwd: work,
          env: sealedEnv(home, sealCase.extraEnv),
          timeoutMs: 60_000,
          label: `${client.id}-sealed`,
        });
        const elapsedMs = Date.now() - started;
        const output = `${result.stdout}\n${result.stderr}`;

        expect(result.timedOut, 'the client must give up, not hang').toBe(false);
        expect(result.exitCode, `the client must fail:\n${output}`).not.toBe(0);
        expect(output, `a refused connection to ${DEAD_PROXY_URL}`).toMatch(sealCase.refused);
        expect(output, 'no package data came back from the public registry').not.toMatch(
          /\b1\.3\.0\b|"version"|added 1 package/,
        );
        expect(elapsedMs, 'fails fast (yarn classic retries for about 12 s)').toBeLessThan(30_000);
      },
    );
  }
});
