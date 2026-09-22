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
 * Cargo protocol-specific tests (step 5b, RPS-294), ported from `repsy-cloud`'s own e2e harness
 * (`protocols/cargo/library/test.ts`'s yank/unyank/search parts, `checksum/test.ts`,
 * `multi_version/test.ts`) into this harness's own conventions: real `cargo` binaries, hand-built
 * `World`s, `clients/cargo.ts`/`cargo-raw.ts` helpers -- never the cloud harness's own `npx tsx
 * subprocess`/`shelljs`/`process.argv` structure. This is "cargo A", the simpler half; a later PR
 * ports the cloud harness's `dependency_tree`/`platform_deps`/`workspace` cases ("cargo B").
 *
 * Every credential here is `token-rw` (a fresh read-write deploy token), built by hand exactly like
 * `tests/nuget/publish-consume.spec.ts`'s dedicated tests -- these are not catalog-loop scenarios, so
 * there is no `world` fixture call; `cargo.publish(world)` is invoked directly. A raw-HTTP
 * verification probe uses `adminCredential()` instead, the same split every other protocol's
 * dedicated tests use for read-side checks. Every crate name is underscore-only
 * (`cargoAdapter.packageName`'s own convention), routing around RPS-1212 (the served sparse index
 * normalises `-` -> `_`, so a hyphenated name here would just re-trip that already-pinned bug instead
 * of exercising yank/search/owners/checksum/multi-version).
 *
 * HC-1/HC-2/HC-6 were probed live against a running local stack BEFORE this file was written (see
 * this PR's own report for the exact commands/output):
 *
 *  - HC-1: real `cargo yank`/`cargo yank --undo`/`cargo search --registry repsy`/`cargo owner --list
 *    --registry repsy` all reach the server and get a real response. Yank/unyank/search work exactly
 *    as a real crates.io-shaped client expects (exit 0, the sparse index's `yanked` field flips, the
 *    search envelope is found). `cargo owner --list`, however, does NOT: Repsy OS's own
 *    `CargoOwnersProtocolMethodHandler` (defined directly in `repsy-backend`, not the shared
 *    `repsy-protocols/cargo` abstract-class family every other cargo route extends) answers every
 *    owners request -- even a GET -- with a FIXED `{"ok":true,"msg":"..."}` body and no `users` array
 *    at all, so a real `cargo owner --list` fails client-side ("missing field `users`", exit 101)
 *    even though the raw HTTP GET itself succeeds (200). This is now filed as RPS-1239 (distinct from
 *    RPS-1124/RPS-1212), pinned below with `test.fail()`, never fixed here.
 *  - HC-2: a yanked crate's `.crate` bytes are STILL downloadable afterwards (confirmed live, 200) --
 *    `AbstractCargoDownloadProtocolMethodHandler` has no yanked check at all. This matches real
 *    Cargo semantics: yank only affects fresh dependency RESOLUTION, never a download of an
 *    already-pinned version.
 *  - HC-6: yank is refused for a read-only deploy token with a real, live 401 `unAuthorized` (both a
 *    real `cargo yank` invocation and a raw `DELETE` probe), confirming the handler's documented
 *    `permission: WRITE` is actually enforced, not merely declared. Cheap enough to pin as its own
 *    test below.
 *
 * Step 5c ("cargo B") adds the rest: `dependency_tree`/`platform_deps`/`workspace`, ported from the
 * same cloud harness (`protocols/cargo/dependency_tree`, `.../platform_deps`, `.../workspace`),
 * again real `cargo` binaries + hand-built `World`-less crate layouts, never that harness's own
 * `npx tsx subprocess`/`shelljs` structure. Three more hypotheses were probed live against a running
 * local stack BEFORE this suite was written (exact commands/output in this PR's own report):
 *
 *  - HC-2: `packageCrate()`'s `cargo package --no-verify --offline` (the step used by `publish()`'s
 *    plain, dependency-free marker crate) CANNOT package a crate that declares a real dependency on
 *    another crate already published to this same repo -- confirmed live, it fails with "no matching
 *    package named `<leaf>` found ... offline mode (via `--offline`) can sometimes cause surprising
 *    resolution failures". A plain `cargo publish --registry repsy --no-verify --allow-dirty`
 *    (online, no separate `cargo package` pre-step at all -- exactly what `publishRealCrate` below
 *    runs) resolves the dependency against the registry and succeeds (exit 0) once the depended-on
 *    crate has already been published. This is why the dependency-tree/platform-deps/workspace tests
 *    below use `publishRealCrate`, never `cargo.publish(world)`/`packageCrate`.
 *  - HC-3: a same-registry dependency's served sparse-index `deps` entry has NO `registry` field at
 *    all -- confirmed live (real Cargo's own manifest normalisation/publish serialization, not a
 *    Repsy server behaviour): `{"name":"<leaf>","req":"=<version>","features":[],"optional":false,
 *    "default_features":true,"kind":"normal"}`, no `"registry"` key present.
 *  - HC-4: `cargo publish --registry repsy --package <member> --no-verify --allow-dirty` works from a
 *    workspace root exactly like a single-crate `cargo publish` -- confirmed live, exit 0 for two
 *    workspace members published in turn, no compilation ever attempted (`--no-verify`, and the
 *    runner image ships no gcc/build-essential -- see `runners/cargo.Dockerfile`'s header).
 *
 * No new candidate bug was found while building this suite; all three hypotheses matched the plan.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { RepoType } from '../../src/api/panel-api.js';
import * as cargo from '../../src/clients/cargo.js';
import { cargoAdapter, cargoEnv, renderCargoConfig } from '../../src/clients/cargo.js';
import {
  adminCredential,
  parseIndex,
  parseSearch,
  rawDownload,
  rawGetIndex,
  rawOwners,
  rawSearch,
  rawYank,
  sha256Hex,
} from '../../src/clients/cargo-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, MaterializedCredential, World } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../../src/packages/cargo');

/** One `deps` entry of a served sparse-index `CrateIndexEntry`, typed just enough for this file's
 *  own assertions (`cargo-raw.ts`'s `ParsedIndexEntry` leaves `deps` as `unknown`). */
interface CargoDepEntry {
  name: string;
  req: string;
  registry?: string;
  target?: string;
}

interface Layout {
  repoName: string;
  packageName: string;
  credential: MaterializedCredential;
}

/** A fresh private cargo repo, a run-unique underscore-only crate name, and a fresh `token-rw`
 *  deploy token for it -- the credential every hand-built `World` in this file uses. */
async function newRepoWithToken(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const credential: MaterializedCredential = {
    transport: 'basic',
    username: token.username,
    password: token.token,
    kind: 'token',
  };
  return { repoName: repo.name, packageName: `e2e_${seeder.runId}_${label}`, credential };
}

function buildWorld(layout: Layout, target: Coordinates, id: string): World {
  const scenario: Scenario = {
    id,
    tags: ['@smoke'],
    repo: { privateRepo: true },
    credential: 'token-rw',
    expect: { publish: 'ok', consume: 'ok' },
  };
  return {
    scenario,
    protocol: 'cargo',
    repoName: layout.repoName,
    credential: layout.credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

/** Renders `templateName` (mustache, `src/packages/cargo/*.template.toml`) to `<destDir>/Cargo.toml`. */
async function renderCargoManifest(
  templateName: string,
  destDir: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(path.join(destDir, 'Cargo.toml'), mustache.render(template, view), 'utf8');
}

