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
 * The Ruby gem (RubyGems/Bundler) client adapter (step 4e, RPS-294 -- the LAST protocol adapter of
 * step 4): `publish`/`seedPublish` hand-build a tiny, dependency-free `.gem` (`ruby-raw.ts`'s
 * `buildGem`, `docker-image.ts`'s `buildTar` + `node:zlib` -- deliberately never `gem build`, see
 * that file's header) and run the REAL `gem push` binary against it; `resolve` runs the REAL `bundle
 * install`.
 *
 * **H1, confirmed live, REFUTED the plan's central gating prediction**: a real `bundle install`
 * against a gem published on Repsy OS succeeds completely and resolves the exact published bytes,
 * even though `quick/Marshal.4.8/*.gemspec.rz` has no backend route at all (RPS-1233) and `/info` never
 * advertises `ruby:`/`rubygems:` requirement keys (RB-2) -- Bundler's compact-index client simply
 * never needs the gemspec side channel for a plain gem with no dependencies. `resolve()` therefore
 * drives the real `bundle` toolchain like every other protocol's consumer, and `rubyAdapter` has NO
 * `knownConsumeFailure`: every scenario's consume side is asserted for real. (`gem install`/`gem
 * fetch` ARE broken by RPS-1233/RPS-1234 respectively -- confirmed live, see the dedicated `test.fail()`-pinned
 * real-client tests in `tests/ruby/publish-consume.spec.ts`, never the catalog loop's own consumer.)
 *
 * Like nuget/helm/golang (a REAL, toggleable override rule that answers a genuine `409`, confirmed
 * live): `publish`'s raw-HTTP companion probe (`rawPublish`, the exact same gem bytes `gem push` was
 * just given, via the same `built.sha256Hex`) is a byte-identical re-POST of the coordinate just
 * attempted -- every `ok`-expected scenario runs under `allowOverride: true` (the fixture default),
 * so the re-POST is an accepted, identical replacement; `no-override` gets the same `409
 * gemVersionAlreadyExists` the client got.
 *
 * Every publish/seed-publish packs a fresh random marker into both `lib/<name>.rb` and
 * `e2e-marker.txt`, so two publishes of one coordinate never share content; `AdapterResult
 * .contentSha256` is the sha256 of the WHOLE `.gem` file (Bundler's cache renames the downloaded gem
 * into place byte-for-byte, confirmed live -- comparing whole-file digests is what that cached file
 * can actually be checked against).
 *
 * Credential mapping (`ruby-raw.ts`'s `apiKeyFor`/`bundleCredentialsValue`, both confirmed live):
 * `GEM_HOST_API_KEY` for `gem push` (the raw deploy-token secret for a `token`-kind credential, `Basic
 * <base64(user:pass)>` for a `password`-kind one -- both dispatched correctly by
 * `RubyAuthComponent`'s bare `ProtocolAuthService`); `BUNDLE_<HOSTKEY>=user:secret` (host-keyed, NOT
 * URI-keyed -- `Bundler::Settings#credentials_for` checks the full-URI key first but this harness
 * never sets that one) for a credentialed `bundle install`. `anonymous` leaves both entirely unset
 * (never an empty string): a real `gem push` with no key configured and closed stdin exits 1 promptly
 * (confirmed live/H4, hitting `POST /api/v1/api_key` -> `404 unknownPath` -- no hang), and `bundle
 * install` with no matching `BUNDLE_*` var sends no `Authorization` header at all.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import { env } from '../env.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  adminCredential,
  apiKeyFor,
  type BuiltGem,
  buildGem,
  bundleCredentialsValue,
  bundleHostKey,
  gemFilename,
  gemVersion,
  infoRelPath,
  md5Hex,
  namesRelPath,
  packageName as rawPackageName,
  parseInfo,
  parseNames,
  parseVersionsIndex,
  publishUrl,
  rawDownload,
  rawGet,
  rawPublish,
  sha256Hex,
  TEMPLATES_DIR,
  versionsRelPath,
} from './ruby-raw.js';

const PUBLISH_TIMEOUT_MS = 60_000;
const CONSUME_TIMEOUT_MS = 120_000;

/** Every env var isolating one `gem` invocation: a private `HOME`/`GEM_HOME`/`GEM_PATH`/
 *  `GEM_SPEC_CACHE`, no inherited `RUBYOPT`/`RUBYGEMS_HOST`, and the credential delivered ONLY via
 *  `GEM_HOST_API_KEY` (never a `~/.gem/credentials` file, never `--key`) -- this file's header.
 *  `anonymous` leaves `GEM_HOST_API_KEY` unset entirely. */
export function gemEnv(home: string, credential: MaterializedCredential): NodeJS.ProcessEnv {
  const result: NodeJS.ProcessEnv = {
    ...process.env,
    HOME: home,
    GEM_HOME: path.join(home, 'gems'),
    GEM_PATH: path.join(home, 'gems'),
    GEM_SPEC_CACHE: path.join(home, 'specs'),
  };
  delete result.RUBYOPT;
  delete result.RUBYGEMS_HOST;
  const apiKey = apiKeyFor(credential);
  if (apiKey !== undefined) {
    result.GEM_HOST_API_KEY = apiKey;
  } else {
    delete result.GEM_HOST_API_KEY;
  }
  return result;
}

/** Every env var isolating one `bundle install`: private `HOME`/`GEM_HOME`/`GEM_PATH`/`BUNDLE_PATH`/
 *  `BUNDLE_APP_CONFIG`/`BUNDLE_USER_HOME` (confirmed-live, real `Settings` keys, this file's header),
 *  and the credential delivered ONLY via `BUNDLE_<HOSTKEY>=user:secret` (confirmed live/H5) --
 *  `anonymous` sets no such var. */
export function bundleEnv(
  home: string,
  work: string,
  credential: MaterializedCredential,
  repoName: string,
): NodeJS.ProcessEnv {
  void repoName; // the credential is keyed by HOST, not by repo (bundleHostKey(env.repoBaseUrl's host))
  const result: NodeJS.ProcessEnv = {
    ...process.env,
    HOME: home,
    GEM_HOME: path.join(home, 'gems'),
    GEM_PATH: path.join(home, 'gems'),
    GEM_SPEC_CACHE: path.join(home, 'specs'),
    BUNDLE_GEMFILE: path.join(work, 'Gemfile'),
    BUNDLE_PATH: path.join(home, 'vendor'),
    BUNDLE_PATH__SYSTEM: 'false',
    BUNDLE_APP_CONFIG: path.join(work, '.bundle'),
    BUNDLE_USER_HOME: path.join(home, '.bundle'),
    BUNDLE_RETRY: '0',
    BUNDLE_JOBS: '1',
    BUNDLE_TIMEOUT: '10',
    BUNDLE_VERSION: 'system',
    BUNDLE_SILENCE_ROOT_WARNING: '1',
    BUNDLE_FROZEN: 'false',
    BUNDLE_DEPLOYMENT: 'false',
    BUNDLE_GLOBAL_GEM_CACHE: 'false',
    BUNDLE_DISABLE_VERSION_CHECK: 'true',
    BUNDLE_FORCE_RUBY_PLATFORM: 'true',
  };
  delete result.RUBYOPT;

  const hostKey = bundleHostKey(new URL(env.repoBaseUrl).hostname);
  const value = bundleCredentialsValue(credential);
  if (value !== undefined) {
    const [user, pass] = value.split(/:(.*)/s);
    result[hostKey] = `${encodeURIComponent(user)}:${encodeURIComponent(pass ?? '')}`;
  } else {
    delete result[hostKey];
  }
  return result;
}

interface PublishRun {
  exitCode: number;
  command: string;
  built: BuiltGem;
}

/** Hand-builds a fresh gem and runs the real `gem push <file> --host <repoBaseUrl>/<repo>` (no
 *  `--key`, exactly the panel's own documented incantation minus `--key`, this file's header). For
 *  `anonymous`, stdin is closed (`input: ''`) so `gem push`'s own interactive sign-in prompt hits EOF
 *  and exits promptly instead of hanging (confirmed live/H4). */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;

  const built = await buildGem({ name: packageName, version, marker: randomUUID() });
  const gemFile = path.join(work, built.filename);
  await fs.writeFile(gemFile, built.bytes);

  const args = ['push', gemFile, '--host', `${env.repoBaseUrl}/${world.repoName}`];
  const secrets = world.credential.password ? [world.credential.password] : [];

  const execResult = await run('gem', args, {
    cwd: work,
    env: gemEnv(home, world.credential),
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
    input: '',
  });

  return { exitCode: execResult.exitCode, command: execResult.command, built };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `ruby-publish-${world.scenario.id}`);

  // Byte-identical re-POST of the exact gem the client just pushed (this file's header): a real
  // override rule, so this is the maven/npm/nuget/pypi re-PUT/re-POST pattern, not cargo's
  // prerelease-sibling workaround.
  const rawRes = await rawPublish(world.repoName, world.credential, published.built.bytes);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.built.sha256Hex,
  };
}

