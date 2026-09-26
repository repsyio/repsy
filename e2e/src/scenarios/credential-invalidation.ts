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
 * RPS-1481: an old credential is refused AT ONCE on the wire, with a real client, after the event that
 * should have ended it. `BasicAuthCacheIT` covers the cache in the backend; this is the same promise
 * seen from `mvn deploy` / `npm publish`. Repsy remembers a successful Basic password check
 * (`VerifiedPasswordCache`, keyed by user + password + stored hash) so that a client that sends its
 * credentials on every request costs one BCrypt check, not one per request, and the user row is re-read on
 * every request. A stale entry would therefore let a deleted user or an old password keep deploying until
 * the entry expired, so every test here first WARMS the cache with a successful deploy: what it then proves is
 * invalidation, not a cold miss.
 *
 * The same four events for every protocol that registers. After each one, a deploy with the old credential
 * must show all of the following, and where there is a replacement credential (password change, rotate) it
 * must deploy:
 *
 *  - the real client exits non-zero;
 *  - the raw companion probe of the same credential (`adapter.publish`) answers 401, not a 403 or 404
 *    (the credential is unknown, not merely not allowed);
 *  - nothing of the refused version is stored (an admin looks, `isStored`).
 *
 * Events: `PUT /api/profile/password` by the user, `DELETE /api/users/{id}` by an admin, a deploy token
 * revoked, a deploy token rotated. Only the password events go through the cache; the token ones have no
 * cache of their own and are here because the story names them and a real client is the proof that counts.
 *
 * Each spec file calls {@link registerCredentialInvalidation} once, with its protocol's adapter.
 */
import { createPanelBackend } from '../api/backend-registry.js';
import { RepoType } from '../api/panel-api.js';
import type { ProtocolAdapter } from './adapter.js';
import { expect, test } from './fixtures.js';
import type { Scenario } from './types.js';
import type { MaterializedCredential, World } from './world.js';

const SCENARIO: Scenario = {
  id: 'credential-invalidation',
  tags: [],
  repo: { privateRepo: true },
  credential: 'user-password',
  expect: { publish: 'ok', consume: 'ok' },
};

const NEW_PASSWORD_SUFFIX = 'Changed9';

export interface CredentialInvalidationProtocol<F = unknown> {
  /** The protocol's real client adapter: `publish` runs the client AND a raw probe of the same credential. */
  adapter: ProtocolAdapter<F>;
  repoType: RepoType;
  /** What an admin sees in the repo: does the registry hold this version of the package? */
  isStored(repoName: string, packageName: string, version: string): Promise<boolean>;
}

export function registerCredentialInvalidation<F>(
  protocol: CredentialInvalidationProtocol<F>,
): void {
  const { adapter } = protocol;

  interface Setup {
    /** Deploys `version` as `credential` with the real client; the client exiting 0 is asserted. */
    accepted(credential: MaterializedCredential, version: string): Promise<void>;
    /** Deploys `version` as `credential`: refused with a 401, a failing client, and nothing stored. */
    refused(credential: MaterializedCredential, version: string): Promise<void>;
  }

  function setupFor(repoName: string, packageName: string): Setup {
    const worldWith = (cred: MaterializedCredential, version: string): World => ({
      scenario: SCENARIO,
      protocol: adapter.protocol,
      repoName,
      credential: cred,
      publishTarget: { packageName, version },
      consumeTarget: { packageName, version },
    });

    return {
      accepted: async (cred, version) => {
        // seedPublish throws unless the client exited 0
        await adapter.seedPublish(worldWith(cred, version));
        expect(
          await protocol.isStored(repoName, packageName, version),
          `${version} deployed by ${cred.username} should be stored`,
        ).toBe(true);
      },
      refused: async (cred, version) => {
        const result = await adapter.publish(worldWith(cred, version));
        const context = `${adapter.client.name} ${adapter.client.publishVerb} of ${version} as ${cred.username}: ${result.command}`;
        expect(result.httpStatus, `raw probe, ${context}`).toBe(401);
        expect(result.outcome, context).toBe('unauthorized');
        expect(result.clientExitCode, `real client, ${context}`).not.toBe(0);
        expect(
          await protocol.isStored(repoName, packageName, version),
          `${version} must not be stored after a refused deploy`,
        ).toBe(false);
      },
    };
  }

  test.describe(`${adapter.protocol} credential invalidation (${adapter.client.name}, RPS-1481)`, () => {
    test(
      'a changed password refuses the old one at once and the new one works',
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
        const user = await seeder.createUser();
        const oldCredential = passwordCredential(user.username, user.password);
        const packageName = adapter.packageName(seeder.runId, SCENARIO);
        const { accepted, refused } = setupFor(repo.name, packageName);

        const first = adapter.version('release');
        const second = adapter.version('release');
        const third = adapter.version('release');

        // Warm the cache: a successful deploy with the password that is about to stop working.
        await accepted(oldCredential, first);

        const userApi = await createPanelBackend();
        await userApi.login(user.username, user.password);
        const newPassword = `${user.password}${NEW_PASSWORD_SUFFIX}`;
        await userApi.changeOwnPassword(newPassword);

        await refused(oldCredential, second);
        await accepted(passwordCredential(user.username, newPassword), third);
      },
    );

    test('a deleted user is refused at once and nothing is stored', async ({
      seeder,
      panelApi,
    }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const user = await seeder.createUser();
      const credential = passwordCredential(user.username, user.password);
      const packageName = adapter.packageName(seeder.runId, SCENARIO);
      const { accepted, refused } = setupFor(repo.name, packageName);

      await accepted(credential, adapter.version('release'));

      await panelApi.deleteRepoUser(user.id);

      await refused(credential, adapter.version('release'));
    });

    test('a revoked deploy token is refused at once and nothing is stored', async ({ seeder }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const credential = tokenCredential(token.username, token.token);
      const packageName = adapter.packageName(seeder.runId, SCENARIO);
      const { accepted, refused } = setupFor(repo.name, packageName);

      await accepted(credential, adapter.version('release'));

      await seeder.revokeNow(repo.name, token.id);

      await refused(credential, adapter.version('release'));
    });

    test('a rotated deploy token refuses the old value at once and the new one works', async ({
      seeder,
    }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const oldCredential = tokenCredential(token.username, token.token);
      const packageName = adapter.packageName(seeder.runId, SCENARIO);
      const { accepted, refused } = setupFor(repo.name, packageName);

      await accepted(oldCredential, adapter.version('release'));

      const rotated = await seeder.rotateNow(repo.name, token.id);

      await refused(oldCredential, adapter.version('release'));
      await accepted(tokenCredential(token.username, rotated), adapter.version('release'));
    });
  });
}

function passwordCredential(username: string, password: string): MaterializedCredential {
  return { transport: 'basic', username, password, kind: 'password' };
}

function tokenCredential(username: string, token: string): MaterializedCredential {
  return { transport: 'basic', username, password: token, kind: 'token' };
}
