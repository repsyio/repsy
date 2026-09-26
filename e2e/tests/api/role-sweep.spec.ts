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
 * RPS-1483: the role sweep. The panel API spec (`openapi-spec.yaml`) documents `403 Forbidden` on exactly
 * the operations a plain USER may not call: the `@RepoOperation(MANAGE)` routes and the `requireAdmin`
 * ones. The sweep is derived from that document (`src/api/spec-ops.ts`), so a new route is in it the
 * moment it is in the spec:
 *
 *  1. every operation that declares 403, called as a USER, answers 403 `accessDenied`, before any 404
 *     (called with names that cannot exist) and before any change (called with names that do, and the
 *     panel read back before and after);
 *  2. every operation that needs authentication answers 401 to an anonymous caller;
 *  3. the reverse: no other operation answers a USER 403, so a MANAGE route that forgot to declare its
 *     403 (or a read route that turned admin-only unannounced) fails here instead of going unnoticed.
 *
 * Its static twin is `OpenApiSpecConsistencyIT.everyDocumented403IsManageOrAdmin` (RPS-1593): every
 * documented 403 is a MANAGE route or in its literal `ADMIN_OPERATIONS`, with the same floor, in every
 * `mvn verify`. A new `requireAdmin` route is added to both.
 *
 * A floor on the size of the set (48 today) and a check of names that must be in it stop a parser bug
 * from emptying the sweep without a failure.
 */
import { loadSpecOperations, requestFor, type SpecOperation } from '../../src/api/spec-ops.js';
import { bodyFor, seedSweepWorld, snapshotWorld, valuesFor } from '../../src/api/sweep-world.js';
import { createPanelBackend } from '../../src/api/backend-registry.js';
import { RepoType } from '../../src/api/panel-api.js';
import { adminBearer, apiUrl, edgeRequest, type EdgeResponse } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { expect, test as base } from '../../src/scenarios/fixtures.js';
import { perTestRunId } from '../../src/seed/run-id.js';
import { seedPackage } from '../../src/seed/packages.js';
import { Seeder } from '../../src/seed/seeder.js';

const OPERATIONS = loadSpecOperations();
const FORBIDDEN = OPERATIONS.filter((op) => op.declares403);
const NOT_FORBIDDEN = OPERATIONS.filter((op) => !op.declares403);

/**
 * Operations the reverse sweep cannot call as the USER without harming the caller or the run, each
 * with the reason. The anonymous sweep still calls them (a 401 changes nothing).
 */
const REVERSE_SKIP: Readonly<Record<string, string>> = {
  deleteProfile: 'deletes the calling USER, whose token the rest of the sweep needs',
  updateUsername: 'renames the calling USER',
  updatePassword: 'changes the calling USER password, so the token stops being the one it was',
  login: 'public: takes credentials, not a token',
  refreshToken: 'public: takes a refresh token, not an access token',
};

/**
 * RPS-1558: `ProtocolEndpointDispatcher.addInterceptors` registers the `@RepoOperation` interceptor for a
 * list of path prefixes, and `/api/mvn/groups/**` (`getMavenGroupSummary`) is not in it, so the
 * permission check never runs: an anonymous caller reads the group summary of a PRIVATE repo, and gets
 * 404 instead of 401 for a repo that does not exist. The operation is kept out of the anonymous sweeps,
 * and the two `test.fail()` tests of "RPS-1558" below assert what it must do. The fix must flip both:
 * the day it lands they pass, `test.fail()` turns that red, and the fix removes this entry and the two
 * `test.fail()` calls (the sweeps then cover the operation).
 */
const ANONYMOUS_KNOWN_GAPS: ReadonlySet<string> = new Set(['getMavenGroupSummary']);

const label = (op: SpecOperation): string => `${op.method} ${op.path} (${op.operationId})`;

interface SweepUser {
  token: string;
}

