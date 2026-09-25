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
 * Helpers shared by the yarn-berry-only specs (`tests/npm-clients/yarn-berry`, RPS-1330): editing the
 * rendered `.yarnrc.yml`, reading a tarball the registry stored, and publishing a small
 * `app -> lib` graph.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { execa } from 'execa';

import type { ClientCtx } from './client.js';
import { publishPackage } from './fixtures.js';
import { YARN_RC, yarnBerryClient } from './yarn-berry-client.js';

/** Rewrites the `.yarnrc.yml` a `prepare` rendered, e.g. to drop one setting. */
export async function editRc(ctx: ClientCtx, edit: (rc: string) => string): Promise<void> {
  const file = path.join(ctx.work, YARN_RC);
  await fs.writeFile(file, edit(await fs.readFile(file, 'utf8')));
}

/** The file names inside a gzipped tarball. */
export async function tarEntries(tarball: Buffer): Promise<string[]> {
  const listed = await execa('tar', ['tzf', '-'], { input: tarball });
  return listed.stdout.split('\n').filter(Boolean).sort();
}

/** One file of a gzipped tarball, as text. */
export async function tarFile(tarball: Buffer, file: string): Promise<string> {
  const extracted = await execa('tar', ['xzOf', '-', file], { input: tarball });
  return extracted.stdout;
}

export interface PublishedApp {
  lib: string;
  app: string;
  libMarker: string;
  appMarker: string;
}

/** Publishes `<lib>@1.0.0` and `<app>@1.0.0` (app -> lib) with berry through `publisher`. */
export async function publishApp(
  publisher: ClientCtx,
  names: { lib: string; app: string },
): Promise<PublishedApp> {
  const lib = await publishPackage(yarnBerryClient, publisher, {
    packageName: names.lib,
    version: '1.0.0',
  });
  const app = await publishPackage(yarnBerryClient, publisher, {
    packageName: names.app,
    version: '1.0.0',
    dependencies: { [names.lib]: '1.0.0' },
  });
  if (lib.result.exitCode !== 0 || app.result.exitCode !== 0) {
    throw new Error(`yarn berry publishApp failed: ${lib.result.stderr}${app.result.stderr}`);
  }
  return { ...names, libMarker: lib.marker, appMarker: app.marker };
}
