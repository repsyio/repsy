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
 * Yarn berry's own install modes and settings against a Repsy npm repository (RPS-1330, matrix rows
 * 5e, 5f and 15 plus the settings the plan's H-6, H-7 and H-9 are about). Everything here is berry
 * behaviour, observed on the pinned version (README "yarn berry"), asserted as it is:
 *
 *  - PnP linker (5e): the install has no `node_modules`; the marker is read through Plug'n'Play
 *    (`yarn node`), which is strict, so a transitive dependency is not visible from the root.
 *  - Hardened mode (5f): berry re-queries the packument of every locked package to validate the
 *    lockfile against the registry; without it a frozen install fetches tarballs only.
 *  - `--immutable`: a stale lockfile is refused (YN0028) and left untouched.
 *  - H-6: berry refuses plain `http://` unless the host is in `unsafeHttpWhitelist`, `localhost`
 *    included (YN0081).
 *  - H-7 / row 15: without `npmAlwaysAuth` an unscoped read of a private repository is anonymous
 *    (YN0041, a 401), while a scoped read still sends the credential (best-effort auth).
 *  - `npmMinimalAgeGate`: berry's default quarantines a version younger than one day (YN0016),
 *    a fresh Repsy publish included; the rendered config turns it off (`0`).
 *  - H-9: with `dist.tarball` at the registry's own address (RPS-1333) berry's lockfile records no
 *    `__archiveUrl`, scoped packages included; when the consumer reaches the registry by another host
 *    it does record it, and berry then sends the registry's credential to that archive URL's origin.
 *  - Berry reads no `.npmrc`.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { sealedEnv, writeYarnBerryRc } from '../../../src/clients/npm-family/config.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import {
  createYarnBerryClient,
  execYarn,
  YARN_LOCK,
  YARN_RC,
  yarnBerryClient,
} from '../../../src/clients/npm-family/yarn-berry-client.js';
import { editRc, publishApp } from '../../../src/clients/npm-family/yarn-berry-support.js';
import { isolatedWorkDir } from '../../../src/clients/exec.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { repoPath, repoUrl } from '../../../src/repo-url.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { optedIn } from '../../../src/stack-overlays.js';
import type { Seeder } from '../../../src/seed/seeder.js';
import { target } from '../../../src/target.js';

const TAG = '@yarn-berry';

/** A private repository with `lib` and `app -> lib` published, and a read-only token for it. */
async function graphRepo(seeder: Seeder, label: string) {
  const repo = await newRepo(seeder);
  const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
  const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
  const published = await publishApp(await yarnBerryClient.prepare(`${label}-pub`, [writer]), {
    lib: packageNameFor(seeder, 'lib'),
    app: packageNameFor(seeder, 'app'),
  });
  return { repo, writer, reader, ...published };
}

