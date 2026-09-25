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
 * Matrix row 6 (RPS-1330): dist-tags, per client with a dist-tag command (`distTagCmd`). Every step
 * asserts the REGISTRY's dist-tags through a raw `GET /-/package/<pkg>/dist-tags` with the admin
 * credential, not the client's own printout, and what a tagged install then resolves:
 *  - a first publish under `--tag beta` sets `beta` AND `latest` (matches the public registry);
 *  - a later publish with no tag moves `latest` and leaves `beta` where it was;
 *  - `dist-tag add` moves a tag, `dist-tag rm` removes it, and `pkg@beta` installs what it names;
 *  - removing `latest` is refused (400 `canNotRemoveTagLatest`), so is tagging a version that does
 *    not exist.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPath } from '../../../src/clients/npm-raw.js';
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

/**
 * The exit code of a client's `dist-tag add` (0 unless listed). Yarn 1's `tag add` exits 1
 * ("Couldn't add tag") although the registry applied the tag: it only accepts an answer with an `ok`
 * field in its body, and `PUT /-/package/<pkg>/dist-tags/<tag>` answers 200 with an empty body
 * (candidate (NC9)). The tag IS set, which the step below asserts on the registry either way.
 */
const TAG_ADD_EXIT: Partial<Record<ClientId, number>> = { 'yarn-classic': 1 };

async function distTagsOf(repoName: string, packageName: string): Promise<Record<string, string>> {
  const res = await rawGetPath(repoName, adminCredential(), `-/package/${packageName}/dist-tags`);
  expect(res.status, `GET dist-tags of ${packageName}`).toBe(200);
  return JSON.parse(res.body.toString('utf8')) as Record<string, string>;
}

for (const client of clientsWith('distTagCmd')) {
  test(
    `${client.label} publishes under a tag, moves and removes tags`,
    {
      tag: [client.tag, '@dist-tags'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('dist-tags', [writer]);
      const distTag = client.distTag as NonNullable<typeof client.distTag>;
      const name = packageNameFor(seeder, 'tagged');

      const first = await publishPackage(
        client,
        ctx,
        { packageName: name, version: '1.0.0' },
        { tag: 'beta' },
      );
      expect(first.result.exitCode, `publish 1.0.0 --tag beta: ${first.result.command}`).toBe(0);
      expect(
        await distTagsOf(repo.name, name),
        'a first publish under a tag also sets latest',
      ).toEqual({
        beta: '1.0.0',
        latest: '1.0.0',
      });

      const second = await publishPackage(client, ctx, { packageName: name, version: '1.1.0' });
      expect(second.result.exitCode, `publish 1.1.0: ${second.result.command}`).toBe(0);
      expect(await distTagsOf(repo.name, name), 'an untagged publish moves latest only').toEqual({
        beta: '1.0.0',
        latest: '1.1.0',
      });

      const listed = await distTag.list(ctx, name);
      expect(listed.exitCode, `dist-tag ls: ${listed.command}\n${listed.stderr}`).toBe(0);
      expect(listed.stdout).toContain('1.0.0');
      expect(listed.stdout).toContain('1.1.0');

      const moved = await distTag.add(ctx, `${name}@1.1.0`, 'beta');
      expect(
        moved.exitCode,
        `dist-tag add: ${moved.command}\n${moved.stderr} (candidate (NC9) for a non-zero exit)`,
      ).toBe(TAG_ADD_EXIT[client.id] ?? 0);
      expect(await distTagsOf(repo.name, name)).toEqual({ beta: '1.1.0', latest: '1.1.0' });

      // A consumer installing `<pkg>@beta` gets what the tag names now.
      const consumer = await client.prepare('dist-tags-con', [
        await tokenBinding(seeder, repo.name, { readOnly: true }),
      ]);
      await renderConsumer(consumer.work, 'dist-tags-consumer');
      const added = await client.add(consumer, [`${name}@beta`]);
      expect(added.exitCode, `add ${name}@beta: ${added.command}\n${added.stderr}`).toBe(0);
      expect(await client.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(second.marker);

      const removed = await distTag.remove(ctx, name, 'beta');
      expect(removed.exitCode, `dist-tag rm: ${removed.command}\n${removed.stderr}`).toBe(0);
      expect(await distTagsOf(repo.name, name)).toEqual({ latest: '1.1.0' });

      const removedLatest = await distTag.remove(ctx, name, 'latest');
      expect(
        removedLatest.exitCode,
        'removing latest is refused (400 canNotRemoveTagLatest)',
      ).not.toBe(0);
      expect(await distTagsOf(repo.name, name), 'latest is still there').toEqual({
        latest: '1.1.0',
      });

      const ghost = await distTag.add(ctx, `${name}@9.9.9`, 'ghost');
      expect(ghost.exitCode, 'tagging a version that does not exist is refused').not.toBe(0);
      expect(await distTagsOf(repo.name, name), 'no tag for a missing version').toEqual({
        latest: '1.1.0',
      });
    },
  );
}
