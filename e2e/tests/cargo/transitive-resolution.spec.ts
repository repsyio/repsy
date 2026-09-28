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
 * Transitive dependency resolution against Repsy-served sparse-index metadata (RPS-1721, epic
 * RPS-1712): a graph in one repo -- `root` depends on `mid` (`^1`), `mid` depends on `leaf` (`^1`)
 * -- published one crate at a time with the real `cargo publish` (online, `--no-verify`; a
 * dependency-free crate can be packaged offline, but `mid`/`root` need to resolve an already
 * -published Repsy dependency, which `cargo package --offline` cannot do -- see
 * `protocol-specific.spec.ts`'s HC-2), then resolved by a REAL, separate consumer project with the
 * real `cargo generate-lockfile`/`cargo fetch`. Every crate name is underscore-only
 * (`cargo-raw.ts`'s `crateName` convention, routing around RPS-1212).
 *
 * `root` additionally declares `[target.'cfg(windows)'.dependencies] win_only`, whose crate is also
 * published to the repo. This proves two DIFFERENT, both correct, cargo behaviours side by side:
 *
 *  - `Cargo.lock` is platform-INDEPENDENT: `cargo generate-lockfile` locks `win_only` even though
 *    this runner is not Windows, because a lockfile has to be usable on any platform the crate
 *    supports. This is correct cargo behaviour, asserted as such here -- not a bug.
 *  - `cargo fetch --target <triple>`, in contrast, is platform-SPECIFIC: only a plain `cargo fetch`
 *    with no `--target` downloads every platform's dependencies, so `win_only`'s `.crate` is
 *    correctly absent from `<CARGO_HOME>/registry/cache` after a `--target`-scoped fetch on a
 *    non-Windows host. `<triple>` is read live from `rustc -vV`'s own `host:` line, never hardcoded.
 *
 * Every published crate's served index `cksum` is also checked against the sha256 of its
 * raw-downloaded `.crate` bytes: the server computes this checksum itself
 * (`CargoDigestCalculator`), cargo's publish body carries none.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { RepoType } from '../../src/api/panel-api.js';
import { cargoEnv, renderCargoConfig } from '../../src/clients/cargo.js';
import {
  adminCredential,
  parseIndex,
  rawDownload,
  rawGetIndex,
  sha256Hex,
} from '../../src/clients/cargo-raw.js';
import { clientEnv } from '../../src/clients/client-env.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

test.describe.configure({ timeout: 300_000 });

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../../src/packages/cargo');

interface Layout {
  repoName: string;
  credential: MaterializedCredential;
}

/** A fresh private cargo repo and a fresh `token-rw` deploy token for it, the credential every
 *  publish/consume in this file uses. */
async function newRepoWithToken(seeder: Seeder): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  return {
    repoName: repo.name,
    credential: {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    },
  };
}

async function renderManifest(
  templateName: string,
  destDir: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.mkdir(destDir, { recursive: true });
  await fs.writeFile(path.join(destDir, 'Cargo.toml'), mustache.render(template, view), 'utf8');
}

async function writeLibRs(destDir: string, body = ''): Promise<void> {
  const srcDir = path.join(destDir, 'src');
  await fs.mkdir(srcDir, { recursive: true });
  await fs.writeFile(path.join(srcDir, 'lib.rs'), body, 'utf8');
}

async function publishManifest(
  repoName: string,
  credential: MaterializedCredential,
  work: string,
  home: string,
  name: string,
  version: string,
  label: string,
): Promise<void> {
  await renderCargoConfig(work, repoName);
  const result = await run(
    'cargo',
    ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
    {
      cwd: work,
      env: { ...cargoEnv(home, credential), CARGO_PUBLISH_TIMEOUT: '30' },
      timeoutMs: 60_000,
      redact: credential.password ? [credential.password] : [],
      label,
    },
  );
  expect(result.exitCode, `cargo publish ${name}@${version}: ${result.command}`).toBe(0);
}

/** A dependency-free crate (`Cargo.template.toml`), published as-is. */
async function publishLeafCrate(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
  label: string,
): Promise<void> {
  const { home, work } = await isolatedWorkDir(label);
  await renderManifest('Cargo.template.toml', work, { crateName: name, version });
  await writeLibRs(work, `pub fn marker() -> &'static str { "${name}" }\n`);
  await publishManifest(repoName, credential, work, home, name, version, label);
}

/** A crate with version-range Repsy-registry dependencies (`Cargo.transitive-deps.template.toml`),
 *  optionally under a `[target.'cfg(<cfg>)'.dependencies]` section. */
async function publishCrateWithDeps(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
  deps: readonly { name: string; req: string }[],
  targetDeps: readonly { cfg: string; name: string; req: string }[],
  label: string,
): Promise<void> {
  const { home, work } = await isolatedWorkDir(label);
  await renderManifest('Cargo.transitive-deps.template.toml', work, {
    crateName: name,
    version,
    deps,
    targetDeps,
  });
  await writeLibRs(work, `pub fn marker() -> &'static str { "${name}" }\n`);
  await publishManifest(repoName, credential, work, home, name, version, label);
}

/** The host triple `rustc` itself reports (`rustc -vV`'s `host:` line), read live -- never
 *  hardcoded, so this test runs correctly whatever platform the runner image is built for. */
async function hostTriple(): Promise<string> {
  const { home } = await isolatedWorkDir('cargo-tr-host-triple');
  const result = await run('rustc', ['-vV'], {
    cwd: home,
    env: clientEnv(home, {}, ['RUSTUP_HOME']),
    timeoutMs: 30_000,
    label: 'rustc-vV',
  });
  expect(result.exitCode, `rustc -vV: ${result.command}`).toBe(0);
  const line = result.stdout.split('\n').find((l) => l.startsWith('host: '));
  expect(line, `rustc -vV reports a host triple. Got:\n${result.stdout}`).toBeDefined();
  return line!.slice('host: '.length).trim();
}

/** `<CARGO_HOME>/registry/cache/<registry-hash>/<name>-<version>.crate`, or `undefined` when
 *  `cargo fetch` never downloaded it -- the same file `clients/cargo.ts`'s own (unexported)
 *  `findFetchedCrate` looks for. */
async function fetchedCrateFile(
  cargoHome: string,
  name: string,
  version: string,
): Promise<string | undefined> {
  const cacheDir = path.join(cargoHome, 'registry', 'cache');
  let regDirs: string[];
  try {
    regDirs = await fs.readdir(cacheDir);
  } catch {
    return undefined;
  }
  const wanted = `${name}-${version}.crate`;
  for (const regDir of regDirs) {
    const candidate = path.join(cacheDir, regDir, wanted);
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      // Not in this registry-hash directory; keep looking.
    }
  }
  return undefined;
}

