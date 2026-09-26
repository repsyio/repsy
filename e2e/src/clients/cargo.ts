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
 * The Cargo (Rust crate registry) client adapter (step 3b, RPS-294): `publish`/`resolve`/
 * `seedPublish` render a tiny, dependency-free crate from `src/packages/cargo/*.template.*` into an
 * isolated work directory and run the real `cargo` binary against it, the Cargo analogue of
 * `clients/npm.ts`.
 *
 * Underscore-only crate names (`cargo-raw.ts`'s `crateName`): the server serves a crate's sparse
 * index under `CrateUtils.normalizeCrateName(name)` (lower-case, `-` -> `_`), never under the name it
 * was published with, so a hyphenated name would (a) still be accepted by the publish PUT, (b) never
 * be found by a real `cargo publish`'s own post-publish index poll (which looks up the exact name it
 * sent), producing a slow, spurious "timed out waiting for ... to be available" warning, and (c) be
 * unresolvable by `cargo fetch` under the name it was declared with. The catalog loop therefore never
 * exercises a hyphenated name; a dedicated real-client test in `tests/cargo/publish-consume.spec.ts`
 * pins this bug (RPS-1212) directly.
 *
 * `cargo` hides the HTTP status behind its own exit code, exactly like `npm`/`mvn`, so `publish`'s
 * `Outcome` is derived from a raw HTTP companion probe (`rawPublish`) -- but NOT a re-PUT of the same
 * version the client just published: cargo's publish route has no override rule at all (every
 * duplicate-version PUT is refused with 400, unconditionally -- see `cargo-raw.ts`'s file header), so
 * re-sending the just-published version would always come back `rejected`, never `ok`. Instead:
 *  - A non-redeploy scenario (`reuseCoordinates` false or absent) probes a PRERELEASE SIBLING,
 *    `<version>-probe`: semver orders a prerelease below its bare version
 *    (`SemverComparator`/semver4j), so it is accepted without disturbing `max_version`, and an
 *    `=<version>` consumer never resolves to it.
 *  - A redeploy scenario (`reuseCoordinates` true -- `no-override`/`override`) re-PUTs the EXACT
 *    version the client just (successfully or not) attempted, which is exactly what a real client
 *    would have hit had it not preflighted the query client-side (`verify_unpublished`,
 *    `ops/registry/publish.rs`). The loop then asserts (`expectNothingStored`) that the refused
 *    duplicate touched neither the DB nor storage (RPS-1124, fixed: the check runs before the write).
 *
 * `resolve`'s `Outcome` is derived from a raw sparse-index GET instead (mirrors npm's packument-GET
 * reasoning): every consume expectation in the catalog is an authn/authz outcome, and an index GET
 * never touches the `.crate` bytes.
 *
 * Every publish/seed-publish writes a fresh random marker file (`e2e-marker.txt`) into the crate, so
 * two publishes of one coordinate never share content; `AdapterResult.contentSha256` is the sha256 of
 * the WHOLE packaged `.crate` file (not just the marker, unlike npm): `cargo fetch` never extracts
 * the archive, so comparing full-file digests is what a downloaded, still-compressed `.crate` can
 * actually be checked against.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import { repoUrl } from '../repo-url.js';
import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { SeedResult, World } from '../scenarios/world.js';
import {
  adminCredential,
  buildPublishBody,
  cargoToken,
  crateName,
  parseIndex,
  rawDownload,
  rawGetIndex,
  rawPublish,
  sha256Hex,
} from './cargo-raw.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import { randomPadding } from './padding.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/cargo');

const PACKAGE_TIMEOUT_MS = 60_000;
const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

const MARKER_FILENAME = 'e2e-marker.txt';

