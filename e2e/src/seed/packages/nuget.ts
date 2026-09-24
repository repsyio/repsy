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
 * NuGet seeder: one raw `PUT v3/package/` (multipart `package`) of a hand-built `.nupkg` (a zip
 * with the nuspec, `fflate`), what `dotnet nuget push` sends. `opts.name` is the package id, which
 * the panel keys its rows by; the default is lower-case.
 */
import { adminCredential } from '../../clients/raw-http.js';
import { buildNupkg, rawPublish } from '../../clients/nuget-raw.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedNuget: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('nuget', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;

  const nupkg = buildNupkg({ packageId: name, version });
  expectPublished(await rawPublish(repoName, adminCredential(), nupkg), `PUT ${name}.${version}`);

  return { protocol: 'nuget', repoName, name, version, extra: {} };
};
