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
 *  - `allowOverride: false` refuses re-uploading a file of a release or of a timestamped build that
 *    exists (403 `artifactOverrideIsProhibited`) but never judges metadata (nor its checksums), and
 *    a Maven SNAPSHOT redeploy only ever writes new timestamped files, which is why
 *    `snapshot-redeploy-no-override` succeeds. A non-unique snapshot, the literal
 *    `a-<base>-SNAPSHOT.pom/.jar` (+ checksums, classifier jars) that sbt and Ivy send every time,
 *    is no override either: it is accepted again and again (RPS-1328), while `snapshots: false`
 *    still refuses it.
 *  - A switched-off version kind (`snapshots: false` / `releases: false`) refuses artifact files of
 *    that kind, new version or existing (403 `snapshotVersionsAreProhibited` /
 *    `releaseVersionsAreProhibited`), and refuses the version-level snapshot metadata by its
 *    `<version>`; the artifact-level metadata indexes both kinds and is never judged, and the other
 *    kind keeps working.
 *  - A path outside the Maven layout (`<group>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>`)
 *    is refused with 400 `invalidArtifactPath` and stores nothing, where it used to answer 200 and
 *    silently drop the file (RPS-1182); every file a real Maven or Gradle client sends conforms
 *    and keeps being stored. A file of a `SNAPSHOT` directory must also carry that directory's
 *    artifactId and base version, literal or timestamped: `lib-2.0-SNAPSHOT.jar` or
 *    `other-1.0-SNAPSHOT.jar` in `lib/1.0-SNAPSHOT/` is refused the same way (RPS-1184).
 *  - A checksum (`.sha1`/`.md5`/`.sha256`/`.sha512`) is judged by the file it belongs to: it is
 *    refused for a path outside the layout (400 `invalidArtifactPath`) or for a switched-off kind
 *    (403), and stores nothing, where it used to be stored and create the directory of a version
 *    whose file was refused (RPS-1183). A metadata checksum is judged by its directory only, as
 *    its body is a hash: `g/a/<X-SNAPSHOT>/maven-metadata.xml.sha1` is a snapshot, the
 *    artifact-level and group-level ones are not judged.
 *  - The `.asc` signature of a metadata file is stored unparsed and judged like a metadata
 *    checksum: by its directory only, never verified (RPS-1185). It used to be parsed as XML and
 *    refused with 400 `malformedMetadataFile`. No official client writes one (maven-gpg-plugin,
 *    Maven Resolver and Gradle sign artifacts only), but Maven Central serves them.
 *  - A POM signature (`<pom>.asc`) is verified before it is stored, so a refused one answers 422
 *    `artifactSignatureNotVerified` and changes nothing: no file, no row, no other version and no
 *    metadata is removed, and the `.asc` itself is not stored (RPS-1186). It used to be stored
 *    first and the whole version, the artifact and the group deleted on a refusal. Only a `.pom.asc`
 *    is verified by default; a `.jar.asc` is stored as sent unless the repo turns on
 *    `pgpVerifyAllSignaturesEnabled` (RPS-1188, pinned in `pgp-signature.spec.ts`).
 *  - The same holds for an `.asc` that is not a signature at all (invalid armor, a bad CRC, binary
 *    garbage): 422 `artifactSignatureNotVerified`, where it used to be a 500 (RPS-1191). And an
 *    `.asc` of a stored POM that has no registered version is refused with 404
 *    `artifactVersionNotFound` before it is stored, not stored and then refused (RPS-1191); such a
 *    POM can no longer be uploaded (see the next point), so `MavenPomSignatureIT` pins that case
 *    by removing the rows of a stored POM.
 *  - A POM whose `<groupId>` (else its `<parent><groupId>`) is not its directory's group is refused
 *    with 400 `pomGroupIdMismatch` and stores nothing, where it used to be stored and answered 200
 *    but never registered: served, yet invisible and undeletable in the panel (RPS-1193). Only the
 *    groupId is compared, case-sensitively; a POM that declares none is not checked, and its
 *    artifactId and version are never compared.
 *  - A POM, its signature and its checksum are told by the file name's `.pom` suffix, never by a
 *    directory or artifactId that merely contains `.pom` (RPS-1196): an artifactId such as
 *    `raw.pom.utils` used to make its jar answer 400 `malformedPomFile` (parsed as XML), its jar
 *    signature look up a POM that was never stored (404), and its stored `maven-metadata.xml` and
 *    that file's signature fail the same way right after being written.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  artifactDir,
  artifactMetadataXml,
  groupPath,
  minimalPom,
  rawGet,
  rawPut,
  type RawResponse,
  repoTree,
  sha256Hex,
  versionDir,
  versionMetadataXml,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const OCTET = 'application/octet-stream';
