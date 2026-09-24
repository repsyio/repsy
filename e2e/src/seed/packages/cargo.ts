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
 * Cargo seeder: one raw `PUT /api/v1/crates/new` of the body `cargo publish` sends (JSON metadata
 * plus a real gzip+tar `.crate` built in code, never the `cargo` binary). `opts.name` is the crate
 * name; the default is underscore-only because the panel keys the row by the NORMALISED name
 * (`-` becomes `_`, README "H2, confirmed live"), so an underscore name is the one the row shows.
 * `publishCrate` is the same publish with the README and dependencies a spec may want.
 */
import { adminCredential } from '../../clients/raw-http.js';
import { buildPublishBody, rawPublish } from '../../clients/cargo-raw.js';
import type { PackageSeeder, SeededPackage } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';
import { tarGz } from './tar-gz.js';

/**
 * A minimal, real `.crate`: `<name>-<version>/Cargo.toml` plus `src/lib.rs` (a library crate, the
 * panel's "Add Dependency" branch) or, with `bin`, `src/main.rs` only (its "Install Binary" branch).
 */
export function buildCrate(name: string, version: string, bin = false): Buffer {
  const target = bin ? '' : '\n[lib]\npath = "src/lib.rs"\n';
  return tarGz([
    {
      name: `${name}-${version}/Cargo.toml`,
      data: Buffer.from(
        `[package]\nname = "${name}"\nversion = "${version}"\nedition = "2021"\n${target}`,
        'utf8',
      ),
    },
    {
      name: `${name}-${version}/src/${bin ? 'main' : 'lib'}.rs`,
      data: Buffer.from(bin ? 'fn main() {}\n' : `// e2e ${name}@${version}\n`, 'utf8'),
    },
  ]);
}

export interface PublishCrateOptions {
  /** A binary crate (`src/main.rs`, no lib) instead of a library. */
  bin?: boolean;
  readme?: string;
  deps?: readonly Record<string, unknown>[];
  description?: string;
}

/** Publishes one crate version as the admin, with optional README and dependencies, or throws. */
export async function publishCrate(
  repoName: string,
  name: string,
  version: string,
  { bin, ...extra }: PublishCrateOptions = {},
): Promise<SeededPackage> {
  const body = buildPublishBody({
    name,
    version,
    crateBytes: buildCrate(name, version, bin),
    ...extra,
  });
  expectPublished(
    await rawPublish(repoName, adminCredential(), body),
    `PUT crate ${name}@${version}`,
  );
  return { protocol: 'cargo', repoName, name, version, extra: {} };
}

/**
 * The default version is `1.0.0` for the first crate and `1.<index - 1>.0` for the next ones: the
 * crate list sorts by `max_version` (a text column, RPS-1301), so crates that all sit at one version
 * tie on it and are ordered by their id only (RPS-1298). Distinct versions per index make the order
 * well defined (and equal to publish order).
 */
export const seedCargo: PackageSeeder = async (repoName, ctx, opts) => {
  const index = opts.index ?? 1;
  return publishCrate(
    repoName,
    opts.name ?? defaultPackageName('cargo', ctx.runId, index),
    opts.version ?? (index > 1 ? `1.${index - 1}.0` : DEFAULT_VERSION),
  );
};
