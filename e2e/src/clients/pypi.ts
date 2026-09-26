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
 * The PyPI (Python package index) client adapter (step 4c, RPS-294): `publish`/`resolve`/
 * `seedPublish` hand-build a tiny, dependency-free wheel (`pypi-raw.ts`'s `buildWheel`, `fflate` --
 * deliberately never `python -m build`/`setuptools`/`wheel`, see that file's header) and run the real
 * `twine`/`pip` binaries against it, the PyPI analogue of `clients/nuget.ts`.
 *
 * Like nuget (a REAL, toggleable override rule, confirmed live) and unlike cargo (no override rule
 * at all): `publish`'s raw-HTTP companion probe (`rawUpload`, the exact same wheel bytes `twine
 * upload` was just given, via the SAME `built.sha256Hex`) is a byte-identical re-POST of the exact
 * coordinate just attempted -- the maven/npm/nuget pattern, not cargo's prerelease-sibling
 * workaround: every `ok`-expected scenario runs under `allowOverride: true` (the fixture default), so
 * the re-POST is an accepted, identical replacement; `no-override` gets the same `403
 * fileAlreadyExists` the client got (confirmed live). Building the wheel is pure JS (no toolchain
 * invocation), so `built` bytes exist even for a scenario whose own `twine upload` never reaches the
 * wire at all (an anonymous credential fails `twine`'s own `--non-interactive` preflight
 * client-side, `NonInteractive: Credential not found for username.`, exit 1, confirmed live -- see
 * `README.md`'s "H4").
 *
 * `resolve`'s `Outcome` is derived from a raw project-page GET instead (mirrors nuget's
 * flat-version-list-GET / cargo's sparse-index-GET reasoning): every consume expectation in the
 * catalog is an authn/authz outcome, and a project-page GET never touches the (possibly P3/P4/P5
 * affected) archive bytes themselves.
 *
 * Every publish/seed-publish packs a fresh random marker file (`<distName>/e2e_marker.txt`) into the
 * wheel, so two publishes of one coordinate never share content; `AdapterResult.contentSha256` is the
 * sha256 of the WHOLE `.whl` file (not just the marker): `pip download` never unpacks the archive
 * into the consumer's own tree, it saves the original file verbatim under `--dest`, so comparing
 * whole-file digests is what a downloaded, still-packed wheel can actually be checked against
 * (confirmed live/H3: twine streams the exact bytes given it, never re-encoding/re-compressing).
 *
 * Credential mapping (both `twine`/`pip` share the plain "username + secret" shape every credential
 * kind in this harness carries): `TWINE_USERNAME`/`TWINE_PASSWORD` and the `PIP_INDEX_URL`'s
 * URL-embedded Basic credentials are both fed the SAME `credential.username`/`credential.password`
 * (a `token`-kind credential's username is ignored server-side either way -- `PypiAuthComponent
 * .handleBasicAuthWithToken` tries the deploy token by PASSWORD alone first). `anonymous` leaves
 * `TWINE_USERNAME`/`TWINE_PASSWORD` UNSET entirely (never set to empty strings), matching a real
 * invocation with no credentials configured at all, and `PIP_INDEX_URL` carries no credentials
 * either.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { expect } from '@playwright/test';

import { env } from '../env.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  adminCredential,
  type BuiltWheel,
  buildWheel,
  downloadPath,
  indexUrlFor,
  packageName as rawPackageName,
  parseSimplePage,
  rawDownload,
  rawGetSimplePage,
  rawUpload,
  sha256Hex,
  uploadUrl,
  wheelFilename,
} from './pypi-raw.js';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

/** Every env var isolating one `twine` invocation from the host and from every other invocation
 *  (parallel Playwright workers, or publish vs. consume in the same test): a private `HOME`, no
 *  system keyring probing (twine imports `keyring` unconditionally), and credentials fed ONLY via
 *  `TWINE_USERNAME`/`TWINE_PASSWORD` -- `--repository-url` makes twine skip `.pypirc` entirely, so
 *  there is no config file to isolate. `anonymous` leaves both unset (this file's header). */
export function twineEnv(home: string, credential: MaterializedCredential): NodeJS.ProcessEnv {
  const result = clientEnv(home, {
    TWINE_NON_INTERACTIVE: '1',
    PYTHON_KEYRING_BACKEND: 'keyring.backends.null.Keyring',
    PYTHONDONTWRITEBYTECODE: '1',
    PYTHONNOUSERSITE: '1',
  });
  if (credential.transport === 'basic') {
    result.TWINE_USERNAME = credential.username ?? '';
    result.TWINE_PASSWORD = credential.password ?? '';
  }
  return result;
}

/** Every env var isolating one `pip` invocation: `PIP_CONFIG_FILE=/dev/null` so no config file
 *  anywhere is ever read, `PIP_INDEX_URL` carrying the credential (`indexUrlFor`, `pypi-raw.ts`), and
 *  a private cache/config dir under `home`. `PIP_TRUSTED_HOST` is set only when
 *  `env.insecureRegistry` (a remote plain-http target, mirroring docker/helm's
 *  `REPSY_E2E_INSECURE_REGISTRY` handling) -- `localhost`/`127.0.0.0/8` are already in pip's own
 *  `SECURE_ORIGINS`, so nothing extra is needed for the local/ci stack. */
export function pipEnv(
  home: string,
  credential: MaterializedCredential,
  repoName: string,
): NodeJS.ProcessEnv {
  const result = clientEnv(home, {
    XDG_CONFIG_HOME: path.join(home, '.config'),
    XDG_CACHE_HOME: path.join(home, '.cache'),
    PIP_CONFIG_FILE: '/dev/null',
    PIP_INDEX_URL: indexUrlFor(repoName, credential),
    PIP_DISABLE_PIP_VERSION_CHECK: '1',
    PIP_NO_INPUT: '1',
    PIP_PROGRESS_BAR: 'off',
    PIP_NO_COLOR: '1',
    PYTHONDONTWRITEBYTECODE: '1',
    PYTHONNOUSERSITE: '1',
  });
  if (env.insecureRegistry) {
    result.PIP_TRUSTED_HOST = new URL(env.repoBaseUrl).host;
  }
  return result;
}

interface PublishRun {
  exitCode: number;
  command: string;
  built: BuiltWheel;
}

/**
 * Builds a fresh wheel (`buildWheel`) and runs the real `python3 -m twine upload` with
 * `world.credential`. Always `python3 -m twine`/`python3 -m pip`, never the console scripts (this
 * file's header / the plan's constraint).
 */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;

  const built = buildWheel({ name: packageName, version, marker: randomUUID() });
  const distDir = path.join(work, 'dist');
  await fs.mkdir(distDir, { recursive: true });
  const wheelFile = path.join(distDir, built.filename);
  await fs.writeFile(wheelFile, built.bytes);

  const secrets = world.credential.password ? [world.credential.password] : [];
  const args = [
    '-m',
    'twine',
    'upload',
    '--non-interactive',
    '--disable-progress-bar',
    '--repository-url',
    uploadUrl(world.repoName),
    wheelFile,
  ];

  const execResult = await run('python3', args, {
    cwd: work,
    env: twineEnv(home, world.credential),
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  return { exitCode: execResult.exitCode, command: execResult.command, built };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `pypi-publish-${world.scenario.id}`);

  // Byte-identical re-POST of the exact wheel the client just uploaded (see this file's header): a
  // real override rule, unlike cargo, so this is the maven/npm/nuget re-PUT/re-POST pattern.
  const rawRes = await rawUpload(world.repoName, world.credential, published.built);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.built.sha256Hex,
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
 * coordinate (`reuseCoordinates`). Only the real client runs, mirroring `clients/nuget.ts`'s
 * `seedPublish`.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `pypi-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `pypi adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(twine exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'wheel actually existing.',
    );
  }
  return { contentSha256: published.built.sha256Hex };
}

/** The wheel file `pip download` leaves under `--dest`, with its original filename (this file's
 *  header: pip never unpacks it). */
async function findDownloadedWheel(
  destDir: string,
  packageName: string,
  version: string,
): Promise<{ file: string; bytes: Buffer } | undefined> {
  const filename = wheelFilename(packageName, version);
  try {
    const bytes = await fs.readFile(path.join(destDir, filename));
    return { file: path.join('downloads', filename), bytes };
  } catch {
    return undefined;
  }
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`pypi-con-${world.scenario.id}`);
  const { packageName, version } = world.consumeTarget;

  const destDir = path.join(work, 'downloads');
  await fs.mkdir(destDir, { recursive: true });

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'python3',
    [
      '-m',
      'pip',
      'download',
      '--no-deps',
      '--only-binary=:all:',
      '--no-cache-dir',
      '--dest',
      destDir,
      `${packageName}==${version}`,
    ],
    {
      cwd: work,
      env: pipEnv(home, world.credential, world.repoName),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `pypi-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe (see this file's header): a project-page GET never touches the
  // (possibly P3/P4/P5 affected) archive bytes.
  const rawRes = await rawGetSimplePage(world.repoName, world.credential, packageName);
  const resolved = await findDownloadedWheel(destDir, packageName, version);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved ? sha256Hex(resolved.bytes) : undefined,
    resolvedFile: resolved?.file,
  };
}

