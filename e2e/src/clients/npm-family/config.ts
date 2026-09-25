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
 * Per-client configuration for the npm-family suite (RPS-1330): renders `.npmrc` (npm, pnpm, yarn
 * classic and bun all read it), yarn classic's `.yarnrc`, yarn berry's `.yarnrc.yml` and bun's
 * `bunfig.toml` from a list of `RegistryBinding`s, from the mustache templates under
 * `src/packages/npm-family/`; and the SEALED environment every client runs in.
 *
 * The seal: `HTTP_PROXY`/`HTTPS_PROXY` point at a dead loopback port (9, `discard`), with `NO_PROXY`
 * naming only the registry under test. Anything a client reaches for beyond it -- registry.npmjs.org,
 * repo.yarnpkg.com, a self-update check, telemetry -- fails at once with a proxy connection error
 * instead of silently succeeding against the internet (or hanging until a timeout). The host comes
 * from `new URL(env.repoBaseUrl).hostname`, so the seal holds for a remote target too. Yarn berry
 * ignores the proxy environment altogether (probed: with `HTTP_PROXY`/`NO_PROXY` alone it reached
 * registry.npmjs.org) and has no `NO_PROXY` either, so its `.yarnrc.yml` sets `httpProxy`/`httpsProxy`
 * to the dead port and re-enables a direct connection per registry host with `networkSettings`.
 * `tests/npm-clients/sealed-network.spec.ts` proves it.
 *
 * Every renderer uses triple mustache (no HTML escaping) and quotes YAML/TOML values, since a
 * credential is arbitrary text. A binding's credential never reaches a command line: it lives only
 * in a config file inside the invocation's isolated HOME.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../../env.js';
import { run, type RunOptions, type RunResult } from '../exec.js';
import type { MaterializedCredential } from '../../scenarios/world.js';
import type { RegistryBinding } from './client.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../../packages/npm-family');

/** The dead loopback port every sealed client's proxy points at (the `discard` service, never open). */
export const DEAD_PROXY_URL = 'http://127.0.0.1:9';

export async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.mkdir(path.dirname(destPath), { recursive: true });
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/** The hosts a sealed client may reach without the (dead) proxy: the registry under test only. */
export function noProxyHosts(): string[] {
  return [...new Set([new URL(env.repoBaseUrl).hostname, 'localhost', '127.0.0.1'])];
}

/**
 * The environment of every client invocation: an allow-list of what the process needs (never the
 * runner's own env, which carries the admin password), the invocation's isolated `HOME`, and the
 * proxy seal. `extra` adds a client's own variables (a cache directory, a telemetry opt-out, ...).
 */
export function sealedEnv(home: string, extra: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  const noProxy = noProxyHosts().join(',');
  const sealed: NodeJS.ProcessEnv = {
    PATH: process.env.PATH ?? '/usr/local/bin:/usr/bin:/bin',
    HOME: home,
    LANG: 'C.UTF-8',
    HTTP_PROXY: DEAD_PROXY_URL,
    HTTPS_PROXY: DEAD_PROXY_URL,
    http_proxy: DEAD_PROXY_URL,
    https_proxy: DEAD_PROXY_URL,
    NO_PROXY: noProxy,
    no_proxy: noProxy,
    ...extra,
  };
  if (process.env.TMPDIR) {
    sealed.TMPDIR = process.env.TMPDIR;
  }
  return sealed;
}

/**
 * `run()` for a command that gets exactly `opts.env` and nothing else: `extendEnv: false`, so the
 * runner's own variables (`REPSY_ADMIN_PASSWORD`, `FORCE_COLOR`, `YARN_VERSION`, `NPM_CLIENTS_*`, ...)
 * never reach the client, whatever the call site forgets (RPS-1364). Every npm-family invocation goes
 * through it, with an env built by `sealedEnv`.
 */
export function runSealed(
  command: string,
  args: readonly string[],
  opts: Omit<RunOptions, 'extendEnv'>,
): Promise<RunResult> {
  return run(command, args, { ...opts, extendEnv: false });
}

function baseUrlOf(binding: RegistryBinding): string {
  return binding.baseUrl ?? env.repoBaseUrl;
}

/** `<base>/<repo>/`, trailing slash on purpose (RPS-1206): npm's per-path scoping needs it. */
export function registryUrlOf(binding: RegistryBinding): string {
  return `${baseUrlOf(binding)}/${binding.repoName}/`;
}

function basicOf(credential: MaterializedCredential): string {
  return Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
}

function hasToken(credential: MaterializedCredential): boolean {
  return credential.transport === 'basic' && credential.kind === 'token';
}

function hasBasic(credential: MaterializedCredential): boolean {
  return credential.transport === 'basic' && credential.kind !== 'token';
}

function scopeName(binding: RegistryBinding): string | undefined {
  return binding.scope;
}

/** At most one binding may be the default (unscoped) registry, and a scope may appear once. */
function checkBindings(bindings: readonly RegistryBinding[]): void {
  const unscoped = bindings.filter((binding) => !scopeName(binding));
  if (unscoped.length > 1) {
    throw new Error(
      'npm-family config: at most one binding may be the default (unscoped) registry',
    );
  }
  const scopes = bindings.map(scopeName).filter((scope): scope is string => scope !== undefined);
  if (new Set(scopes).size !== scopes.length) {
    throw new Error('npm-family config: a scope may be bound to one repository only');
  }
}