const test = base.extend<object, { sweepUser: SweepUser }>({
  sweepUser: [
    async ({}, use, workerInfo) => {
      const admin = await createPanelBackend();
      await admin.login(env.adminUsername, env.adminPassword);
      const seeder = new Seeder(admin, perTestRunId(env.runId, workerInfo.parallelIndex, 0));
      const user = await seeder.createUser();
      const session = await createPanelBackend();
      const login = await session.login(user.username, user.password);
      await use({ token: login.token ?? '' });
      await seeder.cleanup();
    },
    { scope: 'worker' },
  ],
});

async function call(
  op: SpecOperation,
  options: Parameters<typeof requestFor>[1],
  token?: string,
): Promise<EdgeResponse> {
  const request = requestFor(op, options);
  return edgeRequest(apiUrl(request.target), {
    method: request.method,
    body: request.body,
    headers: {
      ...(request.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
}

const msgId = (res: EdgeResponse): unknown => (res.json as { msgId?: unknown } | undefined)?.msgId;

/** Bodies that pass validation, on names that cannot exist, for the operations that need one. */
const PLACEHOLDER_WORLD = {
  fresh: { username: 'e2e-sweep-nobody', repoName: 'e2e-sweep-newrepo' },
};

test.describe('the operations the spec declares 403 on', { tag: ['@smoke'] }, () => {
  test('the set is derived from the spec and is not empty', () => {
    expect(OPERATIONS.length).toBeGreaterThanOrEqual(120);
    expect(FORBIDDEN.length).toBeGreaterThanOrEqual(48);
  });

  test('the set holds the routes that are known to be MANAGE or admin only', () => {
    const ids = new Set(FORBIDDEN.map((op) => op.operationId));

    // Everything under /api/users, and creating a repository, is admin only.
    for (const op of OPERATIONS.filter((candidate) => candidate.path.startsWith('/api/users'))) {
      expect(ids.has(op.operationId), label(op)).toBe(true);
    }
    expect(ids.has('createRepository')).toBe(true);
    // A repo's own settings, tokens, name, description and key stores.
    for (const id of [
      'getRepoSettings',
      'updateRepoSettings',
      'renameRepo',
      'updateRepoDescription',
      'listDeployTokens',
      'createDeployToken',
      'revokeDeployToken',
      'rotateDeployToken',
      'listMavenKeyStores',
      'createMavenKeyStore',
      'deleteMavenKeyStore',
      'listMavenPgpPublicKeys',
      'createMavenPgpPublicKey',
      'deleteMavenPgpPublicKey',
      'deleteRepo',
    ]) {
      expect(ids.has(id), id).toBe(true);
    }
    // Every delete of a package, version or repo, in every protocol; only deleting yourself is not one.
    const deletes = OPERATIONS.filter(
      (candidate) => candidate.method === 'DELETE' && candidate.operationId !== 'deleteProfile',
    );
    expect(deletes.length).toBeGreaterThanOrEqual(28);
    for (const op of deletes) {
      expect(ids.has(op.operationId), label(op)).toBe(true);
    }
  });

  test('every skipped operation exists in the spec', () => {
    const ids = new Set(OPERATIONS.map((op) => op.operationId));
    for (const id of [...Object.keys(REVERSE_SKIP), ...ANONYMOUS_KNOWN_GAPS]) {
      expect(ids.has(id), id).toBe(true);
    }
  });

  for (const op of FORBIDDEN) {
    test(`a USER gets 403 before anything else on ${label(op)}`, async ({ sweepUser }) => {
      const res = await call(
        op,
        { body: bodyFor(op.operationId, PLACEHOLDER_WORLD) },
        sweepUser.token,
      );

      expect(res.status, res.text.slice(0, 200)).toBe(403);
      expect(msgId(res)).toBe('accessDenied');
    });
  }
});

test.describe('anonymous callers', { tag: ['@smoke'] }, () => {
  for (const op of OPERATIONS.filter(
    (candidate) =>
      candidate.security !== 'public' && !ANONYMOUS_KNOWN_GAPS.has(candidate.operationId),
  )) {
    test(`get 401 on ${label(op)}`, async () => {
      const res = await call(op, { body: bodyFor(op.operationId, PLACEHOLDER_WORLD) });

      expect(res.status, res.text.slice(0, 200)).toBe(401);
    });
  }

  for (const op of OPERATIONS.filter((candidate) => candidate.security === 'public')) {
    test(`are let through on the public ${label(op)}`, async () => {
      const res = await call(op, {});

      expect(res.status, res.text.slice(0, 200)).not.toBe(401);
      expect(res.status).not.toBe(403);
    });
  }
});

test.describe('RPS-1558: the Maven group summary is not protected', { tag: ['@smoke'] }, () => {
  const summary = OPERATIONS.find(
    (op) => op.operationId === 'getMavenGroupSummary',
  ) as SpecOperation;

  test('an anonymous caller is refused on a private repo', async ({ seeder }) => {
    test.fail(true, 'RPS-1558: answers 200 with the artifact and version counts');
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const pkg = await seedPackage(repo, seeder, {});
    const [groupName] = pkg.name.split(':');

    const res = await call(summary, {
      values: { repoName: repo.name, groupName: groupName ?? '' },
    });

    expect(res.status, res.text.slice(0, 200)).toBe(401);
  });

  test('an anonymous caller gets 401, not 404, for a repo that does not exist', async () => {
    test.fail(true, 'RPS-1558: answers 404 repoNotFound');

    const res = await call(summary, {});

    expect(res.status, res.text.slice(0, 200)).toBe(401);
  });
});

test.describe('the operations the spec does not declare 403 on', { tag: ['@smoke'] }, () => {
  for (const op of NOT_FORBIDDEN.filter((candidate) => !(candidate.operationId in REVERSE_SKIP))) {
    test(`a USER is not refused with 403 on ${label(op)}`, async ({ sweepUser }) => {
      const res = await call(op, {}, sweepUser.token);

      expect(res.status, res.text.slice(0, 200)).not.toBe(403);
    });
  }
});

test.describe('on real data', () => {
  test('a USER changes nothing: 403 on every forbidden operation, the panel the same before and after', async ({
    panelApi,
    seeder,
    sweepUser,
  }) => {
    test.setTimeout(240_000);
    const adminToken = await adminBearer();
    const world = await seedSweepWorld(seeder, panelApi, adminToken);
    const prefix = `e2e-${seeder.runId}`;

    const before = await snapshotWorld(adminToken, world, OPERATIONS, prefix);
    const answered = Object.values(before).filter((reading) => reading.status === 200).length;
    // The reads have to be reading something, or "unchanged" proves nothing.
    expect(answered).toBeGreaterThan(40);

    const answers = new Map<string, number>();
    for (const op of FORBIDDEN) {
      const res = await call(
        op,
        { values: valuesFor(op, world), body: bodyFor(op.operationId, world) },
        sweepUser.token,
      );
      answers.set(label(op), res.status);
    }
    const refused = Object.fromEntries([...answers].filter(([, status]) => status !== 403));
    expect(refused).toEqual({});

    expect(await snapshotWorld(adminToken, world, OPERATIONS, prefix)).toEqual(before);
  });

  test('an anonymous caller is refused on every operation of a private repository', async ({
    panelApi,
    seeder,
  }) => {
    test.setTimeout(240_000);
    const world = await seedSweepWorld(seeder, panelApi, await adminBearer());

    const inScope = OPERATIONS.filter(
      (op) =>
        op.pathParams.includes('repoName') &&
        op.security !== 'public' &&
        !ANONYMOUS_KNOWN_GAPS.has(op.operationId),
    );
    const answers = new Map<string, number>();
    for (const op of inScope) {
      const res = await call(op, {
        values: valuesFor(op, world),
        body: bodyFor(op.operationId, world),
      });
      answers.set(label(op), res.status);
    }
    const answered = Object.fromEntries([...answers].filter(([, status]) => status !== 401));
    expect(answered).toEqual({});
  });
});
