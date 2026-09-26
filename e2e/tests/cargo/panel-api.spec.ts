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
 * RPS-1483: the panel API of Cargo (`/api/cargo/crates/...`), against what the REAL `cargo publish` stored. Each
 * response is validated against the response schema of `openapi-spec.yaml` (`src/api/spec-contract.ts`: the
 * schema, and no property the schema does not declare), and the values are matched to the client-side facts: the
 * versions and `yanked` flags of the sparse index (flipped by a real `cargo yank`), the manifest the client
 * published (description, authors, keywords, categories, URLs, license, edition, rust-version, README, the
 * dependencies of each kind) and the downloads a real `cargo fetch` made. The delete operations are judged by their
 * effect on the wire: the sparse index drops the version (or answers 404), the `.crate` answers 404 and a real
 * `cargo fetch` fails, while the sibling version still fetches the bytes the client built.
 *
 * Copied from `tests/cargo/protocol-specific.spec.ts` (the real `cargo publish` of a manifest of our own and
 * `cargo yank`) and `tests/pypi/panel-api.spec.ts` (the shared contract helpers). The paging sweeps seed their
 * rows over raw HTTP (`seedPackage`): the pages are the subject there, not the client.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import {
  callOperation,
  contractWorld,
  expectContract,
  expectCovers,
  expectFailure,
  expectPagingSweep,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import * as cargo from '../../src/clients/cargo.js';
import { cargoEnv, renderCargoConfig } from '../../src/clients/cargo.js';
import {
  adminCredential,
  parseIndex,
  rawDownload,
  rawGetIndex,
} from '../../src/clients/cargo-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Cargo panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'searchCargoCrates',
  'getCargoCrate',
  'deleteCargoCrate',
  'getCargoCrateVersion',
  'deleteCargoCrateVersion',
  'listCargoCrateVersions',
];

interface Names {
  repoName: string;
  /** `e2e_<run>_panel` (a crate name is stored with `-` as `_`, so the spec uses only `_`). */
  name: string;
  /** The crate the rich crate depends on. */
  dependency: string;
  /** The crate the rich crate depends on as a development dependency. */
  devDependency: string;
}

interface CrateVersionDetail {
  crateId: string;
  name: string;
  version: string;
  readme?: string;
  license?: string;
  documentation?: string;
  edition?: string;
  deps: Record<string, unknown>[];
  downloads: number;
  hasLib: boolean;
  yanked: boolean;
  rustVersion?: string;
  createdAt: string;
}

interface VersionRow {
  version: string;
  yanked: boolean;
  createdAt: string;
}

const worldOf = (names: Names, crate: string, version: string) =>
  contractWorld('cargo', names.repoName, crate, version, adminCredential());

async function newNames(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
  const base = `e2e_${seeder.runId}_panel`;
  return {
    repoName: repo.name,
    name: base,
    dependency: `${base}_dep`,
    devDependency: `${base}_devdep`,
  };
}

const values = (names: Names, version?: string): Record<string, string> => ({
  repoName: names.repoName,
  crateName: names.name,
  ...(version ? { version } : {}),
});

/** The versions the sparse index lists with their `yanked` flag, or the status when it is not there. */
async function indexEntries(
  names: Names,
  crate = names.name,
): Promise<Record<string, boolean> | number> {
  const res = await rawGetIndex(names.repoName, adminCredential(), crate);
  return res.status === 200
    ? Object.fromEntries(parseIndex(res.body).map((entry) => [entry.vers, entry.yanked]))
    : res.status;
}

async function crateStatus(names: Names, version: string, crate = names.name): Promise<number> {
  return (await rawDownload(names.repoName, adminCredential(), crate, version)).status;
}

async function panelVersions(names: Names): Promise<VersionRow[]> {
  const res = await callOperation('listCargoCrateVersions', values(names));
  return (expectContract('listCargoCrateVersions', res) as { content: VersionRow[] }).content;
}

/** `cargo yank` (or `--undo`) of one version of the crate, with the real client. */
async function cargoYank(names: Names, version: string, undo: boolean): Promise<void> {
  const credential = adminCredential();
  const { home, work } = await isolatedWorkDir(`cargo-panel-yank-${version}`);
  await renderCargoConfig(work, names.repoName);
  const result = await run(
    'cargo',
    ['yank', ...(undo ? ['--undo'] : []), '--registry', 'repsy', '--version', version, names.name],
    {
      cwd: work,
      env: cargoEnv(home, credential),
      timeoutMs: 60_000,
      redact: [credential.password ?? ''],
      label: 'cargo-panel-yank',
    },
  );
  expect(result.exitCode, `cargo yank: ${result.command}`).toBe(0);
}

const README = '# The panel contract crate\n\nA README the panel shows.\n';