/** A minimal, static `src/lib.rs` under `destDir` -- these crates are packaged with `--no-verify`
 *  (see this file's header, HC-2/HC-4), so the body is never actually compiled by anything here. */
async function writeLibRs(destDir: string, body: string): Promise<void> {
  const srcDir = path.join(destDir, 'src');
  await fs.mkdir(srcDir, { recursive: true });
  await fs.writeFile(path.join(srcDir, 'lib.rs'), body, 'utf8');
}

/**
 * Runs the real `cargo publish` directly against `work`, with NO preceding `cargo package --offline`
 * step (see this file's header, HC-2): `packageCrate()` in `clients/cargo.ts` cannot resolve a real
 * registry dependency offline, so a crate that declares one publishes online instead, exactly like
 * `repsy-cloud`'s own `util.ts#cargoPublish`/`publishMember`. `packageArg` runs `--package <name>`
 * for a workspace-root `work` (HC-4); omitted, it publishes the crate manifest at `work` itself.
 */
async function publishRealCrate(
  work: string,
  home: string,
  credential: MaterializedCredential,
  label: string,
  packageArg?: string,
) {
  const args = ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'];
  if (packageArg) {
    args.push('--package', packageArg);
  }
  return run('cargo', args, {
    cwd: work,
    env: { ...cargoEnv(home, credential), CARGO_PUBLISH_TIMEOUT: '30' },
    timeoutMs: 60_000,
    redact: credential.password ? [credential.password] : [],
    label,
  });
}

