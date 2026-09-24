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
 * The `test` of every package-page spec (RPS-1255): `fixtures.ts`'s `test` plus raw-HTTP package
 * seeding bound to the test's own run id. Import it instead of `fixtures.ts`:
 *
 * ```ts
 * import { expect, test } from '../../../src/ui/package-fixtures.js';
 *
 * test('lists a seeded package', async ({ adminPage, seeder, seedPackage }) => {
 *   const repo = await seeder.createRepo(RepoType.NPM);        // deleting the repo is the cleanup
 *   const pkg = await seedPackage(repo);                        // raw HTTP as the admin, no npm binary
 *   ...
 * });
 * ```
 *
 * The protocol is the repo's own type, so a spec never names it twice. Nothing seeded here needs
 * separate cleanup: it lives inside the repo `seeder` deletes.
 */
import {
  type SeededPackage,
  type SeedPackageOptions,
  seedPackage as seedOne,
  seedPackages as seedMany,
  seedVersions as seedSeveral,
} from '../seed/packages.js';
import type { SeededRepo } from '../seed/seeder.js';
import { expect, test as uiTest } from './fixtures.js';

type Repo = Pick<SeededRepo, 'name' | 'type'>;

export interface PackageFixtures {
  /** Publishes one package version into `repo` (a `seeder.createRepo` result) over raw HTTP. */
  seedPackage: (repo: Repo, opts?: SeedPackageOptions) => Promise<SeededPackage>;
  /** Publishes `count` distinct packages, for pagination and sort (10 per page: seed 12 or more). */
  seedPackages: (
    repo: Repo,
    count: number,
    opts?: Omit<SeedPackageOptions, 'name' | 'index'> & { firstIndex?: number },
  ) => Promise<SeededPackage[]>;
  /** Publishes several versions of one package, in the order given (the last is the newest). */
  seedVersions: (
    repo: Repo,
    versions: readonly string[],
    opts?: Omit<SeedPackageOptions, 'version'>,
  ) => Promise<SeededPackage[]>;
}

export const test = uiTest.extend<PackageFixtures>({
  seedPackage: async ({ seeder }, use) => {
    await use((repo, opts) => seedOne(repo, { runId: seeder.runId }, opts));
  },
  seedPackages: async ({ seeder }, use) => {
    await use((repo, count, opts) => seedMany(repo, { runId: seeder.runId }, count, opts));
  },
  seedVersions: async ({ seeder }, use) => {
    await use((repo, versions, opts) => seedSeveral(repo, { runId: seeder.runId }, versions, opts));
  },
});

export { expect };