/**
 * Publishes the crate of `names.name` with a manifest of our own (metadata, features, a normal and a
 * development dependency on crates already in the repo) with the real `cargo publish`.
 */
async function publishRichCrate(names: Names, version: string): Promise<void> {
  const credential = adminCredential();
  const { home, work } = await isolatedWorkDir(`cargo-panel-publish-${version}`);
  await fs.mkdir(path.join(work, 'src'), { recursive: true });
  await fs.writeFile(path.join(work, 'src', 'lib.rs'), 'pub fn panel() {}\n');
  await fs.writeFile(path.join(work, 'README.md'), README);
  await fs.writeFile(
    path.join(work, 'Cargo.toml'),
    `[package]
name = "${names.name}"
version = "${version}"
edition = "2021"
authors = ["A One <a@example.com>", "B Two"]
description = "The panel contract crate"
license = "MIT OR Apache-2.0"
homepage = "https://example.com/home"
repository = "https://example.com/repo"
documentation = "https://example.com/docs"
readme = "README.md"
keywords = ["alpha", "beta"]
categories = ["development-tools"]
rust-version = "1.70"
publish = ["repsy"]

[features]
extra = []

[dependencies]
${names.dependency} = { version = "=1.0.0", registry = "repsy" }

[dev-dependencies]
${names.devDependency} = { version = "1", registry = "repsy" }
`,
  );
  await renderCargoConfig(work, names.repoName);
  const published = await run(
    'cargo',
    ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
    {
      cwd: work,
      env: { ...cargoEnv(home, credential), CARGO_PUBLISH_TIMEOUT: '30' },
      timeoutMs: 60_000,
      redact: [credential.password ?? ''],
      label: `cargo-panel-publish-${version}`,
    },
  );
  expect(published.exitCode, `cargo publish ${names.name}@${version}: ${published.command}`).toBe(
    0,
  );
}