async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/**
 * `.cargo/config.toml` in `work`: `[registries.repsy] index = "sparse+<repoBaseUrl>/<repo>/"` plus
 * an explicit `cargo:token` credential provider. Cargo looks upward from its cwd for this file, so
 * running with `cwd: work` finds it without touching `CARGO_HOME`'s own global config. Exported
 * (step 5b) for `tests/cargo/protocol-specific.spec.ts`'s hand-built-`World` tests, which run the
 * real `cargo yank`/`search`/`owner` subcommands directly instead of through `publish`/`resolve` --
 * the same precedent as `nuget.ts`'s `renderNugetConfig`/`nugetEnv`.
 */
export async function renderCargoConfig(work: string, repoName: string): Promise<void> {
  const cargoDir = path.join(work, '.cargo');
  await fs.mkdir(cargoDir, { recursive: true });
  await renderTemplate('config.template.toml', path.join(cargoDir, 'config.toml'), {
    registryUrl: repoUrl(repoName, ''),
  });
}

/** Renders the tiny publishable crate (Cargo.toml, src/lib.rs, a fresh random marker file). `padBytes`
 *  adds a file of random bytes (`e2e-padding.bin`, `padding.ts`) for the size-limit leg (RPS-1482), which
 *  renders the crate itself to publish it over the limit. */
export async function renderCrate(
  work: string,
  crate: string,
  version: string,
  padBytes?: number,
): Promise<{ marker: string }> {
  await renderTemplate('Cargo.template.toml', path.join(work, 'Cargo.toml'), {
    crateName: crate,
    version,
  });
  const srcDir = path.join(work, 'src');
  await fs.mkdir(srcDir, { recursive: true });
  await renderTemplate('lib.template.rs', path.join(srcDir, 'lib.rs'), {});
  const marker = randomUUID();
  await fs.writeFile(path.join(work, MARKER_FILENAME), marker, 'utf8');
  if (padBytes !== undefined) {
    await fs.writeFile(path.join(work, 'e2e-padding.bin'), randomPadding(padBytes));
  }
  return { marker };
}

/** Renders a minimal, source-only consumer crate that depends on `crate`@`=<version>`. */
async function renderConsumerCrate(work: string, crate: string, version: string): Promise<void> {
  await renderTemplate('consumer-Cargo.template.toml', path.join(work, 'Cargo.toml'), {
    crateName: crate,
    version,
  });
  const srcDir = path.join(work, 'src');
  await fs.mkdir(srcDir, { recursive: true });
  // cargo refuses a package with no targets at all; an empty lib is enough to resolve dependencies.
  await fs.writeFile(path.join(srcDir, 'lib.rs'), '', 'utf8');
}

/** Exported (step 5b) for the same reason as `renderCargoConfig` above. */
export function cargoEnv(home: string, credential: World['credential']): NodeJS.ProcessEnv {
  const token = cargoToken(credential);
  return clientEnv(
    home,
    {
      CARGO_HOME: path.join(home, 'cargo'),
      CARGO_TERM_COLOR: 'never',
      CARGO_NET_RETRY: '0',
      ...(token !== undefined ? { CARGO_REGISTRIES_REPSY_TOKEN: token } : {}),
    },
    // The runner's `cargo` is a rustup proxy (/usr/local/cargo/bin): without RUSTUP_HOME it finds no
    // toolchain (probed live, RPS-1446).
    ['RUSTUP_HOME'],
  );
}

/** `target/package/<name>-<version>.crate`, the file `cargo package`/`cargo publish` write. */
function packagedCratePath(work: string, crate: string, version: string): string {
  return path.join(work, 'target', 'package', `${crate}-${version}.crate`);
}

async function readPackagedCrate(work: string, crate: string, version: string): Promise<Buffer> {
  return fs.readFile(packagedCratePath(work, crate, version));
}

/**
 * `cargo package --no-verify --offline` (no network, no deps to fetch): pre-packages the crate so
 * the raw-probe body has real bytes even for a scenario whose own `cargo publish` never gets far
 * enough to produce one (an auth failure refuses the client BEFORE it packages anything -- the
 * preflight index query cargo runs happens first, see this file's header). `--no-verify` skips
 * building the crate to "verify" it, which needs `rustc` and a linker; a build is the toolchain's
 * concern, not the registry's, and only `cargo install` (install-add.spec.ts) needs the gcc the runner image carries.
 */
