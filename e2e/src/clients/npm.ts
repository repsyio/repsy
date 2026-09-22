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
 * The npm client adapter (step 3a, RPS-294): `publish`/`resolve`/`seedPublish` render a tiny,
 * dependency-free package from `src/packages/npm/*.template.*` into an isolated work directory and
 * run the real `npm` binary against it, the npm analogue of `clients/maven.ts`.
 *
 * `npm` hides the HTTP status behind its own exit code, exactly like `mvn`, so `publish`'s `Outcome`
 * is derived from a raw HTTP companion probe (`rawPublish`, built on the SAME packed tarball bytes
 * `npm publish` sent, so a successful client publish followed by the raw re-PUT is a harmless,
 * byte-identical override of the version it just created -- see `npm-raw.ts`'s file header for the
 * exact publish/download path shapes this depends on, all read from
 * `AbstractNpmProtocolFacade`/`AbstractNpmStorageService`/`PackageUtils`).
 *
 * `resolve`'s `Outcome` is derived from a raw packument GET instead: RPS-1205 makes `npm install`
 * fail to fetch the tarball for ANY published package on Repsy OS, which is a storage/path bug, not
 * an auth one, so only a GET that never touches the tarball can tell "the client failed because of
 * auth" from "the client failed because of RPS-1205" -- see `knownConsumeFailure` below, which is
 * what routes the scenario loop (`scenarios/loop.ts`) around exactly that known failure without
 * weakening the auth-outcome assertion itself.
 *
 * Every publish/seed-publish writes a fresh random marker file into the package (the npm analogue of
 * `clients/maven.ts`'s jar resource marker), so two publishes of one coordinate never share content,
 * and a successful `npm install` (once RPS-1205 is fixed) can prove it got the very bytes that were
 * published by reading that file back out of `node_modules`.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import { env } from '../env.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { slugify } from '../scenarios/coordinates.js';
import { expectationFor } from '../scenarios/types.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  adminCredential,
  buildPublishDocument,
  parsePackument,
  rawGetPackument,
  rawGetTarballCanonical,
  rawPublish,
  sha256Hex,
} from './npm-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/npm');

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;
const PACK_TIMEOUT_MS = 60_000;

const MARKER_FILENAME = 'e2e-marker.txt';

/** 2026-01-01T00:00:00Z: correction #3 -- a bounded version scheme, since
 *  `PackageUtils.extractVersionNameFromPayload` parses with semver4j 3.1.0, which stores parts as
 *  Java `Integer`s. Seconds since this epoch stays well under 2^31 for the lifetime of this harness. */
const VERSION_EPOCH_MS = Date.UTC(2026, 0, 1, 0, 0, 0, 0);

let versionSeq = 0;

/** `0.<seconds since VERSION_EPOCH_MS>.<seq>`; npm has no release/snapshot distinction to honour. */
function uniqueVersion(): string {
  versionSeq += 1;
  const seconds = Math.floor((Date.now() - VERSION_EPOCH_MS) / 1000);
  return `0.${seconds}.${versionSeq}`;
}

async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/**
 * The `.npmrc` view (correction #4): `registry=<repoBaseUrl>/<repo>/` and a matching
 * `//<host:port>/<repo>/:_authToken=`/`:_auth=` line, trailing slash on both so npm's per-path
 * scoping actually matches a request under that registry. No credential (`anonymous`) renders
 * neither auth line, so `npm` sends no `Authorization` header (or, with none configured at all,
 * refuses client-side with `ENEEDAUTH` before ever sending the request).
 */
function npmrcView(repoName: string, credential: MaterializedCredential): Record<string, unknown> {
  const hostAndPort = new URL(env.repoBaseUrl).host;
  return {
    registryUrl: `${env.repoBaseUrl}/${repoName}/`,
    hostAndPort,
    repoName,
    hasToken: credential.kind === 'token',
    hasBasic: credential.kind === 'password',
    token: credential.password ?? '',
    basicAuth:
      credential.transport === 'basic'
        ? Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
            'base64',
          )
        : '',
  };
}

async function renderNpmrc(home: string, repoName: string, credential: MaterializedCredential) {
  const npmrcPath = path.join(home, 'npmrc');
  await renderTemplate('npmrc.template', npmrcPath, npmrcView(repoName, credential));
  return npmrcPath;
}

async function isolatedCache(home: string): Promise<string> {
  const cacheDir = path.join(home, 'npm-cache');
  await fs.mkdir(cacheDir, { recursive: true });
  return cacheDir;
}

