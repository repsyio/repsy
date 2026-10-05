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
 * RPS-1481: the user whose credential a `credential-invalidation` test ends is the target's own
 * `user-password` credential of the test's repo, asked from `PanelBackend.seedUserCredential` like every
 * scenario's is (`seedInvalidationUser`). On Repsy Cloud a user that was merely registered
 * (`seeder.createUser()`) has no grant on the repo, so its deploy and its `npm login` are refused for a reason
 * that is not the one under test; the Cloud backend's `seedUserCredential` registers the collaborator AND
 * grants it. The fake below has the same shape (a grant per repo, a credential without the account's id), so
 * this proves the scenario asks for it, with the repo, and gets an account it can act on. Nothing talks to a
 * server.
 */
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import { BACKEND_MODULE_ENV } from '../../src/api/backend-registry.js';

import { type CredentialSeedContext, RepoType } from '../../src/api/panel-backend.js';
import { seedInvalidationUser } from '../../src/scenarios/credential-invalidation.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import { env } from '../../src/env.js';
import { Seeder } from '../../src/seed/seeder.js';
import { runInner } from './inner-run.js';
import {
  FAKE_CLOUD_CAPABILITIES,
  FAKE_CLOUD_UNSUPPORTED_TICKET,
  FakeCloudPanelBackend,
} from './fake-cloud-panel-backend.js';

/** A Repsy Cloud shaped backend: a collaborator registered AND granted on the repo, its id not in the credential. */
class GrantingFakeCloud extends FakeCloudPanelBackend {
  readonly asked: CredentialSeedContext[] = [];
  /** `username -> repos it may use`: what a tenant registered with no grant lacks. */
  readonly grants = new Map<string, Set<string>>();
  listCalls = 0;

  constructor(
    baseUrl: string,
    private readonly answerUserId = false,
  ) {
    super(baseUrl);
  }

  override async seedUserCredential(ctx: CredentialSeedContext): Promise<MaterializedCredential> {
    this.asked.push(ctx);
    const created = await ctx.seeder.createUser(
      ctx.username === undefined ? {} : { username: ctx.username },
    );
    this.grants.set(created.username, new Set([ctx.repoName]));
    return {
      transport: 'basic',
      username: created.username,
      password: created.password,
      kind: 'password',
      ...(this.answerUserId ? { userId: created.id } : {}),
    };
  }

  override async listAllUsers(filter: { q?: string } = {}) {
    this.listCalls += 1;
    return super.listAllUsers(filter);
  }
}

test.describe('the user of a credential invalidation test (RPS-1481)', () => {
  test('is asked from the backend for the repo, granted on it, and found by its username', async () => {
    const backend = new GrantingFakeCloud(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake10');
    const repo = await seeder.createRepo(RepoType.NPM);

    const result = await seedInvalidationUser(
      seeder,
      repo.name,
      RepoType.NPM,
      FAKE_CLOUD_CAPABILITIES,
    );

    expect(result.skipReason).toBeUndefined();
    expect(backend.asked).toHaveLength(1);
    expect(backend.asked[0]).toMatchObject({ repoName: repo.name, repoType: RepoType.NPM });
    const user = result.user;
    expect(user, 'a user').toBeDefined();
    // It can use the repo the test deploys to: that is what a bare seeder.createUser() lacks on a Cloud.
    expect(backend.grants.get(user?.username ?? '')).toEqual(new Set([repo.name]));
    // The credential carried no id: it was looked up by the exact username, and it is the account's own.
    expect(backend.listCalls).toBe(1);
    expect(backend.users.get(user?.id ?? '')?.username).toBe(user?.username);
    expect(user?.password).toBeTruthy();

    // The seeder tracked the account it created, so the test's cleanup removes it.
    await seeder.cleanup();
    expect(backend.users.size).toBe(0);
  });

  test('takes the id from the credential when the backend gives one, without a lookup', async () => {
    const backend = new GrantingFakeCloud(env.apiBaseUrl, true);
    const seeder = new Seeder(backend, 'fake11');
    const repo = await seeder.createRepo(RepoType.MAVEN);

    const result = await seedInvalidationUser(
      seeder,
      repo.name,
      RepoType.MAVEN,
      FAKE_CLOUD_CAPABILITIES,
    );

    expect(result.user?.id).toBeTruthy();
    expect(backend.users.has(result.user?.id ?? '')).toBe(true);
    expect(backend.listCalls).toBe(0);

    await seeder.cleanup();
  });

  test('is a skip reason, not a failure, where the backend has no such credential', async () => {
    const backend = new FakeCloudPanelBackend(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake12');
    const repo = await seeder.createRepo(RepoType.NPM);

    const result = await seedInvalidationUser(
      seeder,
      repo.name,
      RepoType.NPM,
      FAKE_CLOUD_CAPABILITIES,
    );

    expect(result.user).toBeUndefined();
    expect(result.skipReason).toContain('seedUserCredential');
    expect(result.skipReason).toContain(FAKE_CLOUD_UNSUPPORTED_TICKET);
    expect(backend.users.size).toBe(0);

    await seeder.cleanup();
  });

  test('fails loudly when the backend seeds a user its own list does not know', async () => {
    class Lying extends GrantingFakeCloud {
      override async seedUserCredential(): Promise<MaterializedCredential> {
        return { transport: 'basic', username: 'ghost', password: 'p', kind: 'password' };
      }
    }
    const backend = new Lying(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake13');
    const repo = await seeder.createRepo(RepoType.NPM);

    await expect(
      seedInvalidationUser(seeder, repo.name, RepoType.NPM, FAKE_CLOUD_CAPABILITIES),
    ).rejects.toThrow(/no user "ghost"/);

    await seeder.cleanup();
  });
});

test.describe('the real invalidation tests, against a registry that refuses an ungranted user', () => {
  test.describe.configure({ timeout: 120_000 });

  test('the password events pass with the granted collaborator the backend seeds', () => {
    const r = runInner(
      'cloud-local',
      { [BACKEND_MODULE_ENV]: resolve(import.meta.dirname, 'known-gap-inner/granting-cloud.ts') },
      ['credential-invalidation.inner', '--grep', 'changed password|deleted user|without a grant'],
    );

    const titles = [...r.keys()];
    const changed = titles.find((title) => title.includes('a changed password refuses'));
    const deleted = titles.find((title) => title.includes('a deleted user is refused'));
    // The control: the fake registry refuses a user that was merely registered (else this proves nothing).
    expect(r.get('a registered user without a grant is refused by the fake registry')?.status).toBe(
      'expected',
    );
    expect(changed, JSON.stringify(titles)).toBeDefined();
    expect(deleted, JSON.stringify(titles)).toBeDefined();
    expect(r.get(changed ?? '')?.status, changed).toBe('expected');
    expect(r.get(deleted ?? '')?.status, deleted).toBe('expected');
  });
});