/** The credentials of every binding, for redaction. */
export function secretsOf(bindings: readonly RegistryBinding[]): string[] {
  return bindings.flatMap((binding) =>
    binding.credential.password ? [binding.credential.password, basicOf(binding.credential)] : [],
  );
}

/**
 * `.npmrc`: `registry=` (or `@scope:registry=`) per binding, plus the path-scoped
 * `//<host:port>/<repo>/:_authToken=` (a deploy token, sent as `Bearer`) or `:_auth=` (a password,
 * sent as `Basic`) line. A binding with no credential renders no auth line. Written to
 * `<home>/.npmrc`, never into a package directory (it could be packed).
 */
export async function writeNpmrc(
  home: string,
  bindings: readonly RegistryBinding[],
): Promise<string> {
  checkBindings(bindings);
  const view = {
    bindings: bindings.map((binding) => ({
      registryKey: binding.scope ? `${binding.scope}:registry` : 'registry',
      registryUrl: registryUrlOf(binding),
      hostAndPort: new URL(baseUrlOf(binding)).host,
      repoName: binding.repoName,
      hasToken: hasToken(binding.credential),
      hasBasic: hasBasic(binding.credential),
      token: binding.credential.password ?? '',
      basicAuth: basicOf(binding.credential),
    })),
    alwaysAuth: bindings.some((binding) => binding.alwaysAuth),
  };
  const file = path.join(home, '.npmrc');
  await renderTemplate('npmrc.template', file, view);
  return file;
}

/** Yarn classic's `.yarnrc` (registries only; its auth is read from `.npmrc`), at `file`. */
export async function writeYarnClassicRc(
  file: string,
  bindings: readonly RegistryBinding[],
): Promise<string> {
  checkBindings(bindings);
  await renderTemplate('yarnrc.template', file, {
    bindings: bindings.map((binding) => ({
      registryKey: binding.scope ? `${binding.scope}:registry` : 'registry',
      registryUrl: registryUrlOf(binding),
    })),
  });
  return file;
}

export interface YarnBerryOptions {
  nodeLinker?: 'node-modules' | 'pnp';
  immutableInstalls?: boolean;
  hardenedMode?: boolean;
  /** `npmMinimalAgeGate` (berry quarantines a version younger than it; default here `0`: off). */
  minimalAgeGate?: string;
  cacheFolder: string;
}

/**
 * Yarn berry's `.yarnrc.yml` at `file` (it must sit in the project directory). The default binding
 * is `npmRegistryServer` + `npmAuthToken` (Bearer) or `npmAuthIdent` (Basic); a scoped one is an
 * `npmScopes` entry. `unsafeHttpWhitelist` names every plain-http host, since berry refuses http
 * for anything not listed; the dead proxy pair and the per-host `networkSettings` seal the network
 * (see this file's header).
 */
export async function writeYarnBerryRc(
  file: string,
  bindings: readonly RegistryBinding[],
  options: YarnBerryOptions,
): Promise<string> {
  checkBindings(bindings);
  const entry = (binding: RegistryBinding) => ({
    scopeName: (binding.scope ?? '').replace(/^@/, ''),
    registryUrl: registryUrlOf(binding),
    hasToken: hasToken(binding.credential),
    hasBasic: hasBasic(binding.credential),
    token: binding.credential.password ?? '',
    userAndPassword: `${binding.credential.username ?? ''}:${binding.credential.password ?? ''}`,
    alwaysAuth: Boolean(binding.alwaysAuth),
  });
  const defaultBinding = bindings.find((binding) => !binding.scope);
  const scoped = bindings.filter((binding) => binding.scope).map(entry);
  const hosts = new Set(noProxyHosts());
  for (const binding of bindings) {
    hosts.add(new URL(baseUrlOf(binding)).hostname);
  }
  await renderTemplate('yarnrc.yml.template', file, {
    nodeLinker: options.nodeLinker ?? 'node-modules',
    immutableInstalls: Boolean(options.immutableInstalls),
    hardenedMode: Boolean(options.hardenedMode),
    minimalAgeGate: options.minimalAgeGate ?? '0',
    cacheFolder: options.cacheFolder,
    proxyUrl: DEAD_PROXY_URL,
    registryHosts: [...hosts],
    defaultRegistry: defaultBinding ? entry(defaultBinding) : undefined,
    hasScopes: scoped.length > 0,
    scopes: scoped,
  });
  return file;
}

/** Bun's `bunfig.toml` at `file`: `[install] registry` (+ token or username/password) and `[install.scopes]`. */
export async function writeBunfig(
  file: string,
  bindings: readonly RegistryBinding[],
): Promise<string> {
  checkBindings(bindings);
  const entry = (binding: RegistryBinding) => ({
    scopeName: binding.scope ?? '',
    registryUrl: registryUrlOf(binding),
    hasToken: hasToken(binding.credential),
    hasBasic: hasBasic(binding.credential),
    token: binding.credential.password ?? '',
    username: binding.credential.username ?? '',
    password: binding.credential.password ?? '',
  });
  const defaultBinding = bindings.find((binding) => !binding.scope);
  const scoped = bindings.filter((binding) => binding.scope).map(entry);
  await renderTemplate('bunfig.toml.template', file, {
    defaultRegistry: defaultBinding ? entry(defaultBinding) : undefined,
    hasScopes: scoped.length > 0,
    scopes: scoped,
  });
  return file;
}
