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
 * One `--version` smoke per client the `npm-clients` runner image installs (RPS-1330), registered
 * for the matrix or not: each is called by its absolute path (`CLIENT_BINARIES`) in the sealed
 * environment and must report exactly the version the compose build arg pinned (the Dockerfile
 * exports it as `NPM_CLIENTS_*_VERSION`). npm is the node image's own and has no build arg, so it is
 * checked by major (npm 11 on Node 24). A drifting or missing client fails here, in one place, before
 * any matrix cell does.
 *
 * Also proves that the config renderers (`config.ts`) produce what each client actually reads: the
 * registry a client reports for its own configuration is the one rendered for it. Only the
 * renderers whose output a client can read back without a network call are covered here (pnpm's
 * `.npmrc`, yarn classic's `.yarnrc`, yarn berry's `.yarnrc.yml`); bun's `bunfig.toml` has no such
 * command and is exercised for real by the bun PR's first publish/install.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { CLIENT_BINARIES, CLIENT_VERSION_ENV } from '../../src/clients/npm-family/client.js';
import {
  sealedEnv,
  writeNpmrc,
  writeYarnBerryRc,
  writeYarnClassicRc,
} from '../../src/clients/npm-family/config.js';
import { INSTALLED_CLIENTS, versionOf } from '../../src/clients/npm-family/registry.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

test.describe('npm-family client versions', () => {
  for (const client of INSTALLED_CLIENTS.filter((installed) => installed.id !== 'npm')) {
    test(
      `${client.label} --version is the pinned one`,
      { tag: [client.tag, '@versions'] },
      async () => {
        const result = await versionOf(client.id);
        expect(result.exitCode, `${client.label} --version: ${result.stderr}`).toBe(0);

        const pinnedBy = CLIENT_VERSION_ENV[client.id] ?? '';
        const pinned = process.env[pinnedBy];
        expect(pinned, `${pinnedBy} is set by runners/npm-clients.Dockerfile`).toBeTruthy();
        expect(result.stdout.trim()).toBe(pinned);
      },
    );
  }

  // npm is the node image's own and has no build arg: by major (npm 11 on Node 24).
  test(
    'npm --version is the one of node:24-bookworm-slim',
    { tag: ['@npm', '@versions'] },
    async () => {
      const result = await versionOf('npm');
      expect(result.exitCode, `npm --version: ${result.stderr}`).toBe(0);
      expect(result.stdout.trim()).toMatch(/^11\.\d+\.\d+$/);
    },
  );
});

test.describe('npm-family config renderers, read back by the client', () => {
  const bindings = [
    {
      repoName: 'repo-a',
      credential: {
        transport: 'basic' as const,
        username: 'u',
        password: 'tok',
        kind: 'token' as const,
      },
    },
    {
      repoName: 'repo-b',
      scope: '@scoped',
      credential: {
        transport: 'basic' as const,
        username: 'admin',
        password: 'pw',
        kind: 'password' as const,
      },
    },
  ];
  const registryA = `${env.repoBaseUrl}/repo-a/`;
  const registryB = `${env.repoBaseUrl}/repo-b/`;

  test('pnpm reads the rendered .npmrc', { tag: ['@pnpm', '@versions'] }, async () => {
    const { home, work } = await isolatedWorkDir('npmc-cfg-pnpm');
    await writeNpmrc(home, bindings);

    const defaults = await run(CLIENT_BINARIES.pnpm, ['config', 'get', 'registry'], {
      cwd: work,
      env: sealedEnv(home),
      redact: ['tok', 'pw'],
    });
    expect(defaults.exitCode, defaults.stderr).toBe(0);
    expect(defaults.stdout.trim()).toBe(registryA);

    const scoped = await run(CLIENT_BINARIES.pnpm, ['config', 'get', '@scoped:registry'], {
      cwd: work,
      env: sealedEnv(home),
      redact: ['tok', 'pw'],
    });
    expect(scoped.exitCode, scoped.stderr).toBe(0);
    expect(scoped.stdout.trim()).toBe(registryB);
  });

  test(
    'yarn classic reads the rendered .yarnrc',
    { tag: ['@yarn-classic', '@versions'] },
    async () => {
      const { home, work } = await isolatedWorkDir('npmc-cfg-yarn1');
      await writeYarnClassicRc(path.join(home, '.yarnrc'), bindings);

      const registry = await run(CLIENT_BINARIES['yarn-classic'], ['config', 'get', 'registry'], {
        cwd: work,
        env: sealedEnv(home, { NODE_OPTIONS: '--no-deprecation', YARN_IGNORE_PATH: '1' }),
      });
      expect(registry.exitCode, registry.stderr).toBe(0);
      // Yarn 1 clears the line with ANSI escapes even when its output is not a terminal.
      // eslint-disable-next-line no-control-regex
      expect(registry.stdout.replace(/\u001b\[[0-9;]*[A-Za-z]/g, '').trim()).toBe(registryA);
    },
  );

  test(
    'yarn berry reads the rendered .yarnrc.yml',
    { tag: ['@yarn-berry', '@versions'] },
    async () => {
      const { home, work } = await isolatedWorkDir('npmc-cfg-yarn4');
      // Berry takes the nearest lockfile as the project root; an empty one pins it to `work`.
      await fs.writeFile(path.join(work, 'yarn.lock'), '');
      await fs.writeFile(path.join(work, 'package.json'), '{"name":"cfg","version":"0.0.0"}\n');
      await writeYarnBerryRc(path.join(work, '.yarnrc.yml'), bindings, {
        cacheFolder: path.join(home, 'yarn-cache'),
      });
      const yarnEnv = sealedEnv(home, { YARN_IGNORE_PATH: '1' });

      const registry = await run(
        CLIENT_BINARIES['yarn-berry'],
        ['config', 'get', 'npmRegistryServer'],
        {
          cwd: work,
          env: yarnEnv,
          redact: ['tok', 'pw'],
        },
      );
      expect(registry.exitCode, registry.stderr).toBe(0);
      expect(registry.stdout.trim()).toBe(registryA);

      const scopes = await run(
        CLIENT_BINARIES['yarn-berry'],
        ['config', 'get', 'npmScopes', '--json'],
        {
          cwd: work,
          env: yarnEnv,
          redact: ['tok', 'pw'],
        },
      );
      expect(scopes.exitCode, scopes.stderr).toBe(0);
      expect(JSON.parse(scopes.stdout)).toHaveProperty(['scoped', 'npmRegistryServer'], registryB);
    },
  );
});