test('cargo > yank and unyank round trip', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'yank');
  const version = cargoAdapter.version('release');
  const target: Coordinates = { packageName: layout.packageName, version };
  const world = buildWorld(layout, target, 'yank-roundtrip');

  const published = await cargo.publish(world);
  expect(
    published.outcome,
    `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; cargo ` +
      `exit ${published.clientExitCode}; ${published.command})`,
  ).toBe('ok');
  expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

  const { home, work } = await isolatedWorkDir(`cargo-yank-${seeder.runId}`);
  await renderCargoConfig(work, layout.repoName);
  const env = cargoEnv(home, layout.credential);

  const yankResult = await run(
    'cargo',
    ['yank', '--registry', 'repsy', '--version', version, layout.packageName],
    {
      cwd: work,
      env,
      timeoutMs: 60_000,
      redact: [layout.credential.password ?? ''],
      label: 'cargo-yank',
    },
  );
  expect(yankResult.exitCode, `cargo yank: ${yankResult.command}`).toBe(0);

  const admin = adminCredential();
  const indexAfterYank = await rawGetIndex(layout.repoName, admin, layout.packageName);
  expect(indexAfterYank.status, 'the sparse index still serves this crate').toBe(200);
  const yankedEntry = parseIndex(indexAfterYank.body).find((e) => e.vers === version);
  expect(yankedEntry, `an index entry for version "${version}"`).toBeDefined();
  expect(yankedEntry?.yanked, 'the sparse index reflects yanked=true').toBe(true);

  const unyankResult = await run(
    'cargo',
    ['yank', '--undo', '--registry', 'repsy', '--version', version, layout.packageName],
    {
      cwd: work,
      env,
      timeoutMs: 60_000,
      redact: [layout.credential.password ?? ''],
      label: 'cargo-unyank',
    },
  );
  expect(unyankResult.exitCode, `cargo yank --undo: ${unyankResult.command}`).toBe(0);

  const indexAfterUnyank = await rawGetIndex(layout.repoName, admin, layout.packageName);
  const unyankedEntry = parseIndex(indexAfterUnyank.body).find((e) => e.vers === version);
  expect(unyankedEntry, `an index entry for version "${version}"`).toBeDefined();
  expect(unyankedEntry?.yanked, 'the sparse index reflects yanked=false after undo').toBe(false);
});