/** `npm pack`s the rendered package in `work` into an isolated destination, returning its bytes. */
async function packTarball(
  work: string,
  home: string,
  label: string,
): Promise<{ file: string; bytes: Buffer }> {
  const destDir = path.join(home, 'pack-out');
  await fs.mkdir(destDir, { recursive: true });

  const result = await run('npm', ['pack', '--ignore-scripts', '--pack-destination', destDir], {
    cwd: work,
    env: { ...process.env, HOME: home },
    timeoutMs: PACK_TIMEOUT_MS,
    label,
  });
  if (result.exitCode !== 0) {
    throw new Error(
      `npm adapter: "npm pack" failed unexpectedly (exit ${result.exitCode}): ${label}`,
    );
  }

  const files = await fs.readdir(destDir);
  const file = files[0];
  if (!file) {
    throw new Error(`npm adapter: "npm pack" produced no file in ${destDir}`);
  }
  const bytes = await fs.readFile(path.join(destDir, file));
  return { file: path.join(destDir, file), bytes };
}

/** Renders the tiny publishable package (package.json, index.js, a fresh random marker file). */
async function renderPackage(
  work: string,
  packageName: string,
  version: string,
): Promise<{ marker: string }> {
  await renderTemplate('package.template.json', path.join(work, 'package.json'), {
    packageName,
    version,
  });
  await renderTemplate('index.template.js', path.join(work, 'index.js'), {});
  const marker = randomUUID();
  await fs.writeFile(path.join(work, MARKER_FILENAME), marker, 'utf8');
  return { marker };
}

interface PublishRun {
  exitCode: number;
  command: string;
  tarballBytes: Buffer;
  marker: string;
}

/**
 * Renders the package, packs it, and runs the real `npm publish` of that exact tarball file with
 * `world.credential`. `--force` (correction #1) is passed iff `world.scenario.reuseCoordinates` is
 * true (the `override`/`no-override` scenarios): it bypasses npm 11's client-side "cannot publish
 * over previous version" pre-flight (`lib/commands/publish.js`'s `#publish`, gated `if (!force)`),
 * so the real request actually reaches the server both when the redeploy is allowed and when it
 * isn't, instead of the client refusing on its own before any HTTP call is made.
 */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;

  const { marker } = await renderPackage(work, packageName, version);
  const npmrcPath = await renderNpmrc(home, world.repoName, world.credential);
  const cacheDir = await isolatedCache(home);
  const { file: tarballFile, bytes: tarballBytes } = await packTarball(work, home, `${label}-pack`);

  const secrets = world.credential.password ? [world.credential.password] : [];
  const args = [
    'publish',
    tarballFile,
    '--userconfig',
    npmrcPath,
    '--cache',
    cacheDir,
    '--ignore-scripts',
  ];
  if (world.scenario.reuseCoordinates) {
    args.push('--force');
  }

  const execResult = await run('npm', args, {
    cwd: work,
    env: { ...process.env, HOME: home },
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  return {
    exitCode: execResult.exitCode,
    command: execResult.command,
    tarballBytes,
    marker,
  };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `npm-publish-${world.scenario.id}`);

  const document = buildPublishDocument({
    repoName: world.repoName,
    packageName: world.publishTarget.packageName,
    version: world.publishTarget.version,
    tarballBytes: published.tarballBytes,
  });
  const rawRes = await rawPublish(
    world.repoName,
    world.credential,
    world.publishTarget.packageName,
    document,
  );

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: sha256Hex(published.marker),
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`). Only the real client runs, mirroring `clients/maven.ts`'s `seedPublish`: the
 * raw probe of `publish` would leave an extra, harmless-but-unwanted re-PUT that a later
 * "nothing changed" comparison has no use for.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `npm-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `npm adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(npm exit ${published.exitCode}); its "consume: ok" expectation depends on this package ` +
        'actually existing.',
    );
  }
  return { contentSha256: sha256Hex(published.marker) };
}

/** The directory `npm install` would place `packageName` into, under a consumer's `node_modules`. */
function installedPackageDir(work: string, packageName: string): string {
  return path.join(work, 'node_modules', ...packageName.split('/'));
}

