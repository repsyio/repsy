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
 * uv (RPS-1486), the second PyPI client next to twine/pip (`pypi.ts`): `publish` and `seedPublish`
 * run the real `uv publish` against the same hand-built wheel (`pypi-raw.ts`'s `buildWheel`), and
 * `resolve` runs the real `uv lock` then `uv sync --locked` in an isolated project. Everything the
 * adapter does not change (package naming, the fingerprint of a refused publish, the round-trip
 * checks of the project page) is `pypiAdapter`'s own, spread below.
 *
 * What is uv's own, not twine's or pip's (probed live, see `README.md`'s "PyPI runner"):
 *
 *  - Credentials: `uv publish` reads `UV_PUBLISH_USERNAME`/`UV_PUBLISH_PASSWORD` (a `password`-kind
 *    credential) or `UV_PUBLISH_TOKEN` (a `token`-kind one: uv then sends the username `__token__`,
 *    which Repsy ignores, `PypiAuthComponent` tries the deploy token by its secret alone). The
 *    consume side does NOT put the credential in a URL (as pip's `PIP_INDEX_URL` does): the project
 *    names its index (`[[tool.uv.index]] name = "repsy"`) and uv's own `UV_INDEX_REPSY_USERNAME`/
 *    `UV_INDEX_REPSY_PASSWORD` carry it, so neither `pyproject.toml` nor `uv.lock` ever holds it.
 *  - An `anonymous` publish: uv, given no credential, first tries "trusted publishing" (an OIDC
 *    request to `https://<host>/_/oidc/audience`, three retries) before it gives up with `Missing
 *    credentials`. The adapter passes `--trusted-publishing never`, so an anonymous publish fails
 *    client-side at once, like twine's `--non-interactive` preflight, before any request.
 *  - `resolve` is `uv lock` + `uv sync --locked`, not `uv pip install`: the lock carries the sha256
 *    of the wheel as the index advertised it, and `uv sync` refuses a wheel whose bytes differ
 *    (`uvproject.spec.ts`-style tamper test in `tests/pypi/uv-client.spec.ts`), so a successful sync
 *    proves the bytes uv installed are the ones the lock names. `AdapterResult.contentSha256` is that
 *    hash, compared by the loop with the published wheel's.
 *
 * The environment is `clientEnv` (RPS-1446): a private `HOME`, uv's cache/config/data under it (a
 * netrc or a keyring of the runner is never read), no `uv`-managed Python download, the CPython the
 * runner image carries (`runners/pypi.Dockerfile`), and only the credential of the invocation.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { env } from '../env.js';
import { repoUrl } from '../repo-url.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  type BuiltWheel,
  buildWheel,
  distName,
  rawGetSimplePage,
  rawUpload,
  uploadUrl,
} from './pypi-raw.js';
import { type PypiFingerprint, pypiAdapter } from './pypi.js';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 180_000;

/** The CPython `runners/pypi.Dockerfile` copies in; uv never downloads its own. */
const RUNNER_PYTHON = '/usr/local/bin/python3.14';

/** The name of the index in the consumer project; `UV_INDEX_<NAME>_USERNAME/PASSWORD` carry its credential. */
export const UV_INDEX_NAME = 'repsy';

export type UvCredentialMode = 'publish' | 'index' | 'none';

/**
 * Every env var of one `uv` invocation. `mode` says where the credential goes: `publish` (the
 * `UV_PUBLISH_*` variables), `index` (the `UV_INDEX_REPSY_*` ones of the consumer project) or `none`.
 * `anonymous` never sets any. `extra` wins over everything.
 */
