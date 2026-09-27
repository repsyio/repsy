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
 * Maven seeder: PUTs a real (fflate-built) jar and a minimal POM, then the artifact-level
 * `maven-metadata.xml` a real deploy ends with. `opts.name` is `groupId:artifactId`.
 *
 * A version ending in `-SNAPSHOT` is deployed the way `mvn deploy` does it (RPS-1626): one or more
 * TIMESTAMPED builds (`lib-1.0-20260921.101010-1.jar`, `.pom`), the version-level
 * `<version>-SNAPSHOT/maven-metadata.xml` of the newest build, then the artifact-level metadata. With
 * `opts.maven.sign` the POM (and, for `files: 'all'`, the jar) of every build gets a real detached
 * `.asc` made by `sign.key`; the key must already be registered on the repo
 * (`seeder.registerPgpPublicKey`), or the server refuses the signature and the seed throws.
 */
import { adminCredential } from '../../clients/raw-http.js';
import {
  artifactDir,
  artifactMetadataXml,
  baseVersion,
  buildJar,
  isSnapshotVersion,
  minimalPom,
  parseArtifactVersions,
  rawGet,
  rawPut,
  splitPackageName,
  versionDir,
  versionMetadataXml,
} from '../../clients/maven-raw.js';
import { detachedSign, type PgpKeyPair } from '../../clients/pgp.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

const OCTET = 'application/octet-stream';
const XML = 'application/xml';

/** Maven-only seed options (`SeedPackageOptions.maven`). */
export interface MavenSeedOptions {
  /** A `-SNAPSHOT` version only: how many timestamped builds to deploy, one second apart. Default 1. */
  builds?: number;
  /**
   * A `-SNAPSHOT` version only: the build number of the first build, so a second call adds NEWER builds to a version
   * (`{ builds: 1, firstBuild: 3 }` deploys build 3, and its metadata names it as the newest). Default 1.
   */
  firstBuild?: number;
  /**
   * Signs what is deployed with a real key registered on the repo: `pom` sends a `.pom.asc` per build,
   * `all` a `.jar.asc` too (what a repo with `pgpVerifyAllSignaturesEnabled` needs to call it signed).
   */
  sign?: { key: PgpKeyPair; files?: 'pom' | 'all' };
}

/** The first snapshot build's time; later builds are `n` seconds after it. Fixed, so a seed is reproducible. */
const FIRST_BUILD_AT = Date.UTC(2026, 0, 1, 10, 0, 0);

/** `20260101.100000` for build `n` (1-based). */
function buildTimestamp(n: number): string {
  const iso = new Date(FIRST_BUILD_AT + (n - 1) * 1000).toISOString();
  return `${iso.slice(0, 10).replace(/-/g, '')}.${iso.slice(11, 19).replace(/:/g, '')}`;
}

export const seedMaven: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('maven', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;
  const [groupId, artifactId] = splitPackageName(name);
  const admin = adminCredential();
  const dir = versionDir(groupId, artifactId, version);
  const snapshot = isSnapshotVersion(version);
  const builds = snapshot ? (opts.maven?.builds ?? 1) : 1;
  const firstBuild = snapshot ? (opts.maven?.firstBuild ?? 1) : 1;
  const lastBuild = firstBuild + builds - 1;
  const sign = opts.maven?.sign;
  const put = async (path: string, body: Uint8Array | string, type: string): Promise<void> => {
    expectPublished(await rawPut(repoName, admin, path, body, type), `PUT ${path}`);
  };

  // A real client sends the artifacts first, then the metadata that lists them.
  const extra: Record<string, string> = {};
  for (let build = firstBuild; build <= lastBuild; build++) {
    const timestamp = buildTimestamp(build);
    const stem = snapshot
      ? `${artifactId}-${baseVersion(version)}-${timestamp}-${build}`
      : `${artifactId}-${version}`;
    const base = `${dir}/${stem}`;
    const jar = buildJar({
      groupId,
      artifactId,
      version: snapshot ? `${version}#${build}` : version,
    });
    const pom = minimalPom(groupId, artifactId, version);
    await put(`${base}.jar`, jar, OCTET);
    if (sign?.files === 'all') {
      await put(`${base}.jar.asc`, await detachedSign(sign.key.privateKeyArmored, jar), OCTET);
    }
    await put(`${base}.pom`, pom, OCTET);
    if (sign) {
      await put(
        `${base}.pom.asc`,
        await detachedSign(sign.key.privateKeyArmored, Buffer.from(pom)),
        OCTET,
      );
    }
    if (snapshot) {
      extra.buildNumber = String(build);
      extra.timestamp = timestamp;
      extra.lastFile = stem;
    }
  }
  if (snapshot) {
    await put(
      `${dir}/maven-metadata.xml`,
      versionMetadataXml({
        groupId,
        artifactId,
        version,
        timestamp: extra.timestamp,
        buildNumber: lastBuild,
      }),
      XML,
    );
  }
  // Like Maven, merge into the artifact-level metadata that is already there: it lists EVERY version of the artifact.
  const metadataPath = `${artifactDir(groupId, artifactId)}/maven-metadata.xml`;
  const existing = await rawGet(repoName, admin, `/${metadataPath}`);
  const known =
    existing.status === 200 ? parseArtifactVersions(existing.body.toString('utf8')) : [];
  await put(
    metadataPath,
    artifactMetadataXml({
      groupId,
      artifactId,
      versions: known.includes(version) ? known : [...known, version],
    }),
    XML,
  );

  return { protocol: 'maven', repoName, name, version, extra };
};
