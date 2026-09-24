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
 * Go seeder: one raw `PUT <repo>/<module>/@v/<version>.zip` of a hand-built module zip with the
 * `Content-Sha256` header, the only publisher Go has (`curl -T`, what the panel documents). The
 * server derives `go.mod`, `.info` and `.mod` from the zip. `opts.name` is the module path, the row
 * key. Go versions start with `v`, so a version given without one (the shared scenarios pass
 * `1.0.0`) gets it added, and the returned `version` is the one the panel shows (`v1.0.0`).
 */
import { buildModuleZip, rawUpload } from '../../clients/golang-raw.js';
import { adminCredential } from '../../clients/raw-http.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedGolang: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('golang', ctx.runId, opts.index);
  const requested = opts.version ?? DEFAULT_VERSION;
  const version = requested.startsWith('v') ? requested : `v${requested}`;

  const built = await buildModuleZip({ modulePath: name, version });
  expectPublished(
    await rawUpload(repoName, adminCredential(), built),
    `PUT ${name}@${version}.zip`,
  );

  return { protocol: 'golang', repoName, name, version, extra: {} };
};