export function uvEnv(
  home: string,
  credential: MaterializedCredential,
  mode: UvCredentialMode,
  extra: NodeJS.ProcessEnv = {},
): NodeJS.ProcessEnv {
  const result = clientEnv(
    home,
    {
      XDG_CONFIG_HOME: path.join(home, '.config'),
      XDG_DATA_HOME: path.join(home, '.local', 'share'),
      XDG_CACHE_HOME: path.join(home, '.cache'),
      UV_CACHE_DIR: path.join(home, '.cache', 'uv'),
      UV_PYTHON: RUNNER_PYTHON,
      UV_PYTHON_DOWNLOADS: 'never',
      UV_LINK_MODE: 'copy',
      UV_NO_PROGRESS: '1',
      NO_COLOR: '1',
      PYTHONDONTWRITEBYTECODE: '1',
      PYTHONNOUSERSITE: '1',
    },
    [],
  );
  if (env.insecureRegistry) {
    // The counterpart of pip's PIP_TRUSTED_HOST: a plain-http remote target. `localhost` needs
    // nothing (probed live: uv sends Basic credentials to http://localhost without a flag).
    result.UV_INSECURE_HOST = new URL(env.repoBaseUrl).host;
  }
  if (credential.transport === 'basic') {
    if (mode === 'publish') {
      if (credential.kind === 'token') {
        result.UV_PUBLISH_TOKEN = credential.password ?? '';
      } else {
        result.UV_PUBLISH_USERNAME = credential.username ?? '';
        result.UV_PUBLISH_PASSWORD = credential.password ?? '';
      }
    } else if (mode === 'index') {
      const name = UV_INDEX_NAME.toUpperCase();
      result[`UV_INDEX_${name}_USERNAME`] = credential.username ?? '';
      result[`UV_INDEX_${name}_PASSWORD`] = credential.password ?? '';
    }
  }
  return { ...result, ...extra };
}

/** The simple-index URL of a repo, without a credential (`uv` gets that from its own variables). */
export function simpleIndexUrl(repoName: string): string {
  return repoUrl(repoName, 'simple/');
}

/** The `pyproject.toml` of the isolated consumer project: one pinned dependency, the repo as the
 *  only (default) index, no credential. */
export function consumerPyproject(repoName: string, packageName: string, version: string): string {
  return [
    '[project]',
    'name = "e2e-consumer"',
    'version = "0.0.0"',
    'requires-python = ">=3.9"',
    `dependencies = ["${packageName}==${version}"]`,
    '',
    '[[tool.uv.index]]',
    `name = "${UV_INDEX_NAME}"`,
    `url = "${simpleIndexUrl(repoName)}"`,
    'default = true',
    '',
  ].join('\n');
}

/** One `wheels = [ { url = ..., hash = "sha256:..." } ]` entry of a `uv.lock` package. */
export interface LockedFile {
  url: string;
  sha256: string;
}

/** The registry package block of `uv.lock` for `packageName`: its source and every locked file. */
export interface LockedPackage {
  name: string;
  version: string;
  source: string;
  files: LockedFile[];
}

/** A minimal reader of `uv.lock` (TOML, one `[[package]]` block per package): enough for the
 *  fields these tests assert, no TOML dependency. */
export function parseUvLock(text: string): LockedPackage[] {
  const packages: LockedPackage[] = [];
  for (const block of text.split(/^\[\[package\]\]$/m).slice(1)) {
    const name = /^name = "([^"]+)"/m.exec(block)?.[1];
    const version = /^version = "([^"]+)"/m.exec(block)?.[1];
    const source = /^source = \{ (?:registry|virtual|editable) = "([^"]*)" \}/m.exec(block)?.[1];
    if (!name || !version) {
      continue;
    }
    const files: LockedFile[] = [];
    for (const m of block.matchAll(/\{ url = "([^"]+)", hash = "sha256:([0-9a-f]{64})"/g)) {
      files.push({ url: m[1] ?? '', sha256: m[2] ?? '' });
    }
    packages.push({ name, version, source: source ?? '', files });
  }
  return packages;
}

interface UvPublishRun {
  exitCode: number;
  command: string;
  stdout: string;
  stderr: string;
  built: BuiltWheel;
}

/**
 * Builds a fresh wheel and runs the real `uv publish` with `world.credential` (credentials in uv's
 * own environment variables, never argv). `extraArgs` for the client-specific tests.
 */
