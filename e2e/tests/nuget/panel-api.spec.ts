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
 * RPS-1483: the panel API of NuGet (`/api/nuget/packages/...`), against what the REAL `dotnet nuget push` stored.
 * Each response is validated against the response schema of `openapi-spec.yaml` (`src/api/spec-contract.ts`: the
 * schema, and no property the schema does not declare), and the values are matched to the client-side facts: the
 * versions the flat container lists, the `listed` flag the registration carries (flipped by a real `dotnet nuget
 * delete`), the nuspec metadata the client pushed (title, tags, URLs, README, dependencies) and the downloads a
 * real `dotnet restore` made. The delete operations are judged by their effect on the wire: the flat container
 * and the registration drop the version (or answer 404), the `.nupkg` answers 404 and a real `dotnet restore`
 * fails, while the sibling version still restores to the bytes the client built.
 *
 * Copied from `tests/nuget/transitive-resolution.spec.ts` (the real push of a nupkg with a nuspec of our own),
 * `src/clients/nuget-manage.ts` (`dotnet nuget delete`) and `tests/pypi/panel-api.spec.ts` (the shared contract
 * helpers). The paging sweeps seed their rows over raw HTTP (`seedPackage`): the pages are the subject there,
 * not the client.
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
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import * as nuget from '../../src/clients/nuget.js';
import { nugetEnv, renderNugetConfig } from '../../src/clients/nuget.js';
import { dotnetNugetDelete } from '../../src/clients/nuget-manage.js';
import {
  adminCredential,
  buildNupkg,
  type NuspecDependency,
  type NuspecExtraMetadata,
  nugetApiKey,
  parseRegistrationIndex,
  parseVersions,
  rawDownloadNupkg,
  rawGetRegistrationIndex,
  rawGetVersions,
  sha256Hex,
} from '../../src/clients/nuget-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the NuGet panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'searchNugetPackages',
  'getNugetPackage',
  'listNugetVersions',
  'getNugetVersion',
  'deleteNugetVersion',
  'deleteNugetPackage',
];

interface Names {
  repoName: string;
  /** The package id as the nuspec spells it (mixed case, the wire lower-cases it). */
  id: string;
  /** The package this one depends on. */
  dependencyId: string;
}

interface VersionRow {
  version: string;
  publishedAt: string;
  downloads: number;
  prerelease: boolean;
  listed: boolean;
}

interface VersionDetail {
  packageId: string;
  version: string;
  title?: string;
  description: string;
  authors: string;
  tags?: string;
  iconUrl?: string;
  licenseUrl?: string;
  projectUrl?: string;
  repositoryUrl?: string;
  readme?: string;
  listed: boolean;
  downloadCount: number;
  publishedAt: string;
  dependencies: { packageId: string; versionRange?: string; targetFramework?: string }[];
}

const worldOf = (names: Names, id: string, version: string) =>
  contractWorld('nuget', names.repoName, id, version, adminCredential());

async function newNames(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
  return {
    repoName: repo.name,
    id: `E2E.${seeder.runId}.Panel`,
    dependencyId: `E2E.${seeder.runId}.Dep`,
  };
}

/** Builds a nupkg (its nuspec carries what is given) and pushes it with the real `dotnet nuget push`; returns the bytes pushed. */
async function pushPackage(
  names: Names,
  pkg: {
    id: string;
    version: string;
    metadata?: NuspecExtraMetadata;
    dependencies?: NuspecDependency[];
  },
): Promise<Buffer> {
  const nupkg = buildNupkg({
    packageId: pkg.id,
    version: pkg.version,
    ...(pkg.metadata === undefined ? {} : { metadata: pkg.metadata }),
    ...(pkg.dependencies === undefined ? {} : { dependencies: pkg.dependencies }),
  });
  const { home, work } = await isolatedWorkDir(`nuget-panel-push-${pkg.version}`);
  const file = path.join(work, 'package.nupkg');
  await fs.writeFile(file, nupkg);
  const credential = adminCredential();
  const apiKey = nugetApiKey(credential);
  const args = [
    'nuget',
    'push',
    file,
    '--source',
    'repsy',
    '--configfile',
    await renderNugetConfig(home, names.repoName, credential),
    '--allow-insecure-connections',
    '--no-symbols',
    '--timeout',
    '60',
  ];
  if (apiKey !== undefined) {
    args.push('--api-key', apiKey);
  }
  const pushed = await run('dotnet', args, {
    cwd: work,
    env: nugetEnv(home),
    timeoutMs: 120_000,
    redact: [credential.password, apiKey].filter((s): s is string => Boolean(s)),
    label: `nuget-panel-push-${pkg.version}`,
  });
  expect(pushed.exitCode, `dotnet nuget push ${pkg.id}@${pkg.version}: ${pushed.command}`).toBe(0);
  return nupkg;
}

