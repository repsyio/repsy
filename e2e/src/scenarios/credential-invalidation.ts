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
 *
 * RPS-1552 adds {@link registerLoginTokenInvalidation}: the same events seen through the token a client KEEPS
 * after logging in (a Docker `/v2/token` JWT, the token `npm login` stores, the Cargo `/me` token). Those
 * protocol JWTs carry the user's `token_version` (`tv` claim), so a password change ends them at once; before
 * that they outlived the change until they expired (30 minutes, npm 90 days). A real Docker client always
 * exchanges its Basic credentials again, so the stale token is only visible to a client that holds one:
 * the probes here are raw HTTP with the stored token, and for npm the real `npm publish` sends it as `_authToken`.
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

interface Setup {
  /** Deploys `version` as `credential` with the real client; the client exiting 0 is asserted. */
  accepted(credential: MaterializedCredential, version: string): Promise<void>;
  /** Deploys `version` as `credential`: refused with a 401, a failing client, and nothing stored. */
  refused(credential: MaterializedCredential, version: string): Promise<void>;
}

function deploySetup<F>(
  protocol: Pick<CredentialInvalidationProtocol<F>, 'adapter' | 'isStored'>,
  repoName: string,
  packageName: string,
): Setup {
  const { adapter } = protocol;
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

export function registerCredentialInvalidation<F>(
  protocol: CredentialInvalidationProtocol<F>,
): void {
  const { adapter } = protocol;

  test.describe(`${adapter.protocol} credential invalidation (${adapter.client.name}, RPS-1481)`, () => {
    test(
      'a changed password refuses the old one at once and the new one works',
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
        const user = await seeder.createUser();
        const oldCredential = passwordCredential(user.username, user.password);
        const packageName = adapter.packageName(seeder.runId, SCENARIO);
        const { accepted, refused } = deploySetup(protocol, repo.name, packageName);

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
      const { accepted, refused } = deploySetup(protocol, repo.name, packageName);

      await accepted(credential, adapter.version('release'));

      await panelApi.deleteRepoUser(user.id);

      await refused(credential, adapter.version('release'));
    });

    test('a revoked deploy token is refused at once and nothing is stored', async ({ seeder }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const credential = tokenCredential(token.username, token.token);
      const packageName = adapter.packageName(seeder.runId, SCENARIO);
      const { accepted, refused } = deploySetup(protocol, repo.name, packageName);

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
      const { accepted, refused } = deploySetup(protocol, repo.name, packageName);

      await accepted(oldCredential, adapter.version('release'));

      const rotated = await seeder.rotateNow(repo.name, token.id);

      await refused(oldCredential, adapter.version('release'));
      await accepted(tokenCredential(token.username, rotated), adapter.version('release'));
    });
  });
}

/** What a protocol's login hands the client to keep, and how to use it once more (RPS-1552). */
export interface LoginTokenProtocol<F = unknown> {
  /** Names the tests: `docker`, `npm`, `cargo`. */
  name: string;
  repoType: RepoType;
  /** The login exchange: a user's password (or a deploy token's secret) in, the token the client keeps out. */
  login(repoName: string, username: string, secret: string): Promise<string>;
  /** One raw request that needs the token: `401` when the server no longer accepts it, anything else when it does. */
  probe(repoName: string, token: string): Promise<{ status: number; body: Buffer }>;
  /**
   * Optional: the real client publishes with the login token (npm: `_authToken`), so the refusal is
   * proved with the tool as well as with the raw probe.
   */
  realClient?: Pick<CredentialInvalidationProtocol<F>, 'adapter' | 'isStored'>;
}