test('cargo > search finds a published crate', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'search');
  const version = cargoAdapter.version('release');
  const target: Coordinates = { packageName: layout.packageName, version };
  const world = buildWorld(layout, target, 'search');

  const published = await cargo.publish(world);
  expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
  expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

  const { home, work } = await isolatedWorkDir(`cargo-search-${seeder.runId}`);
  await renderCargoConfig(work, layout.repoName);

  const searchResult = await run('cargo', ['search', layout.packageName, '--registry', 'repsy'], {
    cwd: work,
    env: cargoEnv(home, layout.credential),
    timeoutMs: 60_000,
    redact: [layout.credential.password ?? ''],
    label: 'cargo-search',
  });
  expect(searchResult.exitCode, `cargo search: ${searchResult.command}`).toBe(0);
  expect(searchResult.stdout, 'the search result lists the published crate').toContain(
    layout.packageName,
  );

  const admin = adminCredential();
  const rawRes = await rawSearch(layout.repoName, admin, layout.packageName);
  expect(rawRes.status, 'the raw search GET succeeds').toBe(200);
  const parsed = parseSearch(rawRes.body);
  expect(parsed.meta.total, 'at least one crate matches the query').toBeGreaterThanOrEqual(1);
});

test(
  'cargo > owners lists the publisher (RPS-1239: no "users" array)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'owners');
    const version = cargoAdapter.version('release');
    const target: Coordinates = { packageName: layout.packageName, version };
    const world = buildWorld(layout, target, 'owners');

    const published = await cargo.publish(world);
    expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
    expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

    const { home, work } = await isolatedWorkDir(`cargo-owners-${seeder.runId}`);
    await renderCargoConfig(work, layout.repoName);

    const ownerResult = await run(
      'cargo',
      ['owner', '--list', '--registry', 'repsy', layout.packageName],
      {
        cwd: work,
        env: cargoEnv(home, layout.credential),
        timeoutMs: 60_000,
        redact: [layout.credential.password ?? ''],
        label: 'cargo-owner-list',
      },
    );

    const admin = adminCredential();
    const rawRes = await rawOwners(layout.repoName, admin, layout.packageName);
    expect(rawRes.status, 'the raw owners GET succeeds').toBe(200);

    // Confirmed live: RPS-1239: CargoOwnersProtocolMethodHandler answers every owners
    // request -- even a GET -- with a fixed {"ok":true,"msg":"..."} body and no "users" array at
    // all, so a real `cargo owner --list` fails client-side ("missing field `users`") even though
    // the raw GET above succeeds. Never fixed here -- pinned so a future fix flips this test to an
    // unexpected pass instead of silently starting to fail elsewhere.
    test.fail(
      true,
      'RPS-1239: cargo owner --list fails client-side ("missing field `users`") because ' +
        'CargoOwnersProtocolMethodHandler answers every owners request with a fixed ' +
        '{"ok":true,"msg":"..."} body and no "users" array at all',
    );
    expect(ownerResult.exitCode, `cargo owner --list: ${ownerResult.command}`).toBe(0);
    const body = JSON.parse(rawRes.body.toString('utf8')) as { users?: unknown[] };
    expect(Array.isArray(body.users), 'the owners response carries a "users" array').toBe(true);
  },
);

test(
  'cargo > index cksum equals the served .crate bytes',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'cksum');
    const version = cargoAdapter.version('release');
    const target: Coordinates = { packageName: layout.packageName, version };
    const world = buildWorld(layout, target, 'cksum');

    const published = await cargo.publish(world);
    expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
    expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

    const admin = adminCredential();
    const indexRes = await rawGetIndex(layout.repoName, admin, layout.packageName);
    expect(indexRes.status, 'the sparse index serves this crate').toBe(200);
    const entry = parseIndex(indexRes.body).find((e) => e.vers === version);
    expect(entry, `an index entry for version "${version}"`).toBeDefined();

    const dlRes = await rawDownload(layout.repoName, admin, layout.packageName, version);
    expect(dlRes.status, 'the .crate file downloads').toBe(200);
    const downloadedSha = sha256Hex(dlRes.body);

    expect(entry?.cksum, 'the index cksum matches the raw-downloaded .crate bytes').toBe(
      downloadedSha,
    );
    expect(downloadedSha, 'the downloaded bytes are exactly what the adapter published').toBe(
      published.contentSha256,
    );
  },
);

