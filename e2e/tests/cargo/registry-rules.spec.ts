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
 * The cargo server's registry rules, pinned at the protocol level with raw HTTP PUTs/GETs (no
 * `cargo` client), the cargo analogue of `tests/npm/registry-rules.spec.ts`. Every status/detail here
 * was read from `AbstractCargoPublishProtocolMethodHandler`/`CargoCrateServiceImpl`/`CrateUtils`
 * first and then confirmed against a running instance (see `README.md`'s "Scenario outcomes pinned
 * against a running instance" for the raw evidence):
 *
 *  - A duplicate-version publish is refused unconditionally (400, `{"errors":[{"detail":"this crate
 *    version already exists in this registry"}]}`) -- `allowOverride` is never read by the
 *    protocol at all. RPS-1124 (fixed, #507): the refused attempt used to overwrite the stored `.crate`
 *    bytes with its own (rejected) content before the duplicate check ran, so a download served the
 *    REJECTED attempt's bytes while the index still named the original checksum. The check now runs
 *    first: a refused duplicate leaves the stored `.crate` (and so the index `cksum`) untouched.
 *  - A malformed or int-overflowing version string is refused with 400 and a `"not a valid semver
 *    format"` detail (`CrateUtils.validateVersion`, semver4j 3.1.0), storing nothing.
 *  - `config.json` is served unauthenticated on both a private and a public repo (`skipPreProcessor:
 *    true`), and only differs in whether it carries `"auth-required": true` (a private repo) or omits
 *    the key entirely (a public one) -- confirmed live.
 *  - A crate published under a hyphenated name is served, downloadable and index-lookupable under
 *    EITHER its hyphenated or its normalised (underscored) spelling: `CrateUtils.normalizeCrateName`
 *    is applied both when the crate is stored and whenever it is looked up by name, so the served
 *    entry's own `name` field always differs from a hyphenated publish -- see
 *    `tests/cargo/publish-consume.spec.ts`'s dedicated real-client test for what this does to an
 *    actual `cargo fetch`.
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  buildPublishBody,
  cargoErrorDetail,
  parseIndex,
  rawDownload,
  rawGetConfigJson,
  rawGetIndex,
  rawPublish,
  sha256Hex,
  type RawResponse,
} from '../../src/clients/cargo-raw.js';
import { cargoAdapter } from '../../src/clients/cargo.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
}

/** A fresh cargo repo (permissive defaults) and a run-unique, underscore-only crate name for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e_${seeder.runId}_${label}` };
}

/**
 * A minimal, real gzip+tar `.crate` (`name-version/Cargo.toml` + `.../src/lib.rs`), built with the
 * host's own `tar` binary -- NOT the `cargo` binary (forbidden on the host, see AGENTS.md/this
 * story's constraints): `CrateUtils.isLib` decompresses and reads tar entries before the duplicate
 * check ever runs, so an arbitrary byte string (which `tests/npm/registry-rules.spec.ts`'s
 * `fakeTarball` gets away with) is refused at that earlier step instead of exercising the rule this
 * test is about.
 */
