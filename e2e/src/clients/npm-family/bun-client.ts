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
 * bun (1.3.x, a native binary copied from `oven/bun`) as an `NpmFamilyClient` (RPS-1330), called by
 * its absolute path in the sealed environment of `config.ts`.
 *
 * Three ways to configure it, all real; they differ only in `prepare` and in how `publish` gets its
 * credential, every other command is the same:
 *  - `bunClient` (the one the matrix runs) reads `$HOME/.bunfig.toml` (`[install] registry` +
 *    `[install.scopes]`, with `token` or `username`/`password`). `bun add`/`install`/`info`/`pm
 *    whoami` use it as it is (a password goes out as `Basic`).
 *  - `bunfigOnlyClient` and `bunNpmrcClient` (the latter reads only `$HOME/.npmrc`: `registry=`,
 *    `_authToken=`, `_auth=`; H-11) never touch a credential themselves.
 *
 * `bun publish` only ever sends a Bearer TOKEN, whatever the configuration (probed: bunfig
 * `username`/`password`, `.npmrc` `_auth`, and `username`+`_password` all stop at "missing
 * authentication" before a request is sent). A person who has a Repsy PASSWORD gets a token the way
 * bun's own hint says (`bunx npm login`): the registry's couch login (`PUT /-/user/org.couchdb.user:
 * <name>`) answers a Bearer JWT. `bunClient.publish` does exactly that for a password binding, then
 * publishes with the JWT as `_authToken` in a HOME of its own; so the catalog's password scenarios
 * (and every seed publish by the admin) run through bun. What the bare password does is pinned in
 * `tests/npm-clients/bun/`.
 *
 * `bunfig.toml` has no read-back command, so what bun actually takes from it is asserted by what
 * crosses the wire (`tests/npm-clients/bun/`).
 *
 * Commands: `bun pm pack` + `bun publish <tarball>` (a republish reaches the server with no flag,
 * see `publish`), `bun add --no-save`, `bun install [--frozen-lockfile]` (lockfile `bun.lock`, text),
 * `bun info --json`, `bun pm whoami` and `bun audit --json`. bun has no `dist-tag`, `deprecate`,
 * `ping` or `search` command, so those capabilities are off.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { env } from '../../env.js';
import { isolatedWorkDir, type RunResult } from '../exec.js';
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
import { secretsOf, runSealed, sealedEnv, writeBunfig, writeNpmrc } from './config.js';

const BINARY = CLIENT_BINARIES.bun;

const COMMAND_TIMEOUT_MS = 120_000;
const PACK_TIMEOUT_MS = 60_000;

const CAPS: Capabilities = {
  publish: true,
  distTagCmd: false,
  deprecateCmd: false,
  // `bun info --json` prints a subset of the packument (the latest manifest plus `versions`, no
  // `dist-tags`/`time`) and `bun audit --json` prints the bare advisory map, not npm's report, so the
  // matrix cells that parse npm's document shapes (`view`, `audit`) do not apply; `tests/npm-clients/bun/`
  // covers both commands against what bun does print.
  viewCmd: false,
  whoamiCmd: true,
  pingCmd: false,
  searchCmd: false,
  auditCmd: false,
  frozenInstall: true,
};

/** Where bun's per-user config comes from (see the header). */
export type BunConfigSource = 'bunfig' | 'bunfig-only' | 'npmrc';

export function bunExec(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
  timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<RunResult> {
  return runSealed(BINARY, args, { cwd, env: ctx.env, timeoutMs, redact: ctx.secrets, label });
}

/** bun's package-manager commands refuse to run outside a project: give `ctx.work` a package.json. */
async function ensureProject(ctx: ClientCtx): Promise<void> {
  const manifest = path.join(ctx.work, 'package.json');
  try {
    await fs.access(manifest);
  } catch {
    await fs.writeFile(manifest, '{"name":"bun-e2e-project","version":"0.0.0","private":true}\n');
  }
}

/** The flags every install-family command carries: no lifecycle scripts, no progress bar. */
const QUIET = ['--ignore-scripts', '--no-progress'];

function preparer(config: BunConfigSource) {
  return async (label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> => {
    const { home, work } = await isolatedWorkDir(`npmc-bun-${label}`);
    if (config === 'npmrc') {
      await writeNpmrc(home, bindings);
    } else {
      await writeBunfig(path.join(home, '.bunfig.toml'), bindings);
    }
    const cache = path.join(home, 'bun-cache');
    await fs.mkdir(cache, { recursive: true });
    return {
      home,
      work,
      env: sealedEnv(home, {
        BUN_INSTALL_CACHE_DIR: cache,
        BUN_INSTALL: path.join(home, '.bun'),
        DO_NOT_TRACK: '1',
        // Playwright's workers set FORCE_COLOR, which bun honours over NO_COLOR; the sealed env no
        // longer carries it (RPS-1364), so NO_COLOR alone keeps colour codes out of the messages.
        NO_COLOR: '1',
      }),
      secrets: secretsOf(bindings),
      bindings,
    };
  };
}

async function pack(ctx: ClientCtx, dir: string): Promise<PackedTarball> {
  const destDir = await fs.mkdtemp(path.join(ctx.home, 'pack-'));
  const result = await bunExec(
    ctx,
    'bun-pack',
    ['pm', 'pack', '--ignore-scripts', '--destination', destDir, '--quiet'],
    dir,
    PACK_TIMEOUT_MS,
  );
  const file = (await fs.readdir(destDir))[0];
  if (result.exitCode !== 0 || !file) {
    throw new Error(
      `bun client: "bun pm pack" failed unexpectedly (exit ${result.exitCode})\n${result.stderr}`,
    );
  }
  return { file: path.join(destDir, file), bytes: await fs.readFile(path.join(destDir, file)) };
}

/**
 * The couch login of the registry: a Bearer JWT for a user's password (the token `npm login` would
 * store). A refused login (a wrong password) falls back to the password itself as the token, so
 * `bun publish` sends a request the registry refuses, like a person who pasted the wrong thing.
 */
async function loginToken(binding: RegistryBinding): Promise<string> {
  const { username = '', password = '' } = binding.credential;
  const res = await fetch(
    `${env.repoBaseUrl}/${binding.repoName}/-/user/org.couchdb.user:${encodeURIComponent(username)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: username, password }),
    },
  );
  if (res.status !== 201) {
    return password;
  }
  return ((await res.json()) as { token?: string }).token ?? password;
}

let publishSeq = 0;

/**
 * A HOME for one `bun publish` whose registries carry a Bearer token for every binding: the binding's
 * own token, or the login token of a password binding. The bunfig of the invocation's HOME is not
 * there, so the `.npmrc` here is all `bun publish` reads.
 */
async function tokenHome(ctx: ClientCtx): Promise<{ env: NodeJS.ProcessEnv; secrets: string[] }> {
  publishSeq += 1;
  const home = path.join(ctx.home, `publish-home-${publishSeq}`);
  const secrets = [...ctx.secrets];
  const bindings: RegistryBinding[] = [];
  for (const binding of ctx.bindings) {
    if (binding.credential.kind === 'token') {
      bindings.push(binding);
      continue;
    }
    const token = await loginToken(binding);
    secrets.push(token);
    bindings.push({
      ...binding,
      credential: { ...binding.credential, kind: 'token', password: token },
    });
  }
  await writeNpmrc(home, bindings);
  return { env: { ...ctx.env, HOME: home }, secrets };
}

function add(ctx: ClientCtx, specs: readonly string[], opts: AddOptions = {}): Promise<RunResult> {
  const args = ['add', ...specs, ...QUIET];
  if (!opts.save) {
    args.push('--no-save');
  }
  return bunExec(ctx, 'bun-add', args);
}

function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
  return bunExec(ctx, opts.frozen ? 'bun-install-frozen' : 'bun-install', [
    'install',
    ...(opts.frozen ? ['--frozen-lockfile'] : []),
    ...QUIET,
  ]);
}

async function readInstalledFile(
  ctx: ClientCtx,
  packageName: string,
  file: string = MARKER_FILENAME,
): Promise<string | undefined> {
  try {
    return await fs.readFile(
      path.join(ctx.work, 'node_modules', ...packageName.split('/'), file),
      'utf8',
    );
  } catch {
    return undefined;
  }
}

function createBunClient(config: BunConfigSource, label: string): NpmFamilyClient {
  /**
   * `bun publish <tarball>`: bun sends a republish to the server with no flag (a refusal is the
   * server's 403; `--tolerate-republish` only turns that refusal's exit code into 0 and is never
   * used here), so `forceRepublish` changes nothing. `bunClient` publishes with a token even for a
   * password binding (see the header); the other two variants publish with what they were given.
   */
  async function publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult> {
    const args = ['publish', opts.tarball.file, '--ignore-scripts'];
    if (opts.tag) {
      args.push('--tag', opts.tag);
    }
    if (config !== 'bunfig') {
      return bunExec(ctx, 'bun-publish', args, opts.dir);
    }
    const { env: publishEnv, secrets } = await tokenHome(ctx);
    return runSealed(BINARY, args, {
      cwd: opts.dir,
      env: publishEnv,
      timeoutMs: COMMAND_TIMEOUT_MS,
      redact: secrets,
      label: 'bun-publish',
    });
  }

  return {
    id: 'bun',
    label,
    tag: '@bun',
    binary: BINARY,
    caps: CAPS,
    lockfile: 'bun.lock',

    prepare: preparer(config),
    pack,
    publish,
    add,
    install,
    readInstalledFile,

    view: async (ctx, spec) => {
      await ensureProject(ctx);
      return bunExec(ctx, 'bun-info', ['info', spec, '--json']);
    },
    whoami: async (ctx) => {
      await ensureProject(ctx);
      return bunExec(ctx, 'bun-whoami', ['pm', 'whoami']);
    },
    audit: async (ctx) => {
      await ensureProject(ctx);
      return bunExec(ctx, 'bun-audit', ['audit', '--json']);
    },
  };
}

/** bun configured by a `bunfig.toml` (`$HOME/.bunfig.toml`): the one the matrix runs. */
export const bunClient: NpmFamilyClient = createBunClient('bunfig', 'bun');

/** The bunfig alone (no `.npmrc` `_auth` for a password), to pin what `bun publish` reads from it. */
export const bunfigOnlyClient: NpmFamilyClient = createBunClient('bunfig-only', 'bun-bunfig-only');

/** The same bun configured by `$HOME/.npmrc` alone, for the bun-only config-variant cells. */
export const bunNpmrcClient: NpmFamilyClient = createBunClient('npmrc', 'bun-npmrc');
