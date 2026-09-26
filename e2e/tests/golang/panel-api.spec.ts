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
 * RPS-1483: the panel API of Go (`/api/go/modules/...`), against what the REAL `curl -T` upload (the only publisher
 * Go has) stored. Each response is validated against the response schema of `openapi-spec.yaml`
 * (`src/api/spec-contract.ts`: the schema, and no property the schema does not declare), and the values are matched
 * to the client-side facts: the versions `@v/list` serves, the `@latest` version, the `go` directive of the
 * `go.mod` the client zipped, the module paths (a `/v2` module is a module of its own). The delete operations are
 * judged by their effect on the wire: `@v/list` drops the version (or answers 404), the `.zip` answers 404 and a
 * real `go mod download` fails, while the sibling version still downloads the bytes the client built.
 *
 * The one operation with no data is `checkGolangSumdbSupported`: the panel-port twin of the proxy's `sumdb/.../supported`,
 * which is a bare 404 by design, with or without credentials.
 *
 * Copied from `tests/golang/publish-consume.spec.ts` (the adapter's `seedPublish` and `resolve`) and
 * `tests/pypi/panel-api.spec.ts` (the shared contract helpers). The paging sweeps seed their rows over raw HTTP
 * (`seedPackage`): the pages are the subject there, not the client.
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
import * as go from '../../src/clients/golang.js';
import {
  adminCredential,
  latestRelPath,
  listRelPath,
  MODULE_DOMAIN,
  parseInfo,
  parseVersionList,
  rawGet,
  zipRelPath,
} from '../../src/clients/golang-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Go panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'listGolangModules',
  'searchGolangModules',
  'getGolangModuleInfo',
  'listGolangModuleVersions',
  'deleteGolangModule',
  'deleteGolangModuleVersion',
  'checkGolangSumdbSupported',
];

interface Names {
  repoName: string;
  /** `e2e.repsy.test/e2e-<run>-panel`. */
  modulePath: string;
}

interface VersionRow {
  id: string;
  version: string;
  createdAt: string;
  goVersion: string;
}

const worldOf = (names: Names, version: string, modulePath = names.modulePath) =>
  contractWorld('golang', names.repoName, modulePath, version, adminCredential());

async function newNames(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
  return { repoName: repo.name, modulePath: `${MODULE_DOMAIN}/e2e-${seeder.runId}-panel` };
}

/** The `modulePath` (and `version`) of an operation that names its module in the query string. */
const moduleQuery = (modulePath: string, version?: string): string =>
  `modulePath=${encodeURIComponent(modulePath)}${version ? `&version=${encodeURIComponent(version)}` : ''}`;

const repoOnly = (names: Names): Record<string, string> => ({ repoName: names.repoName });

/** The versions `@v/list` serves, or the status when it is not there. */
async function listedVersions(
  names: Names,
  modulePath = names.modulePath,
): Promise<string[] | number> {
  const res = await rawGet(names.repoName, adminCredential(), listRelPath(modulePath));
  return res.status === 200 ? parseVersionList(res.body).sort() : res.status;
}

async function zipStatus(
  names: Names,
  version: string,
  modulePath = names.modulePath,
): Promise<number> {
  return (await rawGet(names.repoName, adminCredential(), zipRelPath(modulePath, version))).status;
}

async function panelVersions(names: Names, modulePath = names.modulePath): Promise<VersionRow[]> {
  const res = await callOperation('listGolangModuleVersions', repoOnly(names), {
    query: `${moduleQuery(modulePath)}&size=100`,
  });
  return (expectContract('listGolangModuleVersions', res) as { content: VersionRow[] }).content;
}

