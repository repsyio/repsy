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
 * RPS-1483: the role sweep, on real data. The sweep itself (every operation of the panel API spec, called
 * as a USER and as an anonymous caller, with the floors and the known-route guards) is
 * `OpenApiOperationRoleSweepIT` (RPS-2739), derived from the same `openapi-spec.yaml` and run in every
 * `mvn verify`; its static twin is `OpenApiSpecConsistencyIT.everyDocumented403IsManageOrAdmin` (RPS-1593).
 *
 * What stays here is what MockMvc cannot show, because it needs a real stack with seeded data:
 *
 *  1. a USER calling every operation that declares 403, with names that exist, changes nothing: the panel
 *     is read back before and after and is the same;
 *  2. an anonymous caller is refused 401 on every operation of a seeded private repository.
 */
import { loadSpecOperations, requestFor, type SpecOperation } from '../../src/api/spec-ops.js';
import { bodyFor, seedSweepWorld, snapshotWorld, valuesFor } from '../../src/api/sweep-world.js';
import { createPanelBackend } from '../../src/api/backend-registry.js';
import { adminBearer, apiUrl, edgeRequest, type EdgeResponse } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { expect, test as base } from '../../src/scenarios/fixtures.js';
import { perTestRunId } from '../../src/seed/run-id.js';
import { Seeder } from '../../src/seed/seeder.js';

const OPERATIONS = loadSpecOperations();
const FORBIDDEN = OPERATIONS.filter((op) => op.declares403);

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
      (op) => op.pathParams.includes('repoName') && op.security !== 'public',
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
