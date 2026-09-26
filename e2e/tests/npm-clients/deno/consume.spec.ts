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
 * Deno as a consumer of a Repsy npm repository (RPS-1486): dependency graph, lockfile, frozen install,
 * the override that breaks a lockfile, and Deno's own `minimumDependencyAge` gate. Deno only consumes
 * (`deno-client.ts`), so every package is published by `npmClient`, with a read/write deploy token; the
 * consumer is Deno with a read-only one. The catalog's auth scenarios are `publish-consume.spec.ts`; how
 * Deno authenticates and what it asks for on the wire is `config.spec.ts`.
 *
 * Probed with deno 2.9.7:
 *  - Deno lays a package out as `node_modules/<name>` (a symlink) only for what the project depends on;
 *    a transitive dependency lives at `node_modules/.deno/<name>@<version>/node_modules/<name>`.
 *  - `deno.lock` (v5) records every package's `integrity` (sha512 SRI) and its `tarball` URL, the
 *    registry's own (RPS-1333), always (npm and bun do the same, pnpm and berry do not).
 *  - A frozen install from that lockfile needs nothing but `package.json` and the lockfile, and is not
 *    subject to the `minimumDependencyAge` gate (the lockfile already pins what to install).
 *  - Deno's default gate (24 hours) refuses a version published minutes ago, which is every version
 *    these tests publish: `deno-client.ts` switches it off, and the gate's own cell keeps it on.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import type { ClientCtx } from '../../../src/clients/npm-family/client.js';
import { denoClient, denoExec } from '../../../src/clients/npm-family/deno-client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { repoPath, repoUrl } from '../../../src/repo-url.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

interface Graph {
  repoName: string;
  lib: string;
  app: string;
  libMarker: string;
  appMarker: string;
  publisher: ClientCtx;
  readonly: Awaited<ReturnType<typeof tokenBinding>>;
}

/** Publishes `lib@1.0.0` and `app@1.0.0` (app -> lib, exact) into a fresh private repository, with npm. */
async function publishGraph(seeder: Seeder): Promise<Graph> {
  const repo = await newRepo(seeder);
  const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
  const readonly = await tokenBinding(seeder, repo.name, { readOnly: true });
  const publisher = await npmClient.prepare('deno-graph-pub', [writer]);

  const lib = packageNameFor(seeder, 'lib');
  const app = packageNameFor(seeder, 'app');
  const libPublished = await publishPackage(npmClient, publisher, {
    packageName: lib,
    version: '1.0.0',
  });
  expect(libPublished.result.exitCode, `publish lib: ${libPublished.result.command}`).toBe(0);
  const appPublished = await publishPackage(npmClient, publisher, {
    packageName: app,
    version: '1.0.0',
    dependencies: { [lib]: '1.0.0' },
  });
  expect(appPublished.result.exitCode, `publish app: ${appPublished.result.command}`).toBe(0);

  return {
    repoName: repo.name,
    lib,
    app,
    libMarker: libPublished.marker,
    appMarker: appPublished.marker,
    publisher,
    readonly,
  };
}

/** A marker of a TRANSITIVE dependency, where Deno keeps it (see the header). */
async function readTransitive(
  ctx: ClientCtx,
  packageName: string,
  version: string,
): Promise<string | undefined> {
  try {
    return await fs.readFile(
      path.join(
        ctx.work,
        'node_modules',
        '.deno',
        `${packageName}@${version}`,
        'node_modules',
        packageName,
        MARKER_FILENAME,
      ),
      'utf8',
    );
  } catch {
    return undefined;
  }
}

