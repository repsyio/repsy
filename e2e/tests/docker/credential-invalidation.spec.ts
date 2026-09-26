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
 * RPS-1552 for Docker: the JWT `/v2/token` hands out is bound to the user's `token_version`, so a
 * password change (or an admin's reset) ends a token a client still holds, at once, instead of when it
 * expires (`expires_in` 1800). A real docker client exchanges its credentials again for every
 * operation and never shows this, which is why the tests hold the token and start a blob upload with
 * it (`POST blobs/uploads/`, which writes nothing): `202` while it is good, `401 Session expired` after.
 * The scenarios live in `scenarios/credential-invalidation.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { pushScope, rawStartUploadWithToken, rawToken } from '../../src/clients/docker-raw.js';
import { registerLoginTokenInvalidation } from '../../src/scenarios/credential-invalidation.js';

const IMAGE = 'rps1552-image';

registerLoginTokenInvalidation({
  name: 'docker',
  repoType: RepoType.DOCKER,
  login: async (repoName, username, secret) => {
    const res = await rawToken(
      { transport: 'basic', username, password: secret, kind: 'password' },
      pushScope(repoName, IMAGE),
    );
    if (res.status !== 200 || !res.token) {
      throw new Error(
        `/v2/token for ${username} answered ${res.status}: ${res.body.toString('utf8')}`,
      );
    }
    return res.token;
  },
  probe: (repoName, token) => rawStartUploadWithToken(repoName, IMAGE, token),
});
