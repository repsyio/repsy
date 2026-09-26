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
 * The NuGet (.NET package registry) client adapter (step 3c, RPS-294): `publish`/`resolve`/
 * `seedPublish` build a tiny, hand-assembled `.nupkg` (`nuget-raw.ts`'s `buildNupkg`, `fflate` --
 * deliberately never `dotnet pack`, see that file's header) and run the real `dotnet` binary against
 * it, the NuGet analogue of `clients/cargo.ts`.
 *
 * Unlike cargo (no override rule at all) and npm (an existing version is always refused, regardless
 * of `allowOverride`), NuGet has a REAL override rule with a genuine `409 Conflict`
 * (`AbstractNuGetProtocolFacade.publish`, `nuget-raw.ts`'s file header) -- so `publish`'s raw-HTTP
 * companion probe (`rawPublish`, same nupkg bytes `dotnet nuget push` was just given) is a
 * byte-identical RE-PUT of the exact version just attempted, the maven/npm pattern, not cargo's
 * prerelease-sibling workaround: every `ok`-expected scenario runs with `allowOverride: true` (the
 * fixture default), so the re-PUT is an accepted, identical replacement; `no-override` gets the same
 * `409` the client got; a releases/snapshots refusal gets the same `422` (`checkVersionAllowance`
 * runs BEFORE the override check, so a redeploy under `releases:false`/`snapshots:false` is `422`,
 * never `409`).
 *
 * `resolve`'s `Outcome` is derived from a raw flat-version-list GET instead (mirrors npm's
 * packument-GET / cargo's sparse-index-GET reasoning): every consume expectation in the catalog is an
 * authn/authz outcome, and a versions-list GET never touches the `.nupkg` bytes themselves.
 *
 * Every publish/seed-publish packs a fresh random marker file (`content/e2e-marker.txt`) into the
 * nupkg, so two publishes of one coordinate never share content; `AdapterResult.contentSha256` is the
 * sha256 of the WHOLE `.nupkg` file (not just the marker, like cargo's `.crate`): `dotnet restore`
 * never unpacks the archive into the consumer's own tree, it keeps the original file under
 * `NUGET_PACKAGES/<idLower>/<verLower>/<idLower>.<verLower>.nupkg`, so comparing whole-file digests is
 * what a restored, still-packed `.nupkg` can actually be checked against.
 *
 * Credential mapping (both push and restore share ONE rendered `nuget.config`, see `renderNugetConfig`
 * and `nuget-raw.ts`'s `nugetApiKey`/`nugetPublishHeaders`): a `token`-kind credential (a deploy
 * token) is passed to `dotnet nuget push` as `--api-key <token>` -- the panel's "Option B", the
 * server's `X-NuGet-ApiKey` -> Bearer path, authenticates the client's FIRST request. A
 * `password`-kind credential is passed as `--api-key any` (the panel's "Option B" text VERBATIM,
 * confirmed live to be a dummy value here -- see README.md's "H7"): that fails the Bearer path with a
 * `401` + `WWW-Authenticate: Basic` challenge, and the client retries with Basic auth from the
 * rendered `nuget.config`'s `packageSourceCredentials` (present for BOTH credential kinds, so a
 * token-kind credential's Basic retry -- never exercised in practice, since the api-key succeeds on
 * the first try -- would also work). `restore` never gets an `--api-key`; it always authenticates via
 * `packageSourceCredentials` alone.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import { env } from '../env.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  adminCredential,
  buildNupkg,
  nugetApiKey,
  nupkgPath,
  packageName as rawPackageName,
  parseRegistrationIndex,
  parseServiceIndex,
  parseVersions,
  rawDownloadNupkg,
  rawGetRegistrationIndex,
  rawGetServiceIndex,
  rawGetVersions,
  rawPublish,
  serviceIndexUrl,
  sha256Hex,
  normalizeVersion,
} from './nuget-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/nuget');

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/**
 * `nuget.config` in `home`, rendered fresh per invocation (never `dotnet`'s own machine-wide config,
 * which this harness never touches): `<clear/>` plus one `repsy` source pointed at the service index,
 * `allowInsecureConnections="true"` (plain HTTP, this harness's own stack). `packageSourceCredentials`
 * is rendered whenever `credential.transport === 'basic'` (both `token`- and `password`-kind
 * credentials carry a username + secret pair the same shape) AND `includeCredentials` (default
 * `true`) is not turned off -- `tests/nuget/publish-consume.spec.ts`'s "api-key-only push" test turns
 * it off to exercise a pure `X-NuGet-ApiKey` push with no `packageSourceCredentials` fallback at all.
 * Exported for that test; every adapter call above goes through this same function, so the
 * credential-rendering logic never drifts between the two.
 */
export async function renderNugetConfig(
  home: string,
  repoName: string,
  credential: MaterializedCredential,
  opts?: { includeCredentials?: boolean },
): Promise<string> {
  const cfgPath = path.join(home, 'nuget.config');
  await renderTemplate('nuget.config.template.xml', cfgPath, {
    serviceIndexUrl: serviceIndexUrl(repoName),
    hasBasic: credential.transport === 'basic' && opts?.includeCredentials !== false,
    username: credential.username ?? '',
    password: credential.password ?? '',
  });
  return cfgPath;
}

/** Renders the consumer classlib project (`consumer.csproj.template.xml`) into `work`. Exported for
 *  `tests/nuget/publish-consume.spec.ts`'s hand-built-`World` real-client tests. */
export async function renderConsumerProject(
  work: string,
  packageId: string,
  version: string,
): Promise<string> {
  const csprojPath = path.join(work, 'consumer.csproj');
  await renderTemplate('consumer.csproj.template.xml', csprojPath, { packageId, version });
  return csprojPath;
}

/** Every env var isolating one `dotnet`/`nuget` invocation from the host and from every other
 *  invocation (parallel Playwright workers, or publish vs. consume in the same test): a private
 *  global-packages folder, HTTP/plugins caches and scratch dir, all under `home`, plus the telemetry/
 *  first-run/build-server noise this harness never wants. `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT`
 *  avoids coupling the image to a specific ICU version (this harness's packages carry no culture-
 *  sensitive data). It also leaves the CLI only the invariant culture, whose messages are the English
 *  ones, so `DOTNET_CLI_UI_LANGUAGE` is deliberately NOT set: any value ("en", "en-US", "en-us") is
 *  rejected there with "error: Invalid culture identifier in DOTNET_CLI_UI_LANGUAGE ..." in the
 *  command's output (RPS-1462). */
export function nugetEnv(home: string): NodeJS.ProcessEnv {
  return {
    ...process.env,
    HOME: home,
    DOTNET_CLI_HOME: home,
    NUGET_PACKAGES: path.join(home, 'nuget-packages'),
    NUGET_HTTP_CACHE_PATH: path.join(home, 'nuget-http-cache'),
    NUGET_PLUGINS_CACHE_PATH: path.join(home, 'nuget-plugins-cache'),
    NUGET_SCRATCH: path.join(home, 'nuget-scratch'),
    DOTNET_NOLOGO: '1',
    DOTNET_CLI_TELEMETRY_OPTOUT: '1',
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE: '1',
    DOTNET_GENERATE_ASPNET_CERTIFICATE: 'false',
    NUGET_XMLDOC_MODE: 'skip',
    DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE: '1',
    MSBUILDDISABLENODEREUSE: '1',
    DOTNET_SYSTEM_GLOBALIZATION_INVARIANT: '1',
  };
}

/**
 * The package ids `dotnet package search` lists, read back out of its table (`| Package ID | Latest
 * Version | Owners | Total Downloads |`). The client wraps a cell that is wider than its column (24
 * characters) onto further rows whose other cells are empty, so an id of 25 characters or more comes
 * out split across two or more rows; those continuation rows are joined back onto their id. Searching
 * a stdout for the id with `toContain` therefore depends on how long the id is (RPS-1462: the ids of
 * this harness are `e2e-<run id><worker><seq>-<label>`, one character over the width from a run id of
 * 7 characters on).
 */
export function packageSearchIds(stdout: string): string[] {
  const ids: string[] = [];
  let header = true;
  for (const line of stdout.split(/\r?\n/)) {
    if (!line.startsWith('|') || /^\|[\s|-]+$/.test(line)) {
      continue;
    }
    const cells = line
      .replace(/^\|/, '')
      .replace(/\|\s*$/, '')
      .split('|')
      .map((cell) => cell.trim());
    if (header) {
      // The first row is the column titles.
      header = false;
      continue;
    }
    const [id, ...others] = cells;
    if (others.every((cell) => cell === '') && ids.length > 0) {
      ids[ids.length - 1] += id;
    } else {
      ids.push(id);
    }
  }
  return ids;
}

interface PublishRun {
  exitCode: number;
  command: string;
  nupkgBytes: Buffer;
  marker: string;
}

/**
 * Builds a fresh `.nupkg` (`buildNupkg`) and runs the real `dotnet nuget push` with
 * `world.credential`. `--allow-insecure-connections` (the flag) and `allowInsecureConnections="true"`
 * (the source attribute, `renderNugetConfig`) are both passed deliberately -- see README.md's "H2"
 * for which one, if either, turned out to be load-bearing.
 */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: packageId, version } = world.publishTarget;

  const marker = randomUUID();
  const nupkgBytes = buildNupkg({ packageId, version, marker });
  const nupkgFile = path.join(work, 'package.nupkg');
  await fs.writeFile(nupkgFile, nupkgBytes);

  const cfgPath = await renderNugetConfig(home, world.repoName, world.credential);
  const apiKey = nugetApiKey(world.credential);

  const secrets = [world.credential.password, apiKey].filter((s): s is string => Boolean(s));
  const args = [
    'nuget',
    'push',
    nupkgFile,
    '--source',
    'repsy',
    '--configfile',
    cfgPath,
    '--allow-insecure-connections',
    '--no-symbols',
    '--timeout',
    '60',
  ];
  if (apiKey !== undefined) {
    args.push('--api-key', apiKey);
  }

  const execResult = await run('dotnet', args, {
    cwd: work,
    env: nugetEnv(home),
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  return { exitCode: execResult.exitCode, command: execResult.command, nupkgBytes, marker };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `nuget-publish-${world.scenario.id}`);

  // Byte-identical re-PUT of the exact nupkg the client just pushed (see this file's header): a
  // real override rule, unlike cargo/npm, so this is the maven/npm re-PUT pattern, not cargo's
  // prerelease-sibling workaround.
  const rawRes = await rawPublish(world.repoName, world.credential, published.nupkgBytes);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: sha256Hex(published.nupkgBytes),
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`). Only the real client runs, mirroring `clients/cargo.ts`'s `seedPublish`.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `nuget-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `nuget adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(dotnet exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'package actually existing.',
    );
  }
  return { contentSha256: sha256Hex(published.nupkgBytes) };
}

/** The `.nupkg` file `dotnet restore` leaves under the isolated `NUGET_PACKAGES` folder, in its
 *  normal global-packages layout: `<idLower>/<verLower>/<idLower>.<verLower>.nupkg`. */
async function findRestoredNupkg(
  packagesDir: string,
  packageId: string,
  version: string,
): Promise<{ file: string; bytes: Buffer } | undefined> {
  const idLower = packageId.toLowerCase();
  const verLower = normalizeVersion(version);
  const rel = path.join(idLower, verLower, `${idLower}.${verLower}.nupkg`);
  try {
    const bytes = await fs.readFile(path.join(packagesDir, rel));
    return { file: path.join('nuget-packages', rel), bytes };
  } catch {
    return undefined;
  }
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`nuget-con-${world.scenario.id}`);
  const { packageName: packageId, version } = world.consumeTarget;

  const csprojPath = await renderConsumerProject(work, packageId, version);
  const cfgPath = await renderNugetConfig(home, world.repoName, world.credential);
  const packagesDir = path.join(home, 'nuget-packages');

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'dotnet',
    [
      'restore',
      csprojPath,
      '--configfile',
      cfgPath,
      '--packages',
      packagesDir,
      '--no-http-cache',
      '--disable-build-servers',
      '-p:NuGetAudit=false',
      '-v',
      'minimal',
    ],
    {
      cwd: work,
      env: nugetEnv(home),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `nuget-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe (see this file's header): a flat-version-list GET never touches
  // the (possibly troublesome) .nupkg bytes.
  const rawRes = await rawGetVersions(world.repoName, world.credential, packageId.toLowerCase());
  const resolved = await findRestoredNupkg(packagesDir, packageId, version);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved ? sha256Hex(resolved.bytes) : undefined,
    resolvedFile: resolved?.file,
  };
}