/** A project-page body hash plus every listed file's stored content hash, for
 *  `ProtocolAdapter.fingerprint`/`expectNothingStored`. Scoped to the one package a scenario's
 *  publish targets, exactly like `NuGetFingerprint`/`CargoFingerprint`. */
export interface PypiFingerprint {
  indexSha256?: string;
  fileSha256: Record<string, string>;
}

async function fingerprint(world: World): Promise<PypiFingerprint> {
  const admin = adminCredential();
  const packageName = world.publishTarget.packageName;

  const indexRes = await rawGetSimplePage(world.repoName, admin, packageName);
  if (indexRes.status === 404) {
    return { fileSha256: {} };
  }
  if (indexRes.status !== 200) {
    throw new Error(
      `pypi adapter: fingerprint(): GET project page of "${packageName}" answered ${indexRes.status}`,
    );
  }

  const indexSha256 = sha256Hex(indexRes.body);
  const links = parseSimplePage(indexRes.body);
  const fileSha256: Record<string, string> = {};

  for (const link of links) {
    const dlRes = await rawDownload(world.repoName, admin, packageName, link.filename);
    fileSha256[link.filename] =
      dlRes.status === 200 ? sha256Hex(dlRes.body) : `status:${dlRes.status}`;
  }

  return { indexSha256, fileSha256 };
}

