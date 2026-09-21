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
 * The server's upload rules, pinned at the protocol level with raw HTTP PUTs (no `mvn`), for the
 * cases a real client either never produces or hides behind its exit code: overriding a file that
 * exists, and the metadata files of a snapshot deploy under a switched-off version kind. The real
 * client flows built on the same rules are the `snapshot-*` and `redeploy-*` scenarios of
 * `catalog.ts`; every expectation here was probed against a running instance first
 * (RPS-1174/RPS-1176).
 *
 *  - `allowOverride: false` refuses re-uploading a file that exists (403 `artifactOverrideIsProhibited`)
 *    but never judges metadata (nor its checksums), and a SNAPSHOT redeploy only ever writes new
 *    timestamped files, which is why `snapshot-redeploy-no-override` succeeds.
 *  - A switched-off version kind (`snapshots: false` / `releases: false`) refuses artifact files of
 *    that kind, new version or existing (403 `snapshotVersionsAreProhibited` /
 *    `releaseVersionsAreProhibited`), and refuses the version-level snapshot metadata by its
 *    `<version>`; the artifact-level metadata indexes both kinds and is never judged, and the other
 *    kind keeps working.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  artifactDir,
  artifactMetadataXml,
  minimalPom,
  rawPut,
  type RawResponse,
  versionDir,
  versionMetadataXml,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const OCTET = 'application/octet-stream';
const XML = 'application/xml';
const TEXT = 'text/plain';

const ARTIFACT_ID = 'raw';
const SNAPSHOT = '1.0-SNAPSHOT';
const RELEASE = '2.0';

const FIRST_BUILD = { timestamp: '20260101.000000', buildNumber: 1 };
const SECOND_BUILD = { timestamp: '20260101.000100', buildNumber: 2 };

interface Layout {
  repoName: string;
  groupId: string;
  put: (relPath: string, body: string, contentType: string) => Promise<RawResponse>;
}

/** A fresh maven repo (permissive defaults) and an admin-credentialed PUT into it. */
async function newRepo(seeder: Seeder): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const admin = adminCredential();
  return {
    repoName: repo.name,
    groupId: `io.repsy.e2e.${seeder.runId}`,
    put: (relPath, body, contentType) => rawPut(repo.name, admin, relPath, body, contentType),
  };
}

function expectPut(
  res: RawResponse,
  status: number,
  msgId: string | undefined,
  what: string,
): void {
  expect(res.status, `${what}: PUT answered ${res.status} ${res.msgId ?? ''}`).toBe(status);
  expect(res.msgId, `${what}: the error message id`).toBe(msgId);
}

/** The files of one SNAPSHOT deploy, in the order a real client sends them: artifacts, metadata. */
function snapshotDeployFiles(
  layout: Layout,
  build: { timestamp: string; buildNumber: number },
): [string, string, string][] {
  const dir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
  const name = `${ARTIFACT_ID}-1.0-${build.timestamp}-${build.buildNumber}`;
  return [
    [`${dir}/${name}.pom`, minimalPom(layout.groupId, ARTIFACT_ID, SNAPSHOT), OCTET],
    [`${dir}/${name}.jar`, `jar ${build.timestamp}`, OCTET],
    [
      `${dir}/maven-metadata.xml`,
      versionMetadataXml({
        groupId: layout.groupId,
        artifactId: ARTIFACT_ID,
        version: SNAPSHOT,
        ...build,
      }),
      XML,
    ],
  ];
}

function artifactMetadata(layout: Layout, versions: string[]): [string, string, string] {
  return [
    `${artifactDir(layout.groupId, ARTIFACT_ID)}/maven-metadata.xml`,
    artifactMetadataXml({ groupId: layout.groupId, artifactId: ARTIFACT_ID, versions }),
    XML,
  ];
}

/** A snapshot and a release version, both deployed while everything was still allowed. */
async function seedBothKinds(layout: Layout): Promise<void> {
  for (const [path, body, contentType] of [
    ...snapshotDeployFiles(layout, FIRST_BUILD),
    [
      `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.pom`,
      minimalPom(layout.groupId, ARTIFACT_ID, RELEASE),
      OCTET,
    ] as [string, string, string],
    [
      `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.jar`,
      'release jar',
      OCTET,
    ] as [string, string, string],
    artifactMetadata(layout, [SNAPSHOT, RELEASE]),
  ]) {
    expectPut(await layout.put(path, body, contentType), 200, undefined, `seed ${path}`);
  }
}

