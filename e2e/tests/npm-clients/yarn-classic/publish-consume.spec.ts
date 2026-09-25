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
 * Yarn classic (1.22.22) through the npm-family harness (RPS-1330): the shared 13-scenario catalog
 * (`registerPublishConsumeLoop(npmFamilyAdapter(yarnClassicClient))`, titles `npm[yarn-classic] >
 * <scenario>`), then what only yarn 1 does. Every cell below was probed live before it was asserted
 * (README "npm-family clients", "Yarn classic").
 *
 * The client (`yarn-classic-client.ts`) runs with `always-auth=true` in the loop and everywhere else
 * unless a test says otherwise: what a real user of a private repository has to set. The catalog
 * needs no expectation override for yarn, but it needs the `knownClientExitDisagreement` hook: yarn 1
 * prints "Published." and exits 0 when the registry answers a publish with 401 (see the client).
 *
 *  - `@always-auth` (matrix row 15, H-3): WITHOUT `always-auth=true` yarn sends no `Authorization` on an
 *    UNSCOPED packument GET, so a private repository answers 401, which yarn reports as "Couldn't find
 *    package" (an auth failure and a missing package read the same). A SCOPED read carries the
 *    credential either way. This is what the panel's npm instructions leave out (B11).
 *  - `@auth-keys` (H-4): yarn 1 matches the `_authToken` key by the exact `//host:port/<repo>/` path
 *    of the registry; a host-level key or another repository's key sends nothing.
 *  - `@scoped-publish` (H-5): yarn 1 sends `dist.tarball` as `<registry>/@scope/name/-/@scope/name-
 *    <v>.tgz` (under registry.yarnpkg.com unless a `.yarnrc` names the registry) and an attachment
 *    named `@scope/name-<v>.tgz`; the registry stores its own conventional URL and serves it
 *    (RPS-1333), and the scoped install works.
 *  - `@tag`: `yarn tag add` fails although the registry applied the tag (candidate (NC9)).
 *  - `@lockfile`: `yarn.lock` `resolved` is the registry's tarball URL plus the sha1 as a fragment.
 *  - `@publish`: `yarn publish` with no tarball argument, and the `.yarnrc` registry source.
 *  - `@not-applicable`: `yarn audit` always asks registry.yarnpkg.com, and yarn 1 has no `whoami`.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { npmAuthHeader, rawGetPackument, rawGetPath } from '../../../src/clients/npm-raw.js';
import { npmFamilyAdapter } from '../../../src/clients/npm-family/adapter.js';
import type { ClientCtx, RegistryBinding } from '../../../src/clients/npm-family/client.js';
import { writeYarnClassicRc } from '../../../src/clients/npm-family/config.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import {
  runYarn,
  yarnClassicClient as client,
} from '../../../src/clients/npm-family/yarn-classic-client.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../../src/scenarios/loop.js';

registerPublishConsumeLoop(npmFamilyAdapter(client));

/** Yarn 1's "not found" for a package it could not read (a 401, a 404 and a wrong token alike). */
function couldNotFind(name: string): RegExp {
  return new RegExp(`Couldn't find package "${name}" on the "npm" registry`);
}

