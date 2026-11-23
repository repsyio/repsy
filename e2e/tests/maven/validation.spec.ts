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
 * RPS-1716: Maven validation edge cases: the file name length boundary and Bearer deploy-token auth.
 *
 * - The length limits of groupId, artifactId and version (255 characters, 400 groupIdTooLong,
 *   artifactIdTooLong, mavenVersionTooLong, mavenFileNameTooLong) are `MavenPublishLimitsIT` and
 *   `MavenPublishLimitsTest` since RPS-2738. What stays is the artifactId sized so the stored file name is
 *   exactly 255 bytes, which only the real file system answers.
 * - Bearer deploy-token auth: probe whether Maven accepts raw deploy-token Bearer tokens (unlike
 *   Docker which explicitly rejects them). Send a deploy token's raw secret as a Bearer value and
 *   observe the behavior.
 */

import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  minimalPom,
  rawPut,
  type RawResponse,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const OCTET = 'application/octet-stream';

interface Layout {
  repoName: string;
  groupId: string;
  artifactId: string;
  put: (relPath: string, body: string, contentType: string) => Promise<RawResponse>;
}

/** A fresh maven repo (permissive defaults) and an admin-credentialed PUT into it. */
async function newRepo(seeder: Seeder, groupId?: string, artifactId?: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const admin = adminCredential();
  return {
    repoName: repo.name,
    groupId: groupId || `io.repsy.e2e.${seeder.runId}`,
    artifactId: artifactId || 'test-artifact',
    put: (relPath, body, contentType) => rawPut(repo.name, admin, relPath, body, contentType),
  };
}

test.describe('maven validation edge cases (RPS-1716)', () => {
  test(
    'artifactId sized so the stored file name is exactly 255 bytes succeeds',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      // RPS-1732: MavenPublishLimits.MAX_ARTIFACT_ID_LENGTH = 255, but the storage layer names the
      // stored file `<artifactId>-<version>.pom`, and most POSIX file systems (ext4 included) cap a
      // single file name component at 255 bytes. With a 1-char version ("1"), the file name is
      // `<artifactId>-1.pom` = artifactId.length + 6 bytes, so artifactId.length = 249 is the exact
      // byte-for-byte boundary the file system still accepts. This is the real, storage-safe limit;
      // MavenPublishLimits.checkFileNameLength enforces it as a 400 rather than letting a longer
      // combination reach the storage layer.
      const boundaryArtifactId = 'a'.repeat(249);
      const layout = await newRepo(seeder, 'io', boundaryArtifactId);
      const version = '1';

      const dir = versionDir(layout.groupId, boundaryArtifactId, version);
      const path = `${dir}/${boundaryArtifactId}-${version}.pom`;
      const body = minimalPom(layout.groupId, boundaryArtifactId, version);

      const result = await layout.put(path, body, OCTET);

      expect(
        result.status,
        `artifactId at 249 chars (file name exactly 255 bytes) should succeed`,
      ).toBe(200);
    },
  );

  test(
    'raw deploy-token Bearer: Maven accepts it (not like Docker which rejects it)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder);
      const version = '1.0-bearer-auth';

      // Create a deploy token
      const deployToken = await seeder.createToken(layout.repoName, { readOnly: false });

      // Build a simple request path: /groupId/artifactId/version/artifactId-version.pom
      const dir = versionDir(layout.groupId, layout.artifactId, version);
      const relPath = `${dir}/${layout.artifactId}-${version}.pom`;
      const body = minimalPom(layout.groupId, layout.artifactId, version);

      // Use the raw deploy token (its secret value) as a Bearer token
      // Unlike Docker, Maven's ProtocolAuthService.acceptsRawDeployTokenBearer() returns true by default
      const bearerAuth = `Bearer ${deployToken.token}`;

      // Make a raw PUT request with Bearer auth using the deploy token's secret
      const url = repoUrl(layout.repoName, relPath);
      const result = await fetch(url, {
        method: 'PUT',
        headers: {
          Authorization: bearerAuth,
          'Content-Type': OCTET,
        },
        body,
      }).then(async (res) => ({
        status: res.status,
        body: await res.text(),
      }));

      // Maven should ACCEPT the raw deploy token as a Bearer value (200 or 201)
      // This is the key difference from Docker, which rejects it with 401
      expect(
        result.status,
        'Maven accepts raw deploy-token Bearer (unlike Docker which rejects it)',
      ).toBeLessThan(400); // 2xx status means it was accepted
    },
  );
});
