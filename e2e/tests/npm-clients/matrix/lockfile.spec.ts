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
 * Matrix rows 5b-5d (RPS-1330): dependency graphs, lockfiles and frozen installs, per client that
 * has `frozenInstall` (only npm today; each client PR adds its own column by joining
 * `ENABLED_CLIENTS`).
 *
 *  - 5b `graph`: an `app` that depends on a `lib`, both published to the SAME private repository,
 *    installed by a consumer with a read-only deploy token. The first real transitive resolution in
 *    the suite (the fixtures never depend on a third-party package: OS has no proxy repository).
 *  - 5c `lockfile`: the install writes a lockfile that names the registry's own tarball URLs and
 *    integrity, and a FROZEN install from that lockfile alone, in a fresh HOME with an empty cache,
 *    gets the same bytes. `@smoke`.
 *  - 5d `override`: republishing a version (`allowOverride`, the default) changes its bytes, so its
 *    packument `integrity` is recomputed from the stored bytes and the frozen install from the OLD
 *    lockfile then fails its integrity check. Documents that an override breaks every lockfile that
 *    recorded the version.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import type { ClientId, NpmFamilyClient } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

/** What a client says when a frozen install finds bytes that differ from its lockfile. */
const INTEGRITY_FAILURE: Partial<Record<ClientId, RegExp>> = {
  npm: /EINTEGRITY/,
};

interface Graph {
  repoName: string;
  lib: string;
  app: string;
  libMarker: string;
  appMarker: string;
  publisher: Awaited<ReturnType<NpmFamilyClient['prepare']>>;
  readonly: Awaited<ReturnType<typeof tokenBinding>>;
}

/** Publishes `lib@1.0.0` and `app@1.0.0` (app -> lib, exact) into a fresh private repository. */
async function publishGraph(client: NpmFamilyClient, seeder: Seeder): Promise<Graph> {
  const repo = await newRepo(seeder);
  const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
  const readonly = await tokenBinding(seeder, repo.name, { readOnly: true });
  const publisher = await client.prepare('graph-pub', [writer]);

  const lib = packageNameFor(seeder, 'lib');
  const app = packageNameFor(seeder, 'app');
  const libPublished = await publishPackage(client, publisher, {
    packageName: lib,
    version: '1.0.0',
  });
  expect(libPublished.result.exitCode, `publish lib: ${libPublished.result.command}`).toBe(0);
  const appPublished = await publishPackage(client, publisher, {
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

for (const client of clientsWith('frozenInstall')) {
  test.describe(`${client.label} dependency graph, lockfile and frozen install`, () => {
    test(
      `${client.label} installs an app and its dependency from one repository`,
      {
        tag: [client.tag, '@graph'],
      },
      async ({ seeder }) => {
        const graph = await publishGraph(client, seeder);

        const consumer = await client.prepare('graph-con', [graph.readonly]);
        await renderConsumer(consumer.work, 'graph-consumer');
        const added = await client.add(consumer, [`${graph.app}@1.0.0`]);
        expect(added.exitCode, `add app: ${added.command}\n${added.stderr}`).toBe(0);

        expect(await client.readInstalledFile(consumer, graph.app, MARKER_FILENAME)).toBe(
          graph.appMarker,
        );
        expect(
          await client.readInstalledFile(consumer, graph.lib, MARKER_FILENAME),
          'the transitive dependency came from the same repository, with the published bytes',
        ).toBe(graph.libMarker);
      },
    );

    test(
      `${client.label} lockfile names the registry, and a frozen install from it works`,
      {
        tag: [client.tag, '@lockfile', '@smoke'],
      },
      async ({ seeder }) => {
        const graph = await publishGraph(client, seeder);

        const first = await client.prepare('lock-first', [graph.readonly]);
        await renderConsumer(first.work, 'lock-consumer', { [graph.app]: '1.0.0' });
        const installed = await client.install(first, { frozen: false });
        expect(installed.exitCode, `install: ${installed.command}\n${installed.stderr}`).toBe(0);

        const lockfile = await fs.readFile(path.join(first.work, client.lockfile ?? ''), 'utf8');
        for (const name of [graph.app, graph.lib]) {
          expect(
            lockfile,
            `${client.lockfile} records ${name}'s tarball from the registry's own address`,
          ).toContain(`${env.repoBaseUrl}/${graph.repoName}/${name}/-/${name}-1.0.0.tgz`);
        }
        expect(lockfile, 'and an integrity hash for it').toMatch(/sha512-/);

        // A fresh HOME and cache, and only package.json + the lockfile: nothing but the lockfile and
        // the registry can produce the install.
        const second = await client.prepare('lock-second', [graph.readonly]);
        for (const file of ['package.json', client.lockfile ?? '']) {
          await fs.copyFile(path.join(first.work, file), path.join(second.work, file));
        }
        const frozen = await client.install(second, { frozen: true });
        expect(frozen.exitCode, `frozen install: ${frozen.command}\n${frozen.stderr}`).toBe(0);

        expect(await client.readInstalledFile(second, graph.app, MARKER_FILENAME)).toBe(
          graph.appMarker,
        );
        expect(await client.readInstalledFile(second, graph.lib, MARKER_FILENAME)).toBe(
          graph.libMarker,
        );
      },
    );

    test(
      `${client.label} frozen install fails after an override republish`,
      {
        tag: [client.tag, '@lockfile', '@settings'],
      },
      async ({ seeder }) => {
        const integrityFailure = INTEGRITY_FAILURE[client.id];
        expect(
          integrityFailure,
          `no integrity-failure pattern is pinned for ${client.id}`,
        ).toBeDefined();

        const graph = await publishGraph(client, seeder);

        const first = await client.prepare('override-first', [graph.readonly]);
        await renderConsumer(first.work, 'override-consumer', { [graph.lib]: '1.0.0' });
        expect((await client.install(first, { frozen: false })).exitCode).toBe(0);
        const lockfile = await fs.readFile(path.join(first.work, client.lockfile ?? ''), 'utf8');

        // The same version again, new bytes (`allowOverride` is on in a fresh repository).
        const republished = await publishPackage(
          client,
          graph.publisher,
          { packageName: graph.lib, version: '1.0.0' },
          { forceRepublish: true },
        );
        expect(republished.result.exitCode, `override: ${republished.result.command}`).toBe(0);

        // The registry recomputed the integrity from the stored bytes, so it no longer matches the
        // one the first install recorded.
        const after = await rawGetPackument(graph.repoName, adminCredential(), graph.lib);
        const served = (
          JSON.parse(after.body.toString('utf8')) as {
            versions: Record<string, { dist: { integrity: string } }>;
          }
        ).versions['1.0.0']?.dist.integrity;
        expect(served, 'the packument integrity follows the new bytes').toBe(
          `sha512-${createHash('sha512').update(republished.tarball.bytes).digest('base64')}`,
        );
        expect(lockfile, 'the old lockfile still has the old integrity').not.toContain(
          served ?? '',
        );

        const second = await client.prepare('override-second', [graph.readonly]);
        for (const file of ['package.json', client.lockfile ?? '']) {
          await fs.copyFile(path.join(first.work, file), path.join(second.work, file));
        }
        const frozen = await client.install(second, { frozen: true });
        expect(frozen.exitCode, `frozen install must fail: ${frozen.command}`).not.toBe(0);
        expect(`${frozen.stdout}\n${frozen.stderr}`).toMatch(integrityFailure as RegExp);
        expect(
          await client.readInstalledFile(second, graph.lib, MARKER_FILENAME),
          'no package with the new bytes was installed',
        ).toBeUndefined();
      },
    );
  });
}
