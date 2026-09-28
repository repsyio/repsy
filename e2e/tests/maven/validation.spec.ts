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
 * RPS-1716: Maven validation edge cases — groupId/artifactId/version length limits and Bearer
 * deploy-token auth behavior.
 *
 * - Length limits: probe live what length limits exist for groupId, artifactId, and version on
 *   publish; pin refusal at the boundary (one under = ok, one at/over = refused with 400 and
 *   the right msgId).
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

const OCTET = 'application/octet-stream';

interface Layout {
  repoName: string;
  groupId: string;
  artifactId: string;
  put: (relPath: string, body: string, contentType: string) => Promise<RawResponse>;
}

/** A fresh maven repo (permissive defaults) and an admin-credentialed PUT into it. */
async function newRepo(seeder: any, groupId?: string, artifactId?: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const admin = adminCredential();
  return {
    repoName: repo.name,
    groupId: groupId || `io.repsy.e2e.${seeder.runId}`,
    artifactId: artifactId || 'test-artifact',
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
  if (msgId !== undefined) {
    expect(res.msgId, `${what}: error message id should be ${msgId}`).toBe(msgId);
  }
}

test.describe('maven validation edge cases (RPS-1716)', () => {
  test('groupId at 255 chars (max) succeeds', { tag: ['@negative'] }, async ({ seeder }) => {
    // MavenPublishLimits.MAX_GROUP_ID_LENGTH = 255
    const maxGroupId = 'a'.repeat(255);
    const layout = await newRepo(seeder, maxGroupId, 'test');
    const version = '1.0';

    const dir = versionDir(maxGroupId, 'test', version);
    const path = `${dir}/test-${version}.pom`;
    const body = minimalPom(maxGroupId, 'test', version);

    const result = await layout.put(path, body, OCTET);

    expect(result.status, `groupId at max length (255 chars) should succeed`).toBe(200);
  });

  test('groupId at 256 chars (over max) is refused', { tag: ['@negative'] }, async ({
    seeder,
  }) => {
    // MavenPublishLimits.MAX_GROUP_ID_LENGTH = 255. Test one over.
    const tooLongGroupId = 'a'.repeat(256);
    const layout = await newRepo(seeder, tooLongGroupId, 'test');
    const version = '1.0';

    const dir = versionDir(tooLongGroupId, 'test', version);
    const path = `${dir}/test-${version}.pom`;
    const body = minimalPom(tooLongGroupId, 'test', version);

    const result = await layout.put(path, body, OCTET);

    expectPut(
      result,
      400,
      'groupIdTooLong',
      'groupId over max length (256 chars) should fail with groupIdTooLong',
    );
  });

  test('artifactId at 255 chars (max) succeeds', { tag: ['@negative'] }, async ({ seeder }) => {
    // MavenPublishLimits.MAX_ARTIFACT_ID_LENGTH = 255. Test at exact documented boundary.
    const maxArtifactId = 'a'.repeat(255);
    const layout = await newRepo(seeder, 'io', maxArtifactId);
    const version = '1';

    const dir = versionDir(layout.groupId, maxArtifactId, version);
    const path = `${dir}/${maxArtifactId}-${version}.pom`;
    const body = minimalPom(layout.groupId, maxArtifactId, version);

    const result = await layout.put(path, body, OCTET);

    expect(result.status, `artifactId at 255 chars (max) should succeed`).toBe(200);
  });

  test('artifactId at 256 chars (over max) is refused', { tag: ['@negative'] }, async ({
    seeder,
  }) => {
    // MavenPublishLimits.MAX_ARTIFACT_ID_LENGTH = 255. Test one over.
    const tooLongArtifactId = 'a'.repeat(256);
    // Use short groupId to keep path length reasonable (filesystem limits)
    const layout = await newRepo(seeder, 'io', tooLongArtifactId);
    const version = '1';

    const dir = versionDir(layout.groupId, tooLongArtifactId, version);
    const path = `${dir}/${tooLongArtifactId}-${version}.pom`;
    const body = minimalPom(layout.groupId, tooLongArtifactId, version);

    const result = await layout.put(path, body, OCTET);

    expectPut(
      result,
      400,
      'artifactIdTooLong',
      'artifactId over max length (256 chars) should fail with artifactIdTooLong',
    );
  });

  test('version at 255 chars (max) succeeds', { tag: ['@negative'] }, async ({ seeder }) => {
    // MavenPublishLimits.MAX_VERSION_LENGTH = 255. Test at exact documented boundary.
    const maxVersion = 'a'.repeat(255);
    // Use short groupId and artifactId to keep test simple
    const layout = await newRepo(seeder, 'io', 'a');

    const dir = versionDir(layout.groupId, layout.artifactId, maxVersion);
    const path = `${dir}/${layout.artifactId}-${maxVersion}.pom`;
    const body = minimalPom(layout.groupId, layout.artifactId, maxVersion);

    const result = await layout.put(path, body, OCTET);

    expect(result.status, `version at 255 chars (max) should succeed`).toBe(200);
  });

  test('version at 256 chars (over max) is refused', { tag: ['@negative'] }, async ({
    seeder,
  }) => {
    // MavenPublishLimits.MAX_VERSION_LENGTH = 255. Test one over.
    const tooLongVersion = 'a'.repeat(256);
    // Use short groupId and artifactId to keep path length reasonable (filesystem limits)
    const layout = await newRepo(seeder, 'io', 'a');

    const dir = versionDir(layout.groupId, layout.artifactId, tooLongVersion);
    const path = `${dir}/${layout.artifactId}-${tooLongVersion}.pom`;
    const body = minimalPom(layout.groupId, layout.artifactId, tooLongVersion);

    const result = await layout.put(path, body, OCTET);

    expectPut(
      result,
      400,
      'mavenVersionTooLong',
      'version over max length (256 chars) should fail with mavenVersionTooLong',
    );
  });

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
