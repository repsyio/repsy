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
 * The wire permission matrix (RPS-1475): for every operation that changes a registry beyond a plain
 * publish (unpublish, deprecate, dist-tag, yank, unlist, delete...), one cell per credential, and
 * each cell says whether the operation must go through and, if not, that it changes nothing.
 * `scenarios/loop.ts`'s publish-then-consume shape does not fit these operations, so the matrix has
 * its own catalog (this file: the model and the rule that derives an expectation) and runner
 * (`manage-matrix.ts`); a protocol contributes `ManageOperation`s from `clients/<protocol>-manage.ts`
 * and one spec that calls `registerManageMatrix` (`tests/<protocol>/manage-matrix.spec.ts`).
 *
 * The expectation of a cell comes from the operation's `ManagePermission` alone (`expectedForCell`):
 *
 *  - `WRITE`: `admin-password`, `user-password` and `token-rw` are allowed; `token-ro` and
 *    `anonymous` get a 401 (`ProtocolAuthService`: a refused permission is the same
 *    `UnAuthorizedException` as missing credentials, on every protocol).
 *  - `MANAGE`: only `admin-password` is allowed. A USER account and a deploy token, read-write or
 *    not, get a 401 (RPS-1424: a deploy token is never granted MANAGE; a USER never has it).
 *  - `NO_ROUTE`: nothing is allowed; the operation names the status every credential gets.
 *
 * An operation whose real answer differs from that rule lists the cell in `expectByCell` with the
 * probed status, and says why in its own comment, so the exception is written down where it lives.
 * The permissions themselves are the ones the protocol handlers declare (`getProperties()` of each
 * handler in `repsy-protocols/*`); deprecate, dist-tag, unlist, yank and owner changes only change
 * what is advertised and stay WRITE (RPS-1424, RPS-1317), removing a version or a package is MANAGE.
 */
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import type { ManageCellOverride, ManageCredentialKind, ManagePermission } from './types.js';
import type { MaterializedCredential } from './world.js';

/** Every credential, in the order the cells are registered. `anonymous` runs on a public repo. */
export const MANAGE_CREDENTIALS: readonly ManageCredentialKind[] = [
  'admin-password',
  'user-password',
  'token-rw',
  'token-ro',
  'anonymous',
];

/** What one attempt did: the exit code of the real client (when one ran) and the HTTP status. */
export interface ManageRun {
  /** Left out when the operation has no real client (`client: 'raw'`). */
  clientExitCode?: number;
  /** The status the wire request got, when the attempt itself is raw (`NO_ROUTE`, or a client-less
   *  operation). Left out when a real client ran: a client hides the status, so `probe` asks. */
  status?: number;
  /** The command line (redacted) or the raw request, for a failure message. */
  command: string;
}

/**
 * One operation of one protocol, ready to run with any credential: the state it acts on is already
 * seeded (with the admin credential), and `fingerprint` reads it back (always as admin).
 * Build it with `bindPrepared`, so `fingerprint` and `expectEffect` agree on their type.
 */
export interface PreparedManage {
  /** Runs the operation with `credential`: through the real client when there is one. */
  run(credential: MaterializedCredential): Promise<ManageRun>;
  /**
   * The same wire request as raw HTTP, with `credential`'s own header; only asked of a cell that must
   * be refused, after `run`, to pin the HTTP status that a client hides behind its exit code.
   */
  probe?(credential: MaterializedCredential): Promise<number>;
  /** What the operation acts on, read back as admin. */
  fingerprint(): Promise<unknown>;
  /** An allowed cell: `after` shows the operation's effect over `before`. */
  expectEffect(before: unknown, after: unknown): void | Promise<void>;
}

export interface ManageOperation {
  /** Lower-case runner name (`'npm'`), also the key into `fixtures.ts`'s repo type table. */
  protocol: string;
  /** Kebab-case id of the operation (`unpublish-version`), part of the test title. */
  id: string;
  permission: ManagePermission;
  /** The client that runs it, for the title of a failure (`'npm unpublish'`, `'raw'`). */
  client: string;
  /** The status of a refused cell (401 when left out); for `NO_ROUTE` the status every cell gets. */
  refusedStatus?: number;
  /** Cells whose observed answer differs from what `permission` derives. */
  expectByCell?: Partial<Record<ManageCredentialKind, ManageCellOverride>>;
  /** Extra Playwright tags (`@manage` is always added). */
  tags?: readonly `@${string}`[];
  /** Seeds what the operation acts on (with the admin credential) in the cell's own repo. */
  prepare(seeder: Seeder, repo: SeededRepo): Promise<PreparedManage>;
}

/** Erases `F` so operations of different protocols share one list; `fingerprint` and `expectEffect`
 *  are typed against each other where the operation is written. */
export function bindPrepared<F>(prepared: {
  run(credential: MaterializedCredential): Promise<ManageRun>;
  probe?(credential: MaterializedCredential): Promise<number>;
  fingerprint(): Promise<F>;
  expectEffect(before: F, after: F): void | Promise<void>;
}): PreparedManage {
  return {
    run: (credential) => prepared.run(credential),
    probe: prepared.probe ? (credential) => prepared.probe!(credential) : undefined,
    fingerprint: () => prepared.fingerprint(),
    expectEffect: (before, after) => prepared.expectEffect(before as F, after as F),
  };
}

export interface ManageCellExpectation {
  /** The operation goes through and has its effect. */
  allowed: boolean;
  /** The HTTP status a refused cell answers. */
  status: number;
}

const UNAUTHORIZED = 401;

const ALLOWED_TO_WRITE: readonly ManageCredentialKind[] = [
  'admin-password',
  'user-password',
  'token-rw',
];
const ALLOWED_TO_MANAGE: readonly ManageCredentialKind[] = ['admin-password'];

/** The expectation of one cell, derived from the operation's permission and its overrides. */
export function expectedForCell(
  operation: ManageOperation,
  credential: ManageCredentialKind,
): ManageCellExpectation {
  const derivedAllowed =
    operation.permission === 'WRITE'
      ? ALLOWED_TO_WRITE.includes(credential)
      : operation.permission === 'MANAGE'
        ? ALLOWED_TO_MANAGE.includes(credential)
        : false;
  const override = operation.expectByCell?.[credential];
  return {
    allowed: override?.allowed ?? derivedAllowed,
    status: override?.status ?? operation.refusedStatus ?? UNAUTHORIZED,
  };
}
