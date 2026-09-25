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
 * The bun commands the matrix cannot cover (RPS-1330), each against the real registry:
 *
 *  - `bun publish`: under a tag, a scoped package with `--access public`, and `--tolerate-republish`
 *    (H-17: no flag is needed for a republish to reach the server, and this one keeps it from being
 *    sent: bun asks for the packument first and skips). A republish of an existing version is the
 *    catalog's `override` / `no-override` scenarios.
 *  - a workspace member's `bun publish` rewrites `workspace:` and `catalog:` ranges before it sends the
 *    manifest, so the registry stores plain ranges.
 *  - `bun info` (not `view`): its `--json` is the latest manifest plus `versions`, and bun reads
 *    `dist-tags`, `time` and `deprecated` as properties, so the matrix's npm-shaped `view` cell does
 *    not fit it. It is the only place a bun user sees a deprecation.
 *  - `bun pm whoami`, and what it does with no credential, and `bun audit` (RPS-1329): the bare
 *    advisory map on stdout, not npm's report, so the matrix's audit cell does not fit either.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { npmAuthHeader, rawGetPackument, rawGetPath } from '../../../src/clients/npm-raw.js';
import { bunClient, bunExec } from '../../../src/clients/npm-family/bun-client.js';
import type { ClientCtx } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { target } from '../../../src/target.js';

const ISO_UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

async function distTagsOf(repoName: string, packageName: string): Promise<Record<string, string>> {
  const res = await rawGetPath(repoName, adminCredential(), `-/package/${packageName}/dist-tags`);
  expect(res.status, `GET dist-tags of ${packageName}`).toBe(200);
  return JSON.parse(res.body.toString('utf8')) as Record<string, string>;
}

interface StoredVersion {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
  integrity?: string;
  dist?: { integrity?: string };
  [key: string]: unknown;
}

async function storedPackument(
  repoName: string,
  packageName: string,
): Promise<{ versions: Record<string, StoredVersion>; _attachments?: Record<string, unknown> }> {
  const res = await rawGetPackument(repoName, adminCredential(), packageName);
  expect(res.status, `GET packument of ${packageName}`).toBe(200);
  return JSON.parse(res.body.toString('utf8')) as {
    versions: Record<string, StoredVersion>;
    _attachments?: Record<string, unknown>;
  };
}

/** `bun info <args> [--json]` in a project of its own. */
async function info(ctx: ClientCtx, ...args: string[]) {
  await renderConsumer(ctx.work, 'bun-info-project');
  return bunExec(ctx, 'bun-info', ['info', ...args]);
}

