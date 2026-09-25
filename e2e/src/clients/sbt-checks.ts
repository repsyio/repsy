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
 * Checks specific to what an sbt publish leaves in a Maven repository (RPS-134): a SNAPSHOT is
 * published NON-uniquely, under its literal `-SNAPSHOT` file names, and sbt sends no
 * `maven-metadata.xml` at any level.
 */
import { expect } from '@playwright/test';

import type { AdapterResult } from '../scenarios/adapter.js';
import type { World } from '../scenarios/world.js';
import { adminCredential, rawGet, sha256Hex, splitPackageName, versionDir } from './maven-raw.js';

/**
 * A SNAPSHOT that sbt published (once, or twice for a redeploy scenario) and resolved: the jar and the
 * POM sit under their literal names, the latest deploy's jar replaced the earlier one in place (so
 * the consumer resolved the latest publish's bytes), and no timestamped file or version-level
 * metadata exists, because sbt never sends either.
 */
export async function expectLiteralSnapshotStored(
  w: World,
  resolved: AdapterResult,
): Promise<void> {
  const [groupId, artifactId] = splitPackageName(w.consumeTarget.packageName);
  const version = w.consumeTarget.version;
  const dir = versionDir(groupId, artifactId, version);
  const admin = adminCredential();

  const jar = await rawGet(w.repoName, admin, `${dir}/${artifactId}-${version}.jar`);
  expect(jar.status, `GET ${dir}/${artifactId}-${version}.jar`).toBe(200);
  expect(
    resolved.contentSha256,
    'the consumer resolves the jar stored under the literal name',
  ).toBe(sha256Hex(jar.body));
  const pom = await rawGet(w.repoName, admin, `${dir}/${artifactId}-${version}.pom`);
  expect(pom.status, `GET ${dir}/${artifactId}-${version}.pom`).toBe(200);

  const metadata = await rawGet(w.repoName, admin, `${dir}/maven-metadata.xml`);
  expect(metadata.status, 'sbt sends no version-level maven-metadata.xml').toBe(404);
}