test('cargo > two versions coexist independently', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'multiver');

  const version1 = cargoAdapter.version('release');
  const target1: Coordinates = { packageName: layout.packageName, version: version1 };
  const world1 = buildWorld(layout, target1, 'multiver-v1');
  const published1 = await cargo.publish(world1);
  expect(published1.outcome, `publish v1: expected "ok" (${published1.command})`).toBe('ok');
  expect(published1.clientExitCode, `cargo publish v1: ${published1.command}`).toBe(0);

  const version2 = cargoAdapter.version('release');
  expect(version2, 'the two versions are actually distinct').not.toBe(version1);
  const target2: Coordinates = { packageName: layout.packageName, version: version2 };
  const world2 = buildWorld(layout, target2, 'multiver-v2');
  const published2 = await cargo.publish(world2);
  expect(published2.outcome, `publish v2: expected "ok" (${published2.command})`).toBe('ok');
  expect(published2.clientExitCode, `cargo publish v2: ${published2.command}`).toBe(0);

  const admin = adminCredential();
  const indexRes = await rawGetIndex(layout.repoName, admin, layout.packageName);
  expect(indexRes.status, 'the sparse index serves this crate').toBe(200);
  const entries = parseIndex(indexRes.body);
  const entry1 = entries.find((e) => e.vers === version1);
  const entry2 = entries.find((e) => e.vers === version2);
  expect(entry1, `an index entry for version "${version1}"`).toBeDefined();
  expect(entry2, `an index entry for version "${version2}"`).toBeDefined();
  expect(entry1?.cksum, 'the two versions have independent checksums').not.toBe(entry2?.cksum);

  const dl1 = await rawDownload(layout.repoName, admin, layout.packageName, version1);
  const dl2 = await rawDownload(layout.repoName, admin, layout.packageName, version2);
  expect(dl1.status, `${version1} downloads`).toBe(200);
  expect(dl2.status, `${version2} downloads`).toBe(200);
  expect(sha256Hex(dl1.body), 'the two versions serve different bytes').not.toBe(
    sha256Hex(dl2.body),
  );
});