const values = (names: Names, version?: string): Record<string, string> => ({
  repoName: names.repoName,
  packageName: names.id,
  ...(version ? { version } : {}),
});

/** The versions the flat container lists, or the status when it is not there. */
async function flatVersions(names: Names, id = names.id): Promise<string[] | number> {
  const res = await rawGetVersions(names.repoName, adminCredential(), id.toLowerCase());
  return res.status === 200 ? parseVersions(res.body).sort() : res.status;
}

/** The `listed` flag of every leaf of the registration, or the status when it is not there. */
async function registrationListed(
  names: Names,
  id = names.id,
): Promise<Record<string, boolean> | number> {
  const res = await rawGetRegistrationIndex(names.repoName, adminCredential(), id.toLowerCase());
  return res.status === 200
    ? Object.fromEntries(
        parseRegistrationIndex(res.body).map((leaf) => [leaf.version, leaf.listed]),
      )
    : res.status;
}

async function nupkgStatus(names: Names, version: string, id = names.id): Promise<number> {
  return (await rawDownloadNupkg(names.repoName, adminCredential(), id.toLowerCase(), version))
    .status;
}

async function panelVersions(names: Names): Promise<VersionRow[]> {
  const res = await callOperation('listNugetVersions', values(names));
  return (expectContract('listNugetVersions', res) as { content: VersionRow[] }).content;
}

