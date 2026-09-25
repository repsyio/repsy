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
 * H-1 and H-2 (RPS-1330): which pnpm commands run natively and which delegate to `npm`. pnpm 12 (a
 * native binary) implements every registry command of the matrix ITSELF and delegates none: with the
 * wire recorder between it and the registry, every request of `publish`, `dist-tag`, `deprecate`,
 * `undeprecate`, `view`, `whoami`, `ping`, `search`, `unpublish`, `login` and `logout` carries
 * `User-Agent: pnpm/12.x`, never `npm/`, although an `npm` IS on the sealed PATH and could have been
 * spawned. (`audit` is proven the same way in `matrix/registry-endpoints.spec.ts`.) The endpoint each
 * command uses is pinned below too, so a change of pnpm's wire behaviour on a version bump shows here.
 *
 * `login` and `logout` are the two that do not work end to end:
 *  - `pnpm login` first tries npm's web login, `POST /-/v1/login`, which Repsy does not have (404), and
 *    without a terminal it cannot fall back to prompting for a name and password.
 *  - `pnpm logout` asks the registry to revoke the token (`DELETE /-/user/token/<token>`), which
 *    Repsy does not implement (404), and pnpm then FAILS the command (RPS-1361: the token stays
 *    valid, and `pnpm logout` exits 1).
 */
import { createHash } from 'node:crypto';

import type { RunResult } from '../../../src/clients/exec.js';
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { npmAuthHeader, rawGetPackument } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { pnpmClient, runPnpm } from '../../../src/clients/npm-family/pnpm-client.js';
import {
  startWireRecorder,
  type WireEntry,
  type WireRecorder,
} from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

/** `METHOD /path` of an entry, without the repository prefix and the search text. */
function shape(entry: WireEntry, repoName: string): string {
  return `${entry.method} ${entry.path.replace(`/${repoName}`, '').replace(/\?text=.*/, '?text=<term>')}`;
}

/**
 * Follows what a recorder sees: `run` executes one command, checks it succeeded and that pnpm itself
 * sent every request it made (all answered 200), and returns them as `METHOD /path`; `take` returns
 * the entries recorded since the last call (a command whose outcome the caller checks itself).
 */
function wireWatcher(recorder: WireRecorder, repoName: string) {
  let seen = 0;
  const take = (): WireEntry[] => {
    const entries = recorder.entries.slice(seen);
    seen = recorder.entries.length;
    return entries;
  };
  const run = async (step: () => Promise<RunResult> | undefined): Promise<string[]> => {
    const result = await step();
    if (!result) {
      throw new Error('the pnpm client has no such command');
    }
    expect(result.exitCode, `${result.command}\n${result.stderr}`).toBe(0);
    const entries = take();
    expect(entries.length, `${result.command} reached the registry`).toBeGreaterThan(0);
    for (const entry of entries) {
      expect(entry.userAgent, `${entry.method} ${entry.path}`).toMatch(/^pnpm\/12\.\d+\.\d+ /);
      expect(entry.status, `${entry.method} ${entry.path}`).toBe(200);
    }
    return entries.map((entry) => shape(entry, repoName));
  };
  return { run, take };
}

