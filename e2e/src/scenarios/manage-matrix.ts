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
 * The runner of the wire permission matrix (RPS-1475; the model and the expectation rule are in
 * `manage-catalog.ts`). `registerManageMatrix(operations)` registers one test per operation and
 * credential, titled `${protocol} manage > ${operation} > ${credential}` and tagged `@manage`
 * (`@negative` too when the cell must be refused), and every cell gets its own repo (private, public
 * for `anonymous`) so cells never share state and can run in parallel.
 *
 * A cell seeds what the operation acts on (admin), takes the operation's fingerprint, runs the
 * operation with the cell's credential and then checks:
 *
 *  - an ALLOWED cell: the real client exited 0 (when one ran) and `expectEffect` sees the operation's
 *    effect between the fingerprint before and after;
 *  - a REFUSED cell: the real client exited non-zero, the raw request of the same operation got the
 *    expected status (401, or the operation's own probed status), and the fingerprint is byte for
 *    byte what it was: a refused operation must change nothing.
 *
 * A `@negative` cell reserves a slot of the remote failed-auth budget first, exactly as `loop.ts`
 * does, and the negative cells run serially on a remote target.
 */
import { target } from '../target.js';
import type { ManageOperation } from './manage-catalog.js';
import { expectedForCell, MANAGE_CREDENTIALS } from './manage-catalog.js';
import { expect, materializeCredentialKind, repoTypeForProtocol, test } from './fixtures.js';
import { RemoteAuthBudget } from './remote-throttle.js';
import type { ManageCredentialKind } from './types.js';

const SUCCESS_MIN = 200;
const SUCCESS_MAX = 300;

/** Registers every credential's cell of every operation, grouped by protocol. */
export function registerManageMatrix(operations: readonly ManageOperation[]): void {
  const remoteAuthBudget = new RemoteAuthBudget();

  function registerCell(operation: ManageOperation, credential: ManageCredentialKind): void {
    const expected = expectedForCell(operation, credential);
    const tags = [
      '@manage',
      ...(expected.allowed ? [] : ['@negative' as const]),
      ...(operation.tags ?? []),
    ];

    test(
      `${operation.protocol} manage > ${operation.id} > ${credential}`,
      { tag: tags },
      async ({ seeder }) => {
        if (target.isRemote && !expected.allowed) {
          await remoteAuthBudget.reserve();
        }

        const repoType = repoTypeForProtocol(operation.protocol);
        const repo = await seeder.createRepo(repoType, { privateRepo: credential !== 'anonymous' });
        const prepared = await operation.prepare(seeder, repo);
        const materialized = await materializeCredentialKind(
          seeder,
          credential,
          repo.name,
          repoType,
        );

        const before = await prepared.fingerprint();
        const result = await prepared.run(materialized);
        const what =
          `${operation.client} as ${credential} (${operation.permission}; ` +
          `client exit ${result.clientExitCode ?? 'none'}; ${result.command})`;

        if (expected.allowed) {
          if (result.clientExitCode !== undefined) {
            expect(result.clientExitCode, `${what} should succeed`).toBe(0);
          }
          if (result.status !== undefined) {
            expect(result.status, `${what} should be accepted`).toBeGreaterThanOrEqual(SUCCESS_MIN);
            expect(result.status, `${what} should be accepted`).toBeLessThan(SUCCESS_MAX);
          }
          await prepared.expectEffect(before, await prepared.fingerprint());
          return;
        }

        if (result.clientExitCode !== undefined) {
          expect(result.clientExitCode, `${what} should fail`).not.toBe(0);
        }
        // A real client hides the HTTP status behind its exit code: the raw request of the same
        // operation says which refusal it was.
        const status = result.status ?? (await prepared.probe?.(materialized));
        expect(
          status,
          `${what}: the operation has no way to read the refusal's status`,
        ).toBeDefined();
        expect(status, `${what} should be refused with ${expected.status}`).toBe(expected.status);

        expect(
          await prepared.fingerprint(),
          `${what}: a refused operation must change nothing`,
        ).toEqual(before);
      },
    );
  }

  const protocols = [...new Set(operations.map((operation) => operation.protocol))];
  for (const protocol of protocols) {
    const own = operations.filter((operation) => operation.protocol === protocol);
    const cells = own.flatMap((operation) =>
      MANAGE_CREDENTIALS.map((credential) => ({
        operation,
        credential,
        allowed: expectedForCell(operation, credential).allowed,
      })),
    );

    test.describe(`${protocol} manage`, () => {
      for (const cell of cells.filter((c) => c.allowed)) {
        registerCell(cell.operation, cell.credential);
      }
    });

    test.describe(`${protocol} manage (negative)`, () => {
      // Parallel on local/ci, serial on remote (see `loop.ts`): the refused cells spend failed-auth
      // attempts of the server's own throttle.
      test.describe.configure({ mode: target.isRemote ? 'serial' : 'parallel' });

      for (const cell of cells.filter((c) => !c.allowed)) {
        registerCell(cell.operation, cell.credential);
      }
    });
  }
}
