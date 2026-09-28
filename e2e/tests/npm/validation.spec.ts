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
 * RPS-1717: npm validation edge cases: package name/version length limits, packageNameMismatch,
 * unpublish races, and cross-user token revoke.
 *
 * - Name/version length limits: probe live what length limits exist for a package name and
 *   version string on publish; pin refusal at the boundary (one under = ok, one at/over = refused
 *   with the right status/msgId).
 * - packageNameMismatch: publish a tarball whose package.json name field doesn't match the URL path's
 *   package name; probe live for the actual refusal behavior and pin it.
 * - Unpublish races: concurrent unpublish of the same version, or unpublish racing a fresh publish
 *   of the same coordinate. Probe live first to see actual behavior (don't guess at a race outcome;
 *   if it's genuinely non-deterministic, pin the invariant that holds either way).
 * - Cross-user token revoke: while a user's deploy token is being used mid-request (or just: revoke
 *   a token, confirm ALL subsequent requests with it are refused, including any cached/in-flight
 *   assumption).
 */

import { RepoType } from '../../src/api/panel-api.js';
import * as npm from '../../src/clients/npm.js';
import { npmAdapter } from '../../src/clients/npm.js';
import {
  adminCredential,
  rawPublish,
  rawGetPackument,
  buildPublishDocument,
  buildTarball,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential, World } from '../../src/scenarios/world.js';
import type { Scenario } from '../../src/scenarios/types.js';