function fakeCrate(name: string, version: string, seed: string): Buffer {
  const root = mkdtempSync(path.join(os.tmpdir(), 'cargo-raw-crate-'));
  try {
    const pkgDir = path.join(root, `${name}-${version}`);
    mkdirSync(path.join(pkgDir, 'src'), { recursive: true });
    writeFileSync(
      path.join(pkgDir, 'Cargo.toml'),
      `[package]\nname = "${name}"\nversion = "${version}"\nedition = "2021"\n\n[lib]\npath = "src/lib.rs"\n`,
    );
    writeFileSync(path.join(pkgDir, 'src', 'lib.rs'), `// ${seed}\n`);
    const tarPath = path.join(root, 'out.crate');
    execFileSync('tar', ['czf', tarPath, '-C', root, `${name}-${version}`]);
    return readFileSync(tarPath);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function expectPut(res: RawResponse, status: number, detailContains: string | undefined): void {
  const detail = cargoErrorDetail(res.body);
  expect(res.status, `PUT answered ${res.status} ${detail ?? ''}`).toBe(status);
  if (detailContains !== undefined) {
    expect(detail, 'the error detail').toContain(detailContains);
  }
}

test.describe('cargo registry rules (raw HTTP)', () => {
  test(
    're-publishing an existing version is refused whatever allowOverride says, and leaves the ' +
      'stored bytes untouched (RPS-1124, fixed)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();
      const v1 = cargoAdapter.version('release');

      const bytesA = fakeCrate(layout.packageName, v1, 'v1');
      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({ name: layout.packageName, version: v1, crateBytes: bytesA }),
        ),
        200,
        undefined,
      );

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
      });

      const indexBefore = await rawGetIndex(layout.repoName, admin, layout.packageName);
      const dlBefore = await rawDownload(layout.repoName, admin, layout.packageName, v1);
      expect(dlBefore.status, 'the seeded version downloads').toBe(200);
      expect(sha256Hex(dlBefore.body), 'the seeded version is bytesA').toBe(sha256Hex(bytesA));

      // Re-publishing the SAME version, even with different bytes and allowOverride: false, is
      // refused -- and (RPS-1124, fixed) the refusal must not touch storage either.
      const bytesB = fakeCrate(layout.packageName, v1, 'v1-again');
      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({ name: layout.packageName, version: v1, crateBytes: bytesB }),
        ),
        400,
        'already exists',
      );

      const indexAfter = await rawGetIndex(layout.repoName, admin, layout.packageName);
      expect(indexAfter.body.toString('utf8'), 'the served (DB-backed) index is unchanged').toBe(
        indexBefore.body.toString('utf8'),
      );

      // RPS-1124 (fixed, #507): the duplicate check now runs BEFORE the .crate is written, so the
      // refused duplicate no longer overwrites the stored bytes.
      const dlAfter = await rawDownload(layout.repoName, admin, layout.packageName, v1);
      expect(sha256Hex(dlAfter.body), 'the stored .crate is unchanged (bytesA)').toBe(
        sha256Hex(bytesA),
      );
    },
  );

  test(
    'a new version of an existing crate is accepted regardless of allowOverride',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'newver');
      const admin = adminCredential();
      const v1 = cargoAdapter.version('release');

      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({
            name: layout.packageName,
            version: v1,
            crateBytes: fakeCrate(layout.packageName, v1, 'v1'),
          }),
        ),
        200,
        undefined,
      );

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
      });

      const v2 = cargoAdapter.version('release');
      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({
            name: layout.packageName,
            version: v2,
            crateBytes: fakeCrate(layout.packageName, v2, 'v2'),
          }),
        ),
        200,
        undefined,
      );

      const finalIndex = await rawGetIndex(layout.repoName, admin, layout.packageName);
      const versions = parseIndex(finalIndex.body).map((e) => e.vers);
      expect(versions.sort()).toEqual([v1, v2].sort());
    },
  );

  test(
    'an invalid or int-overflowing version is refused and stores nothing',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badversion');
      const admin = adminCredential();

      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({
            name: layout.packageName,
            version: 'not-a-version',
            crateBytes: fakeCrate(layout.packageName, 'not-a-version', 'bad'),
          }),
        ),
        400,
        'not a valid semver format',
      );

      // A version whose numeric part overflows the Java `int` semver4j 3.1.0 parses into (maven's
      // own `0.0.<Date.now()>` scheme) -- refused the same way, not with a raw NumberFormatException.
      const overflowVersion = `0.0.${Date.now()}`;
      expectPut(
        await rawPublish(
          layout.repoName,
          admin,
          buildPublishBody({
            name: layout.packageName,
            version: overflowVersion,
            crateBytes: fakeCrate(layout.packageName, overflowVersion, 'overflow'),
          }),
        ),
        400,
        'not a valid semver format',
      );

      const index = await rawGetIndex(layout.repoName, admin, layout.packageName);
      expect(index.status, 'nothing was ever published for this crate').toBe(404);
    },
  );

  test(
    "config.json advertises this instance's own dl/api URLs and auth-required only for a " +
      'private repo',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const privateRepo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
      const privateCfg = await rawGetConfigJson(privateRepo.name);
      expect(privateCfg.status, 'config.json is served unauthenticated').toBe(200);
      const privateBody = JSON.parse(privateCfg.body.toString('utf8')) as {
        dl: string;
        api: string;
        'auth-required'?: boolean;
      };
      expect(privateBody.dl).toBe(
        `${env.repoBaseUrl}/${privateRepo.name}/api/v1/crates/{crate}/{version}/download`,
      );
      expect(privateBody.api).toBe(`${env.repoBaseUrl}/${privateRepo.name}`);
      expect(privateBody['auth-required'], 'a private repo advertises auth-required').toBe(true);

      const publicRepo = await seeder.createRepo(RepoType.CARGO, { privateRepo: false });
      const publicCfg = await rawGetConfigJson(publicRepo.name);
      expect(publicCfg.status).toBe(200);
      const publicBody = JSON.parse(publicCfg.body.toString('utf8')) as {
        'auth-required'?: boolean;
      };
      expect(
        publicBody['auth-required'],
        'a public repo omits auth-required entirely',
      ).toBeUndefined();
    },
  );

  test(
    'the sparse index serves a crate under the name it was published under, whichever spelling ' +
      'it is looked up by (RPS-1212)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
      const admin = adminCredential();
      const hyphenName = `e2e-${seeder.runId}-rawhyphen`;
      const underscoreName = hyphenName.replace(/-/g, '_');
      const version = cargoAdapter.version('release');
      const bytes = fakeCrate(hyphenName, version, 'rps-xxxx');

      expectPut(
        await rawPublish(
          repo.name,
          admin,
          buildPublishBody({ name: hyphenName, version, crateBytes: bytes }),
        ),
        200,
        undefined,
      );

      const byHyphen = await rawGetIndex(repo.name, admin, hyphenName);
      const byUnderscore = await rawGetIndex(repo.name, admin, underscoreName);
      expect(byHyphen.status, 'looked up by the published (hyphenated) spelling').toBe(200);
      expect(byUnderscore.status, 'looked up by the normalised spelling').toBe(200);

      // RPS-1212 (fixed): lookup stays spelling-insensitive (both requests above answered 200),
      // but the served entry's identity is stable -- it always names the spelling the crate was
      // actually published under, not the normalised lookup key.
      const entry = parseIndex(byHyphen.body)[0];
      expect(entry?.name).toBe(hyphenName);
      const entryByUnderscore = parseIndex(byUnderscore.body)[0];
      expect(entryByUnderscore?.name).toBe(hyphenName);

      // Downloadable under either spelling regardless (the download route normalises its own name
      // segment before looking the crate up).
      const dlHyphen = await rawDownload(repo.name, admin, hyphenName, version);
      const dlUnderscore = await rawDownload(repo.name, admin, underscoreName, version);
      expect(dlHyphen.status, `download by "${hyphenName}"`).toBe(200);
      expect(dlUnderscore.status, `download by "${underscoreName}"`).toBe(200);
    },
  );
});