test(
  'cargo > yank is refused for a read-only token (HC-6, permission: WRITE enforced live)',
  { tag: ['@smoke', '@auth', '@negative'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
    const rwToken = await seeder.createToken(repo.name, { readOnly: false });
    const roToken = await seeder.createToken(repo.name, { readOnly: true });
    const rwCredential: MaterializedCredential = {
      transport: 'basic',
      username: rwToken.username,
      password: rwToken.token,
      kind: 'token',
    };
    const roCredential: MaterializedCredential = {
      transport: 'basic',
      username: roToken.username,
      password: roToken.token,
      kind: 'token',
    };
    const packageName = `e2e_${seeder.runId}_royank`;
    const version = cargoAdapter.version('release');
    const target: Coordinates = { packageName, version };
    const world = buildWorld(
      { repoName: repo.name, packageName, credential: rwCredential },
      target,
      'royank',
    );

    const published = await cargo.publish(world);
    expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
    expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

    const { home, work } = await isolatedWorkDir(`cargo-royank-${seeder.runId}`);
    await renderCargoConfig(work, repo.name);
    const yankResult = await run(
      'cargo',
      ['yank', '--registry', 'repsy', '--version', version, packageName],
      {
        cwd: work,
        env: cargoEnv(home, roCredential),
        timeoutMs: 60_000,
        redact: roCredential.password ? [roCredential.password] : [],
        label: 'cargo-royank',
      },
    );
    expect(yankResult.exitCode, `cargo yank with a read-only token: ${yankResult.command}`).toBe(
      101,
    );
    expect(yankResult.stderr, 'the client reports the server refusal').toContain('401');

    const admin = adminCredential();
    const rawRes = await rawYank(repo.name, roCredential, packageName, version);
    expect(rawRes.status, 'a raw yank DELETE with a read-only token is refused').toBe(401);

    const indexRes = await rawGetIndex(repo.name, admin, packageName);
    const entry = parseIndex(indexRes.body).find((e) => e.vers === version);
    expect(entry?.yanked, 'the refused yank never touched the crate').toBe(false);
  },
);

test('cargo > dependency tree leaf -> mid -> root', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'depchain');
  const version = cargoAdapter.version('release');
  const admin = adminCredential();

  const leafName = `${layout.packageName}_leaf`;
  const midName = `${layout.packageName}_mid`;
  const rootName = `${layout.packageName}_root`;

  // ─── leaf: no dependencies ────────────────────────────────────────────────
  const { home: leafHome, work: leafWork } = await isolatedWorkDir(`cargo-dt-leaf-${seeder.runId}`);
  await renderCargoManifest('Cargo.template.toml', leafWork, {
    crateName: leafName,
    version,
  });
  await writeLibRs(leafWork, `pub fn leaf() -> &'static str { "${leafName}" }\n`);
  await renderCargoConfig(leafWork, layout.repoName);
  const leafPublish = await publishRealCrate(
    leafWork,
    leafHome,
    layout.credential,
    'cargo-dt-leaf',
  );
  expect(leafPublish.exitCode, `cargo publish leaf: ${leafPublish.command}`).toBe(0);

  const leafIndexRes = await rawGetIndex(layout.repoName, admin, leafName);
  expect(leafIndexRes.status, 'leaf is served in the sparse index').toBe(200);
  const leafEntry = parseIndex(leafIndexRes.body).find((e) => e.vers === version);
  expect(leafEntry, `a leaf index entry for "${version}"`).toBeDefined();

  // ─── mid: depends on leaf ──────────────────────────────────────────────────
  const { home: midHome, work: midWork } = await isolatedWorkDir(`cargo-dt-mid-${seeder.runId}`);
  await renderCargoManifest('Cargo.deps.template.toml', midWork, {
    crateName: midName,
    version,
    deps: [{ name: leafName, version }],
  });
  await writeLibRs(midWork, `pub fn mid() -> &'static str { "${midName}" }\n`);
  await renderCargoConfig(midWork, layout.repoName);
  const midPublish = await publishRealCrate(midWork, midHome, layout.credential, 'cargo-dt-mid');
  expect(midPublish.exitCode, `cargo publish mid: ${midPublish.command}`).toBe(0);

  const midIndexRes = await rawGetIndex(layout.repoName, admin, midName);
  expect(midIndexRes.status, 'mid is served in the sparse index').toBe(200);
  const midEntry = parseIndex(midIndexRes.body).find((e) => e.vers === version);
  expect(midEntry, `a mid index entry for "${version}"`).toBeDefined();
  const midDeps = midEntry?.deps as CargoDepEntry[] | undefined;
  const leafDep = midDeps?.find((d) => d.name === leafName);
  expect(
    leafDep,
    `mid declares a dep on "${leafName}". Got: ${JSON.stringify(midDeps)}`,
  ).toBeDefined();
  expect(leafDep?.req, "mid's dep on leaf carries leaf's exact version").toContain(version);
  // Real Cargo's own serialization for a same-registry dependency omits "registry" entirely --
  // confirmed live, see this file's header (HC-3).
  expect(
    leafDep && 'registry' in leafDep,
    'a same-registry dep has no "registry" field at all',
  ).toBe(false);

  // ─── root: depends on mid ──────────────────────────────────────────────────
  const { home: rootHome, work: rootWork } = await isolatedWorkDir(`cargo-dt-root-${seeder.runId}`);
  await renderCargoManifest('Cargo.deps.template.toml', rootWork, {
    crateName: rootName,
    version,
    deps: [{ name: midName, version }],
  });
  await writeLibRs(rootWork, `pub fn root() -> &'static str { "${rootName}" }\n`);
  await renderCargoConfig(rootWork, layout.repoName);
  const rootPublish = await publishRealCrate(
    rootWork,
    rootHome,
    layout.credential,
    'cargo-dt-root',
  );
  expect(rootPublish.exitCode, `cargo publish root: ${rootPublish.command}`).toBe(0);

  const rootIndexRes = await rawGetIndex(layout.repoName, admin, rootName);
  expect(rootIndexRes.status, 'root is served in the sparse index').toBe(200);
  const rootEntry = parseIndex(rootIndexRes.body).find((e) => e.vers === version);
  expect(rootEntry, `a root index entry for "${version}"`).toBeDefined();
  const rootDeps = rootEntry?.deps as CargoDepEntry[] | undefined;
  const midDep = rootDeps?.find((d) => d.name === midName);
  expect(
    midDep,
    `root declares a dep on "${midName}". Got: ${JSON.stringify(rootDeps)}`,
  ).toBeDefined();
  expect(midDep?.req, "root's dep on mid carries mid's exact version").toContain(version);
});

