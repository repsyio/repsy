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
 * RPS-1483: the panel API of npm (`/api/npm/packages/...` and `/api/npm/scopes/...`, scoped and unscoped), against
 * what the REAL `npm publish` stored. Each response is validated against the response schema of
 * `openapi-spec.yaml` (`src/api/spec-contract.ts`: the schema, and no property the schema does not declare), and
 * the values are matched to the client-side facts: the package.json the client packed (name, scope, license,
 * description), the versions and dist-tags the packument on the wire lists, and what `npm deprecate` and
 * `npm dist-tag add` changed. The delete operations are judged by their effect on the wire: the packument
 * answers 404 (package) or drops the version, the tarball answers 404 and a real `npm install` fails.
 *
 * Copied from `tests/npm/unpublish.spec.ts` (the real client and the raw packument reads) and
 * `tests/maven/panel-api.spec.ts` (the shared contract helpers). The paging sweeps seed their rows over raw
 * HTTP (`seedPackage`): the pages are the subject there, not the client.
 */
import {
  callOperation,
  contractWorld,
  expectContract,
  expectCovers,
  expectFailure,
  expectPagingSweep,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import * as npm from '../../src/clients/npm.js';
import { npmAdapter } from '../../src/clients/npm.js';
import {
  adminCredential,
  parsePackument,
  rawGetPackument,
  rawGetTarballCanonical,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the npm panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'listNpmPackages',
  'listUnscopedNpmPackages',
  'listNpmPackagesByScope',
  'getNpmPackage',
  'getNpmScopedPackage',
  'getNpmPackageVersion',
  'getNpmScopedPackageVersion',
  'listNpmPackageVersions',
  'listNpmScopedPackageVersions',
  'listNpmPackageTags',
  'listNpmScopedPackageTags',
  'deleteNpmPackageVersion',
  'deleteNpmScopedPackageVersion',
  'deleteNpmPackage',
  'deleteScopedNpmPackage',
];

interface PackageRow {
  name: string;
  scope?: string;
  latestVersion: string;
}

interface VersionDetail {
  id: string;
  scopeName?: string;
  packageName: string;
  versionName: string;
  description?: string;
  license?: string;
  deprecated: boolean;
  deprecationMessage?: string;
  distributionTags: { tagName: string }[];
}

interface Names {
  repoName: string;
  /** `@e2e-<run>/scoped-lib` */
  scoped: string;
  /** `e2e-<run>-plain` */
  plain: string;
  /** `e2e-<run>` */
  scope: string;
}

const worldOf = (repoName: string, packageName: string, version: string) =>
  contractWorld('npm', repoName, packageName, version, adminCredential());

const publish = (names: Names, packageName: string, version: string) =>
  npm.seedPublish(worldOf(names.repoName, packageName, version));

async function newRepo(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
  return {
    repoName: repo.name,
    scoped: `@e2e-${seeder.runId}/scoped-lib`,
    plain: `e2e-${seeder.runId}-plain`,
    scope: `e2e-${seeder.runId}`,
  };
}

/** The path parameters of the operation that reads one package, scoped or not. */
const pathOf = (names: Names, packageName: string): Record<string, string> =>
  packageName.startsWith('@')
    ? {
        repoName: names.repoName,
        scope: names.scope,
        packageName: packageName.split('/')[1] as string,
      }
    : { repoName: names.repoName, packageName };

const opOf = (packageName: string, scopedOp: string, plainOp: string) =>
  packageName.startsWith('@') ? scopedOp : plainOp;

/** What the wire serves for a package: 404, or its versions and dist-tags. */
async function packument(names: Names, packageName: string) {
  const res = await rawGetPackument(names.repoName, adminCredential(), packageName);
  if (res.status !== 200) {
    return res.status;
  }
  const parsed = parsePackument(res.body);
  return {
    versions: Object.keys(parsed.versions).sort(),
    distTags: parsed.distTags,
    full: JSON.parse(res.body.toString('utf8')) as {
      versions: Record<string, { description?: string; license?: string }>;
    },
  };
}

async function tarball(names: Names, packageName: string, version: string): Promise<number> {
  return (await rawGetTarballCanonical(names.repoName, adminCredential(), packageName, version))
    .status;
}

async function detail(names: Names, packageName: string, version?: string): Promise<VersionDetail> {
  const operationId = version
    ? opOf(packageName, 'getNpmScopedPackageVersion', 'getNpmPackageVersion')
    : opOf(packageName, 'getNpmScopedPackage', 'getNpmPackage');
  const res = await callOperation(operationId, {
    ...pathOf(names, packageName),
    ...(version ? { version } : {}),
  });
  return expectContract(operationId, res) as VersionDetail;
}

async function listedVersions(names: Names, packageName: string): Promise<string[]> {
  const operationId = opOf(packageName, 'listNpmScopedPackageVersions', 'listNpmPackageVersions');
  const res = await callOperation(operationId, pathOf(names, packageName));
  const page = expectContract(operationId, res) as { content: { version: string }[] };
  return page.content.map((row) => row.version).sort();
}

test.describe('the npm panel API against what npm publish stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the npm panel API', () => {
    expectCovers(EXERCISED, '/api/npm/');
  });

  test('lists, gets and describes what npm published, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    const first = npmAdapter.version('release');
    const second = npmAdapter.version('release');
    await publish(names, names.scoped, first);
    await publish(names, names.scoped, second);
    await publish(names, names.plain, first);
    // The two client-side changes the panel has to show: a dist-tag on the older version, a deprecation.
    const tag = await npm.runNpmCommand(
      worldOf(names.repoName, names.scoped, first),
      ['dist-tag', 'add', `${names.scoped}@${first}`, 'beta'],
      'npm-panel-contract-tag',
    );
    expect(tag.exitCode, `${tag.command}\n${tag.stderr}`).toBe(0);
    const deprecate = await npm.runNpmCommand(
      worldOf(names.repoName, names.scoped, first),
      ['deprecate', `${names.scoped}@${first}`, 'superseded by the next version'],
      'npm-panel-contract-deprecate',
    );
    expect(deprecate.exitCode, `${deprecate.command}\n${deprecate.stderr}`).toBe(0);

    const wire = await packument(names, names.scoped);
    expect(wire, 'the packument of the scoped package').toMatchObject({
      versions: [first, second].sort(),
      distTags: { latest: second, beta: first },
    });

    // The three lists of packages: the repo (both), the scope (scoped only) and the unscoped ones.
    const all = expectContract(
      'listNpmPackages',
      await callOperation('listNpmPackages', { repoName: names.repoName }),
    ) as { content: PackageRow[]; page: { totalElements: number } };
    expect(all.page.totalElements).toBe(2);
    expect(all.content.find((row) => row.name === 'scoped-lib')).toMatchObject({
      scope: names.scope,
      latestVersion: second,
    });
    expect(all.content.find((row) => row.name === names.plain)).toMatchObject({
      latestVersion: first,
    });
    const scopeRows = expectContract(
      'listNpmPackagesByScope',
      await callOperation('listNpmPackagesByScope', {
        repoName: names.repoName,
        scope: names.scope,
      }),
    ) as { content: PackageRow[] };
    expect(scopeRows.content.map((row) => row.name)).toEqual(['scoped-lib']);
    const unscopedRows = expectContract(
      'listUnscopedNpmPackages',
      await callOperation('listUnscopedNpmPackages', { repoName: names.repoName }),
    ) as { content: PackageRow[] };
    expect(unscopedRows.content.map((row) => row.name)).toEqual([names.plain]);

    // The package (its newest version) and each version, scoped and unscoped.
    const scoped = await detail(names, names.scoped);
    expect(scoped).toMatchObject({
      scopeName: names.scope,
      packageName: 'scoped-lib',
      versionName: second,
      deprecated: false,
      license: 'MIT',
    });
    expect(scoped.description).toContain('Repsy e2e test package');
    expect(scoped.description, 'the description package.json carried').toBe(
      wire && typeof wire !== 'number' ? wire.full.versions[second]?.description : undefined,
    );
    expect(scoped.distributionTags.map((t) => t.tagName)).toEqual(['latest']);
    const plain = await detail(names, names.plain);
    expect(plain).toMatchObject({
      packageName: names.plain,
      versionName: first,
      deprecated: false,
    });
    expect(plain.scopeName ?? null).toBeNull();
    expect(await detail(names, names.plain, first)).toMatchObject({ versionName: first });

    const older = await detail(names, names.scoped, first);
    expect(older).toMatchObject({
      versionName: first,
      deprecated: true,
      deprecationMessage: 'superseded by the next version',
    });
    expect(older.distributionTags.map((t) => t.tagName)).toEqual(['beta']);
    expect(await detail(names, names.scoped, second)).toMatchObject({ deprecated: false });

    // The version lists: the versions the wire lists, with the deprecation flag.
    expect(await listedVersions(names, names.scoped)).toEqual([first, second].sort());
    expect(await listedVersions(names, names.plain)).toEqual([first]);
    const rows = expectContract(
      'listNpmScopedPackageVersions',
      await callOperation('listNpmScopedPackageVersions', pathOf(names, names.scoped)),
    ) as { content: { version: string; deprecated: boolean }[] };
    expect(rows.content.find((row) => row.version === first)?.deprecated).toBe(true);
    expect(rows.content.find((row) => row.version === second)?.deprecated).toBe(false);

    // The dist-tags: exactly the ones the packument on the wire has.
    const tags = expectContract(
      'listNpmScopedPackageTags',
      await callOperation('listNpmScopedPackageTags', pathOf(names, names.scoped)),
    ) as { tag: string; version: string }[];
    expect(Object.fromEntries(tags.map((t) => [t.tag, t.version]))).toEqual(
      typeof wire === 'number' ? {} : wire.distTags,
    );
    const plainTags = expectContract(
      'listNpmPackageTags',
      await callOperation('listNpmPackageTags', pathOf(names, names.plain)),
    ) as { tag: string; version: string }[];
    expect(plainTags).toEqual([{ tag: 'latest', version: first }]);
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    const version = npmAdapter.version('release');
    await publish(names, names.plain, version);
    const plain = pathOf(names, names.plain);
    const missing = { ...plain, packageName: 'no-such-package' };

    expectFailure(
      'listNpmPackages',
      await callOperation('listNpmPackages', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listNpmPackages',
      await callOperation('listNpmPackages', { repoName: names.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getNpmPackage',
      await callOperation('getNpmPackage', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'getNpmPackageVersion',
      await callOperation('getNpmPackageVersion', { ...plain, version: '9.9.9' }),
      404,
      'packageVersionNotFound',
    );
    expectFailure(
      'listNpmPackageTags',
      await callOperation('listNpmPackageTags', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'listNpmPackageVersions',
      await callOperation('listNpmPackageVersions', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'getNpmScopedPackage',
      await callOperation('getNpmScopedPackage', { ...missing, scope: 'no-such-scope' }),
      404,
      'packageNotFound',
    );
    expectFailure(
      'deleteNpmPackageVersion',
      await callOperation('deleteNpmPackageVersion', { ...plain, version: '9.9.9' }),
      404,
      'packageVersionNotFound',
    );
    expectFailure(
      'deleteNpmPackage',
      await callOperation('deleteNpmPackage', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'deleteScopedNpmPackage',
      await callOperation('deleteScopedNpmPackage', { ...missing, scope: 'no-such-scope' }),
      404,
      'packageNotFound',
    );
    expectFailure(
      'deleteNpmScopedPackageVersion',
      await callOperation('deleteNpmScopedPackageVersion', {
        ...missing,
        scope: 'no-such-scope',
        version: version,
      }),
      404,
      'packageNotFound',
    );

    // Every failure above left the package where it was.
    expect(await listedVersions(names, names.plain)).toEqual([version]);
  });

  test('pages, sorts and narrows the package and version lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage({ name: names.repoName, type: RepoType.NPM }, seeder, { index });
    }
    interface Row {
      name: string;
      scope?: string;
    }
    await expectPagingSweep<Row>({
      operationId: 'listNpmPackagesByScope',
      values: { repoName: names.repoName, scope: names.scope },
      total: 5,
      keyOf: (row) => `${row.scope}/${row.name}`,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const narrowed = expectContract(
      'listNpmPackagesByScope',
      await callOperation(
        'listNpmPackagesByScope',
        { repoName: names.repoName, scope: names.scope },
        { query: 'q=pkg-3' },
      ),
    ) as { content: Row[] };
    expect(narrowed.content.map((row) => row.name)).toEqual(['pkg-3']);

    // The versions of one package, the second list operation with paging.
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage({ name: names.repoName, type: RepoType.NPM }, seeder, {
        name: names.plain,
        version,
        scoped: false,
      });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listNpmPackageVersions',
      values: pathOf(names, names.plain),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
  });

  test('deleting a scoped version, then the scoped package, removes them from the wire and from npm', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    // Published in this order: npm refuses an older version once a newer one holds `latest`.
    const removed = npmAdapter.version('release');
    const kept = npmAdapter.version('release');
    const seeds = new Map<string, string | undefined>();
    for (const version of [removed, kept]) {
      seeds.set(version, (await publish(names, names.scoped, version)).contentSha256);
    }
    const path = pathOf(names, names.scoped);

    const res = await callOperation('deleteNpmScopedPackageVersion', { ...path, version: removed });
    expectContract('deleteNpmScopedPackageVersion', res);

    expect(await listedVersions(names, names.scoped)).toEqual([kept]);
    expectFailure(
      'getNpmScopedPackageVersion',
      await callOperation('getNpmScopedPackageVersion', { ...path, version: removed }),
      404,
      'packageVersionNotFound',
    );
    expect(await packument(names, names.scoped)).toMatchObject({
      versions: [kept],
      distTags: { latest: kept },
    });
    expect(await tarball(names, names.scoped, removed)).toBe(404);
    expect(await tarball(names, names.scoped, kept)).toBe(200);
    const gone = await npm.resolve(worldOf(names.repoName, names.scoped, removed));
    expect(gone.clientExitCode, `npm install of the deleted version: ${gone.command}`).not.toBe(0);
    const resolved = await npm.resolve(worldOf(names.repoName, names.scoped, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expectFailure(
      'deleteNpmScopedPackageVersion',
      await callOperation('deleteNpmScopedPackageVersion', { ...path, version: removed }),
      404,
      'packageVersionNotFound',
    );

    // Now the whole package.
    expectContract('deleteScopedNpmPackage', await callOperation('deleteScopedNpmPackage', path));
    expect(await packument(names, names.scoped), 'the packument').toBe(404);
    expect(await tarball(names, names.scoped, kept), 'the tarball').toBe(404);
    const lost = await npm.resolve(worldOf(names.repoName, names.scoped, kept));
    expect(lost.clientExitCode, `npm install of the deleted package: ${lost.command}`).not.toBe(0);
    expectFailure(
      'getNpmScopedPackage',
      await callOperation('getNpmScopedPackage', path),
      404,
      'packageNotFound',
    );
    const rows = expectContract(
      'listNpmPackagesByScope',
      await callOperation('listNpmPackagesByScope', {
        repoName: names.repoName,
        scope: names.scope,
      }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    expectFailure(
      'deleteScopedNpmPackage',
      await callOperation('deleteScopedNpmPackage', path),
      404,
      'packageNotFound',
    );
  });

  test('deleting the latest version of an unscoped package moves latest, deleting the package empties the wire', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    const older = npmAdapter.version('release');
    const newest = npmAdapter.version('release');
    const seeds = new Map<string, string | undefined>();
    for (const version of [older, newest]) {
      seeds.set(version, (await publish(names, names.plain, version)).contentSha256);
    }
    const path = pathOf(names, names.plain);
    expect(await packument(names, names.plain)).toMatchObject({ distTags: { latest: newest } });

    expectContract(
      'deleteNpmPackageVersion',
      await callOperation('deleteNpmPackageVersion', { ...path, version: newest }),
    );

    expect(await packument(names, names.plain)).toMatchObject({
      versions: [older],
      distTags: { latest: older },
    });
    expect(await detail(names, names.plain)).toMatchObject({ versionName: older });
    expect(await tarball(names, names.plain, newest)).toBe(404);
    const gone = await npm.resolve(worldOf(names.repoName, names.plain, newest));
    expect(gone.clientExitCode, gone.command).not.toBe(0);
    const resolved = await npm.resolve(worldOf(names.repoName, names.plain, older));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(older));

    expectContract('deleteNpmPackage', await callOperation('deleteNpmPackage', path));
    expect(await packument(names, names.plain)).toBe(404);
    expect(await tarball(names, names.plain, older)).toBe(404);
    const lost = await npm.resolve(worldOf(names.repoName, names.plain, older));
    expect(lost.clientExitCode, lost.command).not.toBe(0);
    expectFailure(
      'getNpmPackage',
      await callOperation('getNpmPackage', path),
      404,
      'packageNotFound',
    );
    expectFailure(
      'deleteNpmPackage',
      await callOperation('deleteNpmPackage', path),
      404,
      'packageNotFound',
    );
  });
});
