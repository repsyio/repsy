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
 * Matrix row 17 (RPS-1330, RPS-1333 fixed): the address a package was PUBLISHED through is not the
 * address it is CONSUMED through. The publisher reaches the registry as `127.0.0.1` (npm builds
 * `dist.tarball` from the registry URL it was given and sends it in the publish), the consumer as
 * `localhost`, on a PRIVATE repository, where a client sends its credential only to the host it was
 * configured for. Before RPS-1333 the registry stored and served the publisher's `dist.tarball`
 * verbatim, so the consumer was pointed at `127.0.0.1` and its tarball fetch had no credential (401);
 * now every packument read names the registry's own address (`REPO_BASE_URL`), whatever the
 * publisher sent and whatever address the read came in by, so the install succeeds and the lockfile
 * records the registry address.
 *
 * `@local-only`: it needs two names for the one registry, which a remote target does not have.
 */
import path from 'node:path';
import fs from 'node:fs/promises';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { npmAuthHeader, parsePackument } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { target } from '../../../src/target.js';
import type { ClientId } from '../../../src/clients/npm-family/client.js';

/**
 * Clients whose lockfile records no tarball URL for a conventional one, only integrity (pnpm), or that
 * pin one (`__archiveUrl`) only when the packument's differs from the URL they build themselves (berry).
 */
const LOCKFILE_HAS_NO_TARBALL_URL: ReadonlySet<ClientId> = new Set<ClientId>([
  'pnpm',
  'yarn-berry',
]);

/** The other name of the local registry: `127.0.0.1` for `localhost`, and the reverse. */
function alternateBase(base: string): string {
  const url = new URL(base);
  url.hostname = url.hostname === 'localhost' ? '127.0.0.1' : 'localhost';
  return url.origin;
}

// `@local-only`: registered on a local target only, like the loop's skip of a `@local-only` scenario
// on a remote one -- without a skipped row in the report of a remote run.
for (const client of target.isRemote ? [] : clientsWith('frozenInstall')) {
  test(
    `${client.label} installs a package published through another host name`,
    {
      tag: [client.tag, '@tarball-host', '@local-only'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const publishBase = alternateBase(env.repoBaseUrl);
      expect(publishBase, 'a different host name than the consumer uses').not.toBe(env.repoBaseUrl);
      const name = packageNameFor(seeder, 'twohosts');

      const writer = await tokenBinding(seeder, repo.name, {
        readOnly: false,
        baseUrl: publishBase,
      });
      const published = await publishPackage(
        client,
        await client.prepare('twohosts-pub', [writer]),
        { packageName: name, version: '1.0.0' },
      );
      expect(
        published.result.exitCode,
        `publish via ${publishBase}: ${published.result.command}`,
      ).toBe(0);

      const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
      const registryBase = `${env.repoBaseUrl}/${repo.name}/`;
      const tarballUrl = `${registryBase}${name}/-/${name}-1.0.0.tgz`;

      // Whichever name a read comes in by, the packument names the registry's configured address.
      for (const base of [env.repoBaseUrl, publishBase]) {
        const res = await fetch(`${base}/${repo.name}/${name}`, {
          headers: npmAuthHeader(reader.credential),
        });
        expect(res.status, `GET packument via ${base}`).toBe(200);
        expect(
          parsePackument(Buffer.from(await res.arrayBuffer())).versions['1.0.0']?.dist?.tarball,
          `dist.tarball as read via ${base}`,
        ).toBe(tarballUrl);
      }

      const consumer = await client.prepare('twohosts-con', [reader]);
      await renderConsumer(consumer.work, 'twohosts-consumer', { [name]: '1.0.0' });
      const installed = await client.install(consumer, { frozen: false });
      expect(
        installed.exitCode,
        `install via ${env.repoBaseUrl}: ${installed.command}\n${installed.stderr}`,
      ).toBe(0);
      expect(await client.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
        published.marker,
      );

      const lockfile = await fs.readFile(path.join(consumer.work, client.lockfile ?? ''), 'utf8');
      expect(
        LOCKFILE_HAS_NO_TARBALL_URL.has(client.id) || lockfile.includes(tarballUrl),
        'the lockfile records the registry address',
      ).toBe(true);
      expect(lockfile, "and nothing of the publisher's address").not.toContain(publishBase);
    },
  );
}