const XML = 'application/xml';
const TEXT = 'text/plain';

/**
 * An `.asc` that holds no signature packet: the check refuses it (422) before it asks any key
 * server, so this test needs neither a network nor a key pair.
 */
const NO_SIGNATURE = '';

/** The armor lines of a signature with no packet in them: BouncyCastle rejects it as invalid armor. */
const ARMOR_ONLY = '-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n';

const ARTIFACT_ID = 'raw';
/** An artifactId that itself contains ".pom", the RPS-1196 case. */
const ARTIFACT_ID_POM = 'raw.pom.utils';
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
    'allowOverride:false lets a literal -SNAPSHOT be deployed again, not a timestamped build or a release',
    { tag: ['@settings', '@snapshot'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });
      const dir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      const literal = `${dir}/${ARTIFACT_ID}-1.0-SNAPSHOT`;
      const admin = adminCredential();

      // What sbt and Ivy send, twice: the version is registered after the first POM, the files
      // exist after the first round, and the second round replaces them.
      const round = (n: number): [string, string, string][] => [
        [`${literal}.pom`, minimalPom(layout.groupId, ARTIFACT_ID, SNAPSHOT), OCTET],
        [`${literal}.jar`, `literal jar ${n}`, OCTET],
        [`${literal}.jar.sha1`, `sha1 ${n}`, TEXT],
        [`${literal}-sources.jar`, `sources ${n}`, OCTET],
      ];
      for (const n of [1, 2]) {
        for (const file of round(n)) {
          expectPut(await layout.put(...file), 200, undefined, `literal round ${n} ${file[0]}`);
        }
      }
      const jar = await rawGet(layout.repoName, admin, `${literal}.jar`);
      expect(jar.status).toBe(200);
      expect(jar.body.toString(), 'the second round replaced the jar').toBe('literal jar 2');

      // A timestamped build and a release stay immutable.
      const override = 'artifactOverrideIsProhibited';
      const [firstPom, firstJar] = snapshotDeployFiles(layout, FIRST_BUILD);
      expectPut(await layout.put(...firstPom), 403, override, 'existing timestamped pom');
      expectPut(await layout.put(...firstJar), 403, override, 'existing timestamped jar');
      const releaseDir = versionDir(layout.groupId, ARTIFACT_ID, RELEASE);
      expectPut(
        await layout.put(
          `${releaseDir}/${ARTIFACT_ID}-${RELEASE}.pom`,
          minimalPom(layout.groupId, ARTIFACT_ID, RELEASE),
          OCTET,
        ),
        403,
        override,
        'existing release pom',
      );
      expectPut(
        await layout.put(`${releaseDir}/${ARTIFACT_ID}-${RELEASE}.jar`, 'other', OCTET),
        403,
        override,
        'existing release jar',
      );

      // The switch of the kind still decides (RPS-1174).
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: false,
      });
      expectPut(
        await layout.put(...round(3)[1]),
        403,
        'snapshotVersionsAreProhibited',
        'literal jar with snapshots off',
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

  test(
    'a PUT outside the artifact layout is refused with 400 and stores nothing (RPS-1182)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const releaseDir = versionDir(layout.groupId, ARTIFACT_ID, RELEASE);
      const snapshotDir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      const stray = [
        'io/stray.txt',
        // RPS-1183: the checksum of a path outside the layout is refused like its file.
        'io/stray.txt.sha1',
        `${releaseDir}/other-${RELEASE}.jar`,
        `${releaseDir}/other-${RELEASE}.jar.sha1`,
        `${releaseDir}/${ARTIFACT_ID}-9.9.jar`,
        // No extension at all.
        `${releaseDir}/${ARTIFACT_ID}-${RELEASE}`,
        `${snapshotDir}/stray.txt`,
        // A file name shorter than the artifactId makes the GAV parser throw, not answer null.
        `${snapshotDir}/x-1.0-SNAPSHOT.jar`,
      ];
      const admin = adminCredential();

      for (const path of stray) {
        expectPut(await layout.put(path, 'hello', TEXT), 400, 'invalidArtifactPath', path);
      }
      for (const path of stray) {
        const res = await rawGet(layout.repoName, admin, path);
        expect(res.status, `GET ${path} answered ${res.status}`).toBe(404);
      }

      expect(await repoTree(layout.repoName)).toEqual({});
    },
  );

  test(
    'a snapshot-directory file named for another artifact or version is refused with 400 and stores nothing (RPS-1184)',
    { tag: ['@negative', '@snapshot'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const snapshotDir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      for (const [path, body, contentType] of [
        ...snapshotDeployFiles(layout, FIRST_BUILD),
        artifactMetadata(layout, [SNAPSHOT]),
      ]) {
        expectPut(await layout.put(path, body, contentType), 200, undefined, `seed ${path}`);
      }
      const before = await repoTree(layout.repoName);
      const wrong = [
        // Another base version, literal and timestamped.
        `${snapshotDir}/${ARTIFACT_ID}-2.0-SNAPSHOT.jar`,
        `${snapshotDir}/${ARTIFACT_ID}-2.0-20260101.000000-1.jar`,
        // Another artifactId, and the same one in another case.
        `${snapshotDir}/other-1.0-SNAPSHOT.jar`,
        `${snapshotDir}/Raw-1.0-SNAPSHOT.jar`,
        // Not the literal marker, and not a timestamp.
        `${snapshotDir}/${ARTIFACT_ID}-1.0-SNAPSHOTX.jar`,
        `${snapshotDir}/${ARTIFACT_ID}-1.0-20260101-000000-1.jar`,
        // RPS-1183: the checksum of such a file is refused like the file.
        `${snapshotDir}/${ARTIFACT_ID}-2.0-SNAPSHOT.jar.sha1`,
      ];
      const admin = adminCredential();

      for (const path of wrong) {
        expectPut(await layout.put(path, 'hello', OCTET), 400, 'invalidArtifactPath', path);
      }
      for (const path of wrong) {
        const res = await rawGet(layout.repoName, admin, path);
        expect(res.status, `GET ${path} answered ${res.status}`).toBe(404);
      }

      expect(await repoTree(layout.repoName)).toEqual(before);
    },
  );

  test(
    'a checksum is judged by the file it belongs to (RPS-1183)',
    { tag: ['@settings', '@negative', '@snapshot'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      const admin = adminCredential();
      const before = await repoTree(layout.repoName);
      const newReleaseDir = versionDir(layout.groupId, ARTIFACT_ID, '3.5');
      const snapshotDir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      const [secondPom] = snapshotDeployFiles(layout, SECOND_BUILD);

      // releases:false refuses the checksum of a new release, and does not create its directory.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: false,
        snapshots: true,
      });
      const newRelease = `${newReleaseDir}/${ARTIFACT_ID}-3.5.jar.sha1`;
      expectPut(
        await layout.put(newRelease, 'da39', TEXT),
        403,
        'releaseVersionsAreProhibited',
        'checksum of a new release',
      );
      for (const path of [newRelease, `${newReleaseDir}/`]) {
        const res = await rawGet(layout.repoName, admin, path);
        expect(res.status, `GET ${path} answered ${res.status}`).toBe(404);
      }

      // snapshots:false refuses the checksums of a snapshot, at file and at version level. The
      // artifact-level metadata checksum lists both kinds and is not judged.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: false,
      });
      const refused = 'snapshotVersionsAreProhibited';
      expectPut(
        await layout.put(`${secondPom[0]}.sha1`, 'da39', TEXT),
        403,
        refused,
        'checksum of a snapshot pom',
      );
      expectPut(
        await layout.put(`${snapshotDir}/maven-metadata.xml.sha1`, 'da39', TEXT),
        403,
        refused,
        'checksum of the version-level snapshot metadata',
      );
      const artifactChecksum = `${artifactDir(layout.groupId, ARTIFACT_ID)}/maven-metadata.xml.sha1`;
      expectPut(
        await layout.put(artifactChecksum, 'da39', TEXT),
        200,
        undefined,
        'checksum of the artifact-level metadata',
      );
      expect(await repoTree(layout.repoName)).toEqual({
        ...before,
        [artifactChecksum]: sha256Hex('da39'),
      });

      // With both kinds on again, a checksum may come before its file: nothing is refused for it.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: true,
      });
      const jar = `${versionDir(layout.groupId, ARTIFACT_ID, '3.6')}/${ARTIFACT_ID}-3.6.jar`;
      expectPut(await layout.put(`${jar}.sha1`, 'da39', TEXT), 200, undefined, 'checksum first');
      expectPut(await layout.put(jar, 'jar 3.6', OCTET), 200, undefined, 'its file');
    },
  );

  test(
    'a signature of a metadata file is stored unparsed and judged by its directory (RPS-1185)',
    { tag: ['@settings', '@snapshot', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      const admin = adminCredential();
      const before = await repoTree(layout.repoName);
      const groupSignature = `${groupPath(layout.groupId)}/maven-metadata.xml.asc`;
      const artifactSignature = `${artifactDir(layout.groupId, ARTIFACT_ID)}/maven-metadata.xml.asc`;
      const snapshotSignature = `${versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT)}/maven-metadata.xml.asc`;
      const signatureChecksum = `${artifactSignature}.sha1`;

      // With everything allowed, a signature of a metadata file is stored at every level, as sent,
      // and so is the checksum of a signature. The body is armored text, not XML.
      for (const path of [groupSignature, artifactSignature, snapshotSignature]) {
        expectPut(await layout.put(path, ARMOR_ONLY, OCTET), 200, undefined, `signature ${path}`);
      }
      expectPut(
        await layout.put(signatureChecksum, 'da39', TEXT),
        200,
        undefined,
        'checksum of a metadata signature',
      );
      const stored = await rawGet(layout.repoName, admin, artifactSignature);
      expect(stored.status, `GET ${artifactSignature} answered ${stored.status}`).toBe(200);
      expect(stored.body.toString('utf8')).toBe(ARMOR_ONLY);
      const afterSignatures = {
        ...before,
        [groupSignature]: sha256Hex(ARMOR_ONLY),
        [artifactSignature]: sha256Hex(ARMOR_ONLY),
        [snapshotSignature]: sha256Hex(ARMOR_ONLY),
        [signatureChecksum]: sha256Hex('da39'),
      };
      expect(await repoTree(layout.repoName)).toEqual(afterSignatures);

      // snapshots:false refuses the version-level one, by its directory. The artifact-level one is
      // not judged, is never an override (allowOverride:false) and is not validated: a body that
      // is no signature at all replaces the previous one.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: false,
      });
      expectPut(
        await layout.put(snapshotSignature, ARMOR_ONLY, OCTET),
        403,
        'snapshotVersionsAreProhibited',
        'signature of the version-level snapshot metadata',
      );
      expectPut(
        await layout.put(artifactSignature, 'not a signature', OCTET),
        200,
        undefined,
        'signature of the artifact-level metadata, not an override',
      );
      expect(await repoTree(layout.repoName)).toEqual({
        ...afterSignatures,
        [artifactSignature]: sha256Hex('not a signature'),
      });
    },
  );

  test(
    'a refused POM signature answers 422 and leaves the version untouched (RPS-1186)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      // A snapshot (timestamped files) and a release next to it, so that the snapshot is not the only
      // version of its artifact and the release is not the only file of its directory.
      await seedBothKinds(layout);
      const before = await repoTree(layout.repoName);
      const [snapshotPom] = snapshotDeployFiles(layout, FIRST_BUILD);
      const releasePom = `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.pom`;
      const admin = adminCredential();

      for (const pom of [releasePom, snapshotPom[0]]) {
        expectPut(
          await layout.put(`${pom}.asc`, NO_SIGNATURE, OCTET),
          422,
          'artifactSignatureNotVerified',
          `${pom}.asc`,
        );
        const res = await rawGet(layout.repoName, admin, `${pom}.asc`);
        expect(res.status, `GET ${pom}.asc answered ${res.status}`).toBe(404);
      }

      // Every file, every version directory and both metadata files are byte for byte as before.
      expect(await repoTree(layout.repoName)).toEqual(before);
    },
  );

  test(
    'a POM signature that arrives before its POM answers 404 and stores nothing (RPS-1186)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const pom = `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.pom`;

      expectPut(await layout.put(`${pom}.asc`, NO_SIGNATURE, OCTET), 404, 'itemNotFound', pom);

      expect(await repoTree(layout.repoName)).toEqual({});
    },
  );

  test(
    'a .pom.asc with invalid PGP armor answers 422, not 500 (RPS-1191)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      await seedBothKinds(layout);
      const before = await repoTree(layout.repoName);
      const [snapshotPom] = snapshotDeployFiles(layout, FIRST_BUILD);
      const releasePom = `${versionDir(layout.groupId, ARTIFACT_ID, RELEASE)}/${ARTIFACT_ID}-${RELEASE}.pom`;
      const admin = adminCredential();

      for (const pom of [releasePom, snapshotPom[0]]) {
        expectPut(
          await layout.put(`${pom}.asc`, ARMOR_ONLY, OCTET),
          422,
          'artifactSignatureNotVerified',
          `${pom}.asc`,
        );
        const res = await rawGet(layout.repoName, admin, `${pom}.asc`);
        expect(res.status, `GET ${pom}.asc answered ${res.status}`).toBe(404);
      }

      expect(await repoTree(layout.repoName)).toEqual(before);
    },
  );

  test(
    "a POM whose groupId is not its directory's is refused with 400 and stores nothing (RPS-1193)",
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const admin = adminCredential();
      const pomPath = (version: string): string =>
        `${versionDir(layout.groupId, ARTIFACT_ID, version)}/${ARTIFACT_ID}-${version}.pom`;
      const pomWith = (groupElements: string, version: string): string =>
        '<?xml version="1.0" encoding="UTF-8"?>\n' +
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n' +
        '  <modelVersion>4.0.0</modelVersion>\n' +
        `  ${groupElements}\n` +
        `  <artifactId>${ARTIFACT_ID}</artifactId>\n` +
        `  <version>${version}</version>\n` +
        '</project>\n';
      const parentOf = (groupId: string): string =>
        `<parent><groupId>${groupId}</groupId><artifactId>par</artifactId><version>1</version></parent>`;
      const refusedMessage = 'pomGroupIdMismatch';

      const refused: [string, string][] = [
        ['its own groupId is another', minimalPom('org.other', ARTIFACT_ID, RELEASE)],
        ['only its parent has another groupId', pomWith(parentOf('org.other'), RELEASE)],
        ['its groupId is an expression', pomWith('<groupId>${g}</groupId>', RELEASE)],
        [
          'its groupId differs in case',
          minimalPom(layout.groupId.toUpperCase(), ARTIFACT_ID, RELEASE),
        ],
      ];
      for (const [what, pom] of refused) {
        expectPut(await layout.put(pomPath(RELEASE), pom, OCTET), 400, refusedMessage, what);
        const res = await rawGet(layout.repoName, admin, pomPath(RELEASE));
        expect(res.status, `${what}: GET answered ${res.status}`).toBe(404);
      }
      expect(await repoTree(layout.repoName)).toEqual({});

      // The POMs real clients send: the group of the path, inherited from the parent, or none at all
      // (the artifactId and version are never compared, so a matching group is enough).
      const accepted: [string, string, string][] = [
        ['2.0', 'its own groupId', minimalPom(layout.groupId, ARTIFACT_ID, '2.0')],
        ['2.1', 'the groupId of its parent', pomWith(parentOf(layout.groupId), '2.1')],
        ['2.2', 'no groupId and no parent', pomWith('', '2.2')],
      ];
      for (const [version, what, pom] of accepted) {
        expectPut(await layout.put(pomPath(version), pom, OCTET), 200, undefined, what);
        const res = await rawGet(layout.repoName, admin, pomPath(version));
        expect(res.status, `${what}: GET answered ${res.status}`).toBe(200);
        expect(res.body.toString('utf8'), `${what}: the stored POM`).toBe(pom);
      }

      const tree = await repoTree(layout.repoName);
      expect(Object.keys(tree).sort()).toEqual(
        accepted.map(([version]) => pomPath(version)).sort(),
      );
    },
  );

  test(
    'the files real Maven and Gradle clients send are all stored',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const releaseDir = versionDir(layout.groupId, ARTIFACT_ID, RELEASE);
      const snapshotDir = versionDir(layout.groupId, ARTIFACT_ID, SNAPSHOT);
      const base = `${releaseDir}/${ARTIFACT_ID}-${RELEASE}`;
      const checksum = 'da39a3ee5e6b4b0d3255bfef95601890afd80709';

      const files = new Map<string, string>([
        [`${base}.pom`, minimalPom(layout.groupId, ARTIFACT_ID, RELEASE)],
        [`${base}.jar`, 'jar'],
        [`${base}-sources.jar`, 'sources'],
        [`${base}-javadoc.jar`, 'javadoc'],
        [`${base}-tests.jar`, 'tests'],
        [`${base}.module`, '{"formatVersion":"1.1"}'],
        [`${base}-kotlin-tooling-metadata.json`, '{"schemaVersion":"1.0.0"}'],
        [`${base}.klib`, 'klib'],
        [`${base}.tar.gz`, 'tarball'],
        [`${base}.jar.asc`, '-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n'],
        [`${base}.jar.md5`, checksum.slice(0, 32)],
        [`${base}.jar.sha1`, checksum],
        [`${base}.jar.sha256`, sha256Hex('jar')],
        [`${base}.jar.sha512`, `${sha256Hex('jar')}${sha256Hex('jar')}`],
        [`${base}.module.sha512`, `${sha256Hex('module')}${sha256Hex('module')}`],
        [`${snapshotDir}/${ARTIFACT_ID}-1.0-20260101.000000-1.jar`, 'timestamped'],
        [`${snapshotDir}/${ARTIFACT_ID}-1.0-SNAPSHOT.jar`, 'literal snapshot'],
        [`${snapshotDir}/${ARTIFACT_ID}-1.0-20260101.000000-1-sources.jar`, 'timestamped sources'],
        [`${snapshotDir}/${ARTIFACT_ID}-1.0-SNAPSHOT-sources.jar`, 'literal snapshot sources'],
      ]);

      for (const [path, body] of files) {
        expectPut(await layout.put(path, body, OCTET), 200, undefined, path);
      }

      const admin = adminCredential();
      for (const [path, body] of files) {
        const res = await rawGet(layout.repoName, admin, path);
        expect(res.status, `GET ${path} answered ${res.status}`).toBe(200);
        expect(res.body.toString('utf8'), `the body of ${path}`).toBe(body);
      }

      const tree = await repoTree(layout.repoName);
      expect(Object.keys(tree).sort()).toEqual([...files.keys()].sort());
    },
  );

  test(
    'an artifactId containing ".pom" deploys like any other (RPS-1196)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const releaseDir = versionDir(layout.groupId, ARTIFACT_ID_POM, RELEASE);
      const base = `${releaseDir}/${ARTIFACT_ID_POM}-${RELEASE}`;
      const checksum = 'da39a3ee5e6b4b0d3255bfef95601890afd80709';
      const artifactMetaPath = `${artifactDir(layout.groupId, ARTIFACT_ID_POM)}/maven-metadata.xml`;
      const artifactMetaXml = artifactMetadataXml({
        groupId: layout.groupId,
        artifactId: ARTIFACT_ID_POM,
        versions: [RELEASE],
      });

      const files = new Map<string, string>([
        [`${base}.pom`, minimalPom(layout.groupId, ARTIFACT_ID_POM, RELEASE)],
        [`${base}.pom.sha1`, checksum],
        [`${base}.jar`, 'jar'],
        [`${base}.jar.sha1`, checksum],
        [`${base}.jar.asc`, ARMOR_ONLY],
        [`${base}-sources.jar`, 'sources'],
        [artifactMetaPath, artifactMetaXml],
        [`${artifactMetaPath}.sha1`, checksum],
        [`${artifactMetaPath}.asc`, ARMOR_ONLY],
      ]);

      for (const [path, body] of files) {
        expectPut(await layout.put(path, body, OCTET), 200, undefined, path);
      }

      const admin = adminCredential();
      for (const [path, body] of files) {
        const res = await rawGet(layout.repoName, admin, path);
        expect(res.status, `GET ${path} answered ${res.status}`).toBe(200);
        expect(res.body.toString('utf8'), `the body of ${path}`).toBe(body);
      }

      const tree = await repoTree(layout.repoName);
      expect(Object.keys(tree).sort()).toEqual([...files.keys()].sort());

      // A malformed POM under the same artifactId is still parsed and refused, not stored.
      const malformedPath = `${versionDir(layout.groupId, ARTIFACT_ID_POM, '3.0')}/${ARTIFACT_ID_POM}-3.0.pom`;
      expectPut(
        await layout.put(malformedPath, '<project><groupId>', OCTET),
        400,
        'malformedPomFile',
        malformedPath,
      );
      const malformedRes = await rawGet(layout.repoName, admin, malformedPath);
      expect(malformedRes.status, `GET ${malformedPath} answered ${malformedRes.status}`).toBe(404);

      // A .pom.asc under the same artifactId is still verified and refused, not silently stored.
      expectPut(
        await layout.put(`${base}.pom.asc`, ARMOR_ONLY, OCTET),
        422,
        'artifactSignatureNotVerified',
        `${base}.pom.asc`,
      );
      const ascRes = await rawGet(layout.repoName, admin, `${base}.pom.asc`);
      expect(ascRes.status, `GET ${base}.pom.asc answered ${ascRes.status}`).toBe(404);
    },
  );
});
