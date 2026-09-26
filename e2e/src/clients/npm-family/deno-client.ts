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
 * Deno 2 (a native binary copied from `denoland/deno:bin-<version>`) as a CONSUME-ONLY
 * `NpmFamilyClient` (RPS-1486): `deno install npm:<pkg>@<version>` (or a `package.json` dependency)
 * against a Repsy npm repository, called by its absolute path in the sealed environment of
 * `config.ts`. Deno has no npm `publish` (`pack`/`publish` throw), so every package a Deno cell
 * consumes is published by `npmClient`; that is why it is in `ENABLED_CLIENTS` (the sealed-env spec
 * and the version spec use it) with every capability off: the matrix specs publish with the client
 * under test, so a Deno column there would call a `publish` that does not exist. Its own cells are
 * `tests/npm-clients/deno/`.
 *
 * Configuration, probed (deno 2.9.7):
 *  - `$HOME/.npmrc` is read as it is (`registry=`, `@scope:registry=`, the path-scoped `:_authToken=`
 *    sent as `Bearer`, `:_auth=` and `:username=`/`:_password=` sent as `Basic`), so `writeNpmrc` is
 *    all the configuration there is, exactly like npm and yarn classic. A deploy token works both as
 *    `_authToken` (Bearer) and as `_auth` of `<username>:<token>` (Basic), like every other client.
 *  - `DENO_AUTH_TOKENS` is NOT a way to authenticate to an npm registry: Deno sends the token on its
 *    first packument request only, and asks the next ones with no `Authorization` at all (a 401 for a
 *    private repository, `tests/npm-clients/deno/config.spec.ts` pins it). Cells configure `.npmrc`.
 *  - A `minimumDependencyAge` gate (24 h by default) refuses a version published less than a day ago
 *    and makes Deno ask for the FULL packument (`Accept: *`+`/*`, to read `time`) instead of the
 *    abbreviated `application/vnd.npm.install-v1+json` one. Every test publishes seconds before it
 *    consumes, so `add`/`install` pass `--minimum-dependency-age=0` (the gate off, the abbreviated
 *    packument); `denoExec` is there for the cells that want the default.
 *  - `deno install` with a `package.json` in the directory writes `deno.lock` (lockfile v5, integrity
 *    and the tarball URL of every package) and a symlinked `node_modules/`; `--frozen` refuses to
 *    change the lockfile.
 *
 * The environment is `sealedEnv` plus `DENO_DIR` (the cache) inside the invocation's HOME, so two
 * workers never share one, and `DENO_NO_UPDATE_CHECK`/`NO_COLOR` to keep the output plain.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { isolatedWorkDir, type RunResult } from '../exec.js';
import { MARKER_FILENAME } from '../npm.js';
import {
  CLIENT_BINARIES,
  type Capabilities,
  type ClientCtx,
  type NpmFamilyClient,
  type PackedTarball,
  type PublishOptions,
  type RegistryBinding,
} from './client.js';
import { runSealed, secretsOf, sealedEnv, writeNpmrc } from './config.js';

const BINARY = CLIENT_BINARIES.deno;

const COMMAND_TIMEOUT_MS = 120_000;

/** Switches the `minimumDependencyAge` gate off: see the header. */
export const NO_AGE_GATE = '--minimum-dependency-age=0';

const CAPS: Capabilities = {
  publish: false,
  distTagCmd: false,
  deprecateCmd: false,
  viewCmd: false,
  whoamiCmd: false,
  pingCmd: false,
  searchCmd: false,
  auditCmd: false,
  // Deno has a frozen install, but the matrix's cells publish with the client under test.
  frozenInstall: false,
};

export function denoExec(
  ctx: ClientCtx,
  label: string,
  args: readonly string[],
  cwd: string = ctx.work,
): Promise<RunResult> {
  return runSealed(BINARY, args, {
    cwd,
    env: ctx.env,
    timeoutMs: COMMAND_TIMEOUT_MS,
    redact: ctx.secrets,
    label,
  });
}

async function prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx> {
  const { home, work } = await isolatedWorkDir(`npmc-deno-${label}`);
  await writeNpmrc(home, bindings);
  const cache = path.join(home, 'deno-cache');
  await fs.mkdir(cache, { recursive: true });
  return {
    home,
    work,
    env: sealedEnv(home, { DENO_DIR: cache, DENO_NO_UPDATE_CHECK: '1', NO_COLOR: '1' }),
    secrets: secretsOf(bindings),
    bindings,
  };
}

function consumeOnly(what: string): never {
  throw new Error(`deno client: ${what} does not exist, Deno only consumes npm packages`);
}

async function pack(): Promise<PackedTarball> {
  return consumeOnly('pack');
}

async function publish(_ctx: ClientCtx, _opts: PublishOptions): Promise<RunResult> {
  return consumeOnly('publish');
}

/** `deno install npm:<name>@<version>` for each spec, into the `package.json` project of `ctx.work`. */
function add(ctx: ClientCtx, specs: readonly string[]): Promise<RunResult> {
  return denoExec(ctx, 'deno-install-add', [
    'install',
    NO_AGE_GATE,
    ...specs.map((spec) => `npm:${spec}`),
  ]);
}

function install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult> {
  return denoExec(ctx, opts.frozen ? 'deno-install-frozen' : 'deno-install', [
    'install',
    NO_AGE_GATE,
    ...(opts.frozen ? ['--frozen'] : []),
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

export const denoClient: NpmFamilyClient = {
  id: 'deno',
  label: 'deno',
  tag: '@deno',
  binary: BINARY,
  caps: CAPS,
  lockfile: 'deno.lock',

  prepare,
  pack,
  publish,
  add,
  install,
  readInstalledFile,
};