/** The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
 *  coordinate (`reuseCoordinates`). Only the real client runs, mirroring `clients/pypi.ts`'s/
 *  `clients/golang.ts`'s `seedPublish`. */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `ruby-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `ruby adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(gem push exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'gem actually existing.',
    );
  }
  return { contentSha256: published.built.sha256Hex };
}

/** The `.gem` file `bundle install` leaves under `<BUNDLE_PATH>/ruby/<abi>/cache/`, whichever single
 *  ABI directory bundler created (confirmed live: exactly one, `4.0.0` on the pinned toolchain, but
 *  this globs rather than hardcoding it). */
async function findCachedGem(
  bundlePath: string,
  filename: string,
): Promise<{ file: string; bytes: Buffer } | undefined> {
  const rubyDir = path.join(bundlePath, 'ruby');
  let abiDirs: string[];
  try {
    abiDirs = await fs.readdir(rubyDir);
  } catch {
    return undefined;
  }
  for (const abi of abiDirs) {
    const candidate = path.join(rubyDir, abi, 'cache', filename);
    try {
      const bytes = await fs.readFile(candidate);
      return { file: candidate, bytes };
    } catch {
      // Not in this ABI dir -- try the next one (there is normally exactly one).
    }
  }
  return undefined;
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`ruby-con-${world.scenario.id}`);
  const { packageName, version } = world.consumeTarget;

  const gemfileTemplate = await fs.readFile(path.join(TEMPLATES_DIR, 'Gemfile.template'), 'utf8');
  const gemfile = mustache.render(gemfileTemplate, {
    repoUrl: `${env.repoBaseUrl}/${world.repoName}`,
    name: packageName,
    version,
  });
  await fs.writeFile(path.join(work, 'Gemfile'), gemfile, 'utf8');

  const secrets = world.credential.password ? [world.credential.password] : [];
  const bundleProcessEnv = bundleEnv(home, work, world.credential, world.repoName);
  const execResult = await run('bundle', ['install', '--verbose'], {
    cwd: work,
    env: bundleProcessEnv,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: secrets,
    label: `ruby-consume-${world.scenario.id}`,
  });

  const filename = gemFilename(packageName, version);
  const resolved = await findCachedGem(bundleProcessEnv.BUNDLE_PATH as string, filename);

  // The auth-only companion probe (mirrors pypi's/golang's own resolve()): a plain /info GET never
  // touches the gem bytes `bundle install` itself just fetched.
  const rawRes = await rawGet(world.repoName, world.credential, infoRelPath(packageName));

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved ? sha256Hex(resolved.bytes) : undefined,
    resolvedFile: resolved?.file,
  };
}