test.describe('the Cargo panel API against what cargo publish stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the Cargo panel API', () => {
    expectCovers(EXERCISED, '/api/cargo/');
  });

  test('lists, gets and describes what cargo published, yanked and fetched, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    await cargo.seedPublish(worldOf(names, names.dependency, '1.0.0'));
    await cargo.seedPublish(worldOf(names, names.devDependency, '1.0.0'));
    const stable = '1.0.0';
    const rich = '1.1.0';
    const prerelease = '2.0.0-rc.1';
    const seeds = new Map<string, string | undefined>();
    seeds.set(stable, (await cargo.seedPublish(worldOf(names, names.name, stable))).contentSha256);
    await publishRichCrate(names, rich);
    seeds.set(
      prerelease,
      (await cargo.seedPublish(worldOf(names, names.name, prerelease))).contentSha256,
    );

    // The client-side facts: the sparse index lists the three versions, none yanked, and its checksum of the
    // plain versions is the digest of the .crate `cargo package` built.
    const index = parseIndex(
      (await rawGetIndex(names.repoName, adminCredential(), names.name)).body,
    );
    expect(index.map((entry) => entry.vers).sort()).toEqual([stable, rich, prerelease].sort());
    for (const version of [stable, prerelease]) {
      expect(index.find((entry) => entry.vers === version)?.cksum, `cksum of ${version}`).toBe(
        seeds.get(version),
      );
    }

    // `cargo yank` of the prerelease, then a real `cargo fetch` of the stable version (one download).
    await cargoYank(names, prerelease, false);
    expect(await indexEntries(names)).toEqual({
      [stable]: false,
      [rich]: false,
      [prerelease]: true,
    });
    const fetched = await cargo.resolve(worldOf(names, names.name, stable));
    expect(fetched.clientExitCode, fetched.command).toBe(0);
    expect(fetched.contentSha256).toBe(seeds.get(stable));

    // GET /api/cargo/crates/{repo}: the crate and its two dependencies, by name.
    const crates = expectContract(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: names.repoName }),
    ) as {
      content: {
        name: string;
        maxVersion: string;
        downloads: number;
        description: string;
        updatedAt: string;
      }[];
      page: { totalElements: number };
    };
    expect(crates.page.totalElements).toBe(3);
    expect(crates.content.map((row) => row.name).sort()).toEqual(
      [names.name, names.dependency, names.devDependency].sort(),
    );
    const listed = crates.content.find((row) => row.name === names.name);
    expect(listed).toMatchObject({ downloads: 1 });
    expect(Date.parse(listed?.updatedAt ?? ''), 'updatedAt').not.toBeNaN();

    // GET .../{crate}: what the newest published manifest says.
    const crate = expectContract(
      'getCargoCrate',
      await callOperation('getCargoCrate', { repoName: names.repoName, crateName: names.name }),
    ) as Record<string, unknown>;
    expect(crate).toMatchObject({
      name: names.name,
      originalName: names.name,
      hasLib: true,
      totalDownloads: 1,
      maxVersion: listed?.maxVersion,
    });
    // A `-` is a `_` to the panel, as to the sparse index.
    const hyphenated = expectContract(
      'getCargoCrate',
      await callOperation('getCargoCrate', {
        repoName: names.repoName,
        crateName: names.name.replaceAll('_', '-'),
      }),
    );
    expect(hyphenated).toEqual(crate);

    // GET .../versions: all three, the yank the client made shown on the prerelease only.
    const rows = await panelVersions(names);
    expect(rows.map((row) => row.version).sort()).toEqual([stable, rich, prerelease].sort());
    expect(Object.fromEntries(rows.map((row) => [row.version, row.yanked]))).toEqual({
      [stable]: false,
      [rich]: false,
      [prerelease]: true,
    });
    for (const row of rows) {
      expect(Date.parse(row.createdAt), `createdAt of ${row.version}`).not.toBeNaN();
    }

    // GET .../{crate}/{version}: the manifest cargo published, read back.
    const richDetail = expectContract(
      'getCargoCrateVersion',
      await callOperation('getCargoCrateVersion', values(names, rich)),
    ) as CrateVersionDetail;
    expect(richDetail).toMatchObject({
      name: names.name,
      version: rich,
      readme: README,
      license: 'MIT OR Apache-2.0',
      documentation: 'https://example.com/docs',
      edition: '2021',
      rustVersion: '1.70',
      downloads: 0,
      hasLib: true,
      yanked: false,
      deps: [
        {
          name: names.dependency,
          req: '=1.0.0',
          features: [],
          optional: false,
          defaultFeatures: true,
          kind: 'normal',
        },
        {
          name: names.devDependency,
          req: '^1',
          features: [],
          optional: false,
          defaultFeatures: true,
          kind: 'dev',
        },
      ],
    });
    expect(richDetail.crateId).toBe((crate as { id: string }).id);
    expect(richDetail.deps.map((dep) => dep.name).sort()).toEqual(
      (
        parseIndex((await rawGetIndex(names.repoName, adminCredential(), names.name)).body).find(
          (entry) => entry.vers === rich,
        )?.deps as { name: string }[]
      )
        .map((dep) => dep.name)
        .sort(),
    );

    const stableDetail = expectContract(
      'getCargoCrateVersion',
      await callOperation('getCargoCrateVersion', values(names, stable)),
    ) as CrateVersionDetail;
    expect(stableDetail).toMatchObject({
      version: stable,
      license: 'MIT',
      edition: '2021',
      deps: [],
      downloads: 1,
      hasLib: true,
      yanked: false,
    });
    const yankedDetail = expectContract(
      'getCargoCrateVersion',
      await callOperation('getCargoCrateVersion', values(names, prerelease)),
    ) as CrateVersionDetail;
    expect(yankedDetail, 'a yanked version is still described').toMatchObject({
      version: prerelease,
      yanked: true,
    });

    // `cargo yank --undo` gives the version back: nothing is yanked, and the newest version by semver order (a
    // prerelease of 2.0.0 is above 1.1.0) is what the list and the crate call `maxVersion`.
    await cargoYank(names, prerelease, true);
    const undone = expectContract(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: names.repoName }, { query: 'q=panel' }),
    ) as { content: { name: string; maxVersion: string }[] };
    expect(undone.content.find((row) => row.name === names.name)?.maxVersion).toBe(prerelease);
    expect(await indexEntries(names)).toEqual({
      [stable]: false,
      [rich]: false,
      [prerelease]: false,
    });
    expect((await panelVersions(names)).every((row) => !row.yanked)).toBe(true);
    expect(
      (
        expectContract(
          'getCargoCrateVersion',
          await callOperation('getCargoCrateVersion', values(names, prerelease)),
        ) as CrateVersionDetail
      ).yanked,
    ).toBe(false);
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    await cargo.seedPublish(worldOf(names, names.name, '1.0.0'));
    const missing = { ...values(names), crateName: 'e2e_no_such_crate' };

    expectFailure(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: names.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getCargoCrate',
      await callOperation('getCargoCrate', missing),
      404,
      'crateNotFound',
    );
    expectFailure(
      'listCargoCrateVersions',
      await callOperation('listCargoCrateVersions', missing),
      404,
      'crateNotFound',
    );
    expectFailure(
      'getCargoCrateVersion',
      await callOperation('getCargoCrateVersion', values(names, '9.9.9')),
      404,
      'crateVersionNotFound',
    );
    expectFailure(
      'deleteCargoCrateVersion',
      await callOperation('deleteCargoCrateVersion', values(names, '9.9.9')),
      404,
      'crateVersionNotFound',
    );
    expectFailure(
      'deleteCargoCrate',
      await callOperation('deleteCargoCrate', missing),
      404,
      'crateNotFound',
    );

    // Every failure above left the crate where it was.
    expect((await panelVersions(names)).map((row) => row.version)).toEqual(['1.0.0']);
    expect(await indexEntries(names)).toEqual({ '1.0.0': false });
  });

  test('pages, sorts and narrows the crate and version lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const repo = { name: names.repoName, type: RepoType.CARGO };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'searchCargoCrates',
      values: { repoName: names.repoName },
      total: 5,
      keyOf: (row) => row.name,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const narrowed = expectContract(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: names.repoName }, { query: 'q=pkg_3' }),
    ) as { content: { name: string }[] };
    expect(narrowed.content.map((row) => row.name)).toHaveLength(1);
    expect(narrowed.content[0]?.name).toContain('_pkg_3');

    // The versions of one crate, the second list operation with paging.
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage(repo, seeder, { name: names.name, version });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listCargoCrateVersions',
      values: values(names),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
    const oneVersion = expectContract(
      'listCargoCrateVersions',
      await callOperation('listCargoCrateVersions', values(names), { query: 'q=1.0.3' }),
    ) as { content: { version: string }[] };
    expect(oneVersion.content.map((row) => row.version)).toEqual(['1.0.3']);
  });

  test('deleting a version, then the crate, removes them from the wire and from cargo fetch', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const removed = '1.0.0';
    const kept = '1.1.0';
    const seeds = new Map<string, string | undefined>();
    for (const version of [removed, kept]) {
      seeds.set(
        version,
        (await cargo.seedPublish(worldOf(names, names.name, version))).contentSha256,
      );
    }
    expect(await indexEntries(names)).toEqual({ [removed]: false, [kept]: false });
    expect(await crateStatus(names, removed)).toBe(200);

    expectContract(
      'deleteCargoCrateVersion',
      await callOperation('deleteCargoCrateVersion', values(names, removed)),
    );

    expect((await panelVersions(names)).map((row) => row.version)).toEqual([kept]);
    expectFailure(
      'getCargoCrateVersion',
      await callOperation('getCargoCrateVersion', values(names, removed)),
      404,
      'crateVersionNotFound',
    );
    expect(await indexEntries(names), 'the sparse index drops the version').toEqual({
      [kept]: false,
    });
    expect(await crateStatus(names, removed)).toBe(404);
    expect(await crateStatus(names, kept)).toBe(200);
    const gone = await cargo.resolve(worldOf(names, names.name, removed));
    expect(gone.clientExitCode, `cargo fetch of the deleted version: ${gone.command}`).not.toBe(0);
    const resolved = await cargo.resolve(worldOf(names, names.name, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expect(
      (
        expectContract('getCargoCrate', await callOperation('getCargoCrate', values(names))) as {
          maxVersion: string;
        }
      ).maxVersion,
    ).toBe(kept);
    expectFailure(
      'deleteCargoCrateVersion',
      await callOperation('deleteCargoCrateVersion', values(names, removed)),
      404,
      'crateVersionNotFound',
    );

    // The last version takes the crate with it.
    expectContract(
      'deleteCargoCrateVersion',
      await callOperation('deleteCargoCrateVersion', values(names, kept)),
    );
    expectFailure(
      'getCargoCrate',
      await callOperation('getCargoCrate', values(names)),
      404,
      'crateNotFound',
    );
    expect(await indexEntries(names)).toBe(404);
    expect(await crateStatus(names, kept)).toBe(404);

    // Now the whole crate, on a fresh one with two versions.
    const whole = { ...names, name: `${names.name}_whole` };
    for (const version of ['1.0.0', '2.0.0']) {
      await cargo.seedPublish(worldOf(whole, whole.name, version));
    }
    expect(await indexEntries(whole)).toEqual({ '1.0.0': false, '2.0.0': false });
    expectContract('deleteCargoCrate', await callOperation('deleteCargoCrate', values(whole)));
    expect(await indexEntries(whole)).toBe(404);
    expect(await crateStatus(whole, '2.0.0')).toBe(404);
    const lost = await cargo.resolve(worldOf(whole, whole.name, '2.0.0'));
    expect(lost.clientExitCode, `cargo fetch of the deleted crate: ${lost.command}`).not.toBe(0);
    expectFailure(
      'getCargoCrate',
      await callOperation('getCargoCrate', values(whole)),
      404,
      'crateNotFound',
    );
    const rows = expectContract(
      'searchCargoCrates',
      await callOperation('searchCargoCrates', { repoName: names.repoName }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    expectFailure(
      'deleteCargoCrate',
      await callOperation('deleteCargoCrate', values(whole)),
      404,
      'crateNotFound',
    );
  });
});
