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
 * Deleting one version of a Maven artifact in the panel (RPS-1331), for an artifact published with
 * raw HTTP PUTs. Repsy stores the artifact-level `maven-metadata.xml` only when a client uploads
 * one and never generates it, so an artifact published by Ivy, sbt or a plain `curl -T` has none.
 *
 *  - Without a `maven-metadata.xml` the delete used to move the version's files to the trash and
 *    then answer 404 `itemNotFound`, leaving the row listed and the delete impossible to repeat.
 *    It now answers 200, and neither creates a metadata file nor touches the sibling version.
 *  - With one (a Maven client deployed it) the version is removed from the file, as before.
 *  - A file without `<versioning>` (`<metadata/>`) lists no versions: the delete answers 200 (it
 *    used to be a 500 after the files had moved) and leaves the file as it was. A file that cannot
 *    be parsed at all cannot be uploaded, so that refusal is pinned by the backend IT only.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  artifactDir,
  artifactMetadataXml,
  minimalPom,
  parseArtifactVersions,
  rawGet,
  rawPut,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const OCTET = 'application/octet-stream';
const ARTIFACT_ID = 'lib';
const FIRST = '1.0';
const SECOND = '2.0';

interface Fixture {
  repoName: string;
  groupId: string;
}

/** A private Maven repo holding `lib` in versions 1.0 and 2.0 (pom + jar), and no metadata file. */
async function seedTwoVersions(seeder: Seeder): Promise<Fixture> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const admin = adminCredential();
  for (const version of [FIRST, SECOND]) {
    const base = `${versionDir(groupId, ARTIFACT_ID, version)}/${ARTIFACT_ID}-${version}`;
    const pom = await rawPut(
      repo.name,
      admin,
      `${base}.pom`,
      minimalPom(groupId, ARTIFACT_ID, version),
      OCTET,
    );
    expect(pom.status, `PUT ${base}.pom`).toBe(200);
    const jar = await rawPut(repo.name, admin, `${base}.jar`, `jar ${version}`, OCTET);
    expect(jar.status, `PUT ${base}.jar`).toBe(200);
  }
  return { repoName: repo.name, groupId };
}

async function status(fixture: Fixture, path: string): Promise<number> {
  return (await rawGet(fixture.repoName, adminCredential(), path)).status;
}

test.describe('maven version delete (raw HTTP publisher)', () => {
  test('deleting one of two versions of an artifact with no maven-metadata.xml removes it and keeps the other (RPS-1331)', async ({
    seeder,
    panelApi,
  }) => {
    const fixture = await seedTwoVersions(seeder);
    const { repoName, groupId } = fixture;
    const metadataPath = `${artifactDir(groupId, ARTIFACT_ID)}/maven-metadata.xml`;
    expect(await status(fixture, metadataPath), 'no metadata was uploaded').toBe(404);

    await panelApi.deleteMavenArtifactVersion(repoName, groupId, ARTIFACT_ID, FIRST);

    expect(
      (await panelApi.listMavenArtifactVersionNames(repoName, groupId, ARTIFACT_ID)).sort(),
    ).toEqual([SECOND]);
    const gone = `${versionDir(groupId, ARTIFACT_ID, FIRST)}/${ARTIFACT_ID}-${FIRST}`;
    expect(await status(fixture, `${gone}.jar`)).toBe(404);
    expect(await status(fixture, `${gone}.pom`)).toBe(404);
    const kept = `${versionDir(groupId, ARTIFACT_ID, SECOND)}/${ARTIFACT_ID}-${SECOND}`;
    expect(await status(fixture, `${kept}.jar`)).toBe(200);
    expect(await status(fixture, `${kept}.pom`)).toBe(200);
    expect(await status(fixture, metadataPath), 'Repsy never generates a metadata file').toBe(404);

    const info = await panelApi.getMavenArtifactVersion(repoName, groupId, ARTIFACT_ID, SECOND);
    expect(info.versionName).toBe(SECOND);
  });

  test('deleting a version rewrites the maven-metadata.xml a client did upload', async ({
    seeder,
    panelApi,
  }) => {
    const fixture = await seedTwoVersions(seeder);
    const { repoName, groupId } = fixture;
    const metadataPath = `${artifactDir(groupId, ARTIFACT_ID)}/maven-metadata.xml`;
    const put = await rawPut(
      repoName,
      adminCredential(),
      metadataPath,
      artifactMetadataXml({ groupId, artifactId: ARTIFACT_ID, versions: [FIRST, SECOND] }),
      OCTET,
    );
    expect(put.status, `PUT ${metadataPath}`).toBe(200);

    await panelApi.deleteMavenArtifactVersion(repoName, groupId, ARTIFACT_ID, FIRST);

    const metadata = await rawGet(repoName, adminCredential(), metadataPath);
    expect(metadata.status).toBe(200);
    expect(parseArtifactVersions(metadata.body.toString('utf8'))).toEqual([SECOND]);
    expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, ARTIFACT_ID)).toEqual([
      SECOND,
    ]);
  });

  test('a maven-metadata.xml without <versioning> is left as it is and the delete succeeds (RPS-1331)', async ({
    seeder,
    panelApi,
  }) => {
    const fixture = await seedTwoVersions(seeder);
    const { repoName, groupId } = fixture;
    const metadataPath = `${artifactDir(groupId, ARTIFACT_ID)}/maven-metadata.xml`;
    const put = await rawPut(repoName, adminCredential(), metadataPath, '<metadata/>', OCTET);
    expect(put.status, `PUT ${metadataPath}`).toBe(200);

    await panelApi.deleteMavenArtifactVersion(repoName, groupId, ARTIFACT_ID, FIRST);

    expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, ARTIFACT_ID)).toEqual([
      SECOND,
    ]);
    const metadata = await rawGet(repoName, adminCredential(), metadataPath);
    expect(metadata.body.toString('utf8')).toBe('<metadata/>');
  });
});
