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
 * Yarn berry (4.x, `@yarnpkg/cli-dist`, no corepack) as an `NpmFamilyClient` (RPS-1330), by its
 * absolute path in the sealed environment of `config.ts`.
 *
 * What is different about berry, all probed live and asserted where it matters:
 *  - It reads its settings from `.yarnrc.yml`, not `.npmrc` (`writeYarnBerryRc`), and ignores the
 *    proxy environment variables, so the `.yarnrc.yml` carries the network seal too.
 *  - It picks the PROJECT ROOT by the nearest `yarn.lock` walking up from the working directory
 *    (otherwise the top-most `package.json`), so `prepare` puts an empty `yarn.lock` in `work` and
 *    each package to publish is its own project (`pack`/`publish` write one into the package
 *    directory): a stray `package.json` in a parent directory can never hijack a command.
 *  - It has no `npm publish <tarball>`: `yarn npm publish` packs the project itself, and only after
 *    the project has an install state (`yarn install`), so `publish` installs first. `pack` uses
 *    `yarn pack`, whose tarball is deterministic, so it is the tarball `publish` uploads.
 *  - It sends no credential for an unscoped read unless `npmAlwaysAuth` is set, so a binding is
 *    always-auth unless it says `alwaysAuth: false` (the real-world config).
 *  - It has no `add --no-save`: `add` writes `package.json` and `yarn.lock` into the throw-away
 *    `work` directory, which is harmless.
 *  - No `deprecate`, `ping` or `search` command exists (`caps` false, so no cell is registered).
 *
 * `createYarnBerryClient` takes the `.yarnrc.yml` variations the berry-only tests need (the PnP
 * linker, hardened mode); `yarnBerryClient` is the node-modules default the catalog loop and the
 * matrix run.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

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
import {
  secretsOf,
  runSealed,
  sealedEnv,
  writeYarnBerryRc,
  type YarnBerryOptions,
} from './config.js';

const BINARY = CLIENT_BINARIES['yarn-berry'];

const COMMAND_TIMEOUT_MS = 120_000;
const PACK_TIMEOUT_MS = 60_000;

const CAPS: Capabilities = {
  publish: true,
  distTagCmd: true,
  deprecateCmd: false,
  viewCmd: true,
  whoamiCmd: true,
  pingCmd: false,
  searchCmd: false,
  auditCmd: true,
  frozenInstall: true,
};

export interface YarnBerryClientOptions {
  /** The `.yarnrc.yml` linker: `node-modules` (the loop's default) or `pnp`. */
  nodeLinker?: 'node-modules' | 'pnp';
  /** `enableHardenedMode`: berry re-queries the registry to validate every lockfile resolution. */
  hardenedMode?: boolean;
  /** `npmMinimalAgeGate` (berry quarantines a version younger than it; `0`, off, by default here). */
  minimalAgeGate?: string;
  label?: string;
}

export const YARN_LOCK = 'yarn.lock';
export const YARN_RC = '.yarnrc.yml';

