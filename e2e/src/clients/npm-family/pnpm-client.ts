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
 * pnpm as an `NpmFamilyClient` (RPS-1330), the version the runner image pins (12.6.0, one major on
 * purpose). pnpm 12 is a native binary and runs the commands of the matrix itself (H-1/H-2, probed
 * with the wire recorder in `tests/npm-clients/pnpm/commands.spec.ts`), so every capability is a real
 * pnpm cell.
 *
 * Configuration (pnpm 11+): the `.npmrc` in the isolated HOME carries only what pnpm still reads from
 * one, the registry and the auth keys (`config.ts`'s `writeNpmrc`); every other setting is a CLI flag
 * or a `PNPM_CONFIG_*` variable, so no `pnpm-workspace.yaml` is written into the work directory (a
 * package rendered there would otherwise sit under a workspace root it is not a member of). The store
 * and the state directory are inside the isolated HOME, so two parallel workers never share them.
 * `fetchRetries` is 0: pnpm's default back-off on a refused connection is 70 s.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { isolatedWorkDir, run, type RunResult } from '../exec.js';
import { MARKER_FILENAME } from '../npm.js';
import {
  CLIENT_BINARIES,
  type AddOptions,
  type Capabilities,
  type ClientCtx,
  type NpmFamilyClient,
  type PackedTarball,
  type PublishOptions,
  type RegistryBinding,
} from './client.js';
import { secretsOf, sealedEnv, writeNpmrc } from './config.js';

const BINARY = CLIENT_BINARIES.pnpm;

const COMMAND_TIMEOUT_MS = 120_000;
const PACK_TIMEOUT_MS = 60_000;

const CAPS: Capabilities = {
  publish: true,
  distTagCmd: true,
  deprecateCmd: true,
  viewCmd: true,
  whoamiCmd: true,
  pingCmd: true,
  searchCmd: true,
  auditCmd: true,
  frozenInstall: true,
};

/** The options every project command carries: parseable, progress-free output. */
const QUIET = ['--reporter=append-only', '--no-progress'];

export interface PnpmRunOptions {
  cwd?: string;
  /** Piped to stdin. */
  input?: string;
  timeoutMs?: number;
}

/** Runs any pnpm command in `ctx` (the pnpm-only specs' entry point for what `NpmFamilyClient` lacks). */
export function runPnpm(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  options: PnpmRunOptions = {},
): Promise<RunResult> {
  return run(BINARY, args, {
    cwd: options.cwd ?? ctx.work,
    env: ctx.env,
    timeoutMs: options.timeoutMs ?? COMMAND_TIMEOUT_MS,
    redact: ctx.secrets,
    label,
    ...(options.input !== undefined ? { input: options.input } : {}),
  });
}

function exec(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
  timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<RunResult> {
  return runPnpm(ctx, label, args, { cwd, timeoutMs });
}

/**
 * `pnpm -r publish` from a workspace root: every member that is not in the registry yet, dependencies
 * first, with each `workspace:` range rewritten in the published manifest. The workspace needs one
 * `pnpm install` first (pnpm refuses to resolve a `workspace:` range of a dependency it has not linked).
 */
export async function publishWorkspace(ctx: ClientCtx, root: string): Promise<RunResult> {
  const installed = await runPnpm(
    ctx,
    'pnpm-workspace-install',
    ['install', '--ignore-scripts', ...QUIET],
    {
      cwd: root,
    },
  );
  if (installed.exitCode !== 0) {
    throw new Error(
      `pnpm client: workspace "pnpm install" failed (exit ${installed.exitCode}): ${installed.stderr}`,
    );
  }
  return runPnpm(
    ctx,
    'pnpm-workspace-publish',
    ['--recursive', 'publish', '--no-git-checks', '--ignore-scripts', ...QUIET],
    { cwd: root },
  );
}

async function prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> {
  const { home, work } = await isolatedWorkDir(`npmc-pnpm-${label}`);
  await writeNpmrc(home, bindings);
  const env = sealedEnv(home, {
    PNPM_CONFIG_STORE_DIR: path.join(home, 'pnpm-store'),
    PNPM_CONFIG_STATE_DIR: path.join(home, 'pnpm-state'),
    PNPM_CONFIG_FETCH_RETRIES: '0',
    // pnpm asks the configured registry for a newer `pnpm` on every install, a stray `GET /<repo>/pnpm`.
    PNPM_CONFIG_UPDATE_NOTIFIER: 'false',
    // pnpm 12's default is 1 day: a package published seconds ago is "too new" for a frozen install in
    // a fresh directory. That policy has its own cells (`pnpm/release-age.spec.ts`); here it is off.
    PNPM_CONFIG_MINIMUM_RELEASE_AGE: '0',
  });
  return { home, work, env, secrets: secretsOf(bindings), bindings };
}

async function pack(ctx: ClientCtx, dir: string): Promise<PackedTarball> {
  const destDir = await fs.mkdtemp(path.join(ctx.home, 'pack-'));
  const result = await exec(
    ctx,
    'pnpm-pack',
    ['pack', '--pack-destination', destDir, ...QUIET],
    dir,
    PACK_TIMEOUT_MS,
  );
  const file = (await fs.readdir(destDir))[0];
  if (result.exitCode !== 0 || !file) {
    throw new Error(
      `pnpm client: "pnpm pack" failed unexpectedly (exit ${result.exitCode}): ${result.stderr}`,
    );
  }
  return { file: path.join(destDir, file), bytes: await fs.readFile(path.join(destDir, file)) };
}

function publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult> {
  const args = ['publish', opts.tarball.file, '--no-git-checks', '--ignore-scripts', ...QUIET];
  if (opts.tag) {
    args.push('--tag', opts.tag);
  }
  if (opts.forceRepublish) {
    args.push('--force');
  }
  return exec(ctx, 'pnpm-publish', args, opts.dir);
}

function add(ctx: ClientCtx, specs: readonly string[], _opts: AddOptions = {}): Promise<RunResult> {
  return exec(ctx, 'pnpm-add', ['add', ...specs, '--ignore-scripts', ...QUIET]);
}

function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
  return exec(ctx, opts.frozen ? 'pnpm-install-frozen' : 'pnpm-install', [
    'install',
    opts.frozen ? '--frozen-lockfile' : '--no-frozen-lockfile',
    '--ignore-scripts',
    ...QUIET,
  ]);
}

/**
 * pnpm links only the project's direct dependencies into `node_modules`; a transitive dependency
 * lives in the virtual store, `node_modules/.pnpm/<name>@<version>.../node_modules/<name>` (scoped
 * names spell `/` as `+`). A direct dependency is read through its symlink like any client's.
 */
async function readInstalledFile(
  ctx: ClientCtx,
  packageName: string,
  file: string = MARKER_FILENAME,
): Promise<string | undefined> {
  const modules = path.join(ctx.work, 'node_modules');
  const candidates = [path.join(modules, ...packageName.split('/'))];
  try {
    const prefix = `${packageName.replace('/', '+')}@`;
    for (const entry of await fs.readdir(path.join(modules, '.pnpm'))) {
      if (entry.startsWith(prefix)) {
        candidates.push(
          path.join(modules, '.pnpm', entry, 'node_modules', ...packageName.split('/')),
        );
      }
    }
  } catch {
    // No virtual store: nothing was installed.
  }
  for (const candidate of candidates) {
    try {
      return await fs.readFile(path.join(candidate, file), 'utf8');
    } catch {
      // Not there: try the next place.
    }
  }
  return undefined;
}

export const pnpmClient: NpmFamilyClient = {
  id: 'pnpm',
  label: 'pnpm',
  tag: '@pnpm',
  binary: BINARY,
  caps: CAPS,
  lockfile: 'pnpm-lock.yaml',

  prepare,
  pack,
  publish,
  add,
  install,
  readInstalledFile,

  distTag: {
    add: (ctx, spec, tag) => exec(ctx, 'pnpm-dist-tag-add', ['dist-tag', 'add', spec, tag]),
    list: (ctx, packageName) => exec(ctx, 'pnpm-dist-tag-ls', ['dist-tag', 'ls', packageName]),
    remove: (ctx, packageName, tag) =>
      exec(ctx, 'pnpm-dist-tag-rm', ['dist-tag', 'rm', packageName, tag]),
  },
  deprecate: (ctx, spec, message) =>
    message === ''
      ? exec(ctx, 'pnpm-undeprecate', ['undeprecate', spec])
      : exec(ctx, 'pnpm-deprecate', ['deprecate', spec, message]),
  view: (ctx, spec) => exec(ctx, 'pnpm-view', ['view', spec, '--json']),
  whoami: (ctx) => exec(ctx, 'pnpm-whoami', ['whoami']),
  logout: (ctx) => exec(ctx, 'pnpm-logout', ['logout']),
  ping: (ctx) => exec(ctx, 'pnpm-ping', ['ping']),
  search: (ctx, text) => exec(ctx, 'pnpm-search', ['search', text, '--json']),
  audit: (ctx) => exec(ctx, 'pnpm-audit', ['audit', '--json']),
};