const SCENARIO: Scenario = {
  id: 'validation-edge-cases',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

function worldFor(
  repoName: string,
  packageName: string,
  version: string,
  credential: MaterializedCredential = adminCredential(),
): World {
  const target = { packageName, version };
  return {
    scenario: SCENARIO,
    protocol: 'npm',
    repoName,
    credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

test.describe('npm validation edge cases (RPS-1717)', () => {
  test('package name at 214 chars (max) succeeds', { tag: ['@negative'] }, async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    // NpmPublishLimits.MAX_NAME_LENGTH = 214. Test at exactly the limit.
    const maxName = 'a'.repeat(214);
    const version = npmAdapter.version('release');

    const tarball = buildTarball({ packageName: maxName, version });
    const publishDoc = buildPublishDocument({
      repoName: repo.name,
      packageName: maxName,
      version,
      tarballBytes: tarball,
    });

    const result = await rawPublish(repo.name, adminCredential(), maxName, publishDoc);

    expect(result.status, `raw publish at max name length (214 chars)`).toBe(200);

    // Verify it's stored
    const packument = await rawGetPackument(repo.name, adminCredential(), maxName);
    expect(packument.status, `packument of max-length name ${maxName}`).toBe(200);
  });

  test('package name at 215 chars (over max) is refused', { tag: ['@negative'] }, async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    // NpmPublishLimits.MAX_NAME_LENGTH = 214. Test one over.
    const tooLongName = 'a'.repeat(215);
    const version = npmAdapter.version('release');

    const tarball = buildTarball({ packageName: tooLongName, version });
    const publishDoc = buildPublishDocument({
      repoName: repo.name,
      packageName: tooLongName,
      version,
      tarballBytes: tarball,
    });

    const result = await rawPublish(repo.name, adminCredential(), tooLongName, publishDoc);

    expect(result.status, `raw publish over max name length (215 chars) should fail`).not.toBe(200);
    expect(result.status, `should be 400`).toBe(400);

    // Verify it was not stored
    const packument = await rawGetPackument(repo.name, adminCredential(), tooLongName);
    expect(packument.status, `packument of too-long name should not exist`).toBe(404);
  });

  test('version at 128 chars (max) succeeds', { tag: ['@negative'] }, async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    // NpmPublishLimits.MAX_VERSION_LENGTH = 128. Build a version exactly at the limit.
    const versionAtLimit = '1.2.3-' + 'a'.repeat(122); // "1.2.3-" is 6 chars, + 122 = 128 total
    const packageName = `e2e-${seeder.runId}-version-limit`;

    const tarball = buildTarball({ packageName, version: versionAtLimit });
    const publishDoc = buildPublishDocument({
      repoName: repo.name,
      packageName,
      version: versionAtLimit,
      tarballBytes: tarball,
    });

    const result = await rawPublish(repo.name, adminCredential(), packageName, publishDoc);

    expect(result.status, `raw publish at max version length (128 chars)`).toBe(200);

    // Verify it's stored
    const packument = await rawGetPackument(repo.name, adminCredential(), packageName);
    expect(packument.status, `packument with max-length version`).toBe(200);
  });

  test('version at 129 chars (over max) is refused', { tag: ['@negative'] }, async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    // NpmPublishLimits.MAX_VERSION_LENGTH = 128. Test one over.
    const versionOverLimit = '1.2.3-' + 'a'.repeat(123); // "1.2.3-" is 6 chars, + 123 = 129 total
    const packageName = `e2e-${seeder.runId}-version-over`;

    const tarball = buildTarball({ packageName, version: versionOverLimit });
    const publishDoc = buildPublishDocument({
      repoName: repo.name,
      packageName,
      version: versionOverLimit,
      tarballBytes: tarball,
    });

    const result = await rawPublish(repo.name, adminCredential(), packageName, publishDoc);

    expect(result.status, `raw publish over max version length (129 chars) should fail`).not.toBe(
      200,
    );
    expect(result.status, `should be 400`).toBe(400);

    // Verify it was not stored
    const packument = await rawGetPackument(repo.name, adminCredential(), packageName);
    expect(packument.status, `packument should not exist`).toBe(404);
  });

  test('packageNameMismatch: name in tarball metadata differs from URL', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

    const urlName = `e2e-${seeder.runId}-url-name`;
    const jsonName = `e2e-${seeder.runId}-json-name`;
    const version = npmAdapter.version('release');

    // Build a publish document with one name, but publish under a different URL
    const tarball = buildTarball({ packageName: jsonName, version });
    const publishDoc = buildPublishDocument({
      repoName: repo.name,
      packageName: jsonName, // name inside the tarball
      version,
      tarballBytes: tarball,
    });

    // Try to publish under a different name in the URL
    const result = await rawPublish(repo.name, adminCredential(), urlName, publishDoc);

    // Should get a 400 packageNameMismatch error
    expect(result.status, `raw publish with name mismatch should fail`).not.toBe(200);
    expect(result.status, `should be 400 (bad request)`).toBe(400);

    // Verify nothing was stored under either name
    const packument1 = await rawGetPackument(repo.name, adminCredential(), urlName);
    expect(packument1.status, `no packument under URL name`).toBe(404);

    const packument2 = await rawGetPackument(repo.name, adminCredential(), jsonName);
    expect(packument2.status, `no packument under JSON name`).toBe(404);
  });

  test('unpublish: once removed, package stays gone (invariant check)', { tag: ['@negative'] }, async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const packageName = `e2e-${seeder.runId}-unpublish-check`;
    const version = npmAdapter.version('release');

    // Publish a version
    const world = worldFor(repo.name, packageName, version);
    await npm.seedPublish(world);

    // Verify it's published
    const packument1 = await rawGetPackument(repo.name, adminCredential(), packageName);
    expect(packument1.status, `packument before unpublish`).toBe(200);

    // Unpublish it
    const unpubResult = await npm.unpublish(world, `${packageName}@${version}`, { force: true });
    expect(unpubResult.exitCode, `npm unpublish should succeed: ${unpubResult.command}`).toBe(0);

    // Invariant: once unpublished, the package is gone and stays gone
    const packument2 = await rawGetPackument(repo.name, adminCredential(), packageName);
    expect(packument2.status, `packument after unpublish should be gone`).toBe(404);

    // Check again to verify it stays gone (not a race condition where it reappears)
    const packument3 = await rawGetPackument(repo.name, adminCredential(), packageName);
    expect(packument3.status, `repeated GET after unpublish stays 404`).toBe(404);
  });

  test(
    'unpublish race: concurrent unpublish of same version (at least one succeeds)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = `e2e-${seeder.runId}-unpublish-race`;
      const version = npmAdapter.version('release');

      // Publish a version
      const world = worldFor(repo.name, packageName, version);
      await npm.seedPublish(world);

      // Verify it's published
      const packument1 = await rawGetPackument(repo.name, adminCredential(), packageName);
      expect(packument1.status, `packument before race`).toBe(200);

      // Race: fire two concurrent unpublish commands on the same version
      // Invariant: at least one must succeed, and package must be gone after both complete
      const race1 = npm.unpublish(world, `${packageName}@${version}`, { force: true });
      const race2 = npm.unpublish(world, `${packageName}@${version}`, { force: true });

      const [result1, result2] = await Promise.all([race1, race2]);

      // At least one should succeed
      expect(
        result1.exitCode === 0 || result2.exitCode === 0,
        `at least one unpublish should succeed: race1=${result1.exitCode}, race2=${result2.exitCode}`,
      ).toBe(true);

      // Invariant: after the race, the package is gone
      const packument2 = await rawGetPackument(repo.name, adminCredential(), packageName);
      expect(packument2.status, `packument after concurrent unpublish should be gone`).toBe(404);
    },
  );

  test(
    'cross-user token revoke: after revocation, all requests with that token are refused',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = `e2e-${seeder.runId}-token-revoke`;

      // Create a deploy token
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const credential: MaterializedCredential = {
        transport: 'basic',
        username: token.username,
        password: token.token,
        kind: 'token',
      };

      const version = npmAdapter.version('release');
      const world = worldFor(repo.name, packageName, version, credential);

      // Publish with the valid token (should succeed)
      await npm.seedPublish(world);

      // Verify it's published and readable with valid token
      const packument1 = await rawGetPackument(repo.name, credential, packageName);
      expect(packument1.status, `packument GET with valid token`).toBe(200);

      // Revoke the token immediately
      await seeder.revokeNow(repo.name, token.id);

      // After revocation, BOTH publish and read with that token should be refused
      const publishResult = await npm.publish(world);
      expect(publishResult.outcome, `publish with revoked token should be refused`).not.toBe('ok');

      const packument2 = await rawGetPackument(repo.name, credential, packageName);
      expect(packument2.status, `packument GET with revoked token should be refused (401)`).toBe(
        401,
      );
    },
  );
});
