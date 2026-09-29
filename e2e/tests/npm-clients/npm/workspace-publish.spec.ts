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
 * npm's OWN workspaces (RPS-1717, matrix row 11 for the npm CLI itself -- already covered for pnpm
 * and yarn berry, RPS-1330: `pnpm/workspace-publish.spec.ts`, `yarn-berry/workspaces.spec.ts`).
 * Unlike pnpm and yarn, npm has no `workspace:` protocol: a member depends on a sibling through an
 * ordinary semver range that already happens to be satisfied locally, so npm links it from the
 * workspace during `install` and there is nothing to rewrite at `publish` time -- the packument
 * Repsy stores must carry that same range untouched.
 *
 *  - `npm publish --workspaces` publishes every (non-private) member; a consumer that only knows the
 *    app's coordinates resolves its lib dependency from the registry too, not a leftover local link
 *    (the consumer is a separate project, not a workspace of the published one).
 *  - `npm publish --workspace <path>` filters to that one member: the other is never published.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument, rawGetPath } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import type { ClientCtx } from '../../../src/clients/npm-family/client.js';
import { npmClient, runNpm } from '../../../src/clients/npm-family/npm-client.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

async function writeWorkspaceRoot(work: string, name: string): Promise<void> {
  await fs.writeFile(
    path.join(work, 'package.json'),
    `${JSON.stringify({ name, version: '0.0.0', private: true, workspaces: ['packages/*'] })}\n`,
  );
}

async function npmInstall(ctx: ClientCtx): Promise<void> {
  const installed = await runNpm(ctx, 'npm-ws-install', [
    'install',
    '--ignore-scripts',
    '--no-audit',
    '--no-fund',
  ]);
  expect(installed.exitCode, `npm install: ${installed.command}\n${installed.stderr}`).toBe(0);
}

test(
  '--workspaces publishes every member with its real range, and a consumer resolves the whole graph from the registry',
  { tag: ['@npm', '@workspaces'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await npmClient.prepare('ws-publish', [writer]);
    const lib = packageNameFor(seeder, 'nwslib');
    const app = packageNameFor(seeder, 'nwsapp');

    await writeWorkspaceRoot(ctx.work, 'ws-root');
    const libMarker = (
      await renderPackage(path.join(ctx.work, 'packages/lib'), {
        packageName: lib,
        version: '1.2.3',
      })
    ).marker;
    const appMarker = (
      await renderPackage(path.join(ctx.work, 'packages/app'), {
        packageName: app,
        version: '2.0.0',
        dependencies: { [lib]: '^1.2.3' },
      })
    ).marker;

    await npmInstall(ctx);
    const published = await runNpm(ctx, 'npm-ws-publish', [
      'publish',
      '--workspaces',
      '--ignore-scripts',
    ]);
    expect(
      published.exitCode,
      `${published.command}\n${published.stdout}\n${published.stderr}`,
    ).toBe(0);

    const packument = async (name: string) => {
      const res = await rawGetPackument(repo.name, adminCredential(), name);
      expect(res.status, `GET packument of ${name}`).toBe(200);
      return JSON.parse(res.body.toString('utf8')) as {
        versions: Record<string, { dependencies?: Record<string, string> }>;
        'dist-tags': Record<string, string>;
      };
    };
    const libDoc = await packument(lib);
    const appDoc = await packument(app);
    expect(libDoc['dist-tags']).toEqual({ latest: '1.2.3' });
    expect(appDoc['dist-tags']).toEqual({ latest: '2.0.0' });
    expect(
      appDoc.versions['2.0.0']?.dependencies,
      'npm never used a workspace: protocol, so the stored range is the real one, untouched',
    ).toEqual({ [lib]: '^1.2.3' });

    // A separate project (no workspace of its own) that only knows the app's coordinates.
    const consumer = await npmClient.prepare('ws-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'ws-consumer');
    const added = await npmClient.add(consumer, [`${app}@2.0.0`]);
    expect(added.exitCode, `add ${app}: ${added.command}\n${added.stderr}`).toBe(0);
    expect(await npmClient.readInstalledFile(consumer, app, MARKER_FILENAME)).toBe(appMarker);
    expect(
      await npmClient.readInstalledFile(consumer, lib, MARKER_FILENAME),
      'the lib dependency resolves from the registry, not a workspace link the consumer never had',
    ).toBe(libMarker);
  },
);

test(
  '--workspace <path> filters the publish to that one member',
  { tag: ['@npm', '@workspaces'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await npmClient.prepare('ws-filter', [writer]);
    const keep = packageNameFor(seeder, 'nwskeep');
    const skip = packageNameFor(seeder, 'nwsskip');

    await writeWorkspaceRoot(ctx.work, 'ws-filter-root');
    await renderPackage(path.join(ctx.work, 'packages/keep'), {
      packageName: keep,
      version: '1.0.0',
    });
    await renderPackage(path.join(ctx.work, 'packages/skip'), {
      packageName: skip,
      version: '1.0.0',
    });
    await npmInstall(ctx);

    const published = await runNpm(ctx, 'npm-ws-filter-publish', [
      'publish',
      '--workspace',
      'packages/keep',
      '--ignore-scripts',
    ]);
    expect(
      published.exitCode,
      `${published.command}\n${published.stdout}\n${published.stderr}`,
    ).toBe(0);

    expect(
      (await rawGetPackument(repo.name, adminCredential(), keep)).status,
      'the targeted member was published',
    ).toBe(200);
    expect(
      (await rawGetPath(repo.name, adminCredential(), skip)).status,
      'the filtered-out member was never published',
    ).toBe(404);
  },
);