async function packageCrate(work: string, home: string, label: string): Promise<void> {
  const result = await run('cargo', ['package', '--no-verify', '--offline'], {
    cwd: work,
    env: cargoEnv(home, {}),
    timeoutMs: PACKAGE_TIMEOUT_MS,
    label,
  });
  if (result.exitCode !== 0) {
    throw new Error(
      `cargo adapter: "cargo package" failed unexpectedly (exit ${result.exitCode}): ${label}`,
    );
  }
}

interface PublishRun {
  exitCode: number;
  command: string;
  crateBytes: Buffer;
  marker: string;
}

/**
 * Renders and pre-packages the crate, then runs the real `cargo publish --no-verify` with
 * `world.credential`. `CARGO_PUBLISH_TIMEOUT=30` bounds cargo's own post-publish index poll
 * (`wait_for_any_publish_confirmation`, default 60s) -- the index is DB-backed here, so a successful
 * publish resolves on the first poll; nothing in this harness relies on the longer default.
 */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;

  const { marker } = await renderCrate(work, packageName, version);
  await renderCargoConfig(work, world.repoName);
  await packageCrate(work, home, `${label}-package`);

  // Read right after packaging: a scenario whose own publish attempt never reaches the point of
  // producing this file (an auth failure refuses the client before it packages anything, see this
  // file's header) still needs real bytes for the raw-probe companion below.
  const preBytes = await readPackagedCrate(work, packageName, version);

  const secrets = world.credential.password ? [world.credential.password] : [];
  const args = ['publish', '--registry', 'repsy', '--no-verify'];

  const execResult = await run('cargo', args, {
    cwd: work,
    env: { ...cargoEnv(home, world.credential), CARGO_PUBLISH_TIMEOUT: '30' },
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  // `cargo publish` re-packages to the same path when it gets far enough to do so; that is the file
  // that was actually uploaded. Fall back to the pre-package bytes when publish never got that far
  // (an auth failure, or a client-side "already exists" preflight refusal).
  const crateBytes = await readPackagedCrate(work, packageName, version).catch(() => preBytes);

  return {
    exitCode: execResult.exitCode,
    command: execResult.command,
    crateBytes,
    marker,
  };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `cargo-publish-${world.scenario.id}`);

  // See this file's header: a redeploy scenario re-probes the SAME version (what a real client would
  // have hit without its own client-side preflight); every other scenario probes a prerelease
  // sibling, so an accepted probe never collides with a version the client itself just published.
  const probeVersion = world.scenario.reuseCoordinates
    ? world.publishTarget.version
    : `${world.publishTarget.version}-probe`;

  const body = buildPublishBody({
    name: world.publishTarget.packageName,
    version: probeVersion,
    crateBytes: published.crateBytes,
  });
  const rawRes = await rawPublish(world.repoName, world.credential, body);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: sha256Hex(published.crateBytes),
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
 * coordinate (`reuseCoordinates`). Only the real client runs, mirroring `clients/npm.ts`'s
 * `seedPublish`.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `cargo-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `cargo adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(cargo exit ${published.exitCode}); its "consume: ok" expectation depends on this crate ` +
        'actually existing.',
    );
  }
  return { contentSha256: sha256Hex(published.crateBytes) };
}

/** The `.crate` file `cargo fetch` downloaded into its cache, one level under
 *  `<CARGO_HOME>/registry/cache/<registry-hash>/`. */
async function findFetchedCrate(
  cargoHome: string,
  packageName: string,
  version: string,
): Promise<{ file: string; bytes: Buffer } | undefined> {
  const cacheDir = path.join(cargoHome, 'registry', 'cache');
  let regDirs: string[];
  try {
    regDirs = await fs.readdir(cacheDir);
  } catch {
    return undefined;
  }
  const wanted = `${packageName}-${version}.crate`;
  for (const regDir of regDirs) {
    const candidate = path.join(cacheDir, regDir, wanted);
    try {
      const bytes = await fs.readFile(candidate);
      return { file: path.join('registry', 'cache', regDir, wanted), bytes };
    } catch {
      // Not in this registry-hash directory; keep looking.
    }
  }
  return undefined;
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`cargo-con-${world.scenario.id}`);
  const { packageName, version } = world.consumeTarget;

  await renderConsumerCrate(work, packageName, version);
  await renderCargoConfig(work, world.repoName);

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run('cargo', ['fetch'], {
    cwd: work,
    env: cargoEnv(home, world.credential),
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: secrets,
    label: `cargo-consume-${world.scenario.id}`,
  });

  // The auth-only companion probe (see this file's header): a sparse-index GET never touches the
  // .crate bytes.
  const rawRes = await rawGetIndex(world.repoName, world.credential, packageName);
  const cargoHome = path.join(home, 'cargo');
  const resolved = await findFetchedCrate(cargoHome, packageName, version);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved ? sha256Hex(resolved.bytes) : undefined,
    resolvedFile: resolved?.file,
  };
}

/** A sparse-index body hash plus every listed version's stored `.crate` content hash, for
 *  `ProtocolAdapter.fingerprint`/`expectNothingStored`. Scoped to the one crate a scenario's publish
 *  targets, exactly like `NpmFingerprint`. */
export interface CargoFingerprint {
  indexSha256?: string;
  versionCrateSha256: Record<string, string>;
}

async function fingerprint(world: World): Promise<CargoFingerprint> {
  const admin = adminCredential();
  const packageName = world.publishTarget.packageName;

  const indexRes = await rawGetIndex(world.repoName, admin, packageName);
  if (indexRes.status === 404) {
    return { versionCrateSha256: {} };
  }
  if (indexRes.status !== 200) {
    throw new Error(
      `cargo adapter: fingerprint(): GET index of "${packageName}" answered ${indexRes.status}`,
    );
  }

  const indexSha256 = sha256Hex(indexRes.body);
  const entries = parseIndex(indexRes.body);
  const versionCrateSha256: Record<string, string> = {};

  for (const entry of entries) {
    const dlRes = await rawDownload(world.repoName, admin, packageName, entry.vers);
    versionCrateSha256[entry.vers] =
      dlRes.status === 200 ? sha256Hex(dlRes.body) : `status:${dlRes.status}`;
  }

  return { indexSha256, versionCrateSha256 };
}

async function expectNothingStored(world: World, before: CargoFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the crate exactly as it was').toEqual(before);

  if (!(world.publishTarget.version in before.versionCrateSha256)) {
    const admin = adminCredential();
    const dl = await rawDownload(
      world.repoName,
      admin,
      world.publishTarget.packageName,
      world.publishTarget.version,
    );
    expect(dl.status, 'the refused version was never stored').toBe(404);
  }
}

async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const indexRes = await rawGetIndex(world.repoName, admin, world.consumeTarget.packageName);
  expect(indexRes.status, 'the published crate is listed in the sparse index').toBe(200);

  const entries = parseIndex(indexRes.body);
  const entry = entries.find((e) => e.vers === world.consumeTarget.version);
  expect(entry, `an index entry for version "${world.consumeTarget.version}"`).toBeDefined();
  expect(entry?.yanked, 'a freshly published version is not yanked').toBe(false);

  const expectedCksum =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  // The .crate file's own sha256 differs from the index's cksum field (which is Cargo's own sha256
  // of the .crate bytes, computed server-side -- CargoDigestCalculator) only in what they are hashes
  // OF: both hash the exact same bytes, so they must be equal.
  expect(entry?.cksum, 'the index cksum matches the published crate bytes').toBe(expectedCksum);
  expect(resolved.contentSha256, 'the resolved crate matches the index cksum').toBe(entry?.cksum);
}

export const cargoAdapter: ProtocolAdapter<CargoFingerprint> = {
  protocol: 'cargo',
  client: { name: 'cargo', publishVerb: 'publish', consumeVerb: 'fetch' },

  packageName: (runId, scenario) => crateName(runId, scenario),
  version: () => boundedSemverVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};

// --- RPS-1486: `cargo install` / `cargo add` / `cargo login`, the commands the panel advertises ---

/** What `renderInstallableCrate` renders (`Cargo.install.template.toml`). */
export interface InstallableCrate {
  crate: string;
  version: string;
  /** A `[[bin]]` crate (`src/main.rs` prints the marker), otherwise a `[lib]` one (`src/lib.rs`
   *  returns its own version from `version()`). */
  bin?: boolean;
  /** Feature names, each declared empty (`name = []`). */
  features?: readonly string[];
  /** Repsy-registry dependencies (`registry = "repsy"`), `req` a Cargo version requirement. */
  deps?: readonly { name: string; req: string }[];
}

/**
 * Renders a crate whose sources really compile (unlike `renderCrate`'s, packaged with `--no-verify`):
 * `cargo install` builds it. A bin crate prints `marker=<uuid>` and, when it has a dependency, that
 * dependency's `version()` as `dep=<version>` (the dependency is a `renderInstallableCrate` lib).
 */
export async function renderInstallableCrate(
  work: string,
  crate: InstallableCrate,
): Promise<{ marker: string }> {
  await renderTemplate('Cargo.install.template.toml', path.join(work, 'Cargo.toml'), {
    crateName: crate.crate,
    version: crate.version,
    bin: crate.bin === true,
    features: crate.features ?? [],
    deps: crate.deps ?? [],
  });
  const marker = randomUUID();
  const srcDir = path.join(work, 'src');
  await fs.mkdir(srcDir, { recursive: true });
  if (crate.bin === true) {
    await renderTemplate('main.template.rs', path.join(srcDir, 'main.rs'), {
      marker,
      dep: crate.deps?.[0]?.name ?? false,
    });
  } else {
    await renderTemplate('version-lib.template.rs', path.join(srcDir, 'lib.rs'), {
      version: crate.version,
    });
  }
  return { marker };
}

/**
 * The environment of a user who followed the panel's Cargo page literally: no `CARGO_HOME` (so
 * `$HOME/.cargo`, where the page says the config file goes), no `CARGO_REGISTRIES_REPSY_TOKEN` unless
 * `token` is given, and the allow-list of `clientEnv` (RPS-1446).
 */
export function cargoPanelEnv(home: string, token?: string): NodeJS.ProcessEnv {
  return clientEnv(
    home,
    {
      CARGO_TERM_COLOR: 'never',
      CARGO_NET_RETRY: '0',
      ...(token !== undefined ? { CARGO_REGISTRIES_REPSY_TOKEN: token } : {}),
    },
    ['RUSTUP_HOME'],
  );
}

/**
 * `$HOME/.cargo/config.toml` exactly as the panel's Cargo page tells a user to write it
 * (`cargo-config.component.ts`: `[registries] repsy = { index = "sparse+<repo url>" }` and, for a
 * private repo, `[registry] global-credential-providers = ["cargo:token"]`). `credentialProviders`
 * false is the "Public repo (download only): Skip the [registry] section" variant.
 */
export async function renderPanelCargoConfig(
  home: string,
  repoName: string,
  credentialProviders = true,
): Promise<string> {
  const cargoDir = path.join(home, '.cargo');
  await fs.mkdir(cargoDir, { recursive: true });
  const file = path.join(cargoDir, 'config.toml');
  await renderTemplate('panel-config.template.toml', file, {
    registryUrl: repoUrl(repoName, ''),
    credentialProviders,
  });
  return file;
}