test(
  'every pnpm command talks to the registry itself, none delegates to npm (H-1, H-2)',
  {
    tag: ['@pnpm', '@commands'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const writer = {
        ...(await tokenBinding(seeder, repo.name, { readOnly: false })),
        baseUrl: recorder.baseUrl,
      };
      const ctx = await pnpmClient.prepare('commands', [writer]);
      const name = packageNameFor(seeder, 'native');
      const term = name.replace(/-native$/, '');

      const watch = wireWatcher(recorder, repo.name);
      const onTheWire = watch.run;

      const published = await publishPackage(pnpmClient, ctx, {
        packageName: name,
        version: '1.0.0',
        manifest: { keywords: [term] },
      });
      expect(published.result.exitCode).toBe(0);
      const publishEntries = watch.take();
      expect(publishEntries.map((entry) => shape(entry, repo.name))).toEqual([`PUT /${name}`]);
      expect(publishEntries[0]?.npmCommand, 'publish says so in npm-command').toBe('publish');
      expect(publishEntries[0]?.userAgent).toMatch(/^pnpm\/12\.\d+\.\d+ /);

      expect(await onTheWire(() => pnpmClient.view?.(ctx, name))).toEqual([`GET /${name}`]);
      expect(await onTheWire(() => pnpmClient.whoami?.(ctx))).toEqual(['GET /-/whoami']);
      expect(await onTheWire(() => pnpmClient.ping?.(ctx))).toEqual(['GET /-/ping?write=true']);
      expect(await onTheWire(() => pnpmClient.search?.(ctx, term))).toEqual([
        'GET /-/v1/search?text=<term>',
      ]);

      const tags = `/-/package/${name}/dist-tags`;
      expect(await onTheWire(() => pnpmClient.distTag?.list(ctx, name))).toEqual([`GET ${tags}`]);
      expect(await onTheWire(() => pnpmClient.distTag?.add(ctx, `${name}@1.0.0`, 'next'))).toEqual([
        `PUT ${tags}/next`,
      ]);
      expect(await onTheWire(() => pnpmClient.distTag?.remove(ctx, name, 'next'))).toEqual([
        `GET ${tags}`,
        `DELETE ${tags}/next`,
      ]);

      // deprecate / undeprecate are a read-modify-write of the packument.
      const packumentRoundTrip = [`GET /${name}`, `PUT /${name}`];
      expect(await onTheWire(() => pnpmClient.deprecate?.(ctx, `${name}@1.0.0`, 'nope'))).toEqual(
        packumentRoundTrip,
      );
      expect(await onTheWire(() => pnpmClient.deprecate?.(ctx, `${name}@1.0.0`, ''))).toEqual(
        packumentRoundTrip,
      );
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'pnpm unpublish removes a version (native; a `-rev/undefined` PUT)',
  {
    tag: ['@pnpm', '@commands', '@unpublish'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const writer = {
        ...(await tokenBinding(seeder, repo.name, { readOnly: false })),
        baseUrl: recorder.baseUrl,
      };
      const ctx = await pnpmClient.prepare('unpublish', [writer]);
      const name = packageNameFor(seeder, 'gone');
      for (const version of ['1.0.0', '1.1.0']) {
        const published = await publishPackage(pnpmClient, ctx, { packageName: name, version });
        expect(published.result.exitCode).toBe(0);
      }
      const before = recorder.entries.length;

      const unpublished = await runPnpm(ctx, 'pnpm-unpublish', ['unpublish', `${name}@1.1.0`]);
      expect(unpublished.exitCode, `${unpublished.command}\n${unpublished.stderr}`).toBe(0);

      const entries = recorder.entries.slice(before);
      for (const entry of entries) {
        expect(entry.userAgent).toMatch(/^pnpm\/12\.\d+\.\d+ /);
      }
      // pnpm has no `_rev` to send (a Repsy packument has none), so it sends the literal `undefined`.
      expect(entries.map((entry) => shape(entry, repo.name)).slice(0, 2)).toEqual([
        `GET /${name}`,
        `PUT /${name}/-rev/undefined`,
      ]);

      const packument = await rawGetPackument(repo.name, adminCredential(), name);
      expect(
        Object.keys((JSON.parse(packument.body.toString('utf8')) as { versions: object }).versions),
      ).toEqual(['1.0.0']);
      const tarball = await fetch(`${env.repoBaseUrl}/${repo.name}/${name}/-/${name}-1.1.0.tgz`, {
        headers: npmAuthHeader(adminCredential()),
      });
      expect(tarball.status, 'the removed version cannot be downloaded any more').toBe(404);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'pnpm login needs a web login or a terminal (POST /-/v1/login is 404)',
  {
    tag: ['@pnpm', '@commands', '@login', '@negative'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const ctx = await pnpmClient.prepare('login', [
        {
          ...(await tokenBinding(seeder, repo.name, { readOnly: true })),
          baseUrl: recorder.baseUrl,
        },
      ]);
      const login = await runPnpm(ctx, 'pnpm-login', ['login'], { input: 'user\npassword\n' });
      expect(login.exitCode, 'without a terminal pnpm cannot prompt for credentials').not.toBe(0);
      expect(login.stderr).toContain('ERR_PNPM_LOGIN_NON_INTERACTIVE');

      expect(recorder.entries.map((entry) => shape(entry, repo.name))).toEqual([
        'POST /-/v1/login',
      ]);
      expect(recorder.entries[0]?.status, 'Repsy has no web login').toBe(404);
      expect(recorder.entries[0]?.userAgent).toMatch(/^pnpm\/12\.\d+\.\d+ /);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'pnpm logout fails and the token stays valid: no DELETE /-/user/token/<token> (RPS-1361)',
  {
    tag: ['@pnpm', '@commands', '@logout', '@negative'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const reader = {
        ...(await tokenBinding(seeder, repo.name, { readOnly: true })),
        baseUrl: recorder.baseUrl,
      };
      const ctx = await pnpmClient.prepare('logout', [reader]);

      const logout = await runPnpm(ctx, 'pnpm-logout', ['logout']);
      expect(
        logout.exitCode,
        'RPS-1361: pnpm fails the logout when the revocation is refused',
      ).toBe(1);
      expect(logout.stderr).toContain('ERR_PNPM_LOGOUT_FAILED');
      expect(logout.stdout).toContain('HTTP 404 when revoking token');

      expect(recorder.entries.map((entry) => shape(entry, repo.name))).toEqual([
        `DELETE /-/user/token/${reader.credential.password}`,
      ]);
      expect(
        recorder.entries[0]?.status,
        'RPS-1361: the registry has no token revocation route',
      ).toBe(404);

      const whoami = await pnpmClient.whoami?.(ctx);
      expect(whoami?.exitCode, 'the token was not revoked: it still authenticates').toBe(0);
      expect(whoami?.stdout.trim()).toBe(reader.credential.username);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'pnpm publish of a project directory stores what pnpm pack produces',
  {
    tag: ['@pnpm', '@commands', '@publish'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await pnpmClient.prepare('dir-publish', [writer]);
    const name = packageNameFor(seeder, 'fromdir');

    const dir = `${ctx.work}/project`;
    const { marker } = await renderPackage(dir, { packageName: name, version: '1.0.0' });
    const packed = await pnpmClient.pack(ctx, dir);

    // No tarball argument: pnpm packs the current directory itself and publishes that.
    const published = await runPnpm(
      ctx,
      'pnpm-publish-dir',
      ['publish', '--no-git-checks', '--ignore-scripts', '--reporter=append-only'],
      { cwd: dir },
    );
    expect(published.exitCode, `${published.command}\n${published.stderr}`).toBe(0);

    const res = await rawGetPackument(repo.name, adminCredential(), name);
    const doc = JSON.parse(res.body.toString('utf8')) as {
      versions: Record<string, { dist: { integrity: string; shasum: string } }>;
    };
    expect(
      doc.versions['1.0.0']?.dist.integrity,
      'the stored bytes are the packed bytes (pack is deterministic)',
    ).toBe(`sha512-${createHash('sha512').update(packed.bytes).digest('base64')}`);

    const consumer = await pnpmClient.prepare('dir-publish-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'dir-publish-consumer');
    expect((await pnpmClient.add(consumer, [`${name}@1.0.0`])).exitCode).toBe(0);
    expect(await pnpmClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(marker);
  },
);
