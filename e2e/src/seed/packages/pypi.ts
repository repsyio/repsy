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

/** PyPI seeder: one raw `POST` of the multipart form `twine upload` sends, with a hand-built wheel. */
import { adminCredential } from '../../clients/raw-http.js';
import { buildWheel, rawUpload } from '../../clients/pypi-raw.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedPypi: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('pypi', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;

  const wheel = buildWheel({ name, version });
  expectPublished(await rawUpload(repoName, adminCredential(), wheel), `POST ${wheel.filename}`);

  return { protocol: 'pypi', repoName, name, version, extra: {} };
};
