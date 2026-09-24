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

/**
 * npm seeder: one raw `PUT <registry>/<name>` of the publish document `npm publish` sends, with a
 * built-in tarball. `opts.name` is `@scope/name` or `name` (unscoped: listed under `~`).
 */
import { adminCredential } from '../../clients/raw-http.js';
import { buildPublishDocument, buildTarball, rawPublish } from '../../clients/npm-raw.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedNpm: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('npm', ctx.runId, opts.index, opts.scoped ?? true);
  const version = opts.version ?? DEFAULT_VERSION;

  const document = buildPublishDocument({
    repoName,
    packageName: name,
    version,
    tarballBytes: buildTarball({ packageName: name, version }),
  });
  expectPublished(await rawPublish(repoName, adminCredential(), name, document), `PUT ${name}`);

  return { protocol: 'npm', repoName, name, version, extra: {} };
};
