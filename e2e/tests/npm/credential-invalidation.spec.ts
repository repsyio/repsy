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
 * RPS-1481 for npm: the real `npm publish` with a credential that has just stopped being valid (a
 * changed password, a deleted user, a revoked or rotated deploy token). The scenarios live in
 * `scenarios/credential-invalidation.ts`, shared with `tests/maven/credential-invalidation.spec.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { npmAdapter } from '../../src/clients/npm.js';
import {
  adminCredential,
  parsePackument,
  rawGetPackument,
  rawLogin,
  rawRequestPath,
} from '../../src/clients/npm-raw.js';
import {
  registerCredentialInvalidation,
  registerLoginTokenInvalidation,
} from '../../src/scenarios/credential-invalidation.js';

const isStored = async (repoName: string, packageName: string, version: string) => {
  const res = await rawGetPackument(repoName, adminCredential(), packageName);
  if (res.status === 404) {
    return false;
  }
  if (res.status !== 200) {
    throw new Error(`GET packument of ${packageName} as admin answered ${res.status}`);
  }
  return version in parsePackument(res.body).versions;
};

registerCredentialInvalidation({ adapter: npmAdapter, repoType: RepoType.NPM, isStored });

// RPS-1552: the token `npm login` stores (a 90 day JWT) ends with a password change. The real `npm publish`
// sends it as `_authToken`, so the refusal is proved with the tool as well as with a raw request.
registerLoginTokenInvalidation({
  name: 'npm',
  repoType: RepoType.NPM,
  login: async (repoName, username, secret) => {
    const res = await rawLogin(repoName, username, secret);
    if (!res.token) {
      throw new Error(
        `npm login for ${username} answered ${res.status}: ${res.response.body.toString('utf8')}`,
      );
    }
    return res.token;
  },
  probe: (repoName, token) =>
    rawRequestPath(repoName, 'GET', 'no-such-package-rps1552', {
      Authorization: `Bearer ${token}`,
    }),
  realClient: { adapter: npmAdapter, isStored },
});
