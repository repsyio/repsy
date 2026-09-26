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
 * RPS-1483: the panel API of PyPI (`/api/pypi/packages/...`), against what the REAL `twine upload` stored. Each
 * response is validated against the response schema of `openapi-spec.yaml` (`src/api/spec-contract.ts`: the
 * schema, and no property the schema does not declare), and the values are matched to the client-side facts: the
 * name, the versions and their PEP 440 kind (final, pre, post, dev), the `Summary` and `Requires-Python` the wheel's
 * METADATA carried, and the files the PEP 503 project page lists. The delete operations are judged by their effect
 * on the wire: the project page drops the file (or answers 404), the file answers 404 and a real `pip download`
 * fails.
 *
 * Copied from `tests/pypi/protocol-specific.spec.ts` (the real twine/pip and the raw project-page reads) and
 * `tests/maven/panel-api.spec.ts` (the shared contract helpers). The paging sweeps seed their rows over raw HTTP
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
import * as pypi from '../../src/clients/pypi.js';
import {
  adminCredential,
  parseSimplePage,
  rawDownload,
  rawGetSimplePage,
  wheelFilename,
} from '../../src/clients/pypi-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the PyPI panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'listPypiPackages',
  'getPypiPackage',
  'listPypiReleases',
  'getPypiRelease',
  'deletePypiRelease',
  'deletePypiPackage',
];

interface Names {
  repoName: string;
  /** `e2e-<run>-panel`, already PEP 503 normalized. */
  name: string;
}

type ReleaseFlags = {
  finalRelease: boolean;
  preRelease: boolean;
  postRelease: boolean;
  devRelease: boolean;
};

interface ReleaseDetail extends ReleaseFlags {
  id: string;
  packageName: string;
  stableVersion: string;
  version: string;
  requiresPython: string;
  summary: string;
  classifiers: unknown[];
  projectUrls: unknown[];
}

const worldOf = (names: Names, version: string) =>
  contractWorld('pypi', names.repoName, names.name, version, adminCredential());

const publish = (names: Names, version: string) => pypi.seedPublish(worldOf(names, version));

async function newRepo(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
  return { repoName: repo.name, name: `e2e-${seeder.runId}-panel` };
}

const values = (names: Names, version?: string): Record<string, string> => ({
  repoName: names.repoName,
  packageName: names.name,
  ...(version ? { version } : {}),
});

/** The files the PEP 503 project page lists, or the status when it is not there. */
async function projectFiles(names: Names): Promise<string[] | number> {
  const res = await rawGetSimplePage(names.repoName, adminCredential(), names.name);
  return res.status === 200
    ? parseSimplePage(res.body)
        .map((link) => link.filename)
        .sort()
    : res.status;
}

async function fileStatus(names: Names, version: string): Promise<number> {
  return (
    await rawDownload(
      names.repoName,
      adminCredential(),
      names.name,
      wheelFilename(names.name, version),
    )
  ).status;
}

async function releaseVersions(names: Names): Promise<string[]> {
  const res = await callOperation('listPypiReleases', values(names));
  const page = expectContract('listPypiReleases', res) as { content: { version: string }[] };
  return page.content.map((row) => row.version).sort();
}

