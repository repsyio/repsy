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
 * The npm CLI as an `NpmFamilyClient` (RPS-1330): the baseline every other client's column of the
 * matrix is compared against, and the reference implementation of the interface. It runs the npm 11
 * that `node:24-bookworm-slim` ships, by its absolute path, in the sealed environment of `config.ts`.
 *
 * `npm` runs the way `clients/npm.ts` runs it (`--userconfig` at the isolated `.npmrc`, `--cache`
 * in the isolated HOME, `--ignore-scripts`), so a catalog round trip through this client is the same
 * command sequence as the `npm` runner's. Each command reports its exit code and output; what it
 * proves about the registry is up to the spec (`RunResult`, never a verdict).
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

const BINARY = CLIENT_BINARIES.npm;

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

/** The options every command carries: the isolated user config and cache, no lifecycle scripts. */
function common(ctx: ClientCtx): string[] {
  return [
    '--userconfig',
    path.join(ctx.home, '.npmrc'),
    '--cache',
    path.join(ctx.home, 'npm-cache'),
  ];
}

function exec(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
  timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<RunResult> {
  return run(BINARY, args, {
    cwd,
    env: ctx.env,
    timeoutMs,
    redact: ctx.secrets,
    label,
  });
}

async function prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> {
  const { home, work } = await isolatedWorkDir(`npmc-npm-${label}`);
  await writeNpmrc(home, bindings);
  await fs.mkdir(path.join(home, 'npm-cache'), { recursive: true });
  return { home, work, env: sealedEnv(home), secrets: secretsOf(bindings), bindings };
}

async function pack(ctx: ClientCtx, dir: string): Promise<PackedTarball> {
  const destDir = await fs.mkdtemp(path.join(ctx.home, 'pack-'));
  const result = await exec(
    ctx,
    'npm-pack',
    ['pack', '--ignore-scripts', '--pack-destination', destDir, ...common(ctx)],
    dir,
    PACK_TIMEOUT_MS,
  );
  const file = (await fs.readdir(destDir))[0];
  if (result.exitCode !== 0 || !file) {
    throw new Error(`npm client: "npm pack" failed unexpectedly (exit ${result.exitCode})`);
  }
  return { file: path.join(destDir, file), bytes: await fs.readFile(path.join(destDir, file)) };
}

function publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult> {
  // `--force` bypasses npm 11's client-side "cannot publish over previous version" pre-flight
  // (`lib/commands/publish.js`), so a republish's request reaches the server (clients/npm.ts).
  const args = ['publish', opts.tarball.file, ...common(ctx), '--ignore-scripts'];
  if (opts.tag) {
    args.push('--tag', opts.tag);
  }
  if (opts.forceRepublish) {
    args.push('--force');
  }
  return exec(ctx, 'npm-publish', args, opts.dir);
}

function add(ctx: ClientCtx, specs: readonly string[], opts: AddOptions = {}): Promise<RunResult> {
  const args = ['install', ...specs, ...common(ctx), '--ignore-scripts', '--no-audit', '--no-fund'];
  if (!opts.save) {
    args.push('--no-save', '--no-package-lock');
  }
  return exec(ctx, 'npm-add', args);
}

function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
  return exec(ctx, opts.frozen ? 'npm-ci' : 'npm-install', [
    opts.frozen ? 'ci' : 'install',
    ...common(ctx),
    '--ignore-scripts',
    '--no-audit',
    '--no-fund',
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

export const npmClient: NpmFamilyClient = {
  id: 'npm',
  label: 'npm',
  tag: '@npm',
  binary: BINARY,
  caps: CAPS,
  lockfile: 'package-lock.json',

  prepare,
  pack,
  publish,
  add,
  install,
  readInstalledFile,

  distTag: {
    add: (ctx, spec, tag) =>
      exec(ctx, 'npm-dist-tag-add', ['dist-tag', 'add', spec, tag, ...common(ctx)]),
    list: (ctx, packageName) =>
      exec(ctx, 'npm-dist-tag-ls', ['dist-tag', 'ls', packageName, ...common(ctx)]),
    remove: (ctx, packageName, tag) =>
      exec(ctx, 'npm-dist-tag-rm', ['dist-tag', 'rm', packageName, tag, ...common(ctx)]),
  },
  deprecate: (ctx, spec, message) =>
    exec(ctx, 'npm-deprecate', ['deprecate', spec, message, ...common(ctx)]),
  view: (ctx, spec) => exec(ctx, 'npm-view', ['view', spec, '--json', ...common(ctx)]),
  whoami: (ctx) => exec(ctx, 'npm-whoami', ['whoami', ...common(ctx)]),
  logout: (ctx) => exec(ctx, 'npm-logout', ['logout', ...common(ctx)]),
  ping: (ctx) => exec(ctx, 'npm-ping', ['ping', ...common(ctx)]),
  search: (ctx, text) => exec(ctx, 'npm-search', ['search', text, '--json', ...common(ctx)]),
  audit: (ctx) => exec(ctx, 'npm-audit', ['audit', '--json', ...common(ctx)]),
};