test.describe('yarn berry install modes', () => {
  test(
    "PnP linker installs without node_modules and reads the marker through Plug'n'Play",
    { tag: [TAG, '@pnp'] },
    async ({ seeder }) => {
      const graph = await graphRepo(seeder, 'pnp');
      const pnp = createYarnBerryClient({ nodeLinker: 'pnp', label: 'yarn-berry-pnp' });

      const consumer = await pnp.prepare('pnp-con', [graph.reader]);
      await renderConsumer(consumer.work, 'pnp-consumer');
      const added = await pnp.add(consumer, [`${graph.app}@1.0.0`]);
      expect(added.exitCode, `add: ${added.command}\n${added.stdout}`).toBe(0);

      const files = await fs.readdir(consumer.work);
      expect(files, "Plug'n'Play writes its resolution map").toContain('.pnp.cjs');
      expect(files, 'and no node_modules').not.toContain('node_modules');

      expect(
        await pnp.readInstalledFile(consumer, graph.app, MARKER_FILENAME),
        'the marker of the published bytes, resolved through PnP',
      ).toBe(graph.appMarker);
      expect(
        await pnp.readInstalledFile(consumer, graph.lib, MARKER_FILENAME),
        "PnP is strict: the app's own dependency is not visible from the root project",
      ).toBeUndefined();

      const required = await execYarn(consumer, 'pnp-require', [
        'node',
        '-p',
        `require('${graph.app}').name`,
      ]);
      expect(required.exitCode, required.stderr).toBe(0);
      expect(required.stdout.trim(), 'the package runs through the PnP loader').toBe('repsy-e2e');
    },
  );

  test(
    'hardened mode re-queries the packument of every locked package, plain mode does not',
    { tag: [TAG, '@hardened'] },
    async ({ seeder }) => {
      const graph = await graphRepo(seeder, 'hardened');

      // A lockfile from a first install; a frozen install from it in a fresh HOME and cache, twice,
      // through the recorder, once per mode.
      const first = await yarnBerryClient.prepare('hardened-first', [graph.reader]);
      await renderConsumer(first.work, 'hardened-consumer', { [graph.app]: '1.0.0' });
      expect((await yarnBerryClient.install(first, { frozen: false })).exitCode).toBe(0);

      const metadataGets: Record<string, string[]> = {};
      for (const hardenedMode of [false, true]) {
        const client = createYarnBerryClient({ hardenedMode });
        const recorder = await startWireRecorder({ rewriteTarballUrls: true });
        try {
          const second = await client.prepare(`hardened-${hardenedMode}`, [
            { ...graph.reader, baseUrl: recorder.baseUrl },
          ]);
          for (const file of ['package.json', YARN_LOCK]) {
            await fs.copyFile(path.join(first.work, file), path.join(second.work, file));
          }
          const frozen = await client.install(second, { frozen: true });
          expect(frozen.exitCode, `frozen install: ${frozen.command}\n${frozen.stdout}`).toBe(0);
          expect(await client.readInstalledFile(second, graph.app, MARKER_FILENAME)).toBe(
            graph.appMarker,
          );
          expect(await client.readInstalledFile(second, graph.lib, MARKER_FILENAME)).toBe(
            graph.libMarker,
          );

          const gets = recorder.entries.filter((entry) => entry.method === 'GET');
          metadataGets[String(hardenedMode)] = gets
            .filter((entry) => !entry.path.includes('/-/'))
            .map((entry) => entry.path)
            .sort();
          expect(
            gets.filter((entry) => entry.path.includes('/-/')),
            'both tarballs are fetched in either mode',
          ).toHaveLength(2);
          expect(gets.every((entry) => entry.status === 200)).toBe(true);
        } finally {
          await recorder.stop();
        }
      }

      expect(metadataGets.false, 'plain mode trusts the lockfile: no packument query').toEqual([]);
      expect(
        metadataGets.true,
        'hardened mode validates every locked resolution against the registry',
      ).toEqual(
        [
          `/${repoPath(graph.repo.name)}/${graph.app}`,
          `/${repoPath(graph.repo.name)}/${graph.lib}`,
        ].sort(),
      );
    },
  );

  test(
    '--immutable refuses a stale lockfile and leaves it as it was',
    { tag: [TAG, '@lockfile'] },
    async ({ seeder }) => {
      const graph = await graphRepo(seeder, 'immutable');

      const consumer = await yarnBerryClient.prepare('immutable', [graph.reader]);
      await renderConsumer(consumer.work, 'immutable-consumer', { [graph.app]: '1.0.0' });
      expect((await yarnBerryClient.install(consumer, { frozen: false })).exitCode).toBe(0);
      const lockfile = await fs.readFile(path.join(consumer.work, YARN_LOCK), 'utf8');

      // package.json now asks for one more dependency than the lockfile knows.
      await renderConsumer(consumer.work, 'immutable-consumer', {
        [graph.app]: '1.0.0',
        [graph.lib]: '1.0.0',
      });
      const frozen = await yarnBerryClient.install(consumer, { frozen: true });
      expect(frozen.exitCode, 'a stale lockfile fails --immutable').not.toBe(0);
      expect(frozen.stdout).toContain('YN0028');
      expect(frozen.stdout).toContain('The lockfile would have been modified by this install');
      expect(await fs.readFile(path.join(consumer.work, YARN_LOCK), 'utf8')).toBe(lockfile);

      expect(
        (await yarnBerryClient.install(consumer, { frozen: false })).exitCode,
        'a plain install updates it',
      ).toBe(0);
    },
  );
});

