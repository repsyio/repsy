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
 * Matrix row 18 (RPS-1330): what the ABBREVIATED packument (`Accept:
 * application/vnd.npm.install-v1+json`, the form a client that resolves from the abbreviated
 * document asks for; npm 11.19 asks for the full packument instead, see `wire.spec.ts`) carries
 * compared to the full one, and what each client does with a platform-restricted optional
 * dependency.
 *
 * RPS-1356: the abbreviated document is built by `AbstractNpmStorageService.
 * createAbbreviatedMetadata` from a fixed field list that lacks `os`, `cpu`, `libc`,
 * `peerDependenciesMeta` and `funding` (and `hasInstallScript`), all of which the npm
 * abbreviated-metadata format carries and clients read from it. A client that trusts the
 * abbreviated document alone then cannot skip an `os`/`cpu`-mismatched optional dependency, or
 * tell an optional peer from a required one. The full document has every one of them (the control),
 * so the data is stored; only the abbreviated view drops it. Asserted as observed, so a fix is one
 * flipped block.
 *
 * What a client does about it varies: npm, probed here, still skips the mismatched optional
 * dependency (it reads the platform from the tarball's manifest once it has fetched it), so nothing
 * user-visible breaks for npm. The per-client expectation table below is where a later client that
 * DOES install the wrong-platform package (or not) is pinned.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import type { ClientId } from '../../../src/clients/npm-family/client.js';
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

const PLATFORM_FIELDS = ['os', 'cpu', 'libc', 'peerDependenciesMeta', 'funding'] as const;

/** Whether a client installs an optional dependency whose `os` excludes linux (`win32` here). */
const INSTALLS_WRONG_PLATFORM_OPTIONAL: Partial<Record<ClientId, boolean>> = {
  npm: false,
  pnpm: false,
  // Yarn 1 asks for the abbreviated document too (`Accept: application/vnd.npm.install-v1+json`, see
  // wire.spec.ts), and still skips the mismatched optional dependency: probed, not assumed.
  'yarn-classic': false,
};

for (const client of clientsWith('publish')) {
  test(
    `${client.label} publishes platform fields the abbreviated packument then drops`,
    {
      tag: [client.tag, '@abbreviated'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('abbreviated', [writer]);
      const platform = packageNameFor(seeder, 'winonly');
      const peer = packageNameFor(seeder, 'peer');

      const peerPublished = await publishPackage(client, ctx, {
        packageName: peer,
        version: '1.0.0',
      });
      expect(peerPublished.result.exitCode, `publish peer: ${peerPublished.result.command}`).toBe(
        0,
      );
      const platformPublished = await publishPackage(client, ctx, {
        packageName: platform,
        version: '1.0.0',
        manifest: {
          os: ['win32'],
          cpu: ['arm64'],
          libc: ['glibc'],
          funding: 'https://funding.example/e2e',
          peerDependencies: { [peer]: '*' },
          peerDependenciesMeta: { [peer]: { optional: true } },
        },
      });
      expect(
        platformPublished.result.exitCode,
        `publish: ${platformPublished.result.command}`,
      ).toBe(0);

      const version = async (abbreviated: boolean): Promise<Record<string, unknown>> => {
        const res = await rawGetPackument(repo.name, adminCredential(), platform, abbreviated);
        expect(res.status).toBe(200);
        const doc = JSON.parse(res.body.toString('utf8')) as {
          versions: Record<string, Record<string, unknown>>;
        };
        return doc.versions['1.0.0'] ?? {};
      };

      const full = await version(false);
      expect(full.os, 'the full packument has os').toEqual(['win32']);
      expect(full.cpu).toEqual(['arm64']);
      expect(full.libc).toEqual(['glibc']);
      expect(full.peerDependenciesMeta).toEqual({ [peer]: { optional: true } });
      expect(full.funding).toBe('https://funding.example/e2e');

      const abbreviated = await version(true);
      expect(
        abbreviated.peerDependencies,
        'control: peerDependencies IS in the abbreviated one',
      ).toEqual({
        [peer]: '*',
      });
      for (const field of PLATFORM_FIELDS) {
        expect(
          abbreviated[field],
          `RPS-1356: the abbreviated packument drops "${field}"`,
        ).toBeUndefined();
      }
    },
  );
}

for (const client of clientsWith('frozenInstall')) {
  test(
    `${client.label} handles an optional dependency for another platform`,
    {
      tag: [client.tag, '@abbreviated'],
    },
    async ({ seeder }) => {
      const expected = INSTALLS_WRONG_PLATFORM_OPTIONAL[client.id];
      expect(expected, `no expectation is pinned for ${client.id}`).toBeDefined();

      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('optional-pub', [writer]);
      const platform = packageNameFor(seeder, 'winonly');
      const app = packageNameFor(seeder, 'app');

      const platformPublished = await publishPackage(client, ctx, {
        packageName: platform,
        version: '1.0.0',
        manifest: { os: ['win32'], cpu: ['arm64'] },
      });
      const appPublished = await publishPackage(client, ctx, {
        packageName: app,
        version: '1.0.0',
        optionalDependencies: { [platform]: '1.0.0' },
      });
      expect(platformPublished.result.exitCode).toBe(0);
      expect(appPublished.result.exitCode).toBe(0);

      const consumer = await client.prepare('optional-con', [
        await tokenBinding(seeder, repo.name, { readOnly: true }),
      ]);
      await renderConsumer(consumer.work, 'optional-consumer');
      const added = await client.add(consumer, [`${app}@1.0.0`]);
      expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);

      expect(await client.readInstalledFile(consumer, app, MARKER_FILENAME)).toBe(
        appPublished.marker,
      );
      const installedPlatform = await client.readInstalledFile(consumer, platform, MARKER_FILENAME);
      expect(
        installedPlatform !== undefined,
        `${client.label} ${expected ? 'installs' : 'skips'} an os=win32 optional dependency on linux`,
      ).toBe(expected);
    },
  );
}
