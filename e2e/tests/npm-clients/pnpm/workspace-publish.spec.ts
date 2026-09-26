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
 * Matrix row 11 for pnpm (RPS-1330): `pnpm -r publish` of a workspace whose members depend on each
 * other with the `workspace:` protocol. pnpm rewrites each range in the manifest it publishes
 * (`workspace:^` -> `^1.2.3`, `workspace:~` -> `~1.2.3`, `workspace:*` -> `1.2.3`), so what Repsy
 * stores and serves must carry no `workspace:` at all, and a consumer resolves the graph from the
 * registry alone. Members are named so that alphabetical order is the reverse of dependency order
 * (`a-app` -> `m-lib` -> `z-base`): the PUTs recorded on the wire must come in dependency order
 * (base, lib, app), not in name or directory order. A second `-r publish` finds every member already in
 * the registry (`GET` of each packument) and publishes nothing.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { pnpmClient, publishWorkspace } from '../../../src/clients/npm-family/pnpm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { repoPath } from '../../../src/repo-url.js';

const VERSION = '1.2.3';

test(
  'pnpm -r publish rewrites workspace: ranges and publishes dependencies first',
  {
    tag: ['@pnpm', '@workspaces'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const writer = {
        ...(await tokenBinding(seeder, repo.name, { readOnly: false })),
        baseUrl: recorder.baseUrl,
      };
      const ctx = await pnpmClient.prepare('workspace-pub', [writer]);

      const app = packageNameFor(seeder, 'a-app');
      const lib = packageNameFor(seeder, 'm-lib');
      const base = packageNameFor(seeder, 'z-base');

      const root = path.join(ctx.work, 'ws');
      await fs.mkdir(root, { recursive: true });
      await fs.writeFile(path.join(root, 'pnpm-workspace.yaml'), "packages:\n  - 'packages/*'\n");
      await fs.writeFile(
        path.join(root, 'package.json'),
        `${JSON.stringify({ name: 'ws-root', version: '0.0.0', private: true })}\n`,
      );
      const markers = {
        [app]: (
          await renderPackage(path.join(root, 'packages/app'), {
            packageName: app,
            version: VERSION,
            dependencies: { [lib]: 'workspace:^', [base]: 'workspace:~' },
          })
        ).marker,
        [lib]: (
          await renderPackage(path.join(root, 'packages/lib'), {
            packageName: lib,
            version: VERSION,
            dependencies: { [base]: 'workspace:*' },
          })
        ).marker,
        [base]: (
          await renderPackage(path.join(root, 'packages/base'), {
            packageName: base,
            version: VERSION,
          })
        ).marker,
      };

      const published = await publishWorkspace(ctx, root);
      expect(
        published.exitCode,
        `pnpm -r publish: ${published.command}\n${published.stdout}\n${published.stderr}`,
      ).toBe(0);

      const puts = recorder.entries
        .filter((entry) => entry.method === 'PUT')
        .map((entry) => entry.path);
      expect(puts, 'dependencies are published before their dependents').toEqual(
        [base, lib, app].map((name) => `/${repoPath(repo.name)}/${name}`),
      );
      expect(
        recorder.entries.filter((entry) => entry.method === 'PUT').map((entry) => entry.status),
      ).toEqual([200, 200, 200]);

      const stored = async (name: string): Promise<Record<string, unknown>> => {
        const res = await rawGetPackument(repo.name, adminCredential(), name);
        expect(res.status, `GET packument of ${name}`).toBe(200);
        const doc = JSON.parse(res.body.toString('utf8')) as {
          versions: Record<string, { dependencies?: Record<string, string> }>;
        };
        expect(
          JSON.stringify(doc),
          `nothing of the workspace: protocol reaches ${name}`,
        ).not.toContain('workspace:');
        return doc.versions[VERSION]?.dependencies ?? {};
      };
      expect(await stored(app), 'workspace:^ and workspace:~ become ranges').toEqual({
        [lib]: `^${VERSION}`,
        [base]: `~${VERSION}`,
      });
      expect(await stored(lib), 'workspace:* becomes the exact version').toEqual({
        [base]: VERSION,
      });
      expect(await stored(base)).toEqual({});

      // A consumer resolves the whole graph from the registry alone.
      const consumer = await pnpmClient.prepare('workspace-con', [
        await tokenBinding(seeder, repo.name, { readOnly: true }),
      ]);
      await renderConsumer(consumer.work, 'workspace-consumer');
      const added = await pnpmClient.add(consumer, [`${app}@${VERSION}`]);
      expect(added.exitCode, `add ${app}: ${added.command}\n${added.stderr}`).toBe(0);
      for (const name of [app, lib, base]) {
        expect(
          await pnpmClient.readInstalledFile(consumer, name, MARKER_FILENAME),
          `${name} was installed with the published bytes`,
        ).toBe(markers[name]);
      }

      // Everything is in the registry now: pnpm looks each member up and publishes nothing.
      const putsBefore = recorder.entries.filter((entry) => entry.method === 'PUT').length;
      const again = await publishWorkspace(ctx, root);
      expect(again.exitCode, `second -r publish: ${again.command}\n${again.stderr}`).toBe(0);
      expect(`${again.stdout}\n${again.stderr}`).toContain('no new packages');
      expect(
        recorder.entries.filter((entry) => entry.method === 'PUT'),
        'the second run sent no PUT',
      ).toHaveLength(putsBefore);
    } finally {
      await recorder.stop();
    }
  },
);