export function registerLoginTokenInvalidation<F>(protocol: LoginTokenProtocol<F>): void {
  const { name } = protocol;

  async function accepted(repoName: string, token: string, context: string): Promise<void> {
    const res = await protocol.probe(repoName, token);
    expect(res.status, `${context}: ${res.body.toString('utf8')}`).not.toBe(401);
  }

  /** A 401 that says the session is over (`sessionExpired`, "Session expired."), in every protocol's own envelope. */
  async function endedSession(repoName: string, token: string, context: string): Promise<void> {
    const res = await protocol.probe(repoName, token);
    expect(res.status, `${context}: ${res.body.toString('utf8')}`).toBe(401);
    expect(res.body.toString('utf8'), context).toContain('Session expired');
  }

  test.describe(`${name} login token invalidation (RPS-1552)`, () => {
    test(
      'a changed password ends the login token at once and a new login works',
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
        const user = await seeder.createUser();
        const token = await protocol.login(repo.name, user.username, user.password);

        // Used twice first: what ends is a token that worked, not one that never did.
        await accepted(repo.name, token, 'the login token before the change');
        await accepted(repo.name, token, 'the login token, again');

        const userApi = new PanelApi(env.apiBaseUrl);
        await userApi.login(user.username, user.password);
        const newPassword = `${user.password}${NEW_PASSWORD_SUFFIX}`;
        await userApi.changeOwnPassword(newPassword);

        await endedSession(repo.name, token, 'the login token after the password change');

        const fresh = await protocol.login(repo.name, user.username, newPassword);
        await accepted(repo.name, fresh, 'the token of a login with the new password');
      },
    );

    test('an admin password reset ends the login token at once', async ({ seeder, panelApi }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const user = await seeder.createUser();
      const token = await protocol.login(repo.name, user.username, user.password);
      await accepted(repo.name, token, 'the login token before the reset');

      const generated = await panelApi.resetUserPassword(user.id);

      await endedSession(repo.name, token, 'the login token after the admin reset');
      const fresh = await protocol.login(repo.name, user.username, generated);
      await accepted(repo.name, fresh, 'the token of a login with the generated password');
    });

    test('a password change of another user leaves the login token alone', async ({ seeder }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const user = await seeder.createUser();
      const other = await seeder.createUser();
      const token = await protocol.login(repo.name, user.username, user.password);

      const otherApi = new PanelApi(env.apiBaseUrl);
      await otherApi.login(other.username, other.password);
      await otherApi.changeOwnPassword(`${other.password}${NEW_PASSWORD_SUFFIX}`);

      await accepted(repo.name, token, 'the login token after ANOTHER user changed the password');
    });

    test('a deploy-token JWT is not tied to any user and survives a password change', async ({
      seeder,
    }) => {
      const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
      const user = await seeder.createUser();
      const deployToken = await seeder.createToken(repo.name, { readOnly: false });
      const jwt = await protocol.login(repo.name, deployToken.username, deployToken.token);
      await accepted(repo.name, jwt, 'the deploy-token JWT before the change');

      const userApi = new PanelApi(env.apiBaseUrl);
      await userApi.login(user.username, user.password);
      await userApi.changeOwnPassword(`${user.password}${NEW_PASSWORD_SUFFIX}`);

      await accepted(repo.name, jwt, 'the deploy-token JWT after a user changed the password');
    });

    const { realClient } = protocol;
    if (realClient) {
      test(`a changed password ends the login token the real ${realClient.adapter.client.name} publishes with`, async ({
        seeder,
      }) => {
        const repo = await seeder.createRepo(protocol.repoType, { privateRepo: true });
        const user = await seeder.createUser();
        const packageName = realClient.adapter.packageName(seeder.runId, SCENARIO);
        const { accepted: deployed, refused } = deploySetup(realClient, repo.name, packageName);
        const token = await protocol.login(repo.name, user.username, user.password);
        const asLoginToken = (jwt: string): MaterializedCredential => ({
          transport: 'basic',
          username: user.username,
          password: jwt,
          kind: 'token',
        });

        await deployed(asLoginToken(token), realClient.adapter.version('release'));

        const userApi = new PanelApi(env.apiBaseUrl);
        await userApi.login(user.username, user.password);
        const newPassword = `${user.password}${NEW_PASSWORD_SUFFIX}`;
        await userApi.changeOwnPassword(newPassword);

        await refused(asLoginToken(token), realClient.adapter.version('release'));
        const fresh = await protocol.login(repo.name, user.username, newPassword);
        await deployed(asLoginToken(fresh), realClient.adapter.version('release'));
      });
    }
  });
}

function passwordCredential(username: string, password: string): MaterializedCredential {
  return { transport: 'basic', username, password, kind: 'password' };
}

function tokenCredential(username: string, token: string): MaterializedCredential {
  return { transport: 'basic', username, password: token, kind: 'token' };
}