test(
  'bun publish under a tag sets it, and a tagged install gets it',
  {
    tag: ['@bun', '@publish', '@dist-tags'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('tag-pub', [writer]);
    const name = packageNameFor(seeder, 'tagged');

    const first = await publishPackage(
      bunClient,
      ctx,
      { packageName: name, version: '1.0.0' },
      { tag: 'beta' },
    );
    expect(first.result.exitCode, `publish --tag beta: ${first.result.command}`).toBe(0);
    expect(first.result.stdout, 'bun reports the tag it published under').toContain('Tag: beta');
    expect(
      await distTagsOf(repo.name, name),
      'a first publish under a tag also sets latest',
    ).toEqual({ beta: '1.0.0', latest: '1.0.0' });

    const second = await publishPackage(bunClient, ctx, { packageName: name, version: '1.1.0' });
    expect(second.result.exitCode, `publish: ${second.result.command}`).toBe(0);
    expect(await distTagsOf(repo.name, name)).toEqual({ beta: '1.0.0', latest: '1.1.0' });

    const consumer = await bunClient.prepare('tag-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'tag-consumer');
    const added = await bunClient.add(consumer, [`${name}@beta`]);
    expect(added.exitCode, `add ${name}@beta: ${added.command}\n${added.stderr}`).toBe(0);
    expect(await bunClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(first.marker);

    // bun reads the tags as a property of `bun info`.
    const tags = await info(consumer, name, 'dist-tags', '--json');
    expect(tags.exitCode, `info dist-tags: ${tags.stderr}`).toBe(0);
    expect(JSON.parse(tags.stdout)).toEqual({ beta: '1.0.0', latest: '1.1.0' });
  },
);

test(
  'bun publish takes a scoped package with and without --access public',
  {
    tag: ['@bun', '@publish', '@scopes'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('scoped-pub', [writer]);
    const plain = packageNameFor(seeder, 'plain', true);
    const explicit = packageNameFor(seeder, 'explicit', true);

    const published = await publishPackage(bunClient, ctx, {
      packageName: plain,
      version: '1.0.0',
    });
    expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);
    expect(published.result.stdout).toContain('Access: default');

    const dir = path.join(ctx.work, 'explicit');
    const { marker } = await renderPackage(dir, { packageName: explicit, version: '1.0.0' });
    const tarball = await bunClient.pack(ctx, dir);
    const withAccess = await bunExec(
      ctx,
      'bun-publish-public',
      ['publish', tarball.file, '--access', 'public', '--ignore-scripts'],
      dir,
    );
    expect(withAccess.exitCode, `publish --access public: ${withAccess.stderr}`).toBe(0);
    expect(withAccess.stdout).toContain('Access: public');

    const consumer = await bunClient.prepare('scoped-con', [
      {
        ...(await tokenBinding(seeder, repo.name, { readOnly: true })),
        scope: `@e2e-${seeder.runId}`,
      },
    ]);
    await renderConsumer(consumer.work, 'scoped-consumer');
    const added = await bunClient.add(consumer, [`${plain}@1.0.0`, `${explicit}@1.0.0`]);
    expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
    expect(await bunClient.readInstalledFile(consumer, plain, MARKER_FILENAME)).toBe(
      published.marker,
    );
    expect(await bunClient.readInstalledFile(consumer, explicit, MARKER_FILENAME)).toBe(marker);
  },
);

test(
  'bun publish reaches the server for a republish with no flag; --tolerate-republish keeps it from it',
  {
    tag: ['@bun', '@publish', '@settings'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const writer = await tokenBinding(seeder, repo.name, {
        readOnly: false,
        baseUrl: recorder.baseUrl,
      });
      const ctx = await bunClient.prepare('republish', [writer]);
      const name = packageNameFor(seeder, 'twice');
      const published = await publishPackage(bunClient, ctx, {
        packageName: name,
        version: '1.0.0',
      });
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);
      const integrity = (await storedPackument(repo.name, name)).versions['1.0.0']?.dist?.integrity;

      // A different tarball for the same version, `--tolerate-republish`: bun asks the registry for
      // the packument, sees the version, warns and stops: no PUT, the stored bytes are untouched.
      const dir = path.join(ctx.work, 'again');
      await renderPackage(dir, { packageName: name, version: '1.0.0' });
      const again = await bunClient.pack(ctx, dir);
      const beforeTolerated = recorder.entries.length;
      const tolerated = await bunExec(
        ctx,
        'bun-publish-tolerate',
        ['publish', again.file, '--tolerate-republish', '--ignore-scripts'],
        dir,
      );
      expect(tolerated.exitCode, `tolerated: ${tolerated.stderr}`).toBe(0);
      expect(tolerated.stderr).toContain('Registry already knows about version 1.0.0; skipping');
      expect(
        recorder.entries.slice(beforeTolerated).map((entry) => `${entry.method} ${entry.path}`),
        'a GET of the packument, and no PUT',
      ).toEqual([`GET /${repo.name}/${name}`]);
      expect((await storedPackument(repo.name, name)).versions['1.0.0']?.dist?.integrity).toBe(
        integrity,
      );

      // No flag: bun sends the PUT (the repository allows the override, so it is accepted and the
      // stored bytes change; a repository that does not is `no-override` in the catalog).
      const beforePlain = recorder.entries.length;
      const plain = await bunExec(
        ctx,
        'bun-publish-plain',
        ['publish', again.file, '--ignore-scripts'],
        dir,
      );
      expect(plain.exitCode, `republish: ${plain.stderr}`).toBe(0);
      expect(
        recorder.entries.slice(beforePlain).map((entry) => `${entry.method} ${entry.status}`),
      ).toEqual(['PUT 200']);
      expect((await storedPackument(repo.name, name)).versions['1.0.0']?.dist?.integrity).not.toBe(
        integrity,
      );
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'bun publish of a workspace member rewrites workspace: and catalog: ranges',
  {
    tag: ['@bun', '@publish', '@workspaces'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('workspace', [writer]);
    const lib = packageNameFor(seeder, 'wslib');
    const tools = packageNameFor(seeder, 'wstools');
    const app = packageNameFor(seeder, 'wsapp');
    const dep = packageNameFor(seeder, 'wsdep');

    // `dep` is a third package of the same repository that the workspace's catalog names.
    const depPublished = await publishPackage(bunClient, ctx, {
      packageName: dep,
      version: '2.3.5',
    });
    expect(depPublished.result.exitCode, `publish dep: ${depPublished.result.command}`).toBe(0);

    const write = async (file: string, content: unknown) => {
      await fs.mkdir(path.dirname(path.join(ctx.work, file)), { recursive: true });
      await fs.writeFile(path.join(ctx.work, file), JSON.stringify(content, null, 2));
    };
    await write('package.json', {
      name: 'bun-workspace-root',
      private: true,
      workspaces: ['packages/*'],
      catalog: { [dep]: '^2.3.4' },
    });
    await write('packages/lib/package.json', { name: lib, version: '1.2.3' });
    await write('packages/tools/package.json', { name: tools, version: '0.4.0' });
    await write('packages/app/package.json', {
      name: app,
      version: '1.0.0',
      dependencies: { [lib]: 'workspace:^', [dep]: 'catalog:' },
      devDependencies: { [tools]: 'workspace:*' },
      peerDependencies: { [lib]: 'workspace:~' },
    });
    for (const member of ['lib', 'tools', 'app']) {
      await fs.writeFile(path.join(ctx.work, 'packages', member, 'index.js'), '');
    }
    const publishMember = (member: string) =>
      bunExec(
        ctx,
        `bun-publish-${member}`,
        ['publish', '--ignore-scripts'],
        path.join(ctx.work, 'packages', member),
      );

    // Without a lockfile bun cannot resolve `workspace:^` and refuses to publish, before any request.
    const early = await publishMember('app');
    expect(early.exitCode, 'publish before `bun install`').not.toBe(0);
    expect(early.stderr).toContain('Failed to resolve workspace version');

    const installed = await bunClient.install(ctx, { frozen: false });
    expect(installed.exitCode, `install: ${installed.stderr}`).toBe(0);
    for (const member of ['lib', 'tools', 'app']) {
      const published = await publishMember(member);
      expect(published.exitCode, `publish ${member}: ${published.stderr}`).toBe(0);
    }

    const stored = (await storedPackument(repo.name, app)).versions['1.0.0'];
    expect(
      stored?.dependencies,
      'workspace:^ becomes ^<version>, catalog: the catalog range',
    ).toEqual({
      [lib]: '^1.2.3',
      [dep]: '^2.3.4',
    });
    expect(stored?.devDependencies, 'workspace:* becomes the exact version').toEqual({
      [tools]: '0.4.0',
    });
    expect(stored?.peerDependencies, 'workspace:~ becomes ~<version>').toEqual({
      [lib]: '~1.2.3',
    });

    // The rewritten ranges resolve: a consumer of `app` gets `lib` and `dep` from the repository.
    const consumer = await bunClient.prepare('workspace-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'workspace-consumer');
    const added = await bunClient.add(consumer, [`${app}@1.0.0`]);
    expect(added.exitCode, `add app: ${added.command}\n${added.stderr}`).toBe(0);
    for (const installedName of [app, lib, dep]) {
      expect(
        await bunClient.readInstalledFile(consumer, installedName, 'package.json'),
        `${installedName} is installed`,
      ).toBeDefined();
    }
    expect(await bunClient.readInstalledFile(consumer, dep, MARKER_FILENAME)).toBe(
      depPublished.marker,
    );
    expect(
      await bunClient.readInstalledFile(consumer, tools, 'package.json'),
      'the devDependency is not installed',
    ).toBeUndefined();
  },
);

test(
  'bun info reads the packument: the manifest, dist-tags, time, versions and a deprecation',
  {
    tag: ['@bun', '@info'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('info-pub', [writer]);
    const name = packageNameFor(seeder, 'viewed');
    for (const version of ['1.0.0', '1.1.0']) {
      const published = await publishPackage(bunClient, ctx, {
        packageName: name,
        version,
        manifest: { description: 'viewed by bun info' },
      });
      expect(published.result.exitCode, `publish ${version}: ${published.result.command}`).toBe(0);
    }

    const reader = await bunClient.prepare('info-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    const manifest = await info(reader, name, '--json');
    expect(manifest.exitCode, `info: ${manifest.stderr}`).toBe(0);
    const doc = JSON.parse(manifest.stdout) as {
      name: string;
      version: string;
      description: string;
      versions: string[];
      dist: { tarball: string; integrity: string };
    };
    expect(doc.name).toBe(name);
    expect(doc.version, 'the latest version').toBe('1.1.0');
    expect(doc.description).toBe('viewed by bun info');
    expect(doc.versions).toEqual(['1.0.0', '1.1.0']);
    expect(doc.dist.tarball, "the registry address, not the publisher's (RPS-1333)").toBe(
      `${env.repoBaseUrl}/${repo.name}/${name}/-/${name}-1.1.0.tgz`,
    );
    expect(doc.dist.integrity).toMatch(/^sha512-/);

    const oldVersion = await info(reader, `${name}@1.0.0`, 'version');
    expect(oldVersion.stdout.trim()).toBe('1.0.0');

    const time = await info(reader, name, 'time', '--json');
    const times = JSON.parse(time.stdout) as Record<string, string>;
    for (const key of ['created', 'modified', '1.0.0', '1.1.0']) {
      expect(times[key], `time.${key} is ISO-8601 UTC`).toMatch(ISO_UTC);
    }
    expect(Math.abs(Date.now() - Date.parse(times['1.1.0'] ?? '')), 'and it is now').toBeLessThan(
      target.isRemote ? Number.POSITIVE_INFINITY : 5 * 60 * 1000,
    );

    // A deprecation is shown by `bun info` and by nothing else in bun (`matrix/deprecate.spec.ts`).
    const message = 'use the newer release instead';
    const publisher = await npmClient.prepare('info-deprecate', [writer]);
    const deprecated = await npmClient.deprecate?.(publisher, `${name}@1.0.0`, message);
    expect(deprecated?.exitCode, `deprecate: ${deprecated?.stderr}`).toBe(0);
    const shown = await info(reader, `${name}@1.0.0`, 'deprecated');
    expect(shown.exitCode).toBe(0);
    expect(shown.stdout.trim()).toBe(message);
    const notDeprecated = await info(reader, `${name}@1.1.0`, 'deprecated', '--json');
    expect(notDeprecated.exitCode, 'the other version has no such property').not.toBe(0);

    // What is not there is an error, in the exit code.
    const missing = await info(reader, `${name}-never-published`, '--json');
    expect(missing.exitCode).not.toBe(0);
    expect(missing.stderr).toContain('does not exist in this registry');
    const noVersion = await info(reader, `${name}@9.9.9`, '--json');
    expect(noVersion.exitCode).not.toBe(0);
    expect(noVersion.stdout).toContain('No matching version found');

    // RPS-1357, for a bun publisher: the tarball is kept as `_attachments`, but no `_resolved` path of
    // the publisher's machine (that is npm's `publish <tarball>`, see `matrix/view.spec.ts`).
    const stored = await storedPackument(repo.name, name);
    expect(
      Object.keys(stored._attachments ?? {}),
      'RPS-1357: the latest publish as _attachments',
    ).toEqual([`${name}-1.1.0.tgz`]);
    expect(stored.versions['1.1.0']?._resolved, 'bun adds no _resolved').toBeUndefined();
  },
);

test(
  'bun pm whoami names the credential, and refuses to ask with none or a wrong one',
  {
    tag: ['@bun', '@whoami', '@negative'],
  },
  async ({ seeder }) => {
    const publicRepo = await newRepo(seeder, false);
    const recorder = await startWireRecorder();
    try {
      const anonymous = await bunClient.prepare('whoami-anon', [
        { repoName: publicRepo.name, credential: {}, baseUrl: recorder.baseUrl },
      ]);
      const none = await bunClient.whoami?.(anonymous);
      expect(none?.exitCode, 'no credential, even for a public repository').not.toBe(0);
      expect(none?.stderr).toContain('missing authentication');
      expect(recorder.entries, 'bun stops before sending a request').toHaveLength(0);

      const wrong = await bunClient.prepare('whoami-wrong', [
        {
          repoName: publicRepo.name,
          credential: {
            transport: 'basic',
            username: 'nobody',
            password: 'rdt-not-a-token',
            kind: 'token',
          },
          baseUrl: recorder.baseUrl,
        },
      ]);
      const refused = await bunClient.whoami?.(wrong);
      expect(refused?.exitCode, 'a token the registry does not know').not.toBe(0);
      expect(
        recorder.entries.map(
          (entry) => `${entry.method} ${entry.path} ${entry.authScheme} ${entry.status}`,
        ),
      ).toEqual([`GET /${publicRepo.name}/-/whoami Bearer 401`]);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'bun audit asks the registry and reports nothing',
  {
    tag: ['@bun', '@audit'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const lib = packageNameFor(seeder, 'audited');
    const published = await publishPackage(
      bunClient,
      await bunClient.prepare('audit-pub', [writer]),
      {
        packageName: lib,
        version: '1.0.0',
      },
    );
    expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

    // Through the recorder, so the audit request itself is seen. The stack's scanner is off: the
    // report is empty by design, which is all this asserts.
    const recorder = await startWireRecorder();
    try {
      const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
      const consumer = await bunClient.prepare('audit-con', [
        { ...reader, baseUrl: recorder.baseUrl },
      ]);
      await renderConsumer(consumer.work, 'audit-consumer', { [lib]: '1.0.0' });
      const installed = await bunClient.install(consumer, { frozen: false });
      expect(installed.exitCode, `install: ${installed.stderr}`).toBe(0);

      const audited = await bunClient.audit?.(consumer);
      expect(audited?.exitCode, `audit: ${audited?.command}\n${audited?.stderr}`).toBe(0);
      expect(JSON.parse(audited?.stdout ?? 'null'), 'bun prints the bare advisory map').toEqual({});

      const posts = recorder.entries.filter((entry) => entry.method === 'POST');
      expect(
        posts.map((entry) => `${entry.path} ${entry.authScheme} ${entry.status}`),
        'one bulk advisory request, to THIS repository, with the token',
      ).toEqual([`/${repo.name}/-/npm/v1/security/advisories/bulk Bearer 200`]);

      const text = await bunExec(consumer, 'bun-audit-text', ['audit']);
      expect(text.exitCode, `audit (text): ${text.stderr}`).toBe(0);
      expect(`${text.stdout}\n${text.stderr}`).toMatch(/no vulnerabilities/i);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'the tarball download is served inline as f.txt (raw): candidate (NC17)',
  {
    tag: ['@bun', '@wire'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const name = packageNameFor(seeder, 'download');
    const published = await publishPackage(
      bunClient,
      await bunClient.prepare('download', [writer]),
      {
        packageName: name,
        version: '1.0.0',
      },
    );
    expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

    // What `bun add --verbose` shows on every tarball request, and a browser or `curl -OJ` would act
    // on: a fixed, made-up file name in place of `<name>-<version>.tgz`.
    const res = await fetch(`${env.repoBaseUrl}/${repo.name}/${name}/-/${name}-1.0.0.tgz`, {
      headers: npmAuthHeader(adminCredential()),
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toBe('application/octet-stream');
    expect(
      res.headers.get('content-disposition'),
      'candidate (NC17): the file name is f.txt, not the tarball name',
    ).toBe('inline;filename=f.txt');
  },
);
