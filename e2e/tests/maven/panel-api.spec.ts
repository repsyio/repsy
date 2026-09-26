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
 * RPS-1483: the panel API of Maven (`/api/mvn/artifacts/...` and `/api/mvn/groups/...`), against what the REAL
 * `mvn deploy` stored. Each response is validated against the response schema of `openapi-spec.yaml`
 * (`src/api/spec-contract.ts`: the schema, and no property the schema does not declare), and the values are
 * matched to the client-side facts (group, artifact, versions, packaging, the POM bytes the wire serves, the
 * `<versions>` of `maven-metadata.xml`). The delete operations are then judged by their effect on the wire:
 * the jar and the POM answer 404, the metadata drops the version, and a real `mvn dependency:get` fails.
 *
 * Copied from `tests/maven/version-delete.spec.ts` (the raw-HTTP publisher of RPS-1331) and
 * `tests/api/maven-browser.spec.ts` (the raw panel calls), with the publisher replaced by the real client
 * (`maven.seedPublish`, the adapter's pre-publish). The paging sweep seeds its rows over raw HTTP
 * (`seedPackage`): the pages are the subject there, not the client.
 *
 * Not covered here: the key-store routes (`/api/mvn/key-stores`, RPS-1483's role sweep and the signing
 * specs), and `GET /api/repos/{repo}/contents` (`tests/api/maven-browser.spec.ts`).
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
import * as mvn from '../../src/clients/maven.js';
import { uniqueVersion } from '../../src/clients/maven-adapter.js';
import {
  adminCredential,
  artifactDir,
  parseArtifactVersions,
  rawGet,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Maven panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'listMavenGroups',
  'listMavenArtifacts',
  'getMavenGroupSummary',
  'listMavenArtifactVersions',
  'getMavenArtifact',
  'getMavenArtifactVersion',
  'deleteMavenArtifactVersion',
  'deleteMavenArtifact',
  'deleteMavenGroup',
];

interface Deployed {
  repoName: string;
  groupId: string;
  /** artifactId -> version -> sha256 of the jar `mvn` built. */
  jars: Map<string, Map<string, string>>;
}

interface ArtifactRow {
  groupName: string;
  artifactName: string;
  packaging: string;
  latest: string;
  lastUpdatedAt: string;
}

interface VersionRow {
  versionName: string;
  signed: boolean;
}

interface VersionInfo {
  id: string;
  versionName: string;
  artifactName: string;
  artifactGroupName: string;
  artifactVersionName: string;
  packaging: string;
  type: string;
  hasSources: boolean;
  hasDocuments: boolean;
  signed: boolean;
  pomFile: string;
  createdAt: string;
  lastUpdatedAt: string;
}

const world = (d: Deployed, artifactId: string, version: string) =>
  contractWorld('maven', d.repoName, `${d.groupId}:${artifactId}`, version, adminCredential());

/** A private repo with one group, and the artifacts the real `mvn deploy` stored in it, one deploy per version. */
async function deploy(seeder: Seeder, artifacts: Record<string, string[]>): Promise<Deployed> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const deployed: Deployed = {
    repoName: repo.name,
    groupId: `io.repsy.e2e.${seeder.runId}`,
    jars: new Map(),
  };
  for (const [artifactId, versions] of Object.entries(artifacts)) {
    const jars = new Map<string, string>();
    for (const version of versions) {
      const seeded = await mvn.seedPublish(world(deployed, artifactId, version));
      expect(seeded.contentSha256, `the jar of ${artifactId}@${version}`).toBeTruthy();
      jars.set(version, seeded.contentSha256 as string);
    }
    deployed.jars.set(artifactId, jars);
  }
  return deployed;
}

function fileOf(d: Deployed, artifactId: string, version: string, extension: 'jar' | 'pom') {
  return `${versionDir(d.groupId, artifactId, version)}/${artifactId}-${version}.${extension}`;
}

async function wireStatus(d: Deployed, path: string): Promise<number> {
  return (await rawGet(d.repoName, adminCredential(), path)).status;
}

async function wireVersions(d: Deployed, artifactId: string): Promise<number | string[]> {
  const res = await rawGet(
    d.repoName,
    adminCredential(),
    `${artifactDir(d.groupId, artifactId)}/maven-metadata.xml`,
  );
  return res.status === 200 ? parseArtifactVersions(res.body.toString('utf8')) : res.status;
}

