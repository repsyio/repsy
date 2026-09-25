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
 * What pnpm's own resolution does with the metadata Repsy serves (RPS-1330), beyond `pnpm add
 * <pkg>@<exact version>` of the catalog:
 *
 *  - ranges and dist-tags: `^1.0.0` resolves to the newest matching version, `@next` to what the tag
 *    names, `pnpm update` moves a locked dependency to a newer version;
 *  - deprecation: pnpm 12 resolves a range to the newest version that is NOT deprecated, reading the
 *    `deprecated` field of the abbreviated packument, so Repsy's deprecation reaches resolution and
 *    not only a warning; an exact version is still installed;
 *  - `minimumReleaseAge` (pnpm 11+: 1 day by default), which needs each version's publish time: the
 *    abbreviated packument has no `time` (nor does the public registry's), so pnpm asks for the full
 *    one too. A package published seconds ago is inside a 5-minute window, so a STRICT policy refuses
 *    it and names the publish time the registry served (a time in another zone, or in the past, would
 *    not); the default, non-strict policy installs it and records it in `minimumReleaseAgeExclude` of
 *    `pnpm-workspace.yaml`.
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
import { pnpmClient, runPnpm } from '../../../src/clients/npm-family/pnpm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

/** Output without colour codes (pnpm colours part of a wrapped message) and without line breaks. */
function plain(output: string): string {
  // eslint-disable-next-line no-control-regex
  return output.replace(/\u001b\[[0-9;]*m/g, '').replace(/\s+/g, '');
}

interface Published {
  repoName: string;
  name: string;
  /** The marker each published version carries. */
  markers: Record<string, string>;
  /** Publishes one more version (a version with a prerelease part goes under the tag `next`). */
  publish(version: string): Promise<void>;
}

/** A fresh private repository with one package, published at `versions`. */
async function publishVersions(seeder: Seeder, versions: string[]): Promise<Published> {
  const repo = await newRepo(seeder);
  const ctx = await pnpmClient.prepare('resolution-pub', [
    await tokenBinding(seeder, repo.name, { readOnly: false }),
  ]);
  const name = packageNameFor(seeder, 'resolved');
  const markers: Record<string, string> = {};
  const publish = async (version: string): Promise<void> => {
    const published = await publishPackage(
      pnpmClient,
      ctx,
      { packageName: name, version },
      version.includes('-') ? { tag: 'next' } : {},
    );
    expect(published.result.exitCode, `publish ${version}: ${published.result.command}`).toBe(0);
    markers[version] = published.marker;
  };
  for (const version of versions) {
    await publish(version);
  }
  return { repoName: repo.name, name, markers, publish };
}

/** A consumer project of `repoName` that has run `pnpm add <name>@<spec>`. */
async function consumerOf(seeder: Seeder, published: Published, label: string, spec: string) {
  const ctx = await pnpmClient.prepare(label, [
    await tokenBinding(seeder, published.repoName, { readOnly: true }),
  ]);
  await renderConsumer(ctx.work, label);
  const added = await pnpmClient.add(ctx, [`${published.name}@${spec}`]);
  expect(added.exitCode, `add ${spec}: ${added.command}\n${added.stderr}`).toBe(0);
  return {
    ctx,
    added,
    marker: () => pnpmClient.readInstalledFile(ctx, published.name, MARKER_FILENAME),
  };
}

test(
  'pnpm resolves ranges and tags, and update moves a locked dependency',
  {
    tag: ['@pnpm', '@resolution'],
  },
  async ({ seeder }) => {
    const published = await publishVersions(seeder, ['1.0.0', '1.1.0', '2.0.0-next.1']);

    const ranged = await consumerOf(seeder, published, 'range', '^1.0.0');
    expect(await ranged.marker(), '^1.0.0 is the newest 1.x').toBe(published.markers['1.1.0']);
    const tagged = await consumerOf(seeder, published, 'tag', 'next');
    expect(await tagged.marker(), '@next is what the tag names').toBe(
      published.markers['2.0.0-next.1'],
    );

    await published.publish('1.2.0');
    const updated = await runPnpm(ranged.ctx, 'pnpm-update', [
      'update',
      published.name,
      '--ignore-scripts',
      '--reporter=append-only',
      '--no-progress',
    ]);
    expect(updated.exitCode, `${updated.command}\n${updated.stderr}`).toBe(0);
    expect(await ranged.marker(), 'update installs the newer version').toBe(
      published.markers['1.2.0'],
    );
  },
);

test(
  'pnpm skips a deprecated version when resolving a range (candidate NC5: deprecated "" after undeprecate)',
  {
    tag: ['@pnpm', '@resolution', '@deprecate'],
  },
  async ({ seeder }) => {
    const published = await publishVersions(seeder, ['1.0.0', '1.1.0', '1.2.0']);
    const writer = await pnpmClient.prepare('resolution-dep', [
      await tokenBinding(seeder, published.repoName, { readOnly: false }),
    ]);

    const deprecated = await pnpmClient.deprecate?.(writer, `${published.name}@1.2.0`, 'broken');
    expect(deprecated?.exitCode, `deprecate: ${deprecated?.stderr}`).toBe(0);
    expect(
      await (await consumerOf(seeder, published, 'skips', '^1.0.0')).marker(),
      'a range skips the deprecated 1.2.0 for 1.1.0',
    ).toBe(published.markers['1.1.0']);
    expect(
      await (await consumerOf(seeder, published, 'exact', '1.2.0')).marker(),
      'an exact deprecated version is still installed',
    ).toBe(published.markers['1.2.0']);

    const undeprecated = await pnpmClient.deprecate?.(writer, `${published.name}@1.2.0`, '');
    expect(undeprecated?.exitCode, `undeprecate: ${undeprecated?.stderr}`).toBe(0);

    // Candidate NC5: undeprecating leaves `"deprecated": ""` in the served packument (the public
    // registry drops the field), and pnpm treats a version that HAS the field, empty or not, as
    // deprecated: the range still skips 1.2.0. When the backend drops the field, this reads 1.2.0.
    const served = JSON.parse(
      (
        await rawGetPackument(published.repoName, adminCredential(), published.name, true)
      ).body.toString('utf8'),
    ) as { versions: Record<string, { deprecated?: string }> };
    expect(served.versions['1.2.0']?.deprecated, 'candidate NC5: the empty field is served').toBe(
      '',
    );
    expect(
      await (await consumerOf(seeder, published, 'undeprecated', '^1.0.0')).marker(),
      'candidate NC5: pnpm still skips the undeprecated 1.2.0',
    ).toBe(published.markers['1.1.0']);
  },
);

test(
  'pnpm minimumReleaseAge reads the publish time the registry serves',
  {
    tag: ['@pnpm', '@resolution', '@release-age'],
  },
  async ({ seeder }) => {
    const { repoName, name } = await publishVersions(seeder, ['1.0.0']);
    const served = JSON.parse(
      (await rawGetPackument(repoName, adminCredential(), name)).body.toString('utf8'),
    ) as { time: Record<string, string> };
    const publishedAt = served.time['1.0.0'];
    expect(publishedAt).toBeTruthy();

    const recorder = await startWireRecorder();
    try {
      const reader = {
        ...(await tokenBinding(seeder, repoName, { readOnly: true })),
        baseUrl: recorder.baseUrl,
      };
      const base = await pnpmClient.prepare('release-age', [reader]);
      const withAge = (env: NodeJS.ProcessEnv) => ({ ...base, env: { ...base.env, ...env } });

      // Strict, 5 minutes: the package is seconds old.
      const strict = withAge({
        PNPM_CONFIG_MINIMUM_RELEASE_AGE: '5',
        PNPM_CONFIG_MINIMUM_RELEASE_AGE_STRICT: 'true',
      });
      await renderConsumer(strict.work, 'release-age-strict');
      const refused = await pnpmClient.add(strict, [`${name}@1.0.0`]);
      expect(refused.exitCode, 'a strict policy refuses a package published seconds ago').not.toBe(
        0,
      );
      expect(
        plain(`${refused.stdout}${refused.stderr}`),
        'the message names the publish time the registry served (pnpm wraps its lines)',
      ).toContain(publishedAt ?? '');
      expect(
        await pnpmClient.readInstalledFile(strict, name, MARKER_FILENAME),
        'nothing was installed',
      ).toBeUndefined();

      const accepts = recorder.entries
        .filter((entry) => entry.method === 'GET' && entry.path === `/${repoName}/${name}`)
        .map((entry) => entry.accept);
      expect(
        accepts,
        'the abbreviated packument has no time, so pnpm asks for the full one',
      ).toEqual([
        expect.stringMatching(/^application\/vnd\.npm\.install-v1\+json/),
        'application/json; q=1.0, */*',
      ]);

      // Default, non-strict: installs, and records the package as excluded from the policy.
      const lax = await pnpmClient.prepare('release-age-lax', [
        await tokenBinding(seeder, repoName, { readOnly: true }),
      ]);
      delete lax.env.PNPM_CONFIG_MINIMUM_RELEASE_AGE;
      await renderConsumer(lax.work, 'release-age-lax');
      const added = await pnpmClient.add(lax, [`${name}@1.0.0`]);
      expect(added.exitCode, `${added.command}\n${added.stderr}`).toBe(0);
      expect(await pnpmClient.readInstalledFile(lax, name, MARKER_FILENAME)).toBeDefined();
      expect(await fs.readFile(path.join(lax.work, 'pnpm-workspace.yaml'), 'utf8')).toContain(
        `minimumReleaseAgeExclude:\n  - ${name}@1.0.0`,
      );
    } finally {
      await recorder.stop();
    }
  },
);