test.describe('the Go panel API against what the curl upload stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the Go panel API', () => {
    expectCovers(EXERCISED, '/api/go/');
  });

  test('lists, gets and describes what curl uploaded, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const versions = ['v1.0.0', 'v1.1.0', 'v1.2.0-rc.1'];
    const sums = new Map<string, string | undefined>();
    for (const version of versions) {
      sums.set(version, (await go.seedPublish(worldOf(names, version))).contentSha256);
    }
    // A module of its own that shares the path prefix.
    const v2Path = `${names.modulePath}/v2`;
    await go.seedPublish(worldOf(names, 'v2.0.0', v2Path));

    // The client-side facts: `@v/list` names the versions, `@latest` the newest release.
    expect(await listedVersions(names)).toEqual([...versions].sort());
    expect(await listedVersions(names, v2Path)).toEqual(['v2.0.0']);
    const latest = await rawGet(names.repoName, adminCredential(), latestRelPath(names.modulePath));
    expect(latest.status).toBe(200);
    const restored = await go.resolve(worldOf(names, 'v1.1.0'));
    expect(restored.clientExitCode, restored.command).toBe(0);
    expect(restored.contentSha256).toBe(sums.get('v1.1.0'));

    // GET /api/go/modules/{repo}: the module and its /v2 module, each a row of its own.
    const modules = expectContract(
      'listGolangModules',
      await callOperation('listGolangModules', repoOnly(names)),
    ) as {
      content: { id: string; createdAt: string; modulePath: string }[];
      page: { totalElements: number };
    };
    expect(modules.page.totalElements).toBe(2);
    expect(modules.content.map((row) => row.modulePath).sort()).toEqual(
      [names.modulePath, v2Path].sort(),
    );
    for (const row of modules.content) {
      expect(Date.parse(row.createdAt), `createdAt of ${row.modulePath}`).not.toBeNaN();
    }

    // GET .../search?q=: by a part of the path.
    const found = expectContract(
      'searchGolangModules',
      await callOperation('searchGolangModules', repoOnly(names), { query: 'q=panel' }),
    ) as { content: { modulePath: string }[] };
    expect(found.content.map((row) => row.modulePath).sort()).toEqual(
      [names.modulePath, v2Path].sort(),
    );
    const none = expectContract(
      'searchGolangModules',
      await callOperation('searchGolangModules', repoOnly(names), { query: 'q=no-such-module' }),
    ) as { content: unknown[] };
    expect(none.content).toEqual([]);

    // GET .../versions?modulePath=: every version with the `go` directive of the go.mod the client zipped.
    const rows = await panelVersions(names);
    expect(rows.map((row) => row.version).sort()).toEqual([...versions].sort());
    for (const row of rows) {
      expect(row.goVersion, `go directive of ${row.version}`).toBe('1.21');
      expect(Date.parse(row.createdAt), `createdAt of ${row.version}`).not.toBeNaN();
    }
    expect((await panelVersions(names, v2Path)).map((row) => row.version)).toEqual(['v2.0.0']);

    // GET .../info?modulePath=: the module, its newest version and every version.
    const info = expectContract(
      'getGolangModuleInfo',
      await callOperation('getGolangModuleInfo', repoOnly(names), {
        query: moduleQuery(names.modulePath),
      }),
    ) as {
      id: string;
      modulePath: string;
      latestVersion: string;
      createdAt: string;
      versions: VersionRow[];
    };
    expect(info).toMatchObject({ modulePath: names.modulePath });
    expect(info.id).toBe(modules.content.find((row) => row.modulePath === names.modulePath)?.id);
    expect(info.versions.map((row) => row.version).sort()).toEqual([...versions].sort());
    expect(info.latestVersion, '@latest and the panel agree').toBe(parseInfo(latest.body).Version);
    const v2Info = expectContract(
      'getGolangModuleInfo',
      await callOperation('getGolangModuleInfo', repoOnly(names), { query: moduleQuery(v2Path) }),
    ) as { modulePath: string; latestVersion: string };
    expect(v2Info).toMatchObject({ modulePath: v2Path, latestVersion: 'v2.0.0' });

    // GET .../sumdb/supported: a bare 404 by design, needing no credentials.
    for (const anonymous of [false, true]) {
      const res = await callOperation('checkGolangSumdbSupported', repoOnly(names), { anonymous });
      expect(res.status, `checkGolangSumdbSupported (anonymous ${anonymous})`).toBe(404);
      expect(res.text).toBe('');
    }
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    await go.seedPublish(worldOf(names, 'v1.0.0'));
    const missing = moduleQuery(`${MODULE_DOMAIN}/e2e-no-such-module`);

    expectFailure(
      'listGolangModules',
      await callOperation('listGolangModules', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listGolangModules',
      await callOperation('listGolangModules', repoOnly(names), { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getGolangModuleInfo',
      await callOperation('getGolangModuleInfo', repoOnly(names), { query: missing }),
      404,
      'moduleNotFound',
    );
    expectFailure(
      'listGolangModuleVersions',
      await callOperation('listGolangModuleVersions', repoOnly(names), { query: missing }),
      404,
      'moduleNotFound',
    );
    expectFailure(
      'deleteGolangModuleVersion',
      await callOperation('deleteGolangModuleVersion', repoOnly(names), {
        query: moduleQuery(names.modulePath, 'v9.9.9'),
      }),
      404,
      'versionNotFound',
    );
    expectFailure(
      'deleteGolangModuleVersion',
      await callOperation('deleteGolangModuleVersion', repoOnly(names), {
        query: moduleQuery(`${MODULE_DOMAIN}/e2e-no-such-module`, 'v1.0.0'),
      }),
      404,
      'moduleNotFound',
    );
    expectFailure(
      'deleteGolangModule',
      await callOperation('deleteGolangModule', repoOnly(names), { query: missing }),
      404,
      'moduleNotFound',
    );

    // The module path is required: a request without it is a 400, not a server error.
    for (const operationId of [
      'getGolangModuleInfo',
      'listGolangModuleVersions',
      'deleteGolangModule',
    ]) {
      expectContract(operationId, await callOperation(operationId, repoOnly(names)), 400);
    }

    // Every failure above left the module where it was.
    expect((await panelVersions(names)).map((row) => row.version)).toEqual(['v1.0.0']);
    expect(await listedVersions(names)).toEqual(['v1.0.0']);
  });

  test('pages, sorts and narrows the module and version lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const repo = { name: names.repoName, type: RepoType.GOLANG };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ modulePath: string }>({
      operationId: 'listGolangModules',
      values: repoOnly(names),
      total: 5,
      keyOf: (row) => row.modulePath,
      sorts: [{ property: 'modulePath', value: (row) => row.modulePath }],
    });
    await expectPagingSweep<{ modulePath: string }>({
      operationId: 'searchGolangModules',
      values: repoOnly(names),
      baseQuery: 'q=pkg',
      total: 5,
      keyOf: (row) => row.modulePath,
      sorts: [{ property: 'modulePath', value: (row) => row.modulePath }],
    });
    const narrowed = expectContract(
      'searchGolangModules',
      await callOperation('searchGolangModules', repoOnly(names), { query: 'q=pkg-3' }),
    ) as { content: { modulePath: string }[] };
    expect(narrowed.content).toHaveLength(1);
    expect(narrowed.content[0]?.modulePath).toContain('pkg-3');

    // The versions of one module, the third list operation with paging.
    for (const version of ['v1.0.1', 'v1.0.2', 'v1.0.3', 'v1.0.4', 'v1.0.5']) {
      await seedPackage(repo, seeder, { name: names.modulePath, version });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listGolangModuleVersions',
      values: repoOnly(names),
      baseQuery: moduleQuery(names.modulePath),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
    const oneVersion = expectContract(
      'listGolangModuleVersions',
      await callOperation('listGolangModuleVersions', repoOnly(names), {
        query: `${moduleQuery(names.modulePath)}&q=v1.0.3`,
      }),
    ) as { content: { version: string }[] };
    expect(oneVersion.content.map((row) => row.version)).toEqual(['v1.0.3']);
  });

  test('deleting a version, then the module, removes them from the wire and from go mod download', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const removed = 'v1.0.0';
    const kept = 'v1.1.0';
    const seeds = new Map<string, string | undefined>();
    for (const version of [removed, kept]) {
      seeds.set(version, (await go.seedPublish(worldOf(names, version))).contentSha256);
    }
    // The /v2 module shares the path prefix: deleting the module must leave it alone.
    const v2Path = `${names.modulePath}/v2`;
    await go.seedPublish(worldOf(names, 'v2.0.0', v2Path));
    expect(await listedVersions(names)).toEqual([removed, kept]);
    expect(await zipStatus(names, removed)).toBe(200);

    expectContract(
      'deleteGolangModuleVersion',
      await callOperation('deleteGolangModuleVersion', repoOnly(names), {
        query: moduleQuery(names.modulePath, removed),
      }),
    );

    expect((await panelVersions(names)).map((row) => row.version)).toEqual([kept]);
    expect(await listedVersions(names), '@v/list drops the version').toEqual([kept]);
    expect(await zipStatus(names, removed)).toBe(404);
    expect(await zipStatus(names, kept)).toBe(200);
    const gone = await go.resolve(worldOf(names, removed));
    expect(gone.clientExitCode, `go mod download of the deleted version: ${gone.command}`).not.toBe(
      0,
    );
    const resolved = await go.resolve(worldOf(names, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expectFailure(
      'deleteGolangModuleVersion',
      await callOperation('deleteGolangModuleVersion', repoOnly(names), {
        query: moduleQuery(names.modulePath, removed),
      }),
      404,
      'versionNotFound',
    );

    // Now the whole module: both the panel and the wire forget it, and the /v2 module stays.
    expectContract(
      'deleteGolangModule',
      await callOperation('deleteGolangModule', repoOnly(names), {
        query: moduleQuery(names.modulePath),
      }),
    );
    expectFailure(
      'getGolangModuleInfo',
      await callOperation('getGolangModuleInfo', repoOnly(names), {
        query: moduleQuery(names.modulePath),
      }),
      404,
      'moduleNotFound',
    );
    expect(await listedVersions(names)).toBe(404);
    expect(await zipStatus(names, kept)).toBe(404);
    const lost = await go.resolve(worldOf(names, kept));
    expect(lost.clientExitCode, `go mod download of the deleted module: ${lost.command}`).not.toBe(
      0,
    );
    expect(await listedVersions(names, v2Path)).toEqual(['v2.0.0']);
    const v2 = await go.resolve(worldOf(names, 'v2.0.0', v2Path));
    expect(v2.clientExitCode, v2.command).toBe(0);
    const rows = expectContract(
      'listGolangModules',
      await callOperation('listGolangModules', repoOnly(names)),
    ) as { content: { modulePath: string }[] };
    expect(rows.content.map((row) => row.modulePath)).toEqual([v2Path]);
    expectFailure(
      'deleteGolangModule',
      await callOperation('deleteGolangModule', repoOnly(names), {
        query: moduleQuery(names.modulePath),
      }),
      404,
      'moduleNotFound',
    );

    // The last version of a module takes the module with it.
    expectContract(
      'deleteGolangModuleVersion',
      await callOperation('deleteGolangModuleVersion', repoOnly(names), {
        query: moduleQuery(v2Path, 'v2.0.0'),
      }),
    );
    expectFailure(
      'getGolangModuleInfo',
      await callOperation('getGolangModuleInfo', repoOnly(names), { query: moduleQuery(v2Path) }),
      404,
      'moduleNotFound',
    );
    expect(await listedVersions(names, v2Path)).toBe(404);
    const empty = expectContract(
      'listGolangModules',
      await callOperation('listGolangModules', repoOnly(names)),
    ) as { content: unknown[] };
    expect(empty.content).toEqual([]);
  });
});
