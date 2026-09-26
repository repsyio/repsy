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
 * Proves the skeleton end to end: log in as admin, seed a user, a private Maven repo and three
 * deploy tokens (read-write, read-only, already-expired) through the panel API, confirm they exist,
 * then confirm cleanup removes them. A target that allows fewer tokens per repo seeds only the first
 * `min(3, target.maxDeployTokensPerRepo)` of them, in that order (Repsy Cloud's FREE plan: one, RPS-1498). A second test pins the real HTTP status a raw protocol-port
 * request gets with an expired vs. a valid token, probed against a running instance beforehand
 * (see the os-panel-backend.ts and seeder.ts comments for the endpoints and payload shapes verified this
 * way).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { target } from '../../src/target.js';

const UNAUTHORIZED = 401;
const OK = 200;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

async function repoRootStatus(repoName: string, token: string): Promise<number> {
  // Any username is accepted alongside a deploy token as the Basic password (ProtocolAuthService).
  const credentials = Buffer.from(`e2e-probe:${token}`).toString('base64');
  const res = await fetch(repoUrl(repoName, ''), {
    headers: { Authorization: `Basic ${credentials}` },
  });
  return res.status;
}

test(
  'seeds a user, a private repo and deploy tokens, and cleanup removes them',
  { tag: ['@smoke'] },
  async ({ seeder, panelApi }) => {
    const user = await seeder.createUser();
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const tokenCount = Math.min(3, target.maxDeployTokensPerRepo);
    const seedTokens = [
      () => seeder.createToken(repo.name, { readOnly: false }),
      () => seeder.createToken(repo.name, { readOnly: true }),
      () =>
        seeder.createToken(repo.name, {
          readOnly: false,
          expirationDate: new Date(Date.now() - ONE_DAY_MS),
        }),
    ].slice(0, tokenCount);
    const seededTokens = [];
    for (const seedToken of seedTokens) {
      seededTokens.push(await seedToken());
    }

    const usersBefore = await panelApi.listUsers({ size: 100 });
    expect(usersBefore.some((u) => u.id === user.id)).toBe(true);

    const reposBefore = await panelApi.listAllRepos({ type: RepoType.MAVEN });
    expect(reposBefore.some((r) => r.name === repo.name)).toBe(true);

    const tokensBefore = await panelApi.listDeployTokens(repo.name);
    const tokenIdsBefore = tokensBefore.map((t) => t.id);
    expect(seededTokens).toHaveLength(tokenCount);
    expect(tokenIdsBefore).toEqual(expect.arrayContaining(seededTokens.map((t) => t.id)));

    await seeder.cleanup();

    const usersAfter = await panelApi.listUsers({ size: 100 });
    expect(usersAfter.some((u) => u.id === user.id)).toBe(false);

    const reposAfter = await panelApi.listAllRepos({ type: RepoType.MAVEN });
    expect(reposAfter.some((r) => r.name === repo.name)).toBe(false);
  },
);

test(
  'an expired deploy token is refused on the repo port, a read-write token is not',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    // eslint-disable-next-line playwright/no-skipped-test -- a target that cannot seed this (Repsy Cloud) skips it
    test.skip(
      !target.supportsExpiredTokenSeed || target.maxDeployTokensPerRepo < 2,
      'needs an expired and a valid token in one repo, seeded with a past expiration date',
    );
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const rwToken = await seeder.createToken(repo.name, { readOnly: false });
    const expiredToken = await seeder.createToken(repo.name, {
      readOnly: false,
      expirationDate: new Date(Date.now() - ONE_DAY_MS),
    });

    const expiredStatus = await repoRootStatus(repo.name, expiredToken.token);
    const rwStatus = await repoRootStatus(repo.name, rwToken.token);

    // Pinned by probing a running instance: an expired token answers 401 (deployTokenExpired); a
    // valid read-write token answers 200 for the repo root.
    expect(expiredStatus).toBe(UNAUTHORIZED);
    expect(rwStatus).not.toBe(UNAUTHORIZED);
    expect(rwStatus).toBe(OK);
  },
);
