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
 * Maven-specific checks the scenario loop (`scenarios/loop.ts`) runs through the `ProtocolAdapter`
 * hooks `expectNothingStored`/`afterSuccessfulRoundTrip` (`clients/maven-adapter.ts`). Moved
 * verbatim out of `tests/maven/publish-consume.spec.ts` (step 3a, RPS-294): they are assertions
 * about maven's own repo layout (`maven-raw.ts`'s directory-listing walk and `maven-metadata.xml`
 * parsers), not part of the generalised loop itself.
 */
import { expect } from '@playwright/test';

import type { AdapterResult } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';
import type { World } from '../scenarios/world.js';
import {
  adminCredential,
  artifactDir,
  baseVersion,
  parseArtifactVersions,
  parseListing,
  parseVersionMetadata,
  rawGet,
  repoTree,
  type RepoTree,
  splitPackageName,
  versionDir,
} from './maven-raw.js';

/**
 * After a refused publish: the repository is byte-for-byte what it was before (so nothing was
 * stored and nothing was overwritten, metadata included), and a version that did not exist before
 * still does not, anywhere: no version directory, no version-level metadata, no files, and not
 * listed in the artifact-level metadata (which may legitimately exist for another version).
 */
export async function expectNothingStored(w: World, before: RepoTree): Promise<void> {
  expect(
    await repoTree(w.repoName),
    'a refused deploy must leave the repository exactly as it was',
  ).toEqual(before);

  const [groupId, artifactId] = splitPackageName(w.publishTarget.packageName);
  const version = w.publishTarget.version;
  const dir = versionDir(groupId, artifactId, version);

  if (Object.keys(before).some((path) => path.startsWith(`${dir}/`))) {
    return; // A redeploy: the version existed, the tree comparison above is the whole check.
  }

  const admin = adminCredential();
  for (const path of [
    `${dir}/`,
    `${dir}/maven-metadata.xml`,
    `${dir}/${artifactId}-${version}.pom`,
    `${dir}/${artifactId}-${version}.jar`,
  ]) {
    const res = await rawGet(w.repoName, admin, path);
    expect(res.status, `GET ${path} after the refused deploy`).toBe(404);
  }

  const artifactMetadata = await rawGet(
    w.repoName,
    admin,
    `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );
  if (artifactMetadata.status === 200) {
    expect(parseArtifactVersions(artifactMetadata.body.toString('utf8'))).not.toContain(version);
  } else {
    expect(artifactMetadata.status).toBe(404);
  }
}

/**
 * A SNAPSHOT that was deployed (once, or twice for a redeploy scenario) and resolved: the
 * version-level metadata names the latest build, the consumer resolved exactly that timestamped
 * jar, every deploy's timestamped jar is still stored, and the artifact-level metadata lists the
 * version once.
 */
export async function expectSnapshotFollowedThroughMetadata(
  w: World,
  scenario: Scenario,
  resolved: AdapterResult,
): Promise<void> {
  const [groupId, artifactId] = splitPackageName(w.consumeTarget.packageName);
  const version = w.consumeTarget.version;
  const dir = versionDir(groupId, artifactId, version);
  const deploys = scenario.reuseCoordinates ? 2 : 1;
  const admin = adminCredential();

  const metadataRes = await rawGet(w.repoName, admin, `${dir}/maven-metadata.xml`);
  expect(metadataRes.status, `GET ${dir}/maven-metadata.xml`).toBe(200);
  const metadata = parseVersionMetadata(metadataRes.body.toString('utf8'));
  expect(metadata.version).toBe(version);
  expect(metadata.buildNumber, 'the buildNumber counts the deploys of this SNAPSHOT').toBe(deploys);

  const latest = `${baseVersion(version)}-${metadata.timestamp}-${metadata.buildNumber}`;
  expect(metadata.snapshotVersions).toEqual({ pom: latest, jar: latest });
  expect(resolved.resolvedFile, 'the consumer resolves the latest timestamped jar').toBe(
    `${artifactId}-${latest}.jar`,
  );

  const listing = await rawGet(w.repoName, admin, `${dir}/`);
  const buildNumbers = parseListing(listing.body.toString('utf8'))
    .map((entry) => /-\d{8}\.\d{6}-(\d+)\.jar$/.exec(entry)?.[1])
    .filter((buildNumber) => buildNumber !== undefined)
    .map(Number);
  expect(buildNumbers, 'every deploy keeps its own timestamped jar').toEqual(
    Array.from({ length: deploys }, (_, i) => i + 1),
  );

  const artifactMetadata = await rawGet(
    w.repoName,
    admin,
    `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );
  expect(parseArtifactVersions(artifactMetadata.body.toString('utf8'))).toEqual([version]);
}