/** `/info/<gem>`'s own body hash plus every listed version's (yanked included) downloaded `.gem`
 *  content hash, for `ProtocolAdapter.fingerprint`/`expectNothingStored`. Scoped to the one gem a
 *  scenario's publish targets, exactly like `PypiFingerprint`/`GolangFingerprint`. Deliberately never
 *  includes `/versions` (its `created_at` preamble changes on every request, confirmed live). */
export interface RubyFingerprint {
  infoSha256?: string;
  files: Record<string, string>;
}

async function fingerprint(world: World): Promise<RubyFingerprint> {
  const admin = adminCredential();
  const name = world.publishTarget.packageName;

  const infoRes = await rawGet(world.repoName, admin, infoRelPath(name));
  if (infoRes.status === 404) {
    return { files: {} };
  }
  if (infoRes.status !== 200) {
    throw new Error(`ruby adapter: fingerprint(): GET /info/${name} answered ${infoRes.status}`);
  }

  const infoSha256 = sha256Hex(infoRes.body);
  const entries = parseInfo(infoRes.body);
  const files: Record<string, string> = {};

  for (const entry of entries) {
    const filename = gemFilename(name, entry.version, entry.platform);
    const dlRes = await rawDownload(world.repoName, admin, filename);
    files[filename] = dlRes.status === 200 ? sha256Hex(dlRes.body) : `status:${dlRes.status}`;
  }

  return { infoSha256, files };
}