test.describe('yarn berry settings', () => {
  test(
    'plain http needs unsafeHttpWhitelist, even for localhost (H-6)',
    { tag: [TAG, '@config'] },
    async ({ seeder }) => {
      test.skip(
        optedIn('tls'),
        'the registry is https on a TLS stack: there is no plain http for berry to refuse',
      );
      const graph = await graphRepo(seeder, 'http');

      const consumer = await yarnBerryClient.prepare('http', [graph.reader]);
      await renderConsumer(consumer.work, 'http-consumer');
      const host = new URL(env.repoBaseUrl).hostname;
      await editRc(consumer, (rc) => rc.replace(/unsafeHttpWhitelist:\n( {2}- .*\n)+/, ''));

      const refused = await yarnBerryClient.add(consumer, [`${graph.app}@1.0.0`]);
      expect(refused.exitCode, 'berry refuses an unlisted http registry').not.toBe(0);
      expect(refused.stdout).toContain('YN0081');
      expect(refused.stdout).toContain(
        `Unsafe http requests must be explicitly whitelisted in your configuration (${host})`,
      );

      await editRc(consumer, (rc) => `${rc}unsafeHttpWhitelist:\n  - "${host}"\n`);
      const allowed = await yarnBerryClient.add(consumer, [`${graph.app}@1.0.0`]);
      expect(allowed.exitCode, `listed: ${allowed.command}\n${allowed.stdout}`).toBe(0);
      expect(await yarnBerryClient.readInstalledFile(consumer, graph.app, MARKER_FILENAME)).toBe(
        graph.appMarker,
      );
    },
  );

  test(
    'without npmAlwaysAuth an unscoped read of a private repository is anonymous (H-7)',
    { tag: [TAG, '@config', '@negative'] },
    async ({ seeder }) => {
      const graph = await graphRepo(seeder, 'always');

      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const consumer = await yarnBerryClient.prepare('always-off', [
          { ...graph.reader, alwaysAuth: false, baseUrl: recorder.baseUrl },
        ]);
        await renderConsumer(consumer.work, 'always-consumer');
        const added = await yarnBerryClient.add(consumer, [`${graph.app}@1.0.0`]);
        expect(added.exitCode, 'the read fails on the client side').not.toBe(0);
        expect(added.stdout).toContain('YN0041');
        expect(added.stdout).toContain('Invalid authentication (as an anonymous user)');

        const gets = recorder.entries.filter((entry) => entry.method === 'GET');
        expect(gets.map((entry) => entry.path)).toEqual([
          `/${repoPath(graph.repo.name)}/${graph.app}`,
        ]);
        expect(gets[0]?.authorization, 'berry sent no credential').toBeUndefined();
        expect(gets[0]?.status, 'so the private repository refused it').toBe(401);
      } finally {
        await recorder.stop();
      }

      // `whoami` is an explicitly authenticated command: it sends the credential regardless.
      const asked = await yarnBerryClient.prepare('always-off-whoami', [
        { ...graph.reader, alwaysAuth: false },
      ]);
      const whoami = await yarnBerryClient.whoami?.(asked);
      expect(whoami?.exitCode, `whoami: ${whoami?.command}\n${whoami?.stdout}`).toBe(0);
    },
  );

  test(
    'without npmAlwaysAuth a SCOPED read still sends the credential (H-7)',
    { tag: [TAG, '@config'] },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const repo = await newRepo(seeder);
      const scope = `@e2e-${seeder.runId}`;
      const scoped = packageNameFor(seeder, 'best-effort', true);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const published = await publishPackage(
        yarnBerryClient,
        await yarnBerryClient.prepare('best-effort-pub', [{ ...writer, scope }]),
        { packageName: scoped, version: '1.0.0' },
      );
      expect(published.result.exitCode, published.result.stdout).toBe(0);

      const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const consumer = await yarnBerryClient.prepare('best-effort-con', [
          { ...reader, scope, alwaysAuth: false, baseUrl: recorder.baseUrl },
        ]);
        await renderConsumer(consumer.work, 'best-effort-consumer');
        const added = await yarnBerryClient.add(consumer, [`${scoped}@1.0.0`]);
        expect(added.exitCode, `add: ${added.command}\n${added.stdout}`).toBe(0);
        expect(await yarnBerryClient.readInstalledFile(consumer, scoped, MARKER_FILENAME)).toBe(
          published.marker,
        );

        const bearer = `Bearer ${reader.credential.password}`;
        expect(recorder.entries, 'the packument and the tarball').toHaveLength(2);
        expect(
          recorder.entries.map((entry) => entry.authorization),
          'both carry the credential although always-auth is off',
        ).toEqual([bearer, bearer]);
      } finally {
        await recorder.stop();
      }
    },
  );

  test(
    "berry's default age gate quarantines a fresh version; the rendered config turns it off",
    { tag: [TAG, '@config'] },
    async ({ seeder }) => {
      const graph = await graphRepo(seeder, 'gate');

      const gated = await yarnBerryClient.prepare('gate-default', [graph.reader]);
      await renderConsumer(gated.work, 'gate-consumer');
      // The rendered `.yarnrc.yml` sets `npmMinimalAgeGate: "0"`: drop it to see berry's default.
      await editRc(gated, (rc) => rc.replace(/npmMinimalAgeGate: .*\n/g, ''));
      const configured = await execYarn(gated, 'gate-config', [
        'config',
        'get',
        'npmMinimalAgeGate',
      ]);
      expect(configured.stdout.trim(), "berry's default: 1440 minutes, one day").toBe('1440');

      const refused = await yarnBerryClient.add(gated, [`${graph.app}@1.0.0`]);
      expect(refused.exitCode, 'the version was published moments ago').not.toBe(0);
      expect(refused.stdout).toContain('YN0016');
      expect(refused.stdout).toContain('quarantined');

      const open = await yarnBerryClient.prepare('gate-off', [graph.reader]);
      await renderConsumer(open.work, 'gate-consumer');
      const added = await yarnBerryClient.add(open, [`${graph.app}@1.0.0`]);
      expect(added.exitCode, `npmMinimalAgeGate 0: ${added.command}\n${added.stdout}`).toBe(0);
    },
  );

  test('berry reads no .npmrc', { tag: [TAG, '@config'] }, async ({ seeder }) => {
    const graph = await graphRepo(seeder, 'npmrc');

    // A complete `.npmrc` (registry and token, in HOME and next to package.json) and a
    // `.yarnrc.yml` that names no registry at all: only the latter counts, so berry goes to its
    // default registry, which the seal turns into a refused connection.
    const { home, work } = await isolatedWorkDir('npmc-yarn4-npmrc');
    const npmrc = `registry=${repoUrl(graph.repo.name, '')}\n//${new URL(env.repoBaseUrl).host}/${repoPath(graph.repo.name)}/:_authToken=${graph.reader.credential.password}\n`;
    await fs.writeFile(path.join(home, '.npmrc'), npmrc);
    await fs.writeFile(path.join(work, '.npmrc'), npmrc);
    await fs.writeFile(path.join(work, YARN_LOCK), '');
    await renderConsumer(work, 'npmrc-consumer');
    await writeYarnBerryRc(path.join(work, YARN_RC), [], { cacheFolder: path.join(home, 'cache') });

    const ctx = {
      home,
      work,
      env: sealedEnv(home, { YARN_IGNORE_PATH: '1' }),
      secrets: [graph.reader.credential.password ?? ''],
      bindings: [],
    };
    const added = await execYarn(ctx, 'npmrc-add', ['add', `${graph.app}@1.0.0`]);
    expect(added.exitCode, 'berry did not find the registry in .npmrc').not.toBe(0);
    expect(added.stdout, 'it asked its default registry (through the dead proxy)').toMatch(
      /registry\.yarnpkg\.com|ECONNREFUSED 127\.0\.0\.1:9/,
    );
  });
});