/** Runs `yarn <args>` in `cwd` (default `ctx.work`) in the invocation's sealed environment. */
export function execYarn(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
  timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<RunResult> {
  return runSealed(BINARY, args, {
    cwd,
    env: ctx.env,
    timeoutMs,
    redact: ctx.secrets,
    label,
  });
}

/** A directory becomes a berry project of its own: its own (empty) lockfile, kept if it has one. */
async function makeProject(dir: string): Promise<void> {
  await fs.mkdir(dir, { recursive: true });
  await fs.writeFile(path.join(dir, YARN_LOCK), '', { flag: 'wx' }).catch(() => undefined);
}

export function createYarnBerryClient(options: YarnBerryClientOptions = {}): NpmFamilyClient {
  const rcOptions = (home: string): YarnBerryOptions => ({
    nodeLinker: options.nodeLinker ?? 'node-modules',
    hardenedMode: options.hardenedMode ?? false,
    minimalAgeGate: options.minimalAgeGate,
    cacheFolder: path.join(home, 'yarn-cache'),
  });

  async function prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> {
    const { home, work } = await isolatedWorkDir(`npmc-yarn4-${label}`);
    const alwaysAuth = bindings.map((binding) => ({
      ...binding,
      // An anonymous binding has nothing to send, and berry then fails with YN0033 instead of reading.
      alwaysAuth: binding.alwaysAuth ?? binding.credential.transport === 'basic',
    }));
    await writeYarnBerryRc(path.join(work, YARN_RC), alwaysAuth, rcOptions(home));
    await makeProject(work);
    await fs.writeFile(
      path.join(work, 'package.json'),
      '{"name":"e2e-yarn-berry-project","version":"0.0.0","private":true}\n',
    );
    return {
      home,
      work,
      env: sealedEnv(home, { YARN_IGNORE_PATH: '1' }),
      secrets: secretsOf(bindings),
      bindings,
    };
  }

  async function pack(ctx: ClientCtx, dir: string): Promise<PackedTarball> {
    await makeProject(dir);
    const destDir = await fs.mkdtemp(path.join(ctx.home, 'pack-'));
    const file = path.join(destDir, 'package.tgz');
    const result = await execYarn(ctx, 'yarn4-pack', ['pack', '--out', file], dir, PACK_TIMEOUT_MS);
    if (result.exitCode !== 0) {
      throw new Error(
        `yarn berry client: "yarn pack" failed unexpectedly (exit ${result.exitCode}): ${result.stderr}`,
      );
    }
    return { file, bytes: await fs.readFile(file) };
  }

  async function publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult> {
    // `yarn npm publish` needs the project's install state, and packs the project itself.
    await makeProject(opts.dir);
    const installed = await execYarn(ctx, 'yarn4-publish-install', ['install'], opts.dir);
    if (installed.exitCode !== 0) {
      return installed;
    }
    const args = ['npm', 'publish'];
    if (opts.tag) {
      args.push('--tag', opts.tag);
    }
    return execYarn(ctx, 'yarn4-publish', args, opts.dir);
  }

  function add(
    ctx: ClientCtx,
    specs: readonly string[],
    _opts: AddOptions = {},
  ): Promise<RunResult> {
    return execYarn(ctx, 'yarn4-add', ['add', ...specs]);
  }

  function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
    return execYarn(ctx, opts.frozen ? 'yarn4-install-immutable' : 'yarn4-install', [
      'install',
      ...(opts.frozen ? ['--immutable'] : []),
    ]);
  }

  async function readInstalledFile(
    ctx: ClientCtx,
    packageName: string,
    file: string = MARKER_FILENAME,
  ): Promise<string | undefined> {
    if (options.nodeLinker === 'pnp') {
      // Read through Plug'n'Play's own resolution, not a `node_modules` directory that is not there.
      const read = await execYarn(ctx, 'yarn4-pnp-read', [
        'node',
        '-p',
        `require('fs').readFileSync(require.resolve('${packageName}/${file}'),'utf8')`,
      ]);
      return read.exitCode === 0 ? read.stdout : undefined;
    }
    try {
      return await fs.readFile(
        path.join(ctx.work, 'node_modules', ...packageName.split('/'), file),
        'utf8',
      );
    } catch {
      return undefined;
    }
  }

  return {
    id: 'yarn-berry',
    label: options.label ?? 'yarn-berry',
    tag: '@yarn-berry',
    binary: BINARY,
    caps: CAPS,
    lockfile: YARN_LOCK,

    prepare,
    pack,
    publish,
    add,
    install,
    readInstalledFile,

    distTag: {
      add: (ctx, spec, tag) => execYarn(ctx, 'yarn4-tag-add', ['npm', 'tag', 'add', spec, tag]),
      list: (ctx, packageName) =>
        execYarn(ctx, 'yarn4-tag-list', ['npm', 'tag', 'list', packageName]),
      remove: (ctx, packageName, tag) =>
        execYarn(ctx, 'yarn4-tag-remove', ['npm', 'tag', 'remove', packageName, tag]),
    },
    view: (ctx, spec) => execYarn(ctx, 'yarn4-info', ['npm', 'info', spec, '--json']),
    whoami: (ctx) => execYarn(ctx, 'yarn4-whoami', ['npm', 'whoami']),
    // Without `--json`: berry's JSON report prints nothing at all for a clean tree.
    audit: (ctx) => execYarn(ctx, 'yarn4-audit', ['npm', 'audit']),
  };
}

export const yarnBerryClient: NpmFamilyClient = createYarnBerryClient();