export async function publishWithUv(
  world: World,
  label: string,
  extraArgs: readonly string[] = [],
): Promise<UvPublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;

  const built = buildWheel({ name: packageName, version, marker: randomUUID() });
  const distDir = path.join(work, 'dist');
  await fs.mkdir(distDir, { recursive: true });
  const wheelFile = path.join(distDir, built.filename);
  await fs.writeFile(wheelFile, built.bytes);

  const result = await run(
    'uv',
    [
      'publish',
      '--trusted-publishing',
      'never',
      '--publish-url',
      uploadUrl(world.repoName),
      ...extraArgs,
      wheelFile,
    ],
    {
      cwd: work,
      env: uvEnv(home, world.credential, 'publish'),
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: world.credential.password ? [world.credential.password] : [],
      label,
    },
  );

  return {
    exitCode: result.exitCode,
    command: result.command,
    stdout: result.stdout,
    stderr: result.stderr,
    built,
  };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithUv(world, `uv-publish-${world.scenario.id}`);

  // Byte-identical re-POST of the wheel uv just uploaded: the same companion probe as
  // `pypi.ts`'s `publish`, so the outcome is the raw HTTP status, not uv's exit code.
  const rawRes = await rawUpload(world.repoName, world.credential, published.built);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.built.sha256Hex,
  };
}

export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithUv(world, `uv-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `uv adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(uv exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'wheel actually existing.',
    );
  }
  return { contentSha256: published.built.sha256Hex };
}

/** The isolated consumer project of one resolve: `pyproject.toml` written, nothing else. */
export async function prepareConsumer(
  label: string,
  repoName: string,
  packageName: string,
  version: string,
): Promise<{ home: string; project: string }> {
  const { home, work } = await isolatedWorkDir(label);
  const project = path.join(work, 'consumer');
  await fs.mkdir(project, { recursive: true });
  await fs.writeFile(
    path.join(project, 'pyproject.toml'),
    consumerPyproject(repoName, packageName, version),
  );
  return { home, project };
}

/**
 * One `uv` invocation for a client-specific test: `credential` goes where `mode` says (`uvEnv`),
 * the secret is redacted from the logged command line and the attachments.
 */
export async function runUv(
  label: string,
  args: readonly string[],
  opts: {
    home: string;
    cwd: string;
    credential: MaterializedCredential;
    mode: UvCredentialMode;
    extraEnv?: NodeJS.ProcessEnv;
    timeoutMs?: number;
  },
): ReturnType<typeof run> {
  return run('uv', args, {
    cwd: opts.cwd,
    env: uvEnv(opts.home, opts.credential, opts.mode, opts.extraEnv),
    timeoutMs: opts.timeoutMs ?? CONSUME_TIMEOUT_MS,
    // The password as given and as a URL carries it (`--index-url user:pass@host`, percent-encoded).
    redact: opts.credential.password
      ? [opts.credential.password, encodeURIComponent(opts.credential.password)]
      : [],
    label,
  });
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { packageName, version } = world.consumeTarget;
  const { home, project } = await prepareConsumer(
    `uv-con-${world.scenario.id}`,
    world.repoName,
    packageName,
    version,
  );
  const secrets = world.credential.password ? [world.credential.password] : [];
  const runUv = (args: string[], label: string) =>
    run('uv', args, {
      cwd: project,
      env: uvEnv(home, world.credential, 'index'),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `uv-consume-${label}-${world.scenario.id}`,
    });

  let execResult = await runUv(['lock'], 'lock');
  if (execResult.exitCode === 0) {
    execResult = await runUv(['sync', '--locked'], 'sync');
  }

  // The auth-only companion probe, as `pypi.ts`'s `resolve`: a project-page GET.
  const rawRes = await rawGetSimplePage(world.repoName, world.credential, packageName);

  let contentSha256: string | undefined;
  if (execResult.exitCode === 0) {
    const lock = await fs.readFile(path.join(project, 'uv.lock'), 'utf8');
    const locked = parseUvLock(lock).find((p) => p.name === packageName);
    contentSha256 = locked?.files[0]?.sha256;
  }

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256,
    resolvedFile: contentSha256
      ? `${distName(packageName)}-${version}-py3-none-any.whl`
      : undefined,
  };
}

export const uvAdapter: ProtocolAdapter<PypiFingerprint> = {
  ...pypiAdapter,
  label: 'uv',
  tags: ['@uv'],
  client: { name: 'uv', publishVerb: 'publish', consumeVerb: 'lock/sync' },
  publish,
  resolve,
  seedPublish,
};