async function expectNothingStored(world: World, before: PypiFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the package exactly as it was').toEqual(before);

  const filename = wheelFilename(world.publishTarget.packageName, world.publishTarget.version);
  if (!(filename in before.fileSha256)) {
    const admin = adminCredential();
    const dl = await rawDownload(world.repoName, admin, world.publishTarget.packageName, filename);
    expect(dl.status, 'the refused version was never stored').toBe(404);
  }
}

/**
 * Run only after BOTH the publish and the consume of one scenario succeeded: the project page
 * carries an entry for the published wheel whose `href` names EXACTLY the canonical download URL
 * (single-tenant layout, one `/<repoName>/` segment -- the RPS-1205/nuget-H5 analogue, does NOT
 * reproduce here, confirmed live/H8), whose `#sha256=` fragment and `data-requires-python` match what
 * was published (confirmed live/H9: `>=3.9` renders as `&gt;=3.9`, unescaped back to `>=3.9` by
 * `parseSimplePage`), and whose bytes download intact.
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const name = world.consumeTarget.packageName;
  const version = world.consumeTarget.version;
  const filename = wheelFilename(name, version);

  const res = await rawGetSimplePage(world.repoName, admin, name);
  expect(res.status, 'the project page exists for the published package').toBe(200);

  const links = parseSimplePage(res.body);
  const link = links.find((l) => l.filename === filename);
  expect(link, `a project-page entry for "${filename}"`).toBeDefined();

  // `link.href` carries the `#sha256=...` fragment (this is what `parseSimplePage` reports the
  // fragment hash from); the URL identity check below is about everything BEFORE that fragment.
  const expectedHref = `${env.repoBaseUrl}/${world.repoName}/${downloadPath(name, filename)}`;
  const hrefWithoutFragment = link?.href.split('#')[0];
  expect(hrefWithoutFragment, 'the href names exactly this repo’s own canonical download URL').toBe(
    expectedHref,
  );
  expect(link?.requiresPython, 'requires-python round-trips through the HTML escape/unescape').toBe(
    '>=3.9',
  );

  const dlRes = await rawDownload(world.repoName, admin, name, filename);
  expect(dlRes.status, 'the advertised download URL resolves').toBe(200);
  const expectedSha =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(sha256Hex(dlRes.body), 'the served wheel matches the published bytes').toBe(expectedSha);
  expect(link?.sha256, 'the index fragment matches the served bytes').toBe(expectedSha);
  expect(resolved.contentSha256, 'the resolved wheel matches the served one').toBe(
    sha256Hex(dlRes.body),
  );
}

export const pypiAdapter: ProtocolAdapter<PypiFingerprint> = {
  protocol: 'pypi',
  client: { name: 'twine/pip', publishVerb: 'upload', consumeVerb: 'download' },

  packageName: (runId, scenario) => rawPackageName(runId, scenario),
  // `adapter.version(versionType)` ignores `versionType`: PyPI has no release/prerelease repo-setting
  // distinction (`releases`/`snapshots` are never read by any PyPI code, confirmed live/H23), and
  // `boundedSemverVersion()`'s `0.<secs>.<seq>` output is already a valid PEP 440 CANONICAL version
  // (three dotted, non-zero-padded integers) -- confirmed against `ReleaseVersion`'s own
  // `NORMALIZED_VERSION_PATTERN`, not merely assumed.
  version: () => boundedSemverVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