test.describe('the NuGet panel API against what dotnet nuget push stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the NuGet panel API', () => {
    expectCovers(EXERCISED, '/api/nuget/');
  });

  test('lists, gets and describes what dotnet pushed, unlisted and restored, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const stable = '1.0.0';
    const beta = '2.0.0-beta.1';
    const newest = '3.0.0.0';
    const newestNormalized = '3.0.0';
    const readme = `# ${names.id}\n\nA README of the e2e panel contract.\n`;
    const metadata: NuspecExtraMetadata = {
      title: 'Panel Contract Package',
      tags: 'e2e panel contract',
      iconUrl: 'https://example.com/icon.png',
      licenseUrl: 'https://example.com/license',
      projectUrl: 'https://example.com/project',
      repositoryUrl: 'https://example.com/repo.git',
      readme,
    };
    const dependencies: NuspecDependency[] = [
      { id: names.dependencyId, range: '[1.0.0, )', targetFramework: 'net10.0' },
      { id: 'Some.Other.Package', range: '2.0.0' },
    ];

    await nuget.seedPublish(worldOf(names, names.dependencyId, '1.0.0'));
    const seeds = new Map<string, Buffer>();
    seeds.set(stable, await pushPackage(names, { id: names.id, version: stable }));
    seeds.set(beta, await pushPackage(names, { id: names.id, version: beta }));
    seeds.set(
      newest,
      await pushPackage(names, { id: names.id, version: newest, metadata, dependencies }),
    );
    // The client-side facts: the flat container and the registration know the same versions (the four-part
    // 3.0.0.0 is 3.0.0 to NuGet).
    const expectedVersions = [stable, beta, newestNormalized].sort();
    expect(await flatVersions(names)).toEqual(expectedVersions);
    expect(await registrationListed(names)).toEqual({
      [stable]: true,
      [beta]: true,
      [newestNormalized]: true,
    });

    // `dotnet nuget delete` is NuGet's "unlist": it flips `listed` on the beta and deletes nothing.
    const unlisted = await dotnetNugetDelete(names.repoName, adminCredential(), names.id, beta);
    expect(unlisted.exitCode, `dotnet nuget delete: ${unlisted.command}`).toBe(0);
    expect(await registrationListed(names)).toEqual({
      [stable]: true,
      [beta]: false,
      [newestNormalized]: true,
    });

    // A real restore of the stable version: the one download the panel counts.
    const restoredStable = await nuget.resolve(worldOf(names, names.id, stable));
    expect(restoredStable.clientExitCode, restoredStable.command).toBe(0);
    expect(restoredStable.contentSha256).toBe(sha256Hex(seeds.get(stable) as Buffer));

    // GET /api/nuget/packages/{repo}: the two packages, by id (the wire spells it in lower case).
    const packages = expectContract(
      'searchNugetPackages',
      await callOperation('searchNugetPackages', { repoName: names.repoName }),
    ) as {
      content: {
        packageId: string;
        latestVersion: string;
        description: string;
        totalDownloads: number;
      }[];
      page: { totalElements: number };
    };
    expect(packages.page.totalElements).toBe(2);
    expect(packages.content.map((row) => row.packageId.toLowerCase()).sort()).toEqual(
      [names.id, names.dependencyId].map((id) => id.toLowerCase()).sort(),
    );
    const listed = packages.content.find(
      (row) => row.packageId.toLowerCase() === names.id.toLowerCase(),
    );
    expect(listed).toMatchObject({
      latestVersion: newestNormalized,
      description: `e2e ${names.id}@${newest}`,
      totalDownloads: 1,
    });

    // GET .../{package}: the newest version's metadata.
    const detail = expectContract(
      'getNugetPackage',
      await callOperation('getNugetPackage', { repoName: names.repoName, packageName: names.id }),
    ) as Record<string, unknown>;
    expect(detail).toMatchObject({
      title: metadata.title,
      description: `e2e ${names.id}@${newest}`,
      authors: 'repsy-e2e',
      tags: metadata.tags,
      iconUrl: metadata.iconUrl,
      licenseUrl: metadata.licenseUrl,
      projectUrl: metadata.projectUrl,
      latestVersion: newestNormalized,
      totalDownloads: 1,
    });

    expect((detail.packageId as string).toLowerCase(), 'the package id, in any spelling').toBe(
      names.id.toLowerCase(),
    );

    // GET .../versions: every pushed version, the beta as a prerelease and unlisted, the one restore counted.
    const rows = await panelVersions(names);
    expect(rows.map((row) => row.version).sort()).toEqual(expectedVersions);
    expect(rows.find((row) => row.version === stable)).toMatchObject({
      prerelease: false,
      listed: true,
      downloads: 1,
    });
    expect(rows.find((row) => row.version === beta)).toMatchObject({
      prerelease: true,
      listed: false,
      downloads: 0,
    });
    expect(rows.find((row) => row.version === newestNormalized)).toMatchObject({
      prerelease: false,
      listed: true,
      downloads: 0,
    });
    for (const row of rows) {
      expect(Date.parse(row.publishedAt), `publishedAt of ${row.version}`).not.toBeNaN();
    }

    // GET .../versions/{version}: the nuspec the client pushed, read back.
    const newestDetail = expectContract(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, newest)),
    ) as VersionDetail;
    expect(newestDetail).toMatchObject({
      version: newestNormalized,
      title: metadata.title,
      description: `e2e ${names.id}@${newest}`,
      authors: 'repsy-e2e',
      tags: metadata.tags,
      iconUrl: metadata.iconUrl,
      licenseUrl: metadata.licenseUrl,
      projectUrl: metadata.projectUrl,
      repositoryUrl: metadata.repositoryUrl,
      readme,
      listed: true,
      downloadCount: 0,
      dependencies: [
        { packageId: names.dependencyId, versionRange: '[1.0.0, )', targetFramework: 'net10.0' },
        { packageId: 'Some.Other.Package', versionRange: '2.0.0' },
      ],
    });
    expect(newestDetail.packageId.toLowerCase()).toBe(names.id.toLowerCase());
    // The same version is found by its normalized spelling.
    const byNormalized = expectContract(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, newestNormalized)),
    ) as VersionDetail;
    expect(byNormalized).toEqual(newestDetail);

    const stableDetail = expectContract(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, stable)),
    ) as VersionDetail;
    expect(stableDetail).toMatchObject({
      version: stable,
      listed: true,
      downloadCount: 1,
      dependencies: [],
    });
    expect(stableDetail.readme ?? null).toBeNull();

    const betaDetail = expectContract(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, beta)),
    ) as VersionDetail;
    expect(betaDetail, 'the unlisted beta is still described').toMatchObject({
      version: beta,
      listed: false,
    });
  });

  test('a package whose only version is unlisted is in the list and has a detail that names that version', async ({
    seeder,
  }) => {
    // RPS-1580: the list includes a package with no listed version, so its detail must not answer 404.
    const names = await newNames(seeder);
    const version = '1.0.0';
    await pushPackage(names, { id: names.id, version });
    const unlisted = await dotnetNugetDelete(names.repoName, adminCredential(), names.id, version);
    expect(unlisted.exitCode, `dotnet nuget delete: ${unlisted.command}`).toBe(0);
    expect(await registrationListed(names)).toEqual({ [version]: false });

    const packages = expectContract(
      'searchNugetPackages',
      await callOperation('searchNugetPackages', { repoName: names.repoName }),
    ) as { content: { packageId: string }[] };
    expect(
      packages.content.map((row) => row.packageId.toLowerCase()),
      'the list still has the package',
    ).toEqual([names.id.toLowerCase()]);

    const detail = expectContract(
      'getNugetPackage',
      await callOperation('getNugetPackage', values(names)),
    ) as Record<string, unknown>;
    expect(detail, 'the detail describes the package by its only, unlisted, version').toMatchObject(
      {
        description: `e2e ${names.id}@${version}`,
        latestVersion: version,
        totalDownloads: 0,
      },
    );
    expect((detail.packageId as string).toLowerCase()).toBe(names.id.toLowerCase());

    expect(
      (await panelVersions(names)).map((row) => [row.version, row.listed]),
      'the versions list agrees: the version is there, unlisted',
    ).toEqual([[version, false]]);

    // A newer version pushed after the unlist is listed and becomes the latest one.
    await pushPackage(names, { id: names.id, version: '2.0.0' });
    const afterNewer = expectContract(
      'getNugetPackage',
      await callOperation('getNugetPackage', values(names)),
    ) as Record<string, unknown>;
    expect(afterNewer.latestVersion, 'the newest listed version wins over an unlisted one').toBe(
      '2.0.0',
    );
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    await nuget.seedPublish(worldOf(names, names.id, '1.0.0'));
    const missing = { ...values(names), packageName: 'No.Such.Package' };

    expectFailure(
      'searchNugetPackages',
      await callOperation('searchNugetPackages', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'searchNugetPackages',
      await callOperation('searchNugetPackages', { repoName: names.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getNugetPackage',
      await callOperation('getNugetPackage', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'listNugetVersions',
      await callOperation('listNugetVersions', missing),
      404,
      'packageNotFound',
    );
    expectFailure(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, '9.9.9')),
      404,
      'versionNotFound',
    );
    expectFailure(
      'deleteNugetVersion',
      await callOperation('deleteNugetVersion', values(names, '9.9.9')),
      404,
      'versionNotFound',
    );
    expectFailure(
      'deleteNugetPackage',
      await callOperation('deleteNugetPackage', missing),
      404,
      'packageNotFound',
    );

    // Every failure above left the package where it was.
    expect((await panelVersions(names)).map((row) => row.version)).toEqual(['1.0.0']);
    expect(await flatVersions(names)).toEqual(['1.0.0']);
  });

  test('pages, sorts and narrows the package and version lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const repo = { name: names.repoName, type: RepoType.NUGET };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ packageId: string }>({
      operationId: 'searchNugetPackages',
      values: { repoName: names.repoName },
      total: 5,
      keyOf: (row) => row.packageId,
      sorts: [{ property: 'packageId', value: (row) => row.packageId }],
    });
    const narrowed = expectContract(
      'searchNugetPackages',
      await callOperation(
        'searchNugetPackages',
        { repoName: names.repoName },
        { query: 'q=pkg-3' },
      ),
    ) as { content: { packageId: string }[] };
    expect(narrowed.content).toHaveLength(1);
    expect(narrowed.content[0]?.packageId).toContain('pkg-3');

    // The versions of one package, the second list operation with paging.
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage(repo, seeder, { name: names.id, version });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listNugetVersions',
      values: values(names),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
    const oneVersion = expectContract(
      'listNugetVersions',
      await callOperation('listNugetVersions', values(names), { query: 'q=1.0.3' }),
    ) as { content: { version: string }[] };
    expect(oneVersion.content.map((row) => row.version)).toEqual(['1.0.3']);
  });

  test('deleting a version, then the package, removes them from the wire and from dotnet restore', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const removed = '1.0.0';
    const kept = '1.1.0';
    const seeds = new Map<string, string>();
    for (const version of [removed, kept]) {
      seeds.set(version, sha256Hex(await pushPackage(names, { id: names.id, version })));
    }
    expect(await flatVersions(names)).toEqual([removed, kept]);
    expect(await nupkgStatus(names, removed)).toBe(200);

    // A version that is not the last: the answer says VERSION.
    expect(
      expectContract(
        'deleteNugetVersion',
        await callOperation('deleteNugetVersion', values(names, removed)),
      ),
    ).toBe('VERSION');

    expect((await panelVersions(names)).map((row) => row.version)).toEqual([kept]);
    expectFailure(
      'getNugetVersion',
      await callOperation('getNugetVersion', values(names, removed)),
      404,
      'versionNotFound',
    );
    expect(await flatVersions(names), 'the flat container drops the version').toEqual([kept]);
    expect(await registrationListed(names), 'the registration drops the leaf').toEqual({
      [kept]: true,
    });
    expect(await nupkgStatus(names, removed)).toBe(404);
    expect(await nupkgStatus(names, kept)).toBe(200);
    const gone = await nuget.resolve(worldOf(names, names.id, removed));
    expect(gone.clientExitCode, `dotnet restore of the deleted version: ${gone.command}`).not.toBe(
      0,
    );
    const resolved = await nuget.resolve(worldOf(names, names.id, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expectFailure(
      'deleteNugetVersion',
      await callOperation('deleteNugetVersion', values(names, removed)),
      404,
      'versionNotFound',
    );

    // The last version: the answer says PACKAGE, and the package is gone with it.
    expect(
      expectContract(
        'deleteNugetVersion',
        await callOperation('deleteNugetVersion', values(names, kept)),
      ),
    ).toBe('PACKAGE');
    expectFailure(
      'getNugetPackage',
      await callOperation('getNugetPackage', values(names)),
      404,
      'packageNotFound',
    );
    expect(await flatVersions(names)).toBe(404);
    expect(await registrationListed(names)).toBe(404);
    expect(await nupkgStatus(names, kept)).toBe(404);

    // Now the whole package, on a fresh one with two versions.
    const other = `${names.id}.Whole`;
    const wholeNames = { ...names, id: other };
    for (const version of ['1.0.0', '2.0.0']) {
      await pushPackage(wholeNames, { id: other, version });
    }
    expect(await flatVersions(wholeNames)).toEqual(['1.0.0', '2.0.0']);
    expect(
      expectContract(
        'deleteNugetPackage',
        await callOperation('deleteNugetPackage', values(wholeNames)),
      ),
    ).toBe('PACKAGE');
    expect(await flatVersions(wholeNames)).toBe(404);
    expect(await registrationListed(wholeNames)).toBe(404);
    expect(await nupkgStatus(wholeNames, '2.0.0')).toBe(404);
    const lost = await nuget.resolve(worldOf(wholeNames, other, '2.0.0'));
    expect(lost.clientExitCode, `dotnet restore of the deleted package: ${lost.command}`).not.toBe(
      0,
    );
    expectFailure(
      'getNugetPackage',
      await callOperation('getNugetPackage', values(wholeNames)),
      404,
      'packageNotFound',
    );
    const rows = expectContract(
      'searchNugetPackages',
      await callOperation('searchNugetPackages', { repoName: names.repoName }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    expectFailure(
      'deleteNugetPackage',
      await callOperation('deleteNugetPackage', values(wholeNames)),
      404,
      'packageNotFound',
    );
  });
});