/** A flat-version-list body hash plus every listed version's `.nupkg`/`.nuspec` content hash, for
 *  `ProtocolAdapter.fingerprint`/`expectNothingStored`. Scoped to the one package a scenario's
 *  publish targets, exactly like `NpmFingerprint`/`CargoFingerprint`. */
export interface NuGetFingerprint {
  versionsSha256?: string;
  versionNupkgSha256: Record<string, string>;
  versionNuspecSha256: Record<string, string>;
}

async function fingerprint(world: World): Promise<NuGetFingerprint> {
  const admin = adminCredential();
  const idLower = world.publishTarget.packageName.toLowerCase();

  const versionsRes = await rawGetVersions(world.repoName, admin, idLower);
  if (versionsRes.status === 404) {
    return { versionNupkgSha256: {}, versionNuspecSha256: {} };
  }
  if (versionsRes.status !== 200) {
    throw new Error(
      `nuget adapter: fingerprint(): GET versions of "${idLower}" answered ${versionsRes.status}`,
    );
  }

  const versionsSha256 = sha256Hex(versionsRes.body);
  const versions = parseVersions(versionsRes.body);
  const versionNupkgSha256: Record<string, string> = {};
  const versionNuspecSha256: Record<string, string> = {};

  for (const v of versions) {
    const dlRes = await rawDownloadNupkg(world.repoName, admin, idLower, v);
    versionNupkgSha256[v] = dlRes.status === 200 ? sha256Hex(dlRes.body) : `status:${dlRes.status}`;
  }

  return { versionsSha256, versionNupkgSha256, versionNuspecSha256 };
}