test.describe('yarn berry tarball URLs in the lockfile (H-9)', () => {
  test(
    "the lockfile records no __archiveUrl at the registry's own address, scoped packages included",
    { tag: [TAG, '@lockfile', '@archive-url'] },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const graph = await graphRepo(seeder, 'archive');
      const scope = `@e2e-${seeder.runId}`;
      const scoped = packageNameFor(seeder, 'archived', true);
      const publishedScoped = await publishPackage(
        yarnBerryClient,
        await yarnBerryClient.prepare('archive-scoped-pub', [{ ...graph.writer, scope }]),
        { packageName: scoped, version: '1.0.0' },
      );
      expect(publishedScoped.result.exitCode, publishedScoped.result.stdout).toBe(0);

      // Berry builds `<registry><name>/-/<bare name>-<version>.tgz` itself and records the
      // packument's `dist.tarball` (as `__archiveUrl`) only when it differs: for a Repsy repository at
      // its own address it never does. Scoped, berry fetches `@scope%2fname/-/name-1.0.0.tgz` while
      // Repsy serves `@scope/name/-/name-1.0.0.tgz`: berry counts that spelling as conventional too.
      const served = await rawGetPackument(graph.repo.name, adminCredential(), scoped);
      const doc = JSON.parse(served.body.toString('utf8')) as {
        versions: Record<string, { dist: { tarball: string } }>;
      };
      const bare = scoped.split('/')[1];
      expect(doc.versions['1.0.0']?.dist.tarball, 'the served scoped dist.tarball').toBe(
        repoUrl(graph.repo.name, `${scoped}/-/${bare}-1.0.0.tgz`),
      );

      const consumer = await yarnBerryClient.prepare('archive-con', [
        graph.reader,
        { ...graph.reader, scope },
      ]);
      await renderConsumer(consumer.work, 'archive-consumer');
      const added = await yarnBerryClient.add(consumer, [`${graph.app}@1.0.0`, `${scoped}@1.0.0`]);
      expect(added.exitCode, `add: ${added.command}\n${added.stdout}`).toBe(0);
      expect(await yarnBerryClient.readInstalledFile(consumer, scoped, MARKER_FILENAME)).toBe(
        publishedScoped.marker,
      );

      const lockfile = await fs.readFile(path.join(consumer.work, YARN_LOCK), 'utf8');
      for (const name of [graph.app, graph.lib, scoped]) {
        expect(lockfile).toContain(`resolution: "${name}@npm:1.0.0"`);
      }
      expect(lockfile, 'the served URL is the conventional one').not.toContain('__archiveUrl');
    },
  );

  // `@local-only`, like the loop's skip of such a scenario on a remote target: the recorder reaches
  // the registry over plain http only.
  if (!target.isRemote) {
    test(
      'reached by another host, berry records __archiveUrl and sends the registry credential to it',
      { tag: [TAG, '@lockfile', '@archive-url', '@local-only'] },
      async ({ seeder }) => {
        const graph = await graphRepo(seeder, 'archive-other');

        // No rewriting: the packument still names the registry's own address (`REPO_BASE_URL`), which
        // is not the recorder's address the consumer configured, so it is not berry's conventional
        // URL for that registry.
        const recorder = await startWireRecorder();
        try {
          const consumer = await yarnBerryClient.prepare('archive-other-con', [
            { ...graph.reader, baseUrl: recorder.baseUrl },
          ]);
          await renderConsumer(consumer.work, 'archive-other-consumer');
          const added = await yarnBerryClient.add(consumer, [`${graph.app}@1.0.0`]);
          expect(added.exitCode, `add: ${added.command}\n${added.stdout}`).toBe(0);
          expect(
            await yarnBerryClient.readInstalledFile(consumer, graph.app, MARKER_FILENAME),
          ).toBe(graph.appMarker);
          expect(
            await yarnBerryClient.readInstalledFile(consumer, graph.lib, MARKER_FILENAME),
          ).toBe(graph.libMarker);

          const lockfile = await fs.readFile(path.join(consumer.work, YARN_LOCK), 'utf8');
          for (const name of [graph.app, graph.lib]) {
            const archive = repoUrl(graph.repo.name, `${name}/-/${name}-1.0.0.tgz`);
            expect(lockfile, `${name} is pinned to the URL the packument named`).toContain(
              `resolution: "${name}@npm:1.0.0::__archiveUrl=${encodeURIComponent(archive)}"`,
            );
            expect(
              (await fetch(archive)).status,
              'that tarball is on a private repository: it needs the credential',
            ).toBe(401);
          }

          // Berry fetched the tarballs from the packument's address, not through the recorder, and
          // they came back: it sent the configured registry's credential there. Documented, not
          // asserted as a leak: with RPS-1333 the packument no longer names a foreign origin.
          expect(
            recorder.entries.map((entry) => `${entry.method} ${entry.path}`).sort(),
            'the recorder saw the two packument reads and no tarball',
          ).toEqual(
            [
              `GET /${repoPath(graph.repo.name)}/${graph.app}`,
              `GET /${repoPath(graph.repo.name)}/${graph.lib}`,
            ].sort(),
          );
        } finally {
          await recorder.stop();
        }
      },
    );
  }
});
