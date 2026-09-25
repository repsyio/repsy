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
 * Workspaces under yarn berry (RPS-1330, matrix row 11): `yarn workspaces foreach -A npm publish`
 * publishes every workspace and rewrites the `workspace:` protocol to a real range first. The
 * REGISTRY holds what berry sent, so the stored packument and tarball must carry `^1.2.3`, never
 * `workspace:^`, and a consumer then installs the app with its library from the repository.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import { rawGetPackument, rawGetTarballCanonical } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  renderConsumer,
  renderPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { execYarn, yarnBerryClient } from '../../../src/clients/npm-family/yarn-berry-client.js';
import { tarFile } from '../../../src/clients/npm-family/yarn-berry-support.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

test(
  'foreach npm publish rewrites workspace: ranges, and the published app installs with its library',
  { tag: ['@yarn-berry', '@workspaces'] },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await yarnBerryClient.prepare('workspaces', [writer]);
    const lib = packageNameFor(seeder, 'wslib');
    const app = packageNameFor(seeder, 'wsapp');

    // The project root is `ctx.work` (its empty yarn.lock); two members under packages/.
    await fs.writeFile(
      path.join(ctx.work, 'package.json'),
      `${JSON.stringify({ name: 'root', version: '0.0.0', private: true, workspaces: ['packages/*'] })}\n`,
    );
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
        dependencies: { [lib]: 'workspace:^' },
      })
    ).marker;

    const installed = await execYarn(ctx, 'ws-install', ['install']);
    expect(installed.exitCode, installed.stdout).toBe(0);
    const published = await execYarn(ctx, 'ws-publish', [
      'workspaces',
      'foreach',
      '-A',
      '--no-private',
      '--topological',
      'npm',
      'publish',
    ]);
    expect(published.exitCode, `publish: ${published.command}\n${published.stdout}`).toBe(0);
    expect(published.stdout.match(/Package archive published/g), 'both members').toHaveLength(2);

    const packument = async (name: string) =>
      JSON.parse(
        (await rawGetPackument(repo.name, adminCredential(), name)).body.toString('utf8'),
      ) as {
        versions: Record<string, { dependencies?: Record<string, string> }>;
        'dist-tags': Record<string, string>;
      };
    const libDoc = await packument(lib);
    const appDoc = await packument(app);
    expect(Object.keys(libDoc.versions)).toEqual(['1.2.3']);
    expect(appDoc['dist-tags']).toEqual({ latest: '2.0.0' });
    expect(appDoc.versions['2.0.0']?.dependencies, 'the packument has a real range').toEqual({
      [lib]: '^1.2.3',
    });

    const stored = await rawGetTarballCanonical(repo.name, adminCredential(), app, '2.0.0');
    const manifest = JSON.parse(await tarFile(stored.body, 'package/package.json')) as {
      dependencies: Record<string, string>;
    };
    expect(manifest.dependencies, 'and so does the tarball manifest').toEqual({ [lib]: '^1.2.3' });

    const consumer = await yarnBerryClient.prepare('workspaces-con', [
      await tokenBinding(seeder, repo.name, { readOnly: true }),
    ]);
    await renderConsumer(consumer.work, 'workspaces-consumer');
    const added = await yarnBerryClient.add(consumer, [`${app}@2.0.0`]);
    expect(added.exitCode, `add: ${added.command}\n${added.stdout}`).toBe(0);
    expect(await yarnBerryClient.readInstalledFile(consumer, app, MARKER_FILENAME)).toBe(appMarker);
    expect(await yarnBerryClient.readInstalledFile(consumer, lib, MARKER_FILENAME)).toBe(libMarker);
  },
);