async function expectNothingStored(world: World, before: NuGetFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the package exactly as it was').toEqual(before);

  const verLower = normalizeVersion(world.publishTarget.version);
  if (!(verLower in before.versionNupkgSha256)) {
    const admin = adminCredential();
    const idLower = world.publishTarget.packageName.toLowerCase();
    const dl = await rawDownloadNupkg(world.repoName, admin, idLower, verLower);
    expect(dl.status, 'the refused version was never stored').toBe(404);
  }
}

/**
 * The RPS-1205-analogue check (npm) / index-consistency check (cargo), run only after both the
 * publish and the consume of one scenario succeeded: the registration index carries a leaf for the
 * published version whose `packageContent` names the canonical download URL, that URL actually
 * resolves to the published bytes, and the service index's `PackageBaseAddress/3.0.0` `@id` names
 * exactly this repo's own package-base URL (H5: single-tenant layout, one `/<repoName>/` segment).
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const idLower = world.consumeTarget.packageName.toLowerCase();
  const verLower = normalizeVersion(world.consumeTarget.version);

  const regRes = await rawGetRegistrationIndex(world.repoName, admin, idLower);
  expect(regRes.status, 'the registration index exists for the published package').toBe(200);
  const leaves = parseRegistrationIndex(regRes.body);
  const leaf = leaves.find((l) => l.version.toLowerCase() === verLower);
  expect(leaf, `a registration leaf for version "${verLower}"`).toBeDefined();
  expect(leaf?.listed, 'a freshly published version is listed').toBe(true);

  const expectedContentUrl = `${env.repoBaseUrl}/${world.repoName}/${nupkgPath(idLower, verLower)}`;
  expect(leaf?.packageContent, 'packageContent names the canonical download URL').toBe(
    expectedContentUrl,
  );

  const dlRes = await rawDownloadNupkg(world.repoName, admin, idLower, verLower);
  expect(dlRes.status, 'the advertised nupkg URL resolves').toBe(200);
  const expectedSha =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(sha256Hex(dlRes.body), 'the served nupkg matches the published bytes').toBe(expectedSha);
  expect(resolved.contentSha256, 'the restored nupkg matches the served one').toBe(
    sha256Hex(dlRes.body),
  );

  const svcRes = await rawGetServiceIndex(world.repoName);
  expect(svcRes.status, 'the service index is served').toBe(200);
  const resources = parseServiceIndex(svcRes.body);
  const packageBaseAddress = resources.find((r) => r.type === 'PackageBaseAddress/3.0.0');
  expect(
    packageBaseAddress?.id,
    'PackageBaseAddress/3.0.0 names exactly this repo’s own package-base URL',
  ).toBe(`${env.repoBaseUrl}/${world.repoName}/v3/package`);
}

export const nugetAdapter: ProtocolAdapter<NuGetFingerprint> = {
  protocol: 'nuget',
  client: { name: 'dotnet', publishVerb: 'nuget push', consumeVerb: 'restore' },

  packageName: (runId, scenario) => rawPackageName(runId, scenario),
  // NuGet prerelease = whatever follows the first "-" (`checkVersionAllowance`,
  // `NuGetPackageVersion.prerelease`); a bounded base version keeps the CLIENT's `NuGetVersion`
  // parser happy (it rejects any numeric part over int32, unlike the server's own BigInteger parser
  // -- see nuget-raw.ts's file header and README.md's design-decision section).
  version: (versionType) => {
    const base = boundedSemverVersion();
    return versionType === 'snapshot' ? `${base}-pre` : base;
  },

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
