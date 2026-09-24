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
 * Raw-HTTP package seeding for the UI suite (RPS-1255): puts a real package into a repo the test
 * already created (`seeder.createRepo`), with no package-manager toolchain, because the `ui` runner
 * image has only Node and Chromium. Every publish goes over the PROTOCOL port (`env.repoBaseUrl`,
 * 9090 in the default stack) as the harness admin, using the in-code builders of the `clients/*-raw.ts`
 * modules; the panel port only serves the SPA and the panel API.
 *
 * Everything lives inside the repo, so deleting the repo (which `seeder.cleanup()` does) is the whole
 * cleanup; nothing here is tracked separately.
 *
 * `SEEDERS` is the registry the next stories extend: RPS-1257 replaces the five `notImplemented`
 * entries with `packages/<proto>.ts` modules. A seeder is `(repoName, ctx, opts) => SeededPackage`
 * and must throw (never return) when the server refuses the publish.
 */
import type { RepoType } from '../api/panel-api.js';
import { seedDocker } from './packages/docker.js';
import { seedMaven } from './packages/maven.js';
import { seedNpm } from './packages/npm.js';
import { seedPypi } from './packages/pypi.js';
import type { SeededRepo } from './seeder.js';

export type PackageProtocol =
  'maven' | 'npm' | 'docker' | 'pypi' | 'cargo' | 'nuget' | 'helm' | 'golang' | 'ruby';

export const PACKAGE_PROTOCOLS: readonly PackageProtocol[] = [
  'maven',
  'npm',
  'docker',
  'pypi',
  'cargo',
  'nuget',
  'helm',
  'golang',
  'ruby',
];

/** What identifies a package inside a repo, and what a page object needs to reach its pages. */
export interface PackageRef {
  /**
   * The package identity exactly as the panel keys its list rows: maven `group:artifact`, npm
   * `@scope/name` or `name`, docker the image name, pypi/cargo/gem/chart the name, nuget the package
   * id, golang the module path.
   */
  name: string;
  /** The version (docker: the tag). */
  version: string;
  /** Protocol facts a route or row key needs beyond name and version (see `SeededPackage.extra`). */
  extra?: Readonly<Record<string, string>>;
}

export interface SeededPackage extends PackageRef {
  protocol: PackageProtocol;
  repoName: string;
  /**
   * Facts only some protocols have (always present, possibly empty). docker: `digest` (the manifest digest) and `configDigest`. helm: `variant` (`oci` or `classic`).
   */
  extra: Readonly<Record<string, string>>;
}

export interface SeedContext {
  /** The test's run id (`seeder.runId`); default names are derived from it. */
  runId: string;
}

export interface SeedPackageOptions {
  /** The full identity (see `PackageRef.name`). Default: `defaultPackageName(protocol, runId, index)`. */
  name?: string;
  /** Default `1.0.0`. */
  version?: string;
  /** 1-based number used only to derive the default name, so `seedPackages` gets distinct ones. Default 1. */
  index?: number;
  /** npm only: `false` seeds an UNSCOPED package (listed under `~`). Default `true`. */
  scoped?: boolean;
  /** helm only: which backend module publishes it, `oci` (default) or `classic` (ChartMuseum). */
  variant?: string;
}

/** Publishes one package version into `repoName` over raw HTTP, or throws. */
export type PackageSeeder = (
  repoName: string,
  ctx: SeedContext,
  opts: SeedPackageOptions,
) => Promise<SeededPackage>;

export { DEFAULT_VERSION, defaultPackageName } from './packages/shared.js';

function notImplemented(protocol: PackageProtocol): PackageSeeder {
  return () =>
    Promise.reject(
      new Error(
        `Package seeding for "${protocol}" is not implemented yet (RPS-1257 adds it: ` +
          `create src/seed/packages/${protocol}.ts and replace this entry in SEEDERS)`,
      ),
    );
}

export const SEEDERS: Record<PackageProtocol, PackageSeeder> = {
  maven: seedMaven,
  npm: seedNpm,
  docker: seedDocker,
  pypi: seedPypi,
  cargo: notImplemented('cargo'),
  nuget: notImplemented('nuget'),
  helm: notImplemented('helm'),
  golang: notImplemented('golang'),
  ruby: notImplemented('ruby'),
};

/** The protocol a repo of `type` serves (`RepoType` is upper-case, protocols lower-case). */
export function protocolOf(type: RepoType | string): PackageProtocol {
  const protocol = String(type).toLowerCase();
  if (!(PACKAGE_PROTOCOLS as readonly string[]).includes(protocol)) {
    throw new Error(`protocolOf: "${type}" is not a package protocol`);
  }
  return protocol as PackageProtocol;
}

/** Seeds one package version into a seeded repo; the protocol is the repo's own type. */
export async function seedPackage(
  repo: Pick<SeededRepo, 'name' | 'type'>,
  ctx: SeedContext,
  opts: SeedPackageOptions = {},
): Promise<SeededPackage> {
  return SEEDERS[protocolOf(repo.type)](repo.name, ctx, opts);
}

const SEED_CONCURRENCY = 4;

/**
 * Seeds `count` distinct packages (`index` 1..count, or `firstIndex`..) with a small concurrency, for
 * pagination and sort tests that need more than one page. Other options apply to every package.
 */
export async function seedPackages(
  repo: Pick<SeededRepo, 'name' | 'type'>,
  ctx: SeedContext,
  count: number,
  opts: Omit<SeedPackageOptions, 'name' | 'index'> & { firstIndex?: number } = {},
): Promise<SeededPackage[]> {
  const { firstIndex = 1, ...rest } = opts;
  const results: SeededPackage[] = new Array<SeededPackage>(count);
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < count) {
      const slot = next;
      next += 1;
      results[slot] = await seedPackage(repo, ctx, { ...rest, index: firstIndex + slot });
    }
  };
  await Promise.all(Array.from({ length: Math.min(SEED_CONCURRENCY, count) }, worker));
  return results;
}

/**
 * Publishes several versions of ONE package, sequentially in the order given (so the panel's
 * "Newest" is the last). `name` defaults like `seedPackage`'s.
 */
export async function seedVersions(
  repo: Pick<SeededRepo, 'name' | 'type'>,
  ctx: SeedContext,
  versions: readonly string[],
  opts: Omit<SeedPackageOptions, 'version'> = {},
): Promise<SeededPackage[]> {
  const seeded: SeededPackage[] = [];
  for (const version of versions) {
    seeded.push(await seedPackage(repo, ctx, { ...opts, version }));
  }
  return seeded;
}
