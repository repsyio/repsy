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
 * Checks specific to what an Ivy publish leaves in a Maven repository (RPS-135): the jar and the POM
 * `ivy:makepom` wrote, each followed by the `.sha1` and `.md5` Ivy computes and sends, and nothing else
 * (Ivy sends no `maven-metadata.xml`, at either level, and with `publishivy="false"` no ivy file).
 */
import { createHash } from 'node:crypto';

import { expect } from '@playwright/test';

import type { AdapterResult } from '../scenarios/adapter.js';
import type { World } from '../scenarios/world.js';
import { adminCredential, rawGet, sha256Hex, splitPackageName, versionDir } from './maven-raw.js';

const digestHex = (algorithm: 'sha1' | 'md5', body: Buffer): string =>
  createHash(algorithm).update(body).digest('hex');

/**
 * A publish that succeeded (the latest one, for a redeploy): the jar stored under the version is the
 * jar Ivy built (so the repository holds the latest publish's bytes), the POM is stored beside it, and
 * each of the two has the `.sha1` and `.md5` of exactly its own bytes.
 */
export async function expectPublishStored(
  w: World,
  published: Pick<AdapterResult, 'contentSha256'>,
): Promise<void> {
  const [groupId, artifactId] = splitPackageName(w.publishTarget.packageName);
  const version = w.publishTarget.version;
  const stem = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}`;
  const admin = adminCredential();

  const jar = await rawGet(w.repoName, admin, `${stem}.jar`);
  expect(jar.status, `GET ${stem}.jar`).toBe(200);
  expect(sha256Hex(jar.body), 'the stored jar is the one Ivy built').toBe(published.contentSha256);

  for (const file of [`${stem}.jar`, `${stem}.pom`]) {
    const body = await rawGet(w.repoName, admin, file);
    expect(body.status, `GET ${file}`).toBe(200);
    for (const algorithm of ['sha1', 'md5'] as const) {
      const checksum = await rawGet(w.repoName, admin, `${file}.${algorithm}`);
      expect(checksum.status, `GET ${file}.${algorithm}`).toBe(200);
      expect(checksum.body.toString('utf8').trim(), `${file}.${algorithm}`).toBe(
        digestHex(algorithm, body.body),
      );
    }
  }
}