test(
  'yarn-classic sends no credentials for an unscoped read without always-auth, a scoped read does',
  { tag: [client.tag, '@always-auth'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
    const scope = `@e2e-${seeder.runId}`;
    const unscoped = packageNameFor(seeder, 'plain');
    const scoped = packageNameFor(seeder, 'scoped', true);

    const publishedPlain = await publishPackage(
      client,
      await client.prepare('auth-pub', [writer]),
      { packageName: unscoped, version: '1.0.0' },
    );
    const publishedScoped = await publishPackage(
      client,
      await client.prepare('auth-pub-scoped', [{ ...writer, scope }]),
      { packageName: scoped, version: '1.0.0' },
    );
    expect(publishedPlain.result.exitCode, publishedPlain.result.command).toBe(0);
    expect(publishedScoped.result.exitCode, publishedScoped.result.command).toBe(0);

    const recorder = await startWireRecorder({ rewriteTarballUrls: true });
    try {
      const via = (binding: RegistryBinding): RegistryBinding => ({
        ...binding,
        baseUrl: recorder.baseUrl,
      });
      const repoEntries = () => recorder.under(`/${repo.name}/`);
      const consume = async (label: string, binding: RegistryBinding, spec: string) => {
        const before = recorder.entries.length;
        const ctx = await client.prepare(label, [binding]);
        await renderConsumer(ctx.work, `${label}-consumer`);
        const added = await client.add(ctx, [spec]);
        return { ctx, added, entries: recorder.entries.slice(before) };
      };

      // 1. The panel's snippet as it stands: registry + a path-scoped token, no always-auth.
      const noAuth = await consume(
        'no-always-auth',
        via({ ...reader, alwaysAuth: false }),
        `${unscoped}@1.0.0`,
      );
      expect(noAuth.added.exitCode, 'yarn cannot read an unscoped package of a private repo').toBe(
        1,
      );
      expect(`${noAuth.added.stdout}\n${noAuth.added.stderr}`).toMatch(couldNotFind(unscoped));
      expect(noAuth.entries.map((entry) => [entry.method, entry.path])).toEqual([
        ['GET', `/${repo.name}/${unscoped}`],
      ]);
      expect(
        noAuth.entries[0]?.authorization,
        'no Authorization header at all (H-3)',
      ).toBeUndefined();
      expect(noAuth.entries[0]?.status, 'so the registry refuses it').toBe(401);

      // 2. Control: the same configuration with always-auth (the client's default) works.
      const withAuth = await consume('always-auth', via(reader), `${unscoped}@1.0.0`);
      expect(withAuth.added.exitCode, withAuth.added.stderr).toBe(0);
      expect(await client.readInstalledFile(withAuth.ctx, unscoped, MARKER_FILENAME)).toBe(
        publishedPlain.marker,
      );
      expect(withAuth.entries.map((entry) => entry.authScheme)).toEqual(['Bearer', 'Bearer']);

      // 3. A wrong token reads exactly like a missing package: yarn cannot tell them apart.
      const wrong = await consume(
        'wrong-token',
        via({ ...reader, credential: { ...reader.credential, password: 'rdt-not-a-token' } }),
        `${unscoped}@1.0.0`,
      );
      expect(wrong.added.exitCode).toBe(1);
      expect(`${wrong.added.stdout}\n${wrong.added.stderr}`).toMatch(couldNotFind(unscoped));
      expect(wrong.entries[0]?.authScheme, 'the (wrong) credential WAS sent').toBe('Bearer');
      expect(wrong.entries[0]?.status).toBe(401);

      // 4. A SCOPED package needs no always-auth: yarn sends the credential for a scope's registry.
      const scopedRead = await consume(
        'scoped-no-always-auth',
        via({ ...reader, scope, alwaysAuth: false }),
        `${scoped}@1.0.0`,
      );
      expect(scopedRead.added.exitCode, scopedRead.added.stderr).toBe(0);
      expect(await client.readInstalledFile(scopedRead.ctx, scoped, MARKER_FILENAME)).toBe(
        publishedScoped.marker,
      );
      expect(
        scopedRead.entries.map((entry) => entry.authScheme),
        'the packument and the tarball GET both carry the credential',
      ).toEqual(['Bearer', 'Bearer']);
      expect(repoEntries().length).toBeGreaterThan(0);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'yarn-classic matches the auth key by the exact registry path',
  { tag: [client.tag, '@auth-keys'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const other = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
    const name = packageNameFor(seeder, 'keyed');
    const published = await publishPackage(client, await client.prepare('keys-pub', [writer]), {
      packageName: name,
      version: '1.0.0',
    });
    expect(published.result.exitCode, published.result.command).toBe(0);

    const recorder = await startWireRecorder({ rewriteTarballUrls: true });
    try {
      const host = new URL(recorder.baseUrl).host;
      const registry = `${recorder.baseUrl}/${repo.name}/`;
      const token = reader.credential.password;
      const worksOutput = /Saved 1 new dependency/;
      const cases = [
        {
          title: 'the repository path with a trailing slash (what the panel says)',
          npmrc: `registry=${registry}\n//${host}/${repo.name}/:_authToken=${token}\n`,
          exitCode: 0,
          output: worksOutput,
          statuses: [200, 200],
          schemes: ['Bearer', 'Bearer'],
          marker: published.marker,
        },
        {
          title: 'the same key with a registry URL without the trailing slash',
          npmrc: `registry=${registry.slice(0, -1)}\n//${host}/${repo.name}/:_authToken=${token}\n`,
          exitCode: 0,
          output: worksOutput,
          statuses: [200, 200],
          schemes: ['Bearer', 'Bearer'],
          marker: published.marker,
        },
        {
          title: 'a host-level key (no repository path) does not match the registry',
          npmrc: `registry=${registry}\n//${host}/:_authToken=${token}\n`,
          exitCode: 1,
          output: couldNotFind(name),
          statuses: [401],
          schemes: [undefined],
          marker: undefined,
        },
        {
          title: "another repository's key does not match either",
          npmrc: `registry=${registry}\n//${host}/${other.name}/:_authToken=${token}\n`,
          exitCode: 1,
          output: couldNotFind(name),
          statuses: [401],
          schemes: [undefined],
          marker: undefined,
        },
      ];

      for (const [index, testCase] of cases.entries()) {
        const before = recorder.entries.length;
        const ctx = await client.prepare(`keys-${index}`, []);
        await fs.writeFile(path.join(ctx.home, '.npmrc'), `${testCase.npmrc}always-auth=true\n`);
        await renderConsumer(ctx.work, `keys-consumer-${index}`);
        const added = await client.add(ctx, [`${name}@1.0.0`]);
        const entries = recorder.entries.slice(before);

        expect(added.exitCode, `${testCase.title}: ${added.stderr}`).toBe(testCase.exitCode);
        expect(`${added.stdout}\n${added.stderr}`, testCase.title).toMatch(testCase.output);
        expect(
          entries.map((entry) => entry.status),
          testCase.title,
        ).toEqual(testCase.statuses);
        expect(
          entries.map((entry) => entry.authScheme),
          testCase.title,
        ).toEqual(testCase.schemes);
        expect(await client.readInstalledFile(ctx, name, MARKER_FILENAME), testCase.title).toBe(
          testCase.marker,
        );
      }
    } finally {
      await recorder.stop();
    }
  },
);

/**
 * Where yarn 1 builds `dist.tarball` from: its own `registry` option, which comes from a `.yarnrc`
 * (or `--registry`) and NOT from `.npmrc`, so with the registry (default or scope) in `.npmrc` alone
 * it is registry.yarnpkg.com, never the registry the PUT goes to. Either way the registry replaces
 * it (RPS-1333).
 */
const SCOPED_PUBLISH_CONFIGS: Array<{
  title: string;
  scopeOf: (scope: string) => Partial<RegistryBinding>;
  /** Extra configuration for the publisher, and the base URL yarn then puts in `dist.tarball`. */
  setUp: (ctx: ClientCtx, binding: RegistryBinding) => Promise<void>;
  sentBase: (recorderBase: string, repoName: string) => string;
}> = [
  {
    title: 'a default registry in .npmrc',
    scopeOf: () => ({}),
    setUp: async () => undefined,
    sentBase: () => 'http://registry.yarnpkg.com',
  },
  {
    title: 'a scope registry in .npmrc',
    scopeOf: (scope) => ({ scope }),
    setUp: async () => undefined,
    sentBase: () => 'http://registry.yarnpkg.com',
  },
  {
    title: 'a registry in .yarnrc',
    scopeOf: () => ({}),
    setUp: async (ctx, binding) => {
      await writeYarnClassicRc(path.join(ctx.home, '.yarnrc'), [binding]);
    },
    sentBase: (recorderBase, repoName) => `${recorderBase}/${repoName}`,
  },
];

for (const config of SCOPED_PUBLISH_CONFIGS) {
  test(
    `yarn-classic scoped publish with ${config.title}: the registry serves its own conventional tarball URL`,
    { tag: [client.tag, '@scoped-publish'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const scope = `@e2e-${seeder.runId}`;
      const name = packageNameFor(seeder, 'scoped-pub', true);
      const bare = name.split('/')[1] as string;
      const scopeOf = config.scopeOf(scope);

      const recorder = await startWireRecorder({ captureRequestBody: true });
      try {
        const writer = await tokenBinding(seeder, repo.name, {
          readOnly: false,
          baseUrl: recorder.baseUrl,
          ...scopeOf,
        });
        const publisher = await client.prepare('scoped-pub', [writer]);
        await config.setUp(publisher, writer);
        const published = await publishPackage(client, publisher, {
          packageName: name,
          version: '1.0.0',
        });
        expect(published.result.exitCode, published.result.command).toBe(0);

        const puts = recorder.entries.filter((entry) => entry.method === 'PUT');
        expect(
          puts.map((entry) => entry.path),
          'the scoped name is one encoded path segment',
        ).toEqual([`/${repo.name}/${scope}%2f${bare}`]);
        expect(puts[0]?.status).toBe(200);
        const sent = JSON.parse(puts[0]?.requestBody ?? '{}') as {
          _attachments: Record<string, unknown>;
          versions: Record<string, { dist: { tarball: string } }>;
        };
        const sentBase = config.sentBase(recorder.baseUrl, repo.name);
        expect(
          sent.versions['1.0.0']?.dist.tarball,
          'what yarn 1 sends: the scope repeated in the file name (H-5), under its own registry option',
        ).toBe(`${sentBase}/${name}/-/${name}-1.0.0.tgz`);
        expect(Object.keys(sent._attachments), 'and the attachment is named the same way').toEqual([
          `${name}-1.0.0.tgz`,
        ]);

        // What the registry stores and serves: its own address and the conventional file name.
        const served = await rawGetPackument(repo.name, adminCredential(), name);
        const conventional = `${env.repoBaseUrl}/${repo.name}/${name}/-/${bare}-1.0.0.tgz`;
        expect(
          (
            JSON.parse(served.body.toString('utf8')) as {
              versions: Record<string, { dist: { tarball: string } }>;
            }
          ).versions['1.0.0']?.dist.tarball,
          'the served dist.tarball is the registry address with the bare file name (RPS-1333)',
        ).toBe(conventional);

        const reader = await tokenBinding(seeder, repo.name, { readOnly: true, ...scopeOf });
        const consumer = await client.prepare('scoped-pub-con', [reader]);
        await renderConsumer(consumer.work, 'scoped-pub-consumer', { [name]: '1.0.0' });
        const installed = await client.install(consumer, { frozen: false });
        expect(installed.exitCode, `install: ${installed.command}\n${installed.stderr}`).toBe(0);
        expect(await client.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
          published.marker,
        );
        expect(
          await fs.readFile(path.join(consumer.work, 'yarn.lock'), 'utf8'),
          'the lockfile records that URL',
        ).toContain(`resolved "${conventional}#`);
      } finally {
        await recorder.stop();
      }
    },
  );
}

test(
  'yarn-classic tag add fails although the registry applied the tag (NC9)',
  { tag: [client.tag, '@tag'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await client.prepare('tag-nc9', [writer]);
    const name = packageNameFor(seeder, 'tagged-nc9');
    const published = await publishPackage(client, ctx, { packageName: name, version: '1.0.0' });
    expect(published.result.exitCode, published.result.command).toBe(0);

    const added = await client.distTag?.add(ctx, `${name}@1.0.0`, 'stable');
    expect(added?.exitCode, 'yarn tag add reports a failure').toBe(1);
    expect(`${added?.stdout}\n${added?.stderr}`).toContain("Couldn't add tag");
    const tags = await rawGetPath(repo.name, adminCredential(), `-/package/${name}/dist-tags`);
    expect(JSON.parse(tags.body.toString('utf8')), 'and the tag is set').toEqual({
      latest: '1.0.0',
      stable: '1.0.0',
    });

    // The cause: yarn 1 accepts only an answer with an `ok` field in its body, and the registry's
    // 200 has no body at all. (npm and pnpm read the status only.)
    const put = await fetch(`${env.repoBaseUrl}/${repo.name}/-/package/${name}/dist-tags/other`, {
      method: 'PUT',
      headers: { ...npmAuthHeader(adminCredential()), 'Content-Type': 'application/json' },
      body: '"1.0.0"',
    });
    expect(put.status, 'PUT dist-tags/<tag>').toBe(200);
    expect(await put.text(), 'candidate (NC9): the answer has no body, so no "ok"').toBe('');
  },
);

test(
  'yarn-classic yarn.lock records the registry tarball URL with the sha1 as a fragment',
  { tag: [client.tag, '@lockfile'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const name = packageNameFor(seeder, 'locked');
    const published = await publishPackage(client, await client.prepare('lock-pub', [writer]), {
      packageName: name,
      version: '1.0.0',
    });
    expect(published.result.exitCode, published.result.command).toBe(0);

    const consumer = await client.prepare('lock-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'lock-consumer', { [name]: '1.0.0' });
    const installed = await client.install(consumer, { frozen: false });
    expect(installed.exitCode, installed.stderr).toBe(0);

    const dist = (
      JSON.parse(
        (await rawGetPackument(repo.name, adminCredential(), name)).body.toString('utf8'),
      ) as {
        versions: Record<string, { dist: { shasum: string; integrity: string } }>;
      }
    ).versions['1.0.0']?.dist;
    const lockfile = await fs.readFile(path.join(consumer.work, 'yarn.lock'), 'utf8');
    expect(lockfile).toContain(
      `resolved "${env.repoBaseUrl}/${repo.name}/${name}/-/${name}-1.0.0.tgz#${dist?.shasum}"`,
    );
    expect(lockfile).toContain(`integrity ${dist?.integrity}`);
  },
);

test(
  'yarn-classic publishes a directory without a tarball argument, and reads a .yarnrc registry',
  { tag: [client.tag, '@publish'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await client.prepare('dir-pub', [writer]);
    const name = packageNameFor(seeder, 'from-dir');
    const dir = path.join(ctx.work, 'from-dir');
    const { marker } = await renderPackage(dir, { packageName: name, version: '1.0.0' });

    // `yarn publish` with no argument packs the working directory itself.
    const published = await runYarn(
      ctx,
      'yarn1-publish-dir',
      ['publish', '--ignore-scripts', '--no-git-tag-version'],
      dir,
    );
    expect(published.exitCode, `${published.command}\n${published.stderr}`).toBe(0);

    // The registry now comes from a `.yarnrc` (and the credentials from a `.npmrc` without one).
    const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
    const consumer = await client.prepare('yarnrc-con', [reader]);
    await fs.writeFile(
      path.join(consumer.home, '.npmrc'),
      `//${new URL(env.repoBaseUrl).host}/${repo.name}/:_authToken=${reader.credential.password}\nalways-auth=true\n`,
    );
    await writeYarnClassicRc(path.join(consumer.home, '.yarnrc'), [reader]);
    await renderConsumer(consumer.work, 'yarnrc-consumer');
    const added = await client.add(consumer, [`${name}@1.0.0`]);
    expect(added.exitCode, `${added.command}\n${added.stderr}`).toBe(0);
    expect(await client.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(marker);
  },
);

test(
  'yarn-classic audit always asks registry.yarnpkg.com and has no whoami',
  { tag: [client.tag, '@not-applicable'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const name = packageNameFor(seeder, 'audited');
    const published = await publishPackage(client, await client.prepare('audit-pub', [writer]), {
      packageName: name,
      version: '1.0.0',
    });
    expect(published.result.exitCode, published.result.command).toBe(0);

    const recorder = await startWireRecorder({ rewriteTarballUrls: true });
    try {
      const consumer: ClientCtx = await client.prepare('audit-con', [
        {
          ...(await tokenBinding(seeder, repo.name, { readOnly: true })),
          baseUrl: recorder.baseUrl,
        },
      ]);
      await renderConsumer(consumer.work, 'audit-consumer', { [name]: '1.0.0' });
      const installed = await client.install(consumer, { frozen: false });
      expect(installed.exitCode, installed.stderr).toBe(0);
      const before = recorder.entries.length;

      // The audit endpoint is hard-wired to the public registry, whatever the registry setting or
      // `--registry`; in the sealed network it fails on the (dead) proxy, and Repsy is never asked.
      const audited = await runYarn(consumer, 'yarn1-audit', ['audit']);
      expect(audited.exitCode, 'yarn audit fails').not.toBe(0);
      expect(`${audited.stdout}\n${audited.stderr}`).toContain(
        'https://registry.yarnpkg.com/-/npm/v1/security/audits',
      );
      expect(recorder.entries.slice(before), 'nothing reached Repsy').toEqual([]);

      const whoami = await runYarn(consumer, 'yarn1-whoami', ['whoami']);
      expect(whoami.exitCode, 'yarn 1 has no whoami (it takes it for a script name)').not.toBe(0);
      expect(`${whoami.stdout}\n${whoami.stderr}`).toContain('Command "whoami" not found');
    } finally {
      await recorder.stop();
    }
  },
);
