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
 * RPS-1481, for `../credential-invalidation-seed.spec.ts`: the REAL `registerCredentialInvalidation` tests of
 * the password events, run against a fake Repsy Cloud whose registry refuses a user that has no grant on the
 * repo (`granting-cloud.ts`). They pass only when the scenario takes its user from the backend's
 * `seedUserCredential` (the granted collaborator); a user made by `seeder.createUser()` is answered 401 on
 * the first deploy that has to succeed.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { registerCredentialInvalidation } from '../../../src/scenarios/credential-invalidation.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { fakeRegistryAdapter, isStored } from './granting-cloud.js';

registerCredentialInvalidation({ adapter: fakeRegistryAdapter, repoType: RepoType.NPM, isStored });

// The control: the fake registry does refuse a user that was merely registered.
test('a registered user without a grant is refused by the fake registry', async ({ seeder }) => {
  const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
  const bare = await seeder.createUser();

  await expect(
    fakeRegistryAdapter.seedPublish({
      scenario: {
        id: 'control',
        tags: [],
        repo: { privateRepo: true },
        credential: 'user-password',
        expect: { publish: 'ok', consume: 'ok' },
      },
      protocol: 'npm',
      repoName: repo.name,
      credential: {
        transport: 'basic',
        username: bare.username,
        password: bare.password,
        kind: 'password',
      },
      publishTarget: { packageName: 'control', version: '1.0.0' },
      consumeTarget: { packageName: 'control', version: '1.0.0' },
    }),
  ).rejects.toThrow(/answered 401/);
  expect(await isStored(repo.name, 'control', '1.0.0')).toBe(false);
});
