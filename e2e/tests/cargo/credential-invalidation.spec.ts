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
 * RPS-1552 for Cargo: the token `GET /{repo}/me` answers a user's password with is bound to the user's
 * `token_version`, so a password change ends it at once (it lives 30 minutes otherwise), and `/me` does not
 * renew a token that was ended. The scenarios live in `scenarios/credential-invalidation.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { rawGetIndexWithBearer, rawMe } from '../../src/clients/cargo-raw.js';
import { registerLoginTokenInvalidation } from '../../src/scenarios/credential-invalidation.js';

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
