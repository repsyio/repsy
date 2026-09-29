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
 * The install-side half of RPS-1330's dist-tag/semver-range matrix (RPS-1717): what
 * `npm-clients/pnpm/resolution.spec.ts`'s "ranges and tags" cell proved for pnpm alone, generalized
 * via `clientsWith('publish')` to every client that can publish (npm, pnpm, yarn classic, yarn berry,
 * bun -- deno is consume-only and has no publish cell). `dist-tag ls/add/rm` itself is already
 * generalized in `matrix/dist-tags.spec.ts` (RPS-1330/RPS-1362); this file is only what an INSTALL
 * resolves: `pkg@latest`, `pkg@<tag>`, `pkg@^x.y.z`, `pkg@~x.y.z`. Every step first asserts the
 * REGISTRY's own dist-tags through a raw `GET /-/package/<pkg>/dist-tags` with the admin credential
 * (never a client's printout), then what a fresh consumer's install actually lands, by the marker
 * file each published version carries.
 *
 *  - `resolves @latest, a dist-tag and ^/~ ranges`: four versions (1.0.0, 1.0.1, 1.1.0 untagged,
 *    2.0.0 tagged `next`) so `^1.0.0` (newest 1.x) and `~1.0.0` (newest 1.0.x) resolve to two
 *    DIFFERENT versions, not the same one `@latest` would already prove.
 *  - `a version published later as a backport does not move latest`: 1.0.0, then 2.0.0 (latest),
 *    then a 1.0.1 backport tagged `backport` published AFTER 2.0.0 -- latest must stay 2.0.0, and
 *    both the tag and a `^1.0.0` range must still find the backport, not the newer major. (The tag
 *    is deliberately not `v1`: npm 11 refuses a dist-tag name that parses as its own SemVer range
 *    shorthand -- `npm error Tag name must not be a valid SemVer range: v1` -- client-side, before
 *    the request even reaches the registry.)
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPath } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { NpmFamilyClient } from '../../../src/clients/npm-family/client.js';
import type { Seeder } from '../../../src/seed/seeder.js';

async function distTagsOf(repoName: string, packageName: string): Promise<Record<string, string>> {
  const res = await rawGetPath(repoName, adminCredential(), `-/package/${packageName}/dist-tags`);
  expect(res.status, `GET dist-tags of ${packageName}`).toBe(200);
  return JSON.parse(res.body.toString('utf8')) as Record<string, string>;
}

/** A fresh read-only consumer installs `<packageName>@<spec>`; returns the marker it landed, if any. */
async function installs(
  client: NpmFamilyClient,
  seeder: Seeder,
  repoName: string,
  packageName: string,
  spec: string,
  label: string,
): Promise<string | undefined> {
  const consumer = await client.prepare(label, [
    await tokenBinding(seeder, repoName, { readOnly: true }),
  ]);
  await renderConsumer(consumer.work, label);
  const added = await client.add(consumer, [`${packageName}@${spec}`]);
  expect(added.exitCode, `add ${packageName}@${spec}: ${added.command}\n${added.stderr}`).toBe(0);
  return client.readInstalledFile(consumer, packageName, MARKER_FILENAME);
}

for (const client of clientsWith('publish')) {
  test(
    `${client.label} resolves @latest, a dist-tag and ^/~ ranges`,
    { tag: [client.tag, '@resolution'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('resolution-pub', [writer]);
      const name = packageNameFor(seeder, 'resolved');

      const v100 = await publishPackage(client, ctx, { packageName: name, version: '1.0.0' });
      expect(v100.result.exitCode, `publish 1.0.0: ${v100.result.command}`).toBe(0);
      const v101 = await publishPackage(client, ctx, { packageName: name, version: '1.0.1' });
      expect(v101.result.exitCode, `publish 1.0.1: ${v101.result.command}`).toBe(0);
      const v110 = await publishPackage(client, ctx, { packageName: name, version: '1.1.0' });
      expect(v110.result.exitCode, `publish 1.1.0: ${v110.result.command}`).toBe(0);
      const v200 = await publishPackage(
        client,
        ctx,
        { packageName: name, version: '2.0.0' },
        { tag: 'next' },
      );
      expect(v200.result.exitCode, `publish 2.0.0 --tag next: ${v200.result.command}`).toBe(0);

      expect(
        await distTagsOf(repo.name, name),
        'latest tracks the last untagged publish; next is its own tag',
      ).toEqual({ latest: '1.1.0', next: '2.0.0' });

      expect(
        await installs(client, seeder, repo.name, name, 'latest', `${client.id}-res-latest`),
        '@latest resolves what the tag names',
      ).toBe(v110.marker);
      expect(
        await installs(client, seeder, repo.name, name, 'next', `${client.id}-res-next`),
        '@next resolves the tagged prerelease',
      ).toBe(v200.marker);
      expect(
        await installs(client, seeder, repo.name, name, '^1.0.0', `${client.id}-res-caret`),
        '^1.0.0 resolves the newest 1.x',
      ).toBe(v110.marker);
      expect(
        await installs(client, seeder, repo.name, name, '~1.0.0', `${client.id}-res-tilde`),
        '~1.0.0 resolves the newest 1.0.x, not 1.1.0',
      ).toBe(v101.marker);
    },
  );
}

for (const client of clientsWith('publish')) {
  test(
    `${client.label}: a version published later as a backport does not move latest, and a range still finds it`,
    { tag: [client.tag, '@resolution'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('resolution-bp', [writer]);
      const name = packageNameFor(seeder, 'backport');

      const v100 = await publishPackage(client, ctx, { packageName: name, version: '1.0.0' });
      expect(v100.result.exitCode, `publish 1.0.0: ${v100.result.command}`).toBe(0);
      const v200 = await publishPackage(client, ctx, { packageName: name, version: '2.0.0' });
      expect(v200.result.exitCode, `publish 2.0.0: ${v200.result.command}`).toBe(0);
      // A backport of the 1.x line, published AFTER 2.0.0 was already latest, tagged `backport`
      // rather than left untagged (not `v1`: see the header).
      const v101 = await publishPackage(
        client,
        ctx,
        { packageName: name, version: '1.0.1' },
        { tag: 'backport' },
      );
      expect(v101.result.exitCode, `publish 1.0.1 --tag backport: ${v101.result.command}`).toBe(0);

      expect(
        await distTagsOf(repo.name, name),
        'the backport, published later, does not move latest',
      ).toEqual({ latest: '2.0.0', backport: '1.0.1' });

      expect(
        await installs(client, seeder, repo.name, name, 'latest', `${client.id}-bp-latest`),
        '@latest is still the 2.0.0 major',
      ).toBe(v200.marker);
      expect(
        await installs(client, seeder, repo.name, name, 'backport', `${client.id}-bp-tag`),
        '@backport resolves the backport',
      ).toBe(v101.marker);
      expect(
        await installs(client, seeder, repo.name, name, '^1.0.0', `${client.id}-bp-caret`),
        '^1.0.0 finds the backport, not the newer major',
      ).toBe(v101.marker);
    },
  );
}