test.describe('maven upload rules (raw HTTP)', () => {
  test(
    'allowOverride:false refuses an existing file, never metadata, and lets a snapshot redeploy through',
    { tag: ['@settings', '@snapshot'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);

      const [firstPom, firstJar] = snapshotDeployFiles(layout, FIRST_BUILD);
      const dir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      expectPut(await layout.put(`${firstPom[0]}.sha1`, 'da39', TEXT), 200, undefined, 'seed');

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });
      const override = 'artifactOverrideIsProhibited';

      // Re-uploading a file that exists is an override, whatever kind of file it is.
      expectPut(await layout.put(...firstPom), 403, override, 'existing timestamped pom');
      expectPut(await layout.put(...firstJar), 403, override, 'existing timestamped jar');
      expectPut(
        await layout.put(`${firstPom[0]}.sha1`, 'da39', TEXT),
        403,
        override,
        'existing pom checksum',
      );

      // What Maven does on a snapshot redeploy: new timestamped files, then the same two metadata
      // files again. None of it is an override.
      for (const file of snapshotDeployFiles(layout, SECOND_BUILD)) {
        expectPut(await layout.put(...file), 200, undefined, `redeploy ${file[0]}`);
      }
      expectPut(
        await layout.put(...artifactMetadata(layout, [SNAPSHOT, RELEASE])),
        200,
        undefined,
        'artifact-level metadata again',
      );
      expectPut(
        await layout.put(`${dir}/maven-metadata.xml.sha1`, 'da39', TEXT),
        200,
        undefined,
        'version-level metadata checksum again',
      );
    },
  );

  test(
    'snapshots:false refuses a snapshot redeploy and its version-level metadata, not the rest',
    { tag: ['@settings', '@snapshot', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: false,
      });

      const [secondPom, , secondVersionMetadata] = snapshotDeployFiles(layout, SECOND_BUILD);
      const refused = 'snapshotVersionsAreProhibited';

      // RPS-1174: the version exists, and a redeploy is refused all the same, on its first file.
      expectPut(await layout.put(...secondPom), 403, refused, 'redeploy of an existing snapshot');
      // RPS-1176: version-level metadata is judged by its <version>...
      expectPut(
        await layout.put(...secondVersionMetadata),
        403,
        refused,
        'version-level snapshot metadata',
      );
      // ...the artifact-level metadata (both kinds listed) is not, and releases still work.
      expectPut(
        await layout.put(...artifactMetadata(layout, [SNAPSHOT, RELEASE])),
        200,
        undefined,
        'artifact-level metadata',
      );
      expectPut(
        await layout.put(
          `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.pom`,
          minimalPom(layout.groupId, ARTIFACT_ID, RELEASE),
          OCTET,
        ),
        200,
        undefined,
        'release redeploy while snapshots are off',
      );
    },
  );

  test(
    'releases:false refuses a release redeploy, not snapshot metadata or artifact-level metadata',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: false,
        snapshots: true,
      });

      const refused = 'releaseVersionsAreProhibited';
      const releaseDir = versionDir(layout.groupId, ARTIFACT_ID, RELEASE);

      expectPut(
        await layout.put(
          `${releaseDir}/${ARTIFACT_ID}-${RELEASE}.pom`,
          minimalPom(layout.groupId, ARTIFACT_ID, RELEASE),
          OCTET,
        ),
        403,
        refused,
        'redeploy of an existing release',
      );
      expectPut(
        await layout.put(`${releaseDir}/${ARTIFACT_ID}-${RELEASE}.jar`, 'other', OCTET),
        403,
        refused,
        'release jar',
      );
      // The snapshot kind is still allowed, so its version-level metadata is too (RPS-1176: judged by
      // its own <version>, not by whichever switch happens to be off), and so is the shared index.
      expectPut(
        await layout.put(...snapshotDeployFiles(layout, SECOND_BUILD)[2]),
        200,
        undefined,
        'version-level snapshot metadata while releases are off',
      );
      expectPut(
        await layout.put(...artifactMetadata(layout, [SNAPSHOT, RELEASE])),
        200,
        undefined,
        'artifact-level metadata',
      );
    },
  );
});