/** The `[[package]]` block of `lockText` (a real `Cargo.lock`) naming `name`, if any -- the same
 *  text-split `tests/cargo/install-add.spec.ts`'s own lockfile assertion uses (Cargo.lock has no
 *  stable machine-readable API in this harness, and its TOML is simple enough not to need one). */
function packageBlock(lockText: string, name: string): string | undefined {
  return lockText.split('[[package]]').find((part) => part.includes(`name = "${name}"`));
}

test(
  'cargo > leaf -> mid -> root resolves the highest ^1 leaf, a platform-specific dep is locked but not target-fetched',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder);
    const admin = adminCredential();

    const leafName = `e2e_${seeder.runId}_tr_leaf`;
    const midName = `e2e_${seeder.runId}_tr_mid`;
    const rootName = `e2e_${seeder.runId}_tr_root`;
    const winOnlyName = `e2e_${seeder.runId}_tr_win_only`;

    // leaf: three versions. ^1 (mid's requirement) is satisfied by 1.0.0 and 1.2.0, not by 2.0.0.
    for (const version of ['1.0.0', '1.2.0', '2.0.0']) {
      await publishLeafCrate(
        layout.repoName,
        layout.credential,
        leafName,
        version,
        `cargo-tr-leaf-${version}`,
      );
    }
    await publishLeafCrate(
      layout.repoName,
      layout.credential,
      winOnlyName,
      '1.0.0',
      'cargo-tr-winonly',
    );

    await publishCrateWithDeps(
      layout.repoName,
      layout.credential,
      midName,
      '1.0.0',
      [{ name: leafName, req: '^1' }],
      [],
      'cargo-tr-mid',
    );
    await publishCrateWithDeps(
      layout.repoName,
      layout.credential,
      rootName,
      '1.0.0',
      [{ name: midName, req: '^1' }],
      [{ cfg: 'windows', name: winOnlyName, req: '^1' }],
      'cargo-tr-root',
    );

    // Every published version's index cksum matches the sha256 of the actually-served .crate bytes.
    const versionsByName: Record<string, readonly string[]> = {
      [leafName]: ['1.0.0', '1.2.0', '2.0.0'],
      [midName]: ['1.0.0'],
      [rootName]: ['1.0.0'],
      [winOnlyName]: ['1.0.0'],
    };
    for (const [name, versions] of Object.entries(versionsByName)) {
      const indexRes = await rawGetIndex(layout.repoName, admin, name);
      expect(indexRes.status, `${name} is served in the sparse index`).toBe(200);
      const entries = parseIndex(indexRes.body);
      for (const version of versions) {
        const entry = entries.find((e) => e.vers === version);
        expect(entry, `an index entry for "${name}"@"${version}"`).toBeDefined();
        const dlRes = await rawDownload(layout.repoName, admin, name, version);
        expect(dlRes.status, `"${name}"@"${version}" downloads`).toBe(200);
        expect(
          entry?.cksum,
          `the index cksum of "${name}"@"${version}" matches the served bytes`,
        ).toBe(sha256Hex(dlRes.body));
      }
    }

    // A separate consumer project depends on root only; cargo itself resolves the rest of the graph.
    const { home, work } = await isolatedWorkDir(`cargo-tr-consumer-${seeder.runId}`);
    await renderManifest('consumer-Cargo.template.toml', work, {
      crateName: rootName,
      version: '1.0.0',
    });
    await writeLibRs(work);
    await renderCargoConfig(work, layout.repoName);
    const env = cargoEnv(home, layout.credential);
    const secrets = layout.credential.password ? [layout.credential.password] : [];

    const lockResult = await run('cargo', ['generate-lockfile'], {
      cwd: work,
      env,
      timeoutMs: 120_000,
      redact: secrets,
      label: 'cargo-tr-generate-lockfile',
    });
    expect(lockResult.exitCode, `cargo generate-lockfile: ${lockResult.command}`).toBe(0);

    const lockText = await fs.readFile(path.join(work, 'Cargo.lock'), 'utf8');
    expect(
      packageBlock(lockText, rootName),
      'Cargo.lock has a package block for root',
    ).toBeDefined();
    expect(packageBlock(lockText, midName), 'Cargo.lock has a package block for mid').toBeDefined();
    const leafBlock = packageBlock(lockText, leafName);
    expect(leafBlock, 'Cargo.lock has a package block for leaf').toBeDefined();
    expect(
      leafBlock,
      'the highest ^1-compatible leaf version is locked (2.0.0 does not satisfy ^1)',
    ).toContain('version = "1.2.0"');

    // A Cargo.lock is platform-INDEPENDENT by design: it holds every platform's dependencies, not
    // just the host's. root's [target.'cfg(windows)'.dependencies] entry is therefore locked too,
    // even on this non-Windows runner -- correct cargo behaviour, not a bug (see this file's header).
    expect(
      packageBlock(lockText, winOnlyName),
      "Cargo.lock is platform-independent: root's cfg(windows) dependency is locked even on a non-Windows host",
    ).toBeDefined();

    // cargo fetch --target <host triple>, in contrast, IS platform-specific: only a plain `cargo
    // fetch` with no --target downloads every platform's dependencies (see this file's header).
    const triple = await hostTriple();
    const fetchResult = await run('cargo', ['fetch', '--target', triple], {
      cwd: work,
      env,
      timeoutMs: 120_000,
      redact: secrets,
      label: 'cargo-tr-fetch-target',
    });
    expect(fetchResult.exitCode, `cargo fetch --target ${triple}: ${fetchResult.command}`).toBe(0);

    const cargoHome = path.join(home, 'cargo');
    expect(
      await fetchedCrateFile(cargoHome, rootName, '1.0.0'),
      'root, the direct dependency, is fetched',
    ).toBeDefined();
    expect(await fetchedCrateFile(cargoHome, midName, '1.0.0'), 'mid is fetched').toBeDefined();
    expect(
      await fetchedCrateFile(cargoHome, leafName, '1.2.0'),
      'leaf 1.2.0, the resolved version, is fetched',
    ).toBeDefined();
    expect(
      await fetchedCrateFile(cargoHome, winOnlyName, '1.0.0'),
      'win_only is NOT fetched for a host triple whose cfg(windows) is false, although it is locked',
    ).toBeUndefined();
  },
);
