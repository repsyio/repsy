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
 * Every name this harness seeds starts with `e2e-<runid>-`, so a sweep (see sweep.ts) can find and
 * remove exactly what a run created and nothing else, and so two runs sharing an instance (a local
 * run against a remote target, two CI jobs) never collide.
 *
 * Two spec constraints (`repsy-backend/.../openapi-spec.yaml`) drive the helpers below:
 *   - repo name: max 25 chars, `^[a-zA-Z0-9_][a-zA-Z0-9_\-]*$`
 *   - username:  3-25 chars, `^[a-z0-9_\-]+$` (lowercase only)
 */
export const RUN_PREFIX = 'e2e';

export const MAX_REPO_NAME_LENGTH = 25;
export const MAX_USERNAME_LENGTH = 25;

function assertLength(name: string, max: number, what: string): string {
  if (name.length > max) {
    throw new Error(
      `${what} "${name}" is ${name.length} chars, over the ${max}-char limit. Use a shorter run id.`,
    );
  }
  return name;
}

/** A repo name for this run: `e2e-<runid>-<protocol>-<n>`. */
export function repoName(runId: string, protocol: string, n: number): string {
  return assertLength(`${RUN_PREFIX}-${runId}-${protocol}-${n}`, MAX_REPO_NAME_LENGTH, 'repo name');
}

/** A username for this run: `e2e-<runid>-user-<n>`. */
export function userName(runId: string, n: number): string {
  return assertLength(`${RUN_PREFIX}-${runId}-user-${n}`, MAX_USERNAME_LENGTH, 'username');
}

/**
 * A password meeting the panel's rule (6-50 chars, needs a digit, a lowercase and an uppercase
 * letter, no whitespace) that is still specific to this run, so nothing depends on a shared fixed
 * value across parallel runs.
 */
export function password(runId: string): string {
  return `E2e-${runId}-Pwd1`;
}

/** Whether `name` was seeded by this harness (any run), safe for a sweep to consider deleting. */
export function isRunPrefixed(name: string): boolean {
  return name.startsWith(`${RUN_PREFIX}-`);
}

/**
 * Base-36, lower-case only (so it stays within the username pattern `^[a-z0-9_\-]+$`), left-padded
 * to `width`. Used to keep `perTestRunId`'s suffix both compact and deterministic.
 */
function toBase36(value: number, width: number): string {
  return value.toString(36).padStart(width, '0');
}

/**
 * A per-test variant of a run id: the same id with a worker index and a per-worker test counter
 * appended, instead of step 1's random 2-char salt. Two tests that share one `REPSY_E2E_RUN_ID`
 * (every test in one `run.sh test` invocation does, so a sweep can find them all by the shared
 * `e2e-` prefix) each get their own `Seeder`, and each `Seeder`'s sequence numbers for
 * repos/users/tokens start at 1 — without a per-test variant, two tests running in parallel workers
 * would both try to create `e2e-<runid>-maven-1` and the second would 409.
 *
 * `parallelIndex` (Playwright's `TestInfo.parallelIndex`) is stable per worker slot and distinct
 * across every worker running at once, so pairing it with a counter that a worker increments once
 * per test it runs is collision-free without coordinating across processes or relying on chance,
 * unlike a random salt: two workers can never share a `(parallelIndex, testSeq)` pair, and one
 * worker never reuses its own. `testSeq` must be provided by a counter the caller owns (a
 * module-level counter in `fixtures.ts`, one per worker process) since Playwright gives no
 * built-in "test index within this worker".
 *
 * One worker-index digit supports 36 concurrent workers; three counter digits support 46655 tests
 * in one worker before it would repeat — both are exhausted long before `e2e-<runid>-<protocol>-<n>`
 * (25-char limit) would be, so the total format stays `e2e-<runid(6)><worker(1)><seq(3)>-...`.
 */
export function perTestRunId(runId: string, parallelIndex: number, testSeq: number): string {
  return `${runId}${toBase36(parallelIndex, 1)}${toBase36(testSeq, 3)}`;
}

/** Characters of a base-36 worker slot and of the test counter, the widths `perTestRunId` writes. */
const WORKER_SLOTS = 36;
const TESTS_PER_GENERATION = 36 ** 3 / WORKER_SLOTS;

/**
 * The per-test run id of a test that runs in a Playwright worker, from the worker's `workerIndex` instead of its
 * `parallelIndex`. Playwright replaces a worker after a failed test: the replacement keeps the `parallelIndex` of the
 * slot and its per-worker counter starts at 1 again, so `(parallelIndex, testSeq)` repeats. The replacement then
 * seeds `e2e-<runid>-user-1` again while the tenant the dead worker soft-deleted under that name still exists, and a
 * login or a `where "username" = ...` statement meets both rows (RPS-2581). `workerIndex` is never reused in a run.
 *
 * The id keeps the shape `<runid><slot><counter>` that Cloud's `collaboratorName` reads (the character after the run
 * id must differ between workers that run at once, or two of them register the same collaborator): the slot is
 * `workerIndex % 36`, and every further 36 workers (`generation`) move the counter on by a block of
 * {@link TESTS_PER_GENERATION} tests, so a replacement never repeats an id of the worker before it. Workers that run
 * at once are fewer than 36 and, with replacements, a long way from 36 indexes apart; a worker that runs more than
 * {@link TESTS_PER_GENERATION} tests or the 36 * 36 th worker of a run is an error, not a repeated id.
 */
export function workerTestRunId(runId: string, workerIndex: number, testSeq: number): string {
  const generation = Math.floor(workerIndex / WORKER_SLOTS);
  if (testSeq >= TESTS_PER_GENERATION || generation >= WORKER_SLOTS) {
    throw new Error(`No run id left for test ${testSeq} of worker ${workerIndex}.`);
  }
  return perTestRunId(
    runId,
    workerIndex % WORKER_SLOTS,
    generation * TESTS_PER_GENERATION + testSeq,
  );
}