async function readInstalledMarker(
  work: string,
  packageName: string,
): Promise<{ file: string; sha256: string } | undefined> {
  try {
    const content = await fs.readFile(
      path.join(installedPackageDir(work, packageName), MARKER_FILENAME),
      'utf8',
    );
    return {
      file: path.join('node_modules', packageName, MARKER_FILENAME),
      sha256: sha256Hex(content),
    };
  } catch {
    return undefined;
  }
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`npm-con-${world.scenario.id}`);
  const { packageName, version } = world.consumeTarget;

  // A minimal, source-free consumer project, the npm analogue of clients/maven.ts's `resolve`
  // consumer pom: it declares no dependency of its own, so `npm install <pkg>@<version>` below (an
  // explicit install target, not a `package.json` dependency) is what actually resolves anything.
  await renderTemplate('package.template.json', path.join(work, 'package.json'), {
    packageName: `e2e-consumer-${world.scenario.id}`,
    version: '0.0.0',
  });

  const npmrcPath = await renderNpmrc(home, world.repoName, world.credential);
  const cacheDir = await isolatedCache(home);

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'npm',
    [
      'install',
      `${packageName}@${version}`,
      '--userconfig',
      npmrcPath,
      '--cache',
      cacheDir,
      '--ignore-scripts',
      '--no-save',
      '--no-package-lock',
      '--no-audit',
      '--no-fund',
    ],
    {
      cwd: work,
      env: { ...process.env, HOME: home },
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `npm-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe (see this file's header): a packument GET never touches the
  // (possibly RPS-1205-broken) tarball path, so its status is a clean signal of authn/authz alone.
  const rawRes = await rawGetPackument(world.repoName, world.credential, packageName);
  const resolved = await readInstalledMarker(work, packageName);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved?.sha256,
    resolvedFile: resolved?.file,
  };
}

/** A path -> content hash snapshot of one package, for `ProtocolAdapter.fingerprint`/
 *  `expectNothingStored`: the packument body (`undefined` when the package does not exist at all)
 *  plus every version's stored tarball, by content. No repo-wide directory listing exists for npm
 *  the way maven's does, so this is scoped to the one package a scenario's publish targets -- exactly
 *  what "a refused publish left this package untouched" needs. */
export interface NpmFingerprint {
  packumentSha256?: string;
  versionTarballSha256: Record<string, string>;
}

async function fingerprint(world: World): Promise<NpmFingerprint> {
  const admin = adminCredential();
  const packageName = world.publishTarget.packageName;

  const packumentRes = await rawGetPackument(world.repoName, admin, packageName);
  if (packumentRes.status === 404) {
    return { versionTarballSha256: {} };
  }
  if (packumentRes.status !== 200) {
    throw new Error(
      `npm adapter: fingerprint(): GET packument of "${packageName}" answered ${packumentRes.status}`,
    );
  }

  const packumentSha256 = sha256Hex(packumentRes.body);
  const { versions } = parsePackument(packumentRes.body);
  const versionTarballSha256: Record<string, string> = {};

  for (const version of Object.keys(versions)) {
    const tarballRes = await rawGetTarballCanonical(world.repoName, admin, packageName, version);
    versionTarballSha256[version] =
      tarballRes.status === 200 ? sha256Hex(tarballRes.body) : `status:${tarballRes.status}`;
  }

  return { packumentSha256, versionTarballSha256 };
}

async function expectNothingStored(world: World, before: NpmFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the package exactly as it was').toEqual(before);
}

export const npmAdapter: ProtocolAdapter<NpmFingerprint> = {
  protocol: 'npm',
  client: { name: 'npm', publishVerb: 'publish', consumeVerb: 'install' },

  packageName: (runId, scenario) => `e2e-${runId}-${slugify(scenario.id)}`,
  version: () => uniqueVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,

  /**
   * RPS-1205 (plan section "Known critical fact"): `PackageUtils.fixTarballUrl` mis-rewrites the
   * stored `dist.tarball` for Repsy OS's single-tenant layout, so `npm install` of any published
   * package cannot fetch its tarball. Only the scenario's final client-exit-code/content-equality
   * consume assertions are routed through `test.fail()` for this (see `scenarios/loop.ts`); the
   * outcome itself (the raw packument-GET auth probe) is asserted for real, same as every other
   * scenario.
   */
  knownConsumeFailure: (scenario) =>
    expectationFor(scenario, 'npm').consume === 'ok'
      ? 'RPS-1205: npm install cannot fetch the tarball (fixTarballUrl misrewrites the path for the OS single-tenant layout)'
      : undefined,
};
