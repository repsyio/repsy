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

/**
 * Ruby seeder: one raw `POST /api/v1/gems` of a hand-built `.gem` (metadata, data and checksums
 * entries, built in code), what `gem push` sends. `opts.name` is the gem name; the default is
 * underscore-only, like the harness's own gem names.
 */
import { adminCredential } from '../../clients/raw-http.js';
import { buildGem, rawPublish } from '../../clients/ruby-raw.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedRuby: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('ruby', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;

  const gem = await buildGem({ name, version });
  expectPublished(await rawPublish(repoName, adminCredential(), gem.bytes), `POST ${gem.filename}`);

  return { protocol: 'ruby', repoName, name, version, extra: {} };
};
