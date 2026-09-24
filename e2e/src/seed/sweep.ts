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
 * Deletes every `e2e-*` repo and user older than a given number of hours: leftovers from a run that
 * crashed before its own cleanup ran. Both `RepoListInfo` and `UserResponse` carry a `createdAt`,
 * so age is read from the API rather than assumed; `--all` ignores age entirely, for a full reset
 * regardless of timestamps.
 *
 * Never touches the `admin` user or a repo/user without the `e2e-` prefix (a fresh instance seeds 9
 * default repos, none of them prefixed). Run with `pnpm sweep -- [--hours N] [--all] [--dry-run]`
 * or `./run.sh sweep`.
 */
import { PanelApi } from '../api/panel-api.js';
import { env } from '../env.js';
import { isRunPrefixed, RUN_PREFIX } from './run-id.js';

const DEFAULT_SWEEP_HOURS = 24;
const MS_PER_HOUR = 60 * 60 * 1000;

export interface SweepOptions {
  hours: number;
  all: boolean;
  dryRun: boolean;
}

export interface SweepResult {
  deletedRepos: number;
  deletedUsers: number;
}

export function parseSweepArgs(argv: readonly string[]): SweepOptions {
  let hours = DEFAULT_SWEEP_HOURS;
  let all = false;
  let dryRun = false;

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--hours') {
      i += 1;
      hours = Number(argv[i]);
    } else if (arg === '--all') {
      all = true;
    } else if (arg === '--dry-run') {
      dryRun = true;
    } else {
      throw new Error(`sweep: unknown argument "${arg}"`);
    }
  }

  if (!Number.isFinite(hours) || hours < 0) {
    throw new Error('sweep: --hours must be a non-negative number');
  }

  return { hours, all, dryRun };
}

function isOlderThan(createdAt: string, hours: number): boolean {
  return Date.now() - new Date(createdAt).getTime() > hours * MS_PER_HOUR;
}

export async function sweep(opts: SweepOptions): Promise<SweepResult> {
  const api = new PanelApi(env.apiBaseUrl);
  await api.login(env.adminUsername, env.adminPassword);

  let deletedRepos = 0;

  // Every candidate is collected BEFORE the first delete: the list is paged, and deleting while
  // reading it would shift the following rows into the page already read and skip them.
  const candidates = (await api.listAllRepos({ q: `${RUN_PREFIX}-` })).filter(
    (repo) => isRunPrefixed(repo.name) && (opts.all || isOlderThan(repo.createdAt, opts.hours)),
  );

  for (const repo of candidates) {
    if (opts.dryRun) {
      console.log(`[dry-run] would delete repo ${repo.name}`);
      continue;
    }

    await api.deleteRepo(repo.name);
    deletedRepos += 1;
    console.log(`deleted repo ${repo.name}`);
  }

  let deletedUsers = 0;

  // Same rule for users: read every page first, then delete. Deleting while paging shifts the
  // following users into the page already read (more than 100 e2e users is a normal leftover).
  const staleUsers = (await api.listAllUsers({ q: `${RUN_PREFIX}-` })).filter(
    (user) =>
      user.username !== env.adminUsername &&
      isRunPrefixed(user.username) &&
      (opts.all || isOlderThan(user.createdAt, opts.hours)),
  );

  for (const user of staleUsers) {
    if (opts.dryRun) {
      console.log(`[dry-run] would delete user ${user.username}`);
      continue;
    }

    await api.deleteUser(user.id);
    deletedUsers += 1;
    console.log(`deleted user ${user.username}`);
  }

  return { deletedRepos, deletedUsers };
}

const isMain = process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`;

if (isMain) {
  sweep(parseSweepArgs(process.argv.slice(2)))
    .then(({ deletedRepos, deletedUsers }) => {
      console.log(`sweep: deleted ${deletedRepos} repo(s) and ${deletedUsers} user(s)`);
    })
    .catch((err: unknown) => {
      console.error(err);
      process.exitCode = 1;
    });
}