test(
  'cargo > platform-specific dependency records its target',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'platformdep');
    const version = cargoAdapter.version('release');
    const admin = adminCredential();

    const depName = `${layout.packageName}_dep`;
    const mainName = `${layout.packageName}_main`;

    // ─── dep: published unconditionally, the main crate's cfg(unix) dep must exist first ─────
    const { home: depHome, work: depWork } = await isolatedWorkDir(`cargo-pd-dep-${seeder.runId}`);
    await renderCargoManifest('Cargo.template.toml', depWork, { crateName: depName, version });
    await writeLibRs(depWork, `pub fn dep() -> &'static str { "${depName}" }\n`);
    await renderCargoConfig(depWork, layout.repoName);
    const depPublish = await publishRealCrate(depWork, depHome, layout.credential, 'cargo-pd-dep');
    expect(depPublish.exitCode, `cargo publish dep: ${depPublish.command}`).toBe(0);

    const depIndexRes = await rawGetIndex(layout.repoName, admin, depName);
    expect(depIndexRes.status, 'the platform dep is served in the sparse index').toBe(200);
    expect(
      parseIndex(depIndexRes.body).find((e) => e.vers === version),
      `a dep index entry for "${version}"`,
    ).toBeDefined();

    // ─── main: declares dep under [target.'cfg(unix)'.dependencies] ──────────────────────────
    const { home: mainHome, work: mainWork } = await isolatedWorkDir(
      `cargo-pd-main-${seeder.runId}`,
    );
    await renderCargoManifest('Cargo.platform-deps.template.toml', mainWork, {
      crateName: mainName,
      version,
      depName,
      depVersion: version,
    });
    await writeLibRs(mainWork, `pub fn main_lib() -> &'static str { "${mainName}" }\n`);
    await renderCargoConfig(mainWork, layout.repoName);
    const mainPublish = await publishRealCrate(
      mainWork,
      mainHome,
      layout.credential,
      'cargo-pd-main',
    );
    expect(mainPublish.exitCode, `cargo publish main: ${mainPublish.command}`).toBe(0);

    const mainIndexRes = await rawGetIndex(layout.repoName, admin, mainName);
    expect(mainIndexRes.status, 'main is served in the sparse index').toBe(200);
    const mainEntry = parseIndex(mainIndexRes.body).find((e) => e.vers === version);
    expect(mainEntry, `a main index entry for "${version}"`).toBeDefined();
    const mainDeps = mainEntry?.deps as CargoDepEntry[] | undefined;
    const unixDep = mainDeps?.find((d) => d.name === depName);
    expect(
      unixDep,
      `main declares a dep on "${depName}". Got: ${JSON.stringify(mainDeps)}`,
    ).toBeDefined();
    expect(unixDep?.target, 'the cfg(unix) dep records its target').toContain('cfg(unix)');
  },
);

