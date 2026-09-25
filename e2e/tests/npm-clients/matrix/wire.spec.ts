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
 * Matrix row 19 (RPS-1330): what a client puts on the wire and what the registry answers, recorded by
 * `wire-recorder.ts` between a consumer and the registry. It pins the facts the other cells rest on,
 * per client with `frozenInstall`: which `Accept` the packument is asked for, which `Authorization`
 * scheme goes with each credential kind (a deploy token as `_authToken` is `Bearer`, a password as
 * `_auth` is `Basic`), the client's own identification, and that its tarball fetch carries the
 * credential too.
 *
 * candidate (NC4): the packument answers with no `ETag`, no `Last-Modified` and no `Vary: Accept`
 * (the abbreviated and the full document share one URL), so a client cannot revalidate its metadata
 * cache with a conditional request and a shared cache cannot tell the two documents apart; and no
 * compression. Observed live and asserted as it is.
 *
 * candidate (NC3): npm `HEAD` answers 200 for ANY path of an existing repository, including a
 * package that does not exist (the same class as the PyPI and Ruby HEAD findings); a `GET` of the
 * same path is 404.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { npmAuthHeader } from '../../../src/clients/npm-raw.js';
import type { ClientId } from '../../../src/clients/npm-family/client.js';
import {
  adminBinding,
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

/**
 * What each client sends with a packument GET: its `Accept`, `User-Agent` and `npm-command`
 * (`undefined`: none is sent). Probed, not remembered: npm 11.19 asks for the FULL packument
 * (`Accept: application/json`) on an install, not the abbreviated install document the plan
 * expected, so candidate NC1 (the abbreviated document's missing fields) is not what npm reads.
 */
const IDENTITY: Partial<
  Record<ClientId, { accept: RegExp; userAgent: RegExp; npmCommand?: string }>
> = {
  npm: { accept: /^application\/json$/, userAgent: /^npm\/11\.\d+\.\d+ /, npmCommand: 'install' },
};

/** The `Authorization` scheme each credential kind is sent with (`_authToken` vs `_auth`). */
const SCHEME = { token: 'Bearer', password: 'Basic' } as const;

/** A consumer binding of each credential kind for `repoName`, through the recorder at `baseUrl`. */
const BINDING_OF = {
  token: async (seeder: Seeder, repoName: string, baseUrl: string) => ({
    ...(await tokenBinding(seeder, repoName, { readOnly: true })),
    baseUrl,
  }),
  password: async (_seeder: Seeder, repoName: string, baseUrl: string) =>
    adminBinding(repoName, { baseUrl }),
};

for (const client of clientsWith('frozenInstall')) {
  for (const kind of ['token', 'password'] as const) {
    test(
      `${client.label} reads the packument and tarball with a ${kind} credential`,
      {
        tag: [client.tag, '@wire'],
      },
      async ({ seeder }) => {
        const identity = IDENTITY[client.id];
        expect(identity, `no identity is pinned for ${client.id}`).toBeDefined();

        const repo = await newRepo(seeder);
        const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
        const name = packageNameFor(seeder, 'traced');
        const published = await publishPackage(client, await client.prepare('wire-pub', [writer]), {
          packageName: name,
          version: '1.0.0',
        });
        expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

        const recorder = await startWireRecorder({ rewriteTarballUrls: true });
        try {
          const binding = await BINDING_OF[kind](seeder, repo.name, recorder.baseUrl);
          const consumer = await client.prepare('wire-con', [binding]);
          await renderConsumer(consumer.work, 'wire-consumer');
          const added = await client.add(consumer, [`${name}@1.0.0`]);
          expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
          expect(await client.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
            published.marker,
          );

          const scheme = SCHEME[kind];
          const packumentGets = recorder.entries.filter(
            (entry) => entry.method === 'GET' && entry.path === `/${repo.name}/${name}`,
          );
          const tarballGets = recorder.entries.filter(
            (entry) =>
              entry.method === 'GET' && entry.path === `/${repo.name}/${name}/-/${name}-1.0.0.tgz`,
          );
          expect(packumentGets, 'one packument GET').toHaveLength(1);
          expect(tarballGets, 'one tarball GET, through the recorder').toHaveLength(1);

          const [packument] = packumentGets;
          expect(packument?.accept, 'the media type the client asks the packument with').toMatch(
            identity?.accept as RegExp,
          );
          expect(packument?.userAgent).toMatch(identity?.userAgent as RegExp);
          expect(packument?.npmCommand).toBe(identity?.npmCommand);
          expect(packument?.authScheme, 'the packument GET carries the credential').toBe(scheme);
          expect(tarballGets[0]?.authScheme, 'and so does the tarball GET').toBe(scheme);
          expect(packument?.ifNoneMatch, 'a first fetch is unconditional').toBeUndefined();
          expect(packument?.status).toBe(200);

          // candidate (NC4): nothing to revalidate with, nothing to key a cache on.
          expect(packument?.responseEtag, 'candidate (NC4): no ETag').toBeUndefined();
          expect(
            packument?.responseLastModified,
            'candidate (NC4): no Last-Modified',
          ).toBeUndefined();
          expect(packument?.responseVary ?? '', 'candidate (NC4): no Vary: Accept').not.toMatch(
            /accept\b/i,
          );
          expect(
            packument?.responseContentEncoding,
            'candidate (NC4): not compressed',
          ).toBeUndefined();
        } finally {
          await recorder.stop();
        }
      },
    );
  }
}

test(
  'HEAD answers 200 for a package that does not exist (raw)',
  {
    tag: ['@npm', '@wire'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const missing = `${env.repoBaseUrl}/${repo.name}/${packageNameFor(seeder, 'never-published')}`;
    const headers = npmAuthHeader(adminCredential());

    const get = await fetch(missing, { headers });
    expect(get.status, 'GET of a package that does not exist').toBe(404);

    const head = await fetch(missing, { method: 'HEAD', headers });
    expect(head.status, 'candidate (NC3): HEAD of the same path is 200').toBe(200);

    // Control: a repository that does not exist is 404 for HEAD as well, so the answer is per
    // repository and never per package.
    const noRepo = await fetch(`${env.repoBaseUrl}/e2e-${seeder.runId}-norepo/x`, {
      method: 'HEAD',
      headers,
    });
    expect(noRepo.status, 'HEAD in a repository that does not exist').toBe(404);
  },
);