async function versionNames(d: Deployed, artifactId: string): Promise<string[]> {
  const res = await callOperation('listMavenArtifactVersions', {
    repoName: d.repoName,
    groupName: d.groupId,
    artifactName: artifactId,
  });
  const page = expectContract('listMavenArtifactVersions', res) as { content: VersionRow[] };
  return page.content.map((row) => row.versionName).sort();
}

const artifactValues = (d: Deployed, artifactId: string) => ({
  repoName: d.repoName,
  groupName: d.groupId,
  artifactName: artifactId,
});

test.describe('the Maven panel API against what mvn deploy stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the Maven panel API', () => {
    expectCovers(EXERCISED, '/api/mvn/artifacts', '/api/mvn/groups');
  });

  test('lists, gets and describes what mvn deployed, and the answers match the schema', async ({
    seeder,
  }) => {
    const first = uniqueVersion('release');
    const second = uniqueVersion('release');
    const snapshot = uniqueVersion('snapshot');
    const d = await deploy(seeder, { lib: [first, second, snapshot], tool: [first] });

    // GET /api/mvn/artifacts/{repo}: one row per artifact of every group.
    const groups = expectContract(
      'listMavenGroups',
      await callOperation('listMavenGroups', { repoName: d.repoName }),
    ) as { content: ArtifactRow[]; page: { totalElements: number } };
    expect(groups.page.totalElements).toBe(2);
    expect(groups.content.map((row) => row.artifactName).sort()).toEqual(['lib', 'tool']);
    for (const row of groups.content) {
      expect(row, row.artifactName).toMatchObject({ groupName: d.groupId, packaging: 'jar' });
      expect([...(d.jars.get(row.artifactName)?.keys() ?? [])]).toContain(row.latest);
    }
    expect(groups.content.find((row) => row.artifactName === 'tool')?.latest).toBe(first);

    // GET /api/mvn/artifacts/{repo}/{group}: the same rows, narrowed to the group.
    const artifacts = expectContract(
      'listMavenArtifacts',
      await callOperation('listMavenArtifacts', { repoName: d.repoName, groupName: d.groupId }),
    ) as { content: ArtifactRow[] };
    expect(artifacts.content.map((row) => row.artifactName).sort()).toEqual(['lib', 'tool']);

    // GET /api/mvn/groups/{repo}/{group}: what deleting the group would remove.
    expect(
      expectContract(
        'getMavenGroupSummary',
        await callOperation('getMavenGroupSummary', { repoName: d.repoName, groupName: d.groupId }),
      ),
    ).toEqual({ groupName: d.groupId, artifactCount: 2, versionCount: 4 });

    // GET .../{artifact}/versions: every deployed version, and the same ones the metadata on the wire lists.
    const listed = await versionNames(d, 'lib');
    expect(listed).toEqual([first, second, snapshot].sort());
    const onWire = await wireVersions(d, 'lib');
    expect(
      Array.isArray(onWire) ? [...onWire].sort() : onWire,
      'maven-metadata.xml on the wire',
    ).toEqual(listed);
    const versions = expectContract(
      'listMavenArtifactVersions',
      await callOperation('listMavenArtifactVersions', artifactValues(d, 'lib')),
    ) as { content: VersionRow[] };
    for (const row of versions.content) {
      expect(row.signed, `${row.versionName} was deployed without a signature`).toBe(false);
    }

    // GET .../{artifact}: the newest version's detail.
    const newest = expectContract(
      'getMavenArtifact',
      await callOperation('getMavenArtifact', artifactValues(d, 'lib')),
    ) as VersionInfo;
    expect(newest).toMatchObject({
      artifactName: 'lib',
      artifactGroupName: d.groupId,
      packaging: 'jar',
    });
    expect(listed).toContain(newest.versionName);
    expect(groups.content.find((row) => row.artifactName === 'lib')?.latest).toBe(
      newest.versionName,
    );

    // GET .../{artifact}/versions/{version}: each one, its POM equal to the file the wire serves.
    for (const version of [first, second, snapshot]) {
      const info = expectContract(
        'getMavenArtifactVersion',
        await callOperation('getMavenArtifactVersion', { ...artifactValues(d, 'lib'), version }),
      ) as VersionInfo;
      expect(info, version).toMatchObject({
        versionName: version,
        artifactName: 'lib',
        artifactGroupName: d.groupId,
        packaging: 'jar',
        type: version === snapshot ? 'SNAPSHOT' : 'RELEASE',
        hasSources: false,
        hasDocuments: false,
        signed: false,
      });
    }

    for (const version of [first, second]) {
      const info = expectContract(
        'getMavenArtifactVersion',
        await callOperation('getMavenArtifactVersion', { ...artifactValues(d, 'lib'), version }),
      ) as VersionInfo;
      const pom = await rawGet(d.repoName, adminCredential(), fileOf(d, 'lib', version, 'pom'));
      expect(pom.status).toBe(200);
      expect(info.pomFile, `the POM of ${version}`).toBe(pom.body.toString('utf8'));
      expect(pom.body.toString('utf8')).toContain(`<version>${version}</version>`);
    }
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const pkg = await seedPackage(repo, seeder, {});
    const [groupId, artifactId] = pkg.name.split(':') as [string, string];
    const values = { repoName: repo.name, groupName: groupId, artifactName: artifactId };

    expectFailure(
      'listMavenGroups',
      await callOperation('listMavenGroups', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listMavenGroups',
      await callOperation('listMavenGroups', { repoName: repo.name }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getMavenGroupSummary',
      await callOperation('getMavenGroupSummary', { ...values, groupName: 'no.such.group' }),
      404,
      'groupNotFound',
    );
    expectFailure(
      'getMavenArtifact',
      await callOperation('getMavenArtifact', { ...values, artifactName: 'no-such-artifact' }),
      404,
      'artifactNotFound',
    );
    expectFailure(
      'getMavenArtifactVersion',
      await callOperation('getMavenArtifactVersion', { ...values, version: '9.9.9' }),
      404,
      'artifactVersionNotFound',
    );
    expectFailure(
      'deleteMavenArtifactVersion',
      await callOperation('deleteMavenArtifactVersion', { ...values, version: '9.9.9' }),
      404,
      'artifactVersionNotFound',
    );
    expectFailure(
      'deleteMavenArtifactVersion',
      await callOperation('deleteMavenArtifactVersion', {
        ...values,
        artifactName: 'no-such-artifact',
        version: pkg.version,
      }),
      404,
      'artifactNotFound',
    );

    // Every failure above left the artifact where it was.
    expect(
      await versionNames({ repoName: repo.name, groupId, jars: new Map() }, artifactId),
    ).toEqual([pkg.version]);
  });

  test('pages, sorts and narrows the artifact list, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }

    await expectPagingSweep<ArtifactRow>({
      operationId: 'listMavenGroups',
      values: { repoName: repo.name },
      total: 5,
      keyOf: (row) => `${row.groupName}:${row.artifactName}`,
      sorts: [
        { property: 'groupName', value: (row) => row.groupName },
        { property: 'artifactName', value: (row) => row.artifactName },
      ],
    });

    // `q` narrows by a part of the group or the artifact name.
    const narrowed = expectContract(
      'listMavenGroups',
      await callOperation('listMavenGroups', { repoName: repo.name }, { query: 'q=pkg-4' }),
    ) as { content: ArtifactRow[]; page: { totalElements: number } };
    expect(narrowed.content.map((row) => row.artifactName)).toEqual(['pkg-4']);
    expect(narrowed.page.totalElements).toBe(1);
  });

  test('deleting a version removes it from the wire and from mvn, and keeps its sibling', async ({
    seeder,
  }) => {
    const kept = uniqueVersion('release');
    const removed = uniqueVersion('release');
    const d = await deploy(seeder, { lib: [removed, kept] });
    expect(await wireVersions(d, 'lib')).toEqual([removed, kept].sort());

    const res = await callOperation('deleteMavenArtifactVersion', {
      ...artifactValues(d, 'lib'),
      version: removed,
    });
    expect(expectContract('deleteMavenArtifactVersion', res)).toBe('VERSION');

    // The panel.
    expect(await versionNames(d, 'lib')).toEqual([kept]);
    expectFailure(
      'getMavenArtifactVersion',
      await callOperation('getMavenArtifactVersion', {
        ...artifactValues(d, 'lib'),
        version: removed,
      }),
      404,
      'artifactVersionNotFound',
    );
    // The wire: the deleted version's files are gone, the metadata lists the other, and mvn cannot resolve it.
    expect(await wireStatus(d, fileOf(d, 'lib', removed, 'jar'))).toBe(404);
    expect(await wireStatus(d, fileOf(d, 'lib', removed, 'pom'))).toBe(404);
    expect(await wireVersions(d, 'lib')).toEqual([kept]);
    const gone = await mvn.resolve(world(d, 'lib', removed));
    expect(
      gone.clientExitCode,
      `mvn dependency:get of the deleted version: ${gone.command}`,
    ).not.toBe(0);
    // The sibling is untouched: same bytes over the wire, and mvn resolves exactly the jar it deployed.
    expect(await wireStatus(d, fileOf(d, 'lib', kept, 'jar'))).toBe(200);
    const resolved = await mvn.resolve(world(d, 'lib', kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(d.jars.get('lib')?.get(kept));

    // A second delete of the same version is a 404, not a second 200.
    expectFailure(
      'deleteMavenArtifactVersion',
      await callOperation('deleteMavenArtifactVersion', {
        ...artifactValues(d, 'lib'),
        version: removed,
      }),
      404,
      'artifactVersionNotFound',
    );
  });

  test('deleting an artifact, then its group, empties the wire and mvn resolves nothing of it', async ({
    seeder,
  }) => {
    const version = uniqueVersion('release');
    const other = uniqueVersion('release');
    const d = await deploy(seeder, { lib: [version, other], tool: [version] });

    const artifact = await callOperation('deleteMavenArtifact', artifactValues(d, 'lib'));
    expect(expectContract('deleteMavenArtifact', artifact)).toBe('ARTIFACT');

    expectFailure(
      'getMavenArtifact',
      await callOperation('getMavenArtifact', artifactValues(d, 'lib')),
      404,
      'artifactNotFound',
    );
    for (const v of [version, other]) {
      expect(await wireStatus(d, fileOf(d, 'lib', v, 'jar')), `lib@${v} jar`).toBe(404);
      expect(await wireStatus(d, fileOf(d, 'lib', v, 'pom')), `lib@${v} pom`).toBe(404);
    }
    expect(await wireVersions(d, 'lib'), 'the metadata of the deleted artifact').toBe(404);
    const lost = await mvn.resolve(world(d, 'lib', version));
    expect(lost.clientExitCode, lost.command).not.toBe(0);
    // Its neighbour in the group is untouched, and the group summary counts only that.
    expect(
      expectContract(
        'getMavenGroupSummary',
        await callOperation('getMavenGroupSummary', { repoName: d.repoName, groupName: d.groupId }),
      ),
    ).toEqual({ groupName: d.groupId, artifactCount: 1, versionCount: 1 });
    const toolResolved = await mvn.resolve(world(d, 'tool', version));
    expect(toolResolved.clientExitCode, toolResolved.command).toBe(0);
    expect(toolResolved.contentSha256).toBe(d.jars.get('tool')?.get(version));
    // Deleting 'lib' a second time is a 404 that deletes nothing, although 'tool' is now the only artifact of
    // the group: the check that the artifact exists comes before the "last artifact takes the group" cascade
    // (RPS-1573).
    expectFailure(
      'deleteMavenArtifact',
      await callOperation('deleteMavenArtifact', artifactValues(d, 'lib')),
      404,
      'artifactNotFound',
    );
    expect(
      expectContract(
        'getMavenGroupSummary',
        await callOperation('getMavenGroupSummary', { repoName: d.repoName, groupName: d.groupId }),
      ),
      'the group and its last artifact survive the delete of a missing one',
    ).toEqual({ groupName: d.groupId, artifactCount: 1, versionCount: 1 });
    const toolStill = await mvn.resolve(world(d, 'tool', version));
    expect(toolStill.clientExitCode, toolStill.command).toBe(0);
    // A group that holds no artifact is a 404 as well, not a 200 that deleted nothing.
    expectFailure(
      'deleteMavenGroup',
      await callOperation('deleteMavenGroup', {
        repoName: d.repoName,
        groupName: `${d.groupId}.nosuchgroup`,
      }),
      404,
      'groupNotFound',
    );

    // Now the group.
    const group = await callOperation('deleteMavenGroup', {
      repoName: d.repoName,
      groupName: d.groupId,
    });
    expect(expectContract('deleteMavenGroup', group)).toBe('GROUP');
    expect(await wireStatus(d, fileOf(d, 'tool', version, 'jar'))).toBe(404);
    expect(await wireVersions(d, 'tool')).toBe(404);
    const toolLost = await mvn.resolve(world(d, 'tool', version));
    expect(toolLost.clientExitCode, toolLost.command).not.toBe(0);
    expectFailure(
      'getMavenGroupSummary',
      await callOperation('getMavenGroupSummary', { repoName: d.repoName, groupName: d.groupId }),
      404,
      'groupNotFound',
    );
    const rows = expectContract(
      'listMavenGroups',
      await callOperation('listMavenGroups', { repoName: d.repoName }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    // And the group that is gone is a 404 when it is deleted again.
    expectFailure(
      'deleteMavenGroup',
      await callOperation('deleteMavenGroup', { repoName: d.repoName, groupName: d.groupId }),
      404,
      'groupNotFound',
    );
  });
});
