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
 * RPS-1481 for Cargo: the real `cargo publish` with a credential that has just stopped being valid (a
 * changed password, a deleted user, a revoked or rotated deploy token). The scenarios live in
 * `scenarios/credential-invalidation.ts`, shared with `tests/maven/credential-invalidation.spec.ts`.
 *
 * RPS-1552 for Cargo: the token `GET /{repo}/me` answers a user's password with is bound to the user's
 * `token_version`, so a password change ends it at once (it lives 30 minutes otherwise), and `/me` does not
 * renew a token that was ended. The scenarios live in `scenarios/credential-invalidation.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  parseIndex,
  rawGetIndex,
  rawGetIndexWithBearer,
  rawMe,
  rawRequest,
} from '../../src/clients/cargo-raw.js';
import { cargoAdapter } from '../../src/clients/cargo.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import {
  registerCredentialInvalidation,
  registerLoginTokenInvalidation,
} from '../../src/scenarios/credential-invalidation.js';
import { repoUrl } from '../../src/repo-url.js';

registerCredentialInvalidation({
  adapter: cargoAdapter,
  repoType: RepoType.CARGO,
  isStored: async (repoName, packageName, version) => {
    const res = await rawGetIndex(repoName, adminCredential(), packageName);
    if (res.status === 404) {
      return false;
    }
    if (res.status !== 200) {
      throw new Error(`GET sparse-index of ${packageName} as admin answered ${res.status}`);
    }
    const entries = parseIndex(res.body);
    return entries.some((e) => e.vers === version);
  },
});

registerLoginTokenInvalidation({
  name: 'cargo',
  repoType: RepoType.CARGO,
  login: async (repoName, username, secret) => {
    const res = await rawMe(repoName, {
      transport: 'basic',
      username,
      password: secret,
      kind: 'password',
    });
    if (!res.token) {
      throw new Error(
        `GET /me for ${username} answered ${res.status}: ${res.response.body.toString('utf8')}`,
      );
    }
    return res.token;
  },
  probe: (repoName, token) => rawGetIndexWithBearer(repoName, 'no_such_crate_rps1552', token),
});

/**
 * RPS-1576 for Cargo: the `cargo:token` credential provider sends a renewed token bare (with no
 * `Bearer ` scheme prefix), and the `/me` endpoint must accept and renew it. This is orthogonal to
 * the login token invalidation tests above: here we prove the scheme-less format is accepted.
 */
test(
  'cargo > /me accepts and renews a bare (scheme-less) token from cargo:token credential provider (RPS-1576)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
    // The user probes the index of a private repo, so it needs the grant a Repsy Cloud tenant lacks by
    // default (RPS-1890): the target's `user-password` credential has it on both stacks.
    const cred = await seeder.backend.seedUserCredential({
      seeder,
      repoName: repo.name,
      repoType: RepoType.CARGO,
    });

    // Get the initial token via /me with Basic auth
    const initialRes = await rawMe(repo.name, cred);
    expect(initialRes.status).toBe(200);
    const initialToken = initialRes.token;
    expect(initialToken).toBeTruthy();

    // Renew the token by sending it back bare (no "Bearer " prefix), exactly as cargo:token does.
    // repoUrl's one-arg form has no trailing slash (repo-url.ts's own doc comment: pass `rel` for a
    // path under it), unlike cargo-raw.ts's internal repoUrl(name) wrapper that rawMe() uses above --
    // two different functions sharing a name. Use the documented two-arg form here to avoid a
    // concatenated, un-routable URL (confirmed live: this previously 404'd as "unknownPath").
    const bareRenewalRes = await rawRequest(repoUrl(repo.name, 'me'), {
      headers: { Authorization: initialToken! }, // bare token, no scheme
    });
    expect(bareRenewalRes.status, `bare token renewal should succeed`).toBe(200);
    const renewedData = JSON.parse(bareRenewalRes.body.toString('utf8')) as { token?: unknown };
    const renewedToken = typeof renewedData.token === 'string' ? renewedData.token : undefined;
    expect(renewedToken).toBeTruthy();

    // Use the renewed token with Bearer prefix to verify it works
    const probeRes = await rawGetIndexWithBearer(repo.name, 'any-crate', renewedToken!);
    // The crate doesn't exist, so we expect 404, but the token is accepted (not 401)
    expect(probeRes.status, `renewed token should be accepted for index access`).toBe(404);
  },
);
