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
 * `NpmFamilyClient` (RPS-1330): what the npm-family cross-client suite (`tests/npm-clients`) needs
 * from one package manager that talks to a Repsy npm repository -- npm, pnpm, yarn classic, yarn
 * berry and bun -- so a matrix spec is written once and runs against every client that has the
 * capability it needs. It is the npm-family analogue of `scenarios/adapter.ts`'s `ProtocolAdapter`,
 * one level below it: `npm-family/adapter.ts` turns any `NpmFamilyClient` into a `ProtocolAdapter`
 * so the shared scenario catalog (`scenarios/loop.ts`) also runs per client.
 *
 * Every client is called by ABSOLUTE path (`CLIENT_BINARIES`, installed by
 * `runners/npm-clients.Dockerfile` under /opt/clients), never through PATH, inside a sealed
 * environment (`config.ts`'s `sealedEnv`): a client can only reach the registry under test.
 * `prepare` gives each invocation its own isolated HOME, work directory, cache and store, so two
 * parallel workers never share state, and every command runs with that context.
 */
import type { RunResult } from '../exec.js';
import type { Outcome, Scenario } from '../../scenarios/types.js';
import type { MaterializedCredential } from '../../scenarios/world.js';

export type ClientId = 'npm' | 'pnpm' | 'yarn-classic' | 'yarn-berry' | 'bun';

/** Absolute path of each client's binary in the runner image (`runners/npm-clients.Dockerfile`). */
export const CLIENT_BINARIES: Record<ClientId, string> = {
  npm: '/usr/local/bin/npm',
  pnpm: '/opt/clients/pnpm/bin/pnpm',
  'yarn-classic': '/opt/clients/yarn1/bin/yarn',
  'yarn-berry': '/opt/clients/yarn4/bin/yarn',
  bun: '/opt/clients/bun/bin/bun',
};

/**
 * The environment variable of the runner image that pins each client's exact version (the compose
 * build args, exported as `ENV` by the Dockerfile). `npm` has none: it is whatever
 * `node:24-bookworm-slim` ships, printed in the image's build log and asserted by major only.
 */
export const CLIENT_VERSION_ENV: Partial<Record<ClientId, string>> = {
  pnpm: 'NPM_CLIENTS_PNPM_VERSION',
  'yarn-classic': 'NPM_CLIENTS_YARN_CLASSIC_VERSION',
  'yarn-berry': 'NPM_CLIENTS_YARN_BERRY_VERSION',
  bun: 'NPM_CLIENTS_BUN_VERSION',
};

/** What a client can do, so a matrix spec registers a cell only for a client that supports it. */
export interface Capabilities {
  /** `publish` of a packed tarball. */
  publish: boolean;
  distTagCmd: boolean;
  deprecateCmd: boolean;
  viewCmd: boolean;
  whoamiCmd: boolean;
  pingCmd: boolean;
  searchCmd: boolean;
  auditCmd: boolean;
  /** A lockfile written by `add`/`install` and a frozen (`npm ci`-like) install from it. */
  frozenInstall: boolean;
}

/** One Repsy repository a client is configured for, optionally for one scope only. */
export interface RegistryBinding {
  repoName: string;
  /** `@scope`: only that scope resolves from this repository; left out, it is the default registry. */
  scope?: string;
  credential: MaterializedCredential;
  /** Send credentials for an unscoped GET too (yarn classic / berry need it for a private repo). */
  alwaysAuth?: boolean;
  /** The address the client uses; defaults to `env.repoBaseUrl`. Another host (a recorder, or
   *  `127.0.0.1` for `localhost`) makes the client reach the same repository under another name. */
  baseUrl?: string;
}

/** One isolated invocation context: its own HOME, work directory, cache/store and sealed env. */
export interface ClientCtx {
  readonly home: string;
  readonly work: string;
  readonly env: NodeJS.ProcessEnv;
  /** Credentials to redact from any logged command line or attached output. */
  readonly secrets: readonly string[];
  readonly bindings: readonly RegistryBinding[];
}

export interface PackedTarball {
  file: string;
  bytes: Buffer;
}

export interface PublishOptions {
  /** The directory the package was rendered and packed in (a client that publishes a project). */
  dir: string;
  /** The tarball `pack` produced. */
  tarball: PackedTarball;
  tag?: string;
  /** Publish over an existing version (bypasses a client's own client-side refusal). */
  forceRepublish?: boolean;
}

export interface AddOptions {
  /** Write the dependency into package.json and a lockfile (default: neither, like the catalog). */
  save?: boolean;
}

export interface NpmFamilyClient {
  readonly id: ClientId;
  /** The name a test title / describe block carries: `npm`, `pnpm`, `yarn-classic`, ... */
  readonly label: string;
  /** `@npm`, `@pnpm`, `@yarn-classic`, `@yarn-berry`, `@bun`: `--grep` selects one client. */
  readonly tag: `@${string}`;
  readonly binary: string;
  readonly caps: Capabilities;
  /** The lockfile the client writes, relative to its work directory. */
  readonly lockfile?: string;
  /**
   * A client whose exit code does not follow the registry's answer: a reason (never empty) for an
   * `outcome` the client reports as SUCCESS (exit 0) although the registry refused it, `undefined`
   * where it fails as it should. Yarn classic exits 0 for a publish refused with 401. The catalog loop
   * then pins the quirk instead of asserting the usual "a refused request fails the client".
   */
  exitQuirk?(scenario: Scenario, side: 'publish' | 'consume', outcome: Outcome): string | undefined;

  /** An isolated context (HOME, work directory, config, cache) configured for `bindings`. */
  prepare(label: string, bindings: readonly RegistryBinding[]): Promise<ClientCtx>;

  /** Packs the package rendered in `dir` and returns the tarball's file and bytes. */
  pack(ctx: ClientCtx, dir: string): Promise<PackedTarball>;
  publish(ctx: ClientCtx, opts: PublishOptions): Promise<RunResult>;
  /** Adds `specs` (`name@version`) to the project in `ctx.work`. */
  add(ctx: ClientCtx, specs: readonly string[], opts?: AddOptions): Promise<RunResult>;
  /** Installs the project in `ctx.work`; `frozen` refuses to change the lockfile. */
  install(ctx: ClientCtx, opts: { frozen: boolean }): Promise<RunResult>;
  /** The content of `<package>/<file>` as installed under `ctx.work`, or `undefined`. */
  readInstalledFile(ctx: ClientCtx, packageName: string, file: string): Promise<string | undefined>;

  distTag?: {
    add(ctx: ClientCtx, spec: string, tag: string): Promise<RunResult>;
    list(ctx: ClientCtx, packageName: string): Promise<RunResult>;
    remove(ctx: ClientCtx, packageName: string, tag: string): Promise<RunResult>;
  };
  deprecate?(ctx: ClientCtx, spec: string, message: string): Promise<RunResult>;
  view?(ctx: ClientCtx, spec: string): Promise<RunResult>;
  whoami?(ctx: ClientCtx): Promise<RunResult>;
  ping?(ctx: ClientCtx): Promise<RunResult>;
  search?(ctx: ClientCtx, text: string): Promise<RunResult>;
  audit?(ctx: ClientCtx): Promise<RunResult>;
}