test.describe('the PyPI panel API against what twine upload stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the PyPI panel API', () => {
    expectCovers(EXERCISED, '/api/pypi/');
  });

  test('lists, gets and describes what twine uploaded, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    // One release of each PEP 440 kind, uploaded in this order: the last one is the newest upload.
    const kinds: [string, ReleaseFlags][] = [
      ['1.0.0', { finalRelease: true, preRelease: false, postRelease: false, devRelease: false }],
      [
        '1.1.0rc1',
        { finalRelease: false, preRelease: true, postRelease: false, devRelease: false },
      ],
      [
        '1.0.0.post1',
        { finalRelease: false, preRelease: false, postRelease: true, devRelease: false },
      ],
      [
        '1.2.0.dev1',
        { finalRelease: false, preRelease: false, postRelease: false, devRelease: true },
      ],
    ];
    for (const [version] of kinds) {
      await publish(names, version);
    }
    const versions = kinds.map(([version]) => version).sort();
    const files = kinds.map(([version]) => wheelFilename(names.name, version)).sort();
    expect(await projectFiles(names), 'the project page on the wire').toEqual(files);

    // GET /api/pypi/packages/{repo}: one row for the package, the newest upload as `latest`, the newest final release as `stable`.
    const packages = expectContract(
      'listPypiPackages',
      await callOperation('listPypiPackages', { repoName: names.repoName }),
    ) as { content: Record<string, string>[]; page: { totalElements: number } };
    expect(packages.page.totalElements).toBe(1);
    expect(packages.content[0]).toMatchObject({
      name: names.name,
      stableVersion: '1.0.0',
      latestVersion: '1.2.0.dev1',
    });

    // GET .../releases: every uploaded version with its kind.
    const releases = expectContract(
      'listPypiReleases',
      await callOperation('listPypiReleases', values(names)),
    ) as { content: ({ version: string } & ReleaseFlags)[] };
    expect(releases.content.map((row) => row.version).sort()).toEqual(versions);
    for (const [version, flags] of kinds) {
      expect(
        releases.content.find((row) => row.version === version),
        version,
      ).toMatchObject(flags);
    }

    // GET .../releases/{version}: each release's detail, the METADATA of the wheel twine sent.
    for (const [version, flags] of kinds) {
      const release = expectContract(
        'getPypiRelease',
        await callOperation('getPypiRelease', values(names, version)),
      ) as ReleaseDetail;
      expect(release, version).toMatchObject({
        ...flags,
        packageName: names.name,
        version,
        stableVersion: '1.0.0',
        requiresPython: '>=3.9',
        summary: `e2e ${names.name}@${version}`,
        classifiers: [],
        projectUrls: [],
      });
    }

    // GET .../{package}: the newest upload's detail.
    const latest = expectContract(
      'getPypiPackage',
      await callOperation('getPypiPackage', values(names)),
    ) as ReleaseDetail;
    expect(latest).toMatchObject({
      packageName: names.name,
      version: '1.2.0.dev1',
      devRelease: true,
    });
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    await publish(names, '1.0.0');
    const missing = { ...values(names), packageName: 'no-such-package' };

    expectFailure(
      'listPypiPackages',
      await callOperation('listPypiPackages', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listPypiPackages',
      await callOperation('listPypiPackages', { repoName: names.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getPypiPackage',
      await callOperation('getPypiPackage', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'listPypiReleases',
      await callOperation('listPypiReleases', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'getPypiRelease',
      await callOperation('getPypiRelease', values(names, '9.9.9')),
      404,
      'releaseNotFound',
    );
    expectFailure(
      'deletePypiRelease',
      await callOperation('deletePypiRelease', values(names, '9.9.9')),
      404,
      'releaseNotFound',
    );
    expectFailure(
      'deletePypiPackage',
      await callOperation('deletePypiPackage', missing),
      404,
      'packageNotFound',
    );

    // Every failure above left the package where it was.
    expect(await releaseVersions(names)).toEqual(['1.0.0']);
  });

  test('pages, sorts and narrows the package and release lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    const repo = { name: names.repoName, type: RepoType.PYPI };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'listPypiPackages',
      values: { repoName: names.repoName },
      total: 5,
      keyOf: (row) => row.name,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const narrowed = expectContract(
      'listPypiPackages',
      await callOperation('listPypiPackages', { repoName: names.repoName }, { query: 'q=pkg-3' }),
    ) as { content: { name: string }[] };
    expect(narrowed.content.map((row) => row.name)).toEqual([`e2e-${seeder.runId}-pkg-3`]);

    // The releases of one package, the second list operation with paging.
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage(repo, seeder, { name: names.name, version });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listPypiReleases',
      values: values(names),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
  });

  test('deleting a release, then the package, removes them from the wire and from pip', async ({
    seeder,
  }) => {
    const names = await newRepo(seeder);
    const removed = '1.0.0';
    const kept = '1.1.0';
    const seeds = new Map<string, string | undefined>();
    for (const version of [removed, kept]) {
      seeds.set(version, (await publish(names, version)).contentSha256);
    }
    expect(await projectFiles(names)).toEqual(
      [removed, kept].map((version) => wheelFilename(names.name, version)).sort(),
    );

    expectContract(
      'deletePypiRelease',
      await callOperation('deletePypiRelease', values(names, removed)),
    );

    expect(await releaseVersions(names)).toEqual([kept]);
    expectFailure(
      'getPypiRelease',
      await callOperation('getPypiRelease', values(names, removed)),
      404,
      'releaseNotFound',
    );
    expect(await projectFiles(names), 'the project page drops the file').toEqual([
      wheelFilename(names.name, kept),
    ]);
    expect(await fileStatus(names, removed)).toBe(404);
    expect(await fileStatus(names, kept)).toBe(200);
    const gone = await pypi.resolve(worldOf(names, removed));
    expect(gone.clientExitCode, `pip download of the deleted release: ${gone.command}`).not.toBe(0);
    const resolved = await pypi.resolve(worldOf(names, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expect(
      expectContract('getPypiPackage', await callOperation('getPypiPackage', values(names))),
    ).toMatchObject({ version: kept });
    expectFailure(
      'deletePypiRelease',
      await callOperation('deletePypiRelease', values(names, removed)),
      404,
      'releaseNotFound',
    );

    // Now the whole package.
    expectContract('deletePypiPackage', await callOperation('deletePypiPackage', values(names)));
    expect(await projectFiles(names), 'the project page').toBe(404);
    expect(await fileStatus(names, kept)).toBe(404);
    const lost = await pypi.resolve(worldOf(names, kept));
    expect(lost.clientExitCode, `pip download of the deleted package: ${lost.command}`).not.toBe(0);
    expectFailure(
      'getPypiPackage',
      await callOperation('getPypiPackage', values(names)),
      404,
      'packageNotFound',
    );
    const rows = expectContract(
      'listPypiPackages',
      await callOperation('listPypiPackages', { repoName: names.repoName }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    expectFailure(
      'deletePypiPackage',
      await callOperation('deletePypiPackage', values(names)),
      404,
      'packageNotFound',
    );
  });
});