test(
  'cargo > workspace members publish in dependency order',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'workspace');
    const version = cargoAdapter.version('release');
    const admin = adminCredential();

    const utilsName = `${layout.packageName}_utils`;
    const coreName = `${layout.packageName}_core`;
    const appName = `${layout.packageName}_app`;

    const { home, work } = await isolatedWorkDir(`cargo-ws-${seeder.runId}`);
    await fs.mkdir(work, { recursive: true });
    await fs.writeFile(
      path.join(work, 'Cargo.toml'),
      ['[workspace]', 'members = ["utils", "core", "app"]', 'resolver = "2"', ''].join('\n'),
      'utf8',
    );

    // utils: no dependencies
    const utilsDir = path.join(work, 'utils');
    await fs.mkdir(utilsDir, { recursive: true });
    await renderCargoManifest('Cargo.template.toml', utilsDir, { crateName: utilsName, version });
    await writeLibRs(utilsDir, `pub fn utils() -> &'static str { "${utilsName}" }\n`);

    // core: depends on utils
    const coreDir = path.join(work, 'core');
    await fs.mkdir(coreDir, { recursive: true });
    await renderCargoManifest('Cargo.deps.template.toml', coreDir, {
      crateName: coreName,
      version,
      deps: [{ name: utilsName, version }],
    });
    await writeLibRs(coreDir, `pub fn core() -> &'static str { "${coreName}" }\n`);

    // app: depends on core + utils
    const appDir = path.join(work, 'app');
    await fs.mkdir(appDir, { recursive: true });
    await renderCargoManifest('Cargo.deps.template.toml', appDir, {
      crateName: appName,
      version,
      deps: [
        { name: coreName, version },
        { name: utilsName, version },
      ],
    });
    await writeLibRs(appDir, `pub fn app() -> &'static str { "${appName}" }\n`);

    await renderCargoConfig(work, layout.repoName);

    // Publish in dependency order (leaf -> trunk -> app), each a real `cargo publish --package`
    // run from the workspace root (HC-4).
    const utilsPublish = await publishRealCrate(
      work,
      home,
      layout.credential,
      'cargo-ws-utils',
      utilsName,
    );
    expect(utilsPublish.exitCode, `cargo publish --package utils: ${utilsPublish.command}`).toBe(0);

    const corePublish = await publishRealCrate(
      work,
      home,
      layout.credential,
      'cargo-ws-core',
      coreName,
    );
    expect(corePublish.exitCode, `cargo publish --package core: ${corePublish.command}`).toBe(0);

    const appPublish = await publishRealCrate(
      work,
      home,
      layout.credential,
      'cargo-ws-app',
      appName,
    );
    expect(appPublish.exitCode, `cargo publish --package app: ${appPublish.command}`).toBe(0);

    // All 3 members must appear in the sparse index.
    for (const name of [utilsName, coreName, appName]) {
      const indexRes = await rawGetIndex(layout.repoName, admin, name);
      expect(indexRes.status, `${name} is served in the sparse index`).toBe(200);
      expect(
        parseIndex(indexRes.body).find((e) => e.vers === version),
        `an index entry for "${name}"@"${version}"`,
      ).toBeDefined();
    }

    // core's index entry must record its dep on utils.
    const coreIndexRes = await rawGetIndex(layout.repoName, admin, coreName);
    const coreEntry = parseIndex(coreIndexRes.body).find((e) => e.vers === version);
    const coreDeps = coreEntry?.deps as CargoDepEntry[] | undefined;
    expect(
      coreDeps?.find((d) => d.name === utilsName),
      `core declares a dep on "${utilsName}". Got: ${JSON.stringify(coreDeps)}`,
    ).toBeDefined();

    // app's index entry must record deps on both core and utils.
    const appIndexRes = await rawGetIndex(layout.repoName, admin, appName);
    const appEntry = parseIndex(appIndexRes.body).find((e) => e.vers === version);
    const appDeps = appEntry?.deps as CargoDepEntry[] | undefined;
    for (const depName of [coreName, utilsName]) {
      expect(
        appDeps?.find((d) => d.name === depName),
        `app declares a dep on "${depName}". Got: ${JSON.stringify(appDeps)}`,
      ).toBeDefined();
    }
  },
);
