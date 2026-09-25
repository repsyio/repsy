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
 * Yarn classic (1.22.22, the frozen last release of the 1.x line) as an `NpmFamilyClient` (RPS-1330),
 * called by its absolute path (`/opt/clients/yarn1/bin/yarn`) in the sealed environment of `config.ts`.
 *
 * Configuration, as probed (README "npm-family clients", "Yarn classic"):
 *  - Registry, scopes and credentials all come from the isolated `$HOME/.npmrc` (`registry=`,
 *    `@scope:registry=`, the path-scoped `//host:port/<repo>/:_authToken=` / `:_auth=`), which yarn 1
 *    reads by walking up from the working directory through `$HOME`. It does NOT need a `.yarnrc`
 *    for any of that: `yarn config get registry` reads yarn's own rc and prints registry.yarnpkg.com,
 *    but `add`/`install`/`publish` use the npm registry object, which reads `.npmrc`.
 *  - `--no-default-rc` is NOT used: it makes yarn ignore every `.npmrc` (so the credentials never
 *    reach a request), and a `_authToken` line in a `.yarnrc` is not read either.
 *  - `always-auth=true` (rendered by `writeNpmrc` for `alwaysAuth`): without it yarn sends no
 *    `Authorization` on an UNSCOPED packument GET, only for a scoped one (H-3, probed), so a private
 *    repository answers 401, which yarn then reports as "Couldn't find package". The client turns it
 *    ON by default (what a real user of a private repository has to do); a binding says
 *    `alwaysAuth: false` to observe the quirk.
 *  - `YARN_IGNORE_PATH=1` (no `yarnPath`/`packageManager` redirect), `NODE_OPTIONS=--no-deprecation`
 *    (Node 24's punycode warning), `--non-interactive`, `--cache-folder` in the isolated HOME. The
 *    fixture package.json carries no `packageManager` field.
 *
 * `yarn publish <tarball>` reads the manifest (name, version, everything but the file list) from the
 * package.json of the WORKING DIRECTORY and the bytes from the tarball, so it runs in the package's own
 * directory. Yarn 1 has no `--force`: a republish is just another PUT, so `forceRepublish` is a no-op.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { isolatedWorkDir, type RunResult } from '../exec.js';
import { MARKER_FILENAME } from '../npm.js';
import type { Outcome, Scenario } from '../../scenarios/types.js';
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
import { secretsOf, runSealed, sealedEnv, writeNpmrc } from './config.js';

const BINARY = CLIENT_BINARIES['yarn-classic'];

const COMMAND_TIMEOUT_MS = 120_000;
const PACK_TIMEOUT_MS = 60_000;

const CAPS: Capabilities = {
  publish: true,
  distTagCmd: true,
  // Yarn 1 has no `deprecate` command and no `whoami`, `ping` or `search` for a private registry;
  // `yarn audit` always asks registry.yarnpkg.com (a dedicated test in the yarn-classic spec).
  deprecateCmd: false,
  viewCmd: true,
  whoamiCmd: false,
  pingCmd: false,
  searchCmd: false,
  auditCmd: false,
  frozenInstall: true,
};

/**
 * `yarn publish` treats a 400, 401 or 404 answer as a "rejection" the caller must look at (its
 * `request` resolves `false`) and `publish` never looks: it prints "Published." and exits 0 for a
 * publish the registry refused with 401 (`cli.js`, `RequestManager` and `publish`; observed live).
 * Every other refusal (403, 409, 5xx) is an error and fails the command. A consume is not affected:
 * an unauthorised packument GET fails `add` ("Couldn't find package").
 */
function exitQuirk(
  scenario: Scenario,
  side: 'publish' | 'consume',
  outcome: Outcome,
): string | undefined {
  // With no credential at all `yarn publish` stops before it sends anything ("No token found and
  // can't prompt for login when running with --non-interactive", exit 1): that one fails as it should.
  return side === 'publish' && outcome === 'unauthorized' && scenario.credential !== 'anonymous'
    ? 'yarn classic reports "Published." and exits 0 when the registry answers a publish with 401 ' +
        '(it resolves 400/401/404 as a soft rejection and never checks it)'
    : undefined;
}

/** Yarn 1's options every command carries: no prompts, its cache in the isolated HOME. */
function common(ctx: ClientCtx): string[] {
  return ['--cache-folder', path.join(ctx.home, 'yarn-cache'), '--non-interactive'];
}

/** Runs any yarn 1 command in `ctx` (its isolated cache, no prompts): for what the interface has no verb for. */
export function runYarn(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
): Promise<RunResult> {
  return exec(ctx, label, args, cwd);
}

function exec(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
  timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<RunResult> {
  return runSealed(BINARY, [...args, ...common(ctx)], {
    cwd,
    env: ctx.env,
    timeoutMs,
    redact: ctx.secrets,
    label,
  });
}

async function prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> {
  const { home, work } = await isolatedWorkDir(`npmc-yarn1-${label}`);
  // always-auth is ON unless a binding says otherwise (see the header).
  await writeNpmrc(
    home,
    bindings.map((binding) => ({ ...binding, alwaysAuth: binding.alwaysAuth ?? true })),
  );
  await fs.mkdir(path.join(home, 'yarn-cache'), { recursive: true });
  return {
    home,
    work,
    env: sealedEnv(home, { NODE_OPTIONS: '--no-deprecation', YARN_IGNORE_PATH: '1' }),
    secrets: secretsOf(bindings),
    bindings,
  };
}

async function pack(ctx: ClientCtx, dir: string): Promise<PackedTarball> {
  const destDir = await fs.mkdtemp(path.join(ctx.home, 'pack-'));
  const file = path.join(destDir, 'package.tgz');
  const result = await exec(
    ctx,
    'yarn1-pack',
    ['pack', '--ignore-scripts', '--filename', file],
    dir,
    PACK_TIMEOUT_MS,
  );
  if (result.exitCode !== 0) {
    throw new Error(
      `yarn classic client: "yarn pack" failed unexpectedly (exit ${result.exitCode})`,
    );
  }
  return { file, bytes: await fs.readFile(file) };
}

function publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult> {
  // No `--force`, and no need for one: yarn 1 has no client-side "version exists" check.
  // `--no-git-tag-version`: `publish` bumps and tags through git, which a fixture has no business with.
  const args = ['publish', opts.tarball.file, '--ignore-scripts', '--no-git-tag-version'];
  if (opts.tag) {
    args.push('--tag', opts.tag);
  }
  return exec(ctx, 'yarn1-publish', args, opts.dir);
}

function add(ctx: ClientCtx, specs: readonly string[], opts: AddOptions = {}): Promise<RunResult> {
  const args = ['add', ...specs, '--ignore-scripts'];
  if (!opts.save) {
    args.push('--no-lockfile');
  }
  return exec(ctx, 'yarn1-add', args);
}

function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
  return exec(ctx, opts.frozen ? 'yarn1-install-frozen' : 'yarn1-install', [
    'install',
    '--ignore-scripts',
    ...(opts.frozen ? ['--frozen-lockfile'] : []),
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

/**
 * `yarn info <spec> --json` prints one `{"type":"inspect","data":{<packument fields>}}` line; the
 * matrix reads the document itself, so the result carries `data` as its stdout (the exit code, the
 * command and stderr are yarn's own). Anything that is not that line is left untouched.
 */
async function view(ctx: ClientCtx, spec: string): Promise<RunResult> {
  const result = await exec(ctx, 'yarn1-info', ['info', spec, '--json']);
  try {
    const line = JSON.parse(result.stdout.trim()) as { type?: string; data?: unknown };
    return line.type === 'inspect' ? { ...result, stdout: JSON.stringify(line.data) } : result;
  } catch {
    return result;
  }
}

export const yarnClassicClient: NpmFamilyClient = {
  id: 'yarn-classic',
  label: 'yarn-classic',
  tag: '@yarn-classic',
  binary: BINARY,
  caps: CAPS,
  lockfile: 'yarn.lock',
  exitQuirk,

  prepare,
  pack,
  publish,
  add,
  install,
  readInstalledFile,

  // `tag list`/`tag remove`: the `ls`/`rm` spellings are deprecated in yarn 1.22 (they warn).
  distTag: {
    add: (ctx, spec, tag) => exec(ctx, 'yarn1-tag-add', ['tag', 'add', spec, tag]),
    list: (ctx, packageName) => exec(ctx, 'yarn1-tag-list', ['tag', 'list', packageName]),
    remove: (ctx, packageName, tag) =>
      exec(ctx, 'yarn1-tag-remove', ['tag', 'remove', packageName, tag]),
  },
  view,
};