async function expectNothingStored(world: World, before: RubyFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the gem exactly as it was').toEqual(before);

  const filename = gemFilename(world.publishTarget.packageName, world.publishTarget.version);
  if (!(filename in before.files)) {
    const admin = adminCredential();
    const dl = await rawDownload(world.repoName, admin, filename);
    expect(dl.status, 'the refused version was never stored').toBe(404);
  }
}

/**
 * Run only after BOTH the publish and the consume of one scenario succeeded: `/info/<gem>` lists
 * exactly the published version, non-yanked, with `checksum:` equal to the published sha256; `/
 * versions`' md5 for this gem matches `md5Hex(the /info body)`; `/names` contains the gem name; the
 * canonical `gems/<filename>` download matches the published bytes; the resolved (bundler-cached)
 * file matches it too.
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const name = world.consumeTarget.packageName;
  const version = world.consumeTarget.version;
  const filename = gemFilename(name, version);

  const infoRes = await rawGet(world.repoName, admin, infoRelPath(name));
  expect(infoRes.status, 'the .info route serves the published gem').toBe(200);
  const entries = parseInfo(infoRes.body);
  const entry = entries.find((e) => e.version === version);
  expect(entry, `an /info entry for "${version}"`).toBeDefined();
  expect(entry?.yanked, 'the published version is not yanked').toBe(false);

  const expectedSha =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(entry?.checksum, 'the /info checksum matches the published sha256').toBe(expectedSha);

  const versionsRes = await rawGet(world.repoName, admin, versionsRelPath());
  const versionsIndex = parseVersionsIndex(versionsRes.body);
  expect(versionsIndex[name]?.md5, '/versions’ md5 matches md5Hex(the /info body)').toBe(
    md5Hex(infoRes.body),
  );

  const namesRes = await rawGet(world.repoName, admin, namesRelPath());
  expect(parseNames(namesRes.body), '/names carries the published gem').toContain(name);

  const dlRes = await rawDownload(world.repoName, admin, filename);
  expect(dlRes.status, 'the canonical gem file is served').toBe(200);
  expect(dlRes.contentType, 'served as application/octet-stream').toBe('application/octet-stream');
  expect(sha256Hex(dlRes.body), 'the served gem matches the published bytes').toBe(expectedSha);
  expect(resolved.contentSha256, 'the resolved (bundler-cached) gem matches the served one').toBe(
    expectedSha,
  );
}

export { publishUrl };

export const rubyAdapter: ProtocolAdapter<RubyFingerprint> = {
  protocol: 'ruby',
  client: { name: 'gem/bundle', publishVerb: 'push', consumeVerb: 'bundle install' },

  packageName: (runId, scenario) => rawPackageName(runId, scenario),
  // Ruby has no release/prerelease repo-setting distinction (`releases`/`snapshots` are never read
  // by any Ruby code, grep-confirmed), so `versionType` is ignored, same as pypi/docker/helm/golang.
  version: () => gemVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,

  // Deliberately no knownConsumeFailure: H1 (the plan's gating hypothesis) is REFUTED -- a real
  // `bundle install` succeeds completely against Repsy OS (see this file's header and
  // `ruby-raw.ts`'s). No knownPublishSideEffect either: RB-0 (RPS-1060's row-first publish order) is
  // re-verified live, so a refused re-publish under allowOverride:false changes nothing on disk.
};