test.describe('deno dependency graph, lockfile and frozen install', () => {
  test(
    'deno installs an app and its dependency from one private repository',
    { tag: [denoClient.tag, '@graph'] },
    async ({ seeder }) => {
      const graph = await publishGraph(seeder);

      const consumer = await denoClient.prepare('graph-con', [graph.readonly]);
      await renderConsumer(consumer.work, 'graph-consumer');
      const added = await denoClient.add(consumer, [`${graph.app}@1.0.0`]);
      expect(added.exitCode, `add app: ${added.command}\n${added.stderr}`).toBe(0);

      expect(await denoClient.readInstalledFile(consumer, graph.app, MARKER_FILENAME)).toBe(
        graph.appMarker,
      );
      expect(
        await readTransitive(consumer, graph.lib, '1.0.0'),
        'the transitive dependency came from the same repository, with the published bytes',
      ).toBe(graph.libMarker);

      // `deno install npm:<spec>` in a project with a package.json records the dependency there.
      const manifest = JSON.parse(
        await fs.readFile(path.join(consumer.work, 'package.json'), 'utf8'),
      ) as { dependencies?: Record<string, string> };
      expect(manifest.dependencies, 'deno install added the dependency to package.json').toEqual({
        [graph.app]: '1.0.0',
      });
    },
  );

  test(
    'deno lockfile names the registry, and a frozen install from it works',
    { tag: [denoClient.tag, '@lockfile'] },
    async ({ seeder }) => {
      const graph = await publishGraph(seeder);

      const first = await denoClient.prepare('lock-first', [graph.readonly]);
      await renderConsumer(first.work, 'lock-consumer', { [graph.app]: '1.0.0' });
      const installed = await denoClient.install(first, { frozen: false });
      expect(installed.exitCode, `install: ${installed.command}\n${installed.stderr}`).toBe(0);

      const lockfile = await fs.readFile(path.join(first.work, 'deno.lock'), 'utf8');
      for (const name of [graph.app, graph.lib]) {
        expect(lockfile, `deno.lock records the registry's own tarball URL of ${name}`).toContain(
          repoUrl(graph.repoName, `${name}/-/${name}-1.0.0.tgz`),
        );
      }
      expect(lockfile, 'and an integrity hash for it').toMatch(/"integrity": "sha512-/);

      // A fresh HOME and cache, and only package.json + the lockfile: nothing but the lockfile and
      // the registry can produce the install. Run with Deno's DEFAULT age gate (no
      // `--minimum-dependency-age=0`): the lockfile pins the version, so the 24 h gate does not apply.
      const second = await denoClient.prepare('lock-second', [graph.readonly]);
      for (const file of ['package.json', 'deno.lock']) {
        await fs.copyFile(path.join(first.work, file), path.join(second.work, file));
      }
      const frozen = await denoExec(second, 'deno-install-frozen-default-age', [
        'install',
        '--frozen',
      ]);
      expect(frozen.exitCode, `frozen install: ${frozen.command}\n${frozen.stderr}`).toBe(0);

      expect(await denoClient.readInstalledFile(second, graph.app, MARKER_FILENAME)).toBe(
        graph.appMarker,
      );
      expect(await readTransitive(second, graph.lib, '1.0.0')).toBe(graph.libMarker);
    },
  );

  test(
    'deno frozen install fails after an override republish',
    { tag: [denoClient.tag, '@lockfile', '@settings'] },
    async ({ seeder }) => {
      const graph = await publishGraph(seeder);

      const first = await denoClient.prepare('override-first', [graph.readonly]);
      await renderConsumer(first.work, 'override-consumer', { [graph.lib]: '1.0.0' });
      expect((await denoClient.install(first, { frozen: false })).exitCode).toBe(0);
      const lockfile = await fs.readFile(path.join(first.work, 'deno.lock'), 'utf8');

      // The same version again, new bytes (`allowOverride` is on in a fresh repository).
      const republished = await publishPackage(
        npmClient,
        graph.publisher,
        { packageName: graph.lib, version: '1.0.0' },
        { forceRepublish: true },
      );
      expect(republished.result.exitCode, `override: ${republished.result.command}`).toBe(0);

      // The registry recomputed the integrity from the stored bytes, so it no longer matches the one
      // the first install recorded.
      const after = await rawGetPackument(graph.repoName, adminCredential(), graph.lib);
      const served = (
        JSON.parse(after.body.toString('utf8')) as {
          versions: Record<string, { dist: { integrity: string } }>;
        }
      ).versions['1.0.0']?.dist.integrity;
      expect(served, 'the packument integrity follows the new bytes').toBe(
        `sha512-${createHash('sha512').update(republished.tarball.bytes).digest('base64')}`,
      );
      expect(lockfile, 'the old lockfile still has the old integrity').not.toContain(served ?? '');

      const second = await denoClient.prepare('override-second', [graph.readonly]);
      for (const file of ['package.json', 'deno.lock']) {
        await fs.copyFile(path.join(first.work, file), path.join(second.work, file));
      }
      const frozen = await denoClient.install(second, { frozen: true });
      expect(frozen.exitCode, `frozen install must fail: ${frozen.command}`).not.toBe(0);
      expect(`${frozen.stdout}\n${frozen.stderr}`).toMatch(INTEGRITY_FAILURE);
      expect(
        await denoClient.readInstalledFile(second, graph.lib, MARKER_FILENAME),
        'no package with the new bytes was installed',
      ).toBeUndefined();
    },
  );
});

/** What Deno says when the tarball it fetched does not match the lockfile's integrity. */
const INTEGRITY_FAILURE = /Tarball checksum did not match what was provided by npm registry/;

test.describe('deno minimumDependencyAge gate', () => {
  test(
    'the default 24 h gate refuses a version published minutes ago and asks for the full packument',
    { tag: [denoClient.tag, '@gate'] },
    async ({ seeder }) => {
      const graph = await publishGraph(seeder);
      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const consumer = await denoClient.prepare('gate-con', [
          { ...graph.readonly, baseUrl: recorder.baseUrl },
        ]);
        await renderConsumer(consumer.work, 'gate-consumer');
        const refused = await denoExec(consumer, 'deno-install-default-age', [
          'install',
          `npm:${graph.lib}@1.0.0`,
        ]);
        expect(refused.exitCode, `the gate refuses: ${refused.command}`).not.toBe(0);
        expect(`${refused.stdout}\n${refused.stderr}`).toMatch(/minimum dependency age/i);

        const packuments = recorder.entries.filter(
          (entry) =>
            entry.method === 'GET' && entry.path === `/${repoPath(graph.repoName)}/${graph.lib}`,
        );
        expect(packuments.length, 'the packument was asked for').toBeGreaterThanOrEqual(1);
        // The abbreviated document has no `time`, which the gate needs: Deno asks for anything.
        expect(packuments.map((entry) => entry.accept)).toEqual(packuments.map(() => '*/*'));
        expect(
          recorder.entries.some((entry) => entry.path.includes('/-/')),
          'no tarball was fetched',
        ).toBe(false);

        // The gate off (what the suite's own installs pass): the same version installs.
        const allowed = await denoClient.add(consumer, [`${graph.lib}@1.0.0`]);
        expect(allowed.exitCode, `gate off: ${allowed.command}\n${allowed.stderr}`).toBe(0);
        expect(await denoClient.readInstalledFile(consumer, graph.lib, MARKER_FILENAME)).toBe(
          graph.libMarker,
        );
      } finally {
        await recorder.stop();
      }
    },
  );
});
