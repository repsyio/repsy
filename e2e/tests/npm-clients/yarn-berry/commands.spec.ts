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
 * Yarn berry's `yarn npm` commands against a Repsy npm repository (RPS-1330, matrix rows 2, 3, 6, 9
 * and 10, the berry side that is not in the shared matrix specs): what `yarn npm publish` needs and
 * uploads (H-8), `--tolerate-republish`, `yarn npm info`, `yarn npm tag` and `yarn npm whoami`.
 * Every registry-side fact is read back raw with the admin credential, not from berry's printout.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import {
  rawGetPackument,
  rawGetTarballCanonical,
  sha256Hex,
} from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import {
  execYarn,
  YARN_LOCK,
  YARN_RC,
  yarnBerryClient,
} from '../../../src/clients/npm-family/yarn-berry-client.js';
import { tarEntries, tarFile } from '../../../src/clients/npm-family/yarn-berry-support.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

const TAG = '@yarn-berry';

test.describe('yarn npm publish', () => {
  test(
    'needs the project installed first, and uploads exactly what yarn pack builds (H-8)',
    { tag: [TAG, '@publish'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await yarnBerryClient.prepare('publish', [writer]);
      const name = packageNameFor(seeder, 'published');

      const dir = path.join(ctx.work, 'project');
      const { marker } = await renderPackage(dir, { packageName: name, version: '1.0.0' });
      // Files a berry project has next to its sources, which must never reach the tarball.
      await fs.writeFile(path.join(dir, YARN_LOCK), '');
      await fs.writeFile(path.join(dir, YARN_RC), 'enableTelemetry: false\n');
      await fs.writeFile(path.join(dir, '.pnp.cjs'), '// pnp map');
      await fs.writeFile(path.join(dir, '.pnp.loader.mjs'), '// pnp loader');

      const early = await execYarn(ctx, 'publish-early', ['npm', 'publish'], dir);
      expect(early.exitCode, 'a project that was never installed cannot be published').not.toBe(0);
      expect(`${early.stdout}${early.stderr}`).toContain(
        'This package doesn\'t seem to be present in your lockfile; run "yarn install" to update the lockfile',
      );
      const missing = await rawGetPackument(repo.name, adminCredential(), name);
      expect(missing.status, 'nothing was stored').toBe(404);

      const installed = await execYarn(ctx, 'publish-install', ['install'], dir);
      expect(installed.exitCode, installed.stdout).toBe(0);
      const packed = await yarnBerryClient.pack(ctx, dir);
      const published = await execYarn(ctx, 'publish', ['npm', 'publish'], dir);
      expect(published.exitCode, `publish: ${published.command}\n${published.stdout}`).toBe(0);
      expect(published.stdout).toContain(
        `Publishing to ${env.repoBaseUrl}/${repo.name} with tag latest`,
      );

      const stored = await rawGetTarballCanonical(repo.name, adminCredential(), name, '1.0.0');
      expect(stored.status).toBe(200);
      expect(await tarEntries(stored.body), 'the pack leaves out the project files').toEqual([
        'package/e2e-marker.txt',
        'package/index.js',
        'package/package.json',
      ]);
      expect(await tarFile(stored.body, 'package/e2e-marker.txt')).toBe(marker);
      expect(
        sha256Hex(stored.body),
        "the stored tarball is byte-identical to what yarn pack builds (berry's pack is deterministic)",
      ).toBe(sha256Hex(packed.bytes));
    },
  );

  test(
    '--tolerate-republish skips a version the registry has; a plain publish overrides it',
    { tag: [TAG, '@publish'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const name = packageNameFor(seeder, 'twice');

      const recorder = await startWireRecorder();
      try {
        const ctx = await yarnBerryClient.prepare('twice', [
          { ...writer, baseUrl: recorder.baseUrl },
        ]);
        const first = await publishPackage(yarnBerryClient, ctx, {
          packageName: name,
          version: '1.0.0',
        });
        expect(first.result.exitCode, first.result.stdout).toBe(0);
        const stored = async () =>
          sha256Hex(
            (await rawGetTarballCanonical(repo.name, adminCredential(), name, '1.0.0')).body,
          );
        const firstSha = await stored();
        const puts = () => recorder.entries.filter((entry) => entry.method === 'PUT').length;
        expect(puts()).toBe(1);

        const tolerated = await execYarn(
          ctx,
          'tolerate',
          ['npm', 'publish', '--tolerate-republish'],
          first.dir,
        );
        expect(tolerated.exitCode, tolerated.stdout).toBe(0);
        expect(tolerated.stdout).toContain('Registry already knows about version 1.0.0; skipping.');
        expect(puts(), 'berry read the packument and sent no second PUT').toBe(1);
        expect(await stored()).toBe(firstSha);

        // New bytes under the same version: the default `allowOverride` accepts it (a plain berry
        // publish has no client-side republish check at all).
        await fs.writeFile(path.join(first.dir, 'e2e-marker.txt'), 'changed');
        const overridden = await execYarn(ctx, 'override', ['npm', 'publish'], first.dir);
        expect(overridden.exitCode, overridden.stdout).toBe(0);
        expect(puts()).toBe(2);
        expect(await stored(), 'the stored bytes changed').not.toBe(firstSha);
      } finally {
        await recorder.stop();
      }
    },
  );
});

test.describe('yarn npm info, tag and whoami', () => {
  test(
    'info answers a version spec and fails on a package that does not exist',
    { tag: [TAG, '@view'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await yarnBerryClient.prepare('info', [writer]);
      const name = packageNameFor(seeder, 'informed');
      for (const version of ['1.0.0', '1.1.0']) {
        const published = await publishPackage(yarnBerryClient, ctx, {
          packageName: name,
          version,
        });
        expect(published.result.exitCode, published.result.stdout).toBe(0);
      }

      const one = await execYarn(ctx, 'info-spec', [
        'npm',
        'info',
        `${name}@1.0.0`,
        '--json',
        '-f',
        'name,version',
      ]);
      expect(one.exitCode, one.stdout).toBe(0);
      expect(JSON.parse(one.stdout)).toEqual({ name, version: '1.0.0' });

      const latest = await execYarn(ctx, 'info-latest', [
        'npm',
        'info',
        name,
        '--json',
        '-f',
        'version,versions,dist-tags',
      ]);
      expect(JSON.parse(latest.stdout)).toEqual({
        name,
        version: '1.1.0',
        versions: ['1.0.0', '1.1.0'],
        'dist-tags': { latest: '1.1.0' },
      });

      const ghost = await execYarn(ctx, 'info-missing', ['npm', 'info', `${name}-nope`, '--json']);
      expect(ghost.exitCode, 'a package the repository does not have').not.toBe(0);
      expect(ghost.stdout).toContain('YN0035');
      expect(ghost.stdout).toContain('Package not found');
    },
  );

  test(
    'info shows a deprecation that berry does not print on install',
    { tag: [TAG, '@deprecate'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const name = packageNameFor(seeder, 'old');
      const message = 'use the newer release instead';

      // Berry has no `deprecate` command: npm sets it (matrix/deprecate.spec.ts).
      const publisher = await npmClient.prepare('deprecate-info', [writer]);
      const published = await publishPackage(npmClient, publisher, {
        packageName: name,
        version: '1.0.0',
      });
      expect(published.result.exitCode, published.result.stderr).toBe(0);
      const deprecated = await npmClient.deprecate?.(publisher, `${name}@1.0.0`, message);
      expect(deprecated?.exitCode, deprecated?.stderr).toBe(0);

      const consumer = await yarnBerryClient.prepare('deprecate-info-con', [
        await tokenBinding(seeder, repo.name, { readOnly: true }),
      ]);
      const info = await execYarn(consumer, 'info-deprecated', [
        'npm',
        'info',
        `${name}@1.0.0`,
        '--json',
        '-f',
        'name,deprecated',
      ]);
      expect(info.exitCode, info.stdout).toBe(0);
      expect(JSON.parse(info.stdout)).toEqual({ name, deprecated: message });
    },
  );

  test(
    'tag: the server refuses a tag on a missing version, berry itself refuses to remove latest',
    { tag: [TAG, '@dist-tags'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const name = packageNameFor(seeder, 'tagged');

      const recorder = await startWireRecorder();
      try {
        const ctx = await yarnBerryClient.prepare('tag', [
          { ...writer, baseUrl: recorder.baseUrl },
        ]);
        const published = await publishPackage(yarnBerryClient, ctx, {
          packageName: name,
          version: '1.0.0',
        });
        expect(published.result.exitCode, published.result.stdout).toBe(0);

        const ghost = await yarnBerryClient.distTag?.add(ctx, `${name}@9.9.9`, 'ghost');
        expect(ghost?.exitCode, 'the registry answers 400 for a version it does not have').not.toBe(
          0,
        );
        expect(ghost?.stdout).toContain('Response Code: 400 (Bad Request)');
        expect(
          recorder.entries
            .filter((entry) => entry.path.includes('/dist-tags/ghost'))
            .map((entry) => entry.status),
        ).toEqual([400]);

        const before = recorder.entries.length;
        const removed = await yarnBerryClient.distTag?.remove(ctx, name, 'latest');
        expect(removed?.exitCode).not.toBe(0);
        expect(`${removed?.stdout}${removed?.stderr}`).toContain(
          "The 'latest' tag cannot be removed.",
        );
        expect(recorder.entries, 'refused on the client side: no request was sent').toHaveLength(
          before,
        );
      } finally {
        await recorder.stop();
      }
    },
  );

  test(
    'whoami answers for the publish registry and for a scope',
    { tag: [TAG, '@whoami'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const scope = `@e2e-${seeder.runId}`;
      const token = await tokenBinding(seeder, repo.name, { readOnly: true });

      // `--publish` asks the default publish registry, `--scope` the registry of that scope.
      const publishing = await yarnBerryClient.prepare('whoami-publish', [token]);
      const scoped = await yarnBerryClient.prepare('whoami-scope', [{ ...token, scope }]);
      for (const [ctx, args] of [
        [publishing, ['--publish']],
        [scoped, ['--scope', scope.slice(1)]],
      ] as const) {
        const asked = await execYarn(ctx, 'whoami', ['npm', 'whoami', ...args]);
        expect(asked.exitCode, `${asked.command}\n${asked.stdout}`).toBe(0);
        expect(asked.stdout).toContain(`YN0000: ${token.credential.username}`);
      }
    },
  );
});
