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
 * `pnpm check:pr-set` (RPS-2735): the stackless guard of the `@pr` set, the crucial tests of a pull request.
 * It runs `playwright test --list --grep '@pr\b'` once per project and compares the number of tests with the
 * pinned `PR_SET` below. A project that lists fewer tests (a tag removed, a spec renamed away) or more (a
 * tag added) fails, and so does an empty set: there is no `--pass-with-no-tests`, which is how a typo in
 * a tag or a grep would otherwise turn a PR gate into a green no-op.
 *
 * Changing the set on purpose means changing the numbers here in the same commit, and the table in the
 * README section "Tags". It needs no stack and no real secret: `--list` loads the specs but runs nothing,
 * so a placeholder admin password only satisfies the env loader.
 */
import { execFileSync } from 'node:child_process';

/**
 * The grep of the set. Plain `@pr` is a prefix of `@proxy` and `@probe-ban`, so it would also select those
 * specs (it lists 12 api and 17 ui tests instead of 6 and 7): the word boundary is part of the tag.
 */
export const PR_GREP = '@pr\\b';

/** project -> number of `@pr` tests. The sum is the size of the set. */
export const PR_SET: Readonly<Record<string, number>> = {
  skeleton: 2, // seed: a user, a private repo and deploy tokens; an expired token is refused
  api: 6, // port separation: the panel API and the wire protocols stay on their own ports
  npm: 2, // npm: password-admin, token-rw
  pypi: 1, // pip: password-admin
  docker: 1, // crane: password-admin
  ui: 7, // login, logout, guard, create repo (maven), delete repo, deploy token, visibility
  maven: 4, // mvn: password-admin, token-rw; gradle kotlin: password-admin; sbt 1.13: password-admin
};

interface JsonSpec {
  tests?: unknown[];
}
interface JsonSuite {
  specs?: JsonSpec[];
  suites?: JsonSuite[];
}

function countTests(suite: JsonSuite): number {
  const own = (suite.specs ?? []).reduce((sum, spec) => sum + (spec.tests?.length ?? 0), 0);
  return own + (suite.suites ?? []).reduce((sum, child) => sum + countTests(child), 0);
}

function listedFor(project: string): number {
  const out = execFileSync(
    'pnpm',
    [
      'exec',
      'playwright',
      'test',
      '--list',
      '--grep',
      PR_GREP,
      '--project',
      project,
      '--reporter=json',
    ],
    {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      env: {
        // Only what `pnpm` and the env loader need; a `--list` run reads no real credential.
        PATH: process.env.PATH,
        HOME: process.env.HOME,
        REPSY_ADMIN_PASSWORD: 'list-only',
      },
    },
  );
  const report = JSON.parse(out) as JsonSuite;
  return countTests(report);
}

const problems: string[] = [];
let total = 0;
for (const [project, expected] of Object.entries(PR_SET)) {
  const listed = listedFor(project);
  total += listed;
  console.log(`${project.padEnd(10)} ${String(listed).padStart(3)} (pinned ${expected})`);
  if (listed !== expected) {
    problems.push(`${project}: lists ${listed} @pr tests, pinned ${expected}`);
  }
}
const pinnedTotal = Object.values(PR_SET).reduce((sum, n) => sum + n, 0);
console.log(`total      ${String(total).padStart(3)} (pinned ${pinnedTotal})`);

if (total === 0) {
  problems.push('the @pr set is empty');
}
if (problems.length > 0) {
  console.error(`\nThe @pr set changed:\n- ${problems.join('\n- ')}`);
  process.exit(1);
}
