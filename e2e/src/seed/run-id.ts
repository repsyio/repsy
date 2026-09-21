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

const SALT_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';
const SALT_LENGTH = 2;

/**
 * A per-test variant of a run id: the same id with a couple of random characters appended. Two
 * tests that share one `REPSY_E2E_RUN_ID` (every test in one `run.sh test` invocation does, so a
 * sweep can find them all by the shared `e2e-` prefix) each get their own `Seeder`, and each
 * `Seeder`'s sequence numbers for repos/users/tokens start at 1 — without this, two tests running
 * in parallel workers would both try to create `e2e-<runid>-maven-1` and the second would 409.
 */
export function perTestRunId(runId: string): string {
  let salt = '';
  for (let i = 0; i < SALT_LENGTH; i += 1) {
    salt += SALT_ALPHABET[Math.floor(Math.random() * SALT_ALPHABET.length)];
  }
  return `${runId}${salt}`;
}
