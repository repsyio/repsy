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
 * The Cargo commands the panel's own pages advertise (RPS-1486, epic RPS-1473), run by the real
 * `cargo` against a real Repsy: `cargo install <crate> --version <v> --registry repsy` (the crate page's
 * "Install Binary"), `cargo add <crate>@<v> --registry repsy` ("Add Dependency"), and the registry
 * page's `$HOME/.cargo/config.toml` plus `cargo login --registry repsy` (the token is pasted at Cargo's
 * prompt, so it is read from stdin; RPS-1598) (`repsy-frontend/.../cargo-config.component.ts`; `tests/ui/packages/cargo.spec.ts` pins that the
 * page shows those strings, this file proves the strings work). The suite's other specs publish and
 * `cargo fetch` a dependency-free crate; this one builds: the runner image carries `gcc` for it.
 *
 * Everything runs as the panel tells a user: the config in `$HOME/.cargo/config.toml` (`CARGO_HOME`
 * unset, `cargoPanelEnv`), no `--root` unless a test installs twice, and a token from either
 * `CARGO_REGISTRIES_REPSY_TOKEN` or `cargo login`. Every hypothesis was probed live before it was pinned
 * (README "Cargo install, add and login"):
 *
 *  - H1 confirmed: a binary crate (`[[bin]]`) with a Repsy dependency installs, links and runs; the
 *    sparse-index entry is what resolves it (`deps` names the dependency, `features` the feature list
 *    `cargo add --features` validates against, `yanked` what a resolution skips).
 *  - H2 REFUTED (the plan said a yanked version installs when the exact `--version` is given): real
 *    cargo refuses `cargo install` of a yanked version in every spelling (`1.1.0`, `=1.1.0`, `^1.1`),
 *    "cannot install package ..., it has been yanked". Only a dependency in a packaged `Cargo.lock` may
 *    stay yanked, and only under `--locked` (a warning).
 *  - H3 confirmed: `global-credential-providers = ["cargo:token"]` from the panel's config plus
 *    `CARGO_REGISTRIES_REPSY_TOKEN` or the `credentials.toml` that `cargo login` writes are all Repsy
 *    needs; a private repo without a token stops in the client ("no token found", `config.json`
 *    advertises `auth-required`), a wrong one gets a 401.
 *  - H4 confirmed: `cargo search --limit N` sends `per_page=N`; the envelope's `meta.total` gives cargo
 *    its "... and K crates more" line. Search order is the storage order (no sort in the query), so
 *    the pages are compared as sets, never as a sequence.
 *
 * `cargo login <token>` prints "deprecated in favor of reading `<token>` from stdin" (cargo 1.98), so the
 * panel tells users the stdin form (`cargo login --registry repsy`, RPS-1598). Both forms are run.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import {
  cargoPanelEnv,
  type InstallableCrate,
  renderInstallableCrate,
  renderPanelCargoConfig,
} from '../../src/clients/cargo.js';
import {
  adminCredential,
  cargoErrorDetail,
  parseIndex,
  parseSearch,
  rawGetIndex,
  rawSearchPage,
  sha256Hex,
} from '../../src/clients/cargo-raw.js';
import { clientEnv } from '../../src/clients/client-env.js';
import { repoUrl } from '../../src/repo-url.js';
import { isolatedWorkDir, run, type RunResult } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

// A test builds up to five crates with `rustc`; the default 120 s is for a publish and a fetch.
test.describe.configure({ timeout: 240_000 });

interface Registry {
  repoName: string;
  /** `<repo base URL>/<repo>/`, the index URL without its `sparse+`. */
  url: string;
  /** A read-write deploy token of the repo. */
  token: string;
  home: string;
  work: string;
  seeder: Seeder;
}

/** A fresh cargo repo, a read-write deploy token and an isolated `$HOME` whose `.cargo/config.toml`
 *  is the panel's. `privateRepo` false renders the panel's "public repo" variant of the config. */
async function newRegistry(seeder: Seeder, label: string, privateRepo = true): Promise<Registry> {
  const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const { home, work } = await isolatedWorkDir(`cargo-${label}`);
  await renderPanelCargoConfig(home, repo.name, privateRepo);
  return {
    repoName: repo.name,
    url: repoUrl(repo.name, ''),
    token: token.token,
    home,
    work,
    seeder,
  };
}

/** `cargo <args>` in `cwd` as the user of `home`; `token` is `CARGO_REGISTRIES_REPSY_TOKEN` when given.
 *  Every secret that appears in an argument or an output is redacted. */
async function cargo(
  args: readonly string[],
  opts: {
    home: string;
    cwd: string;
    token?: string;
    input?: string;
    secrets?: readonly string[];
  },
): Promise<RunResult> {
  return run('cargo', args, {
    cwd: opts.cwd,
    env: { ...cargoPanelEnv(opts.home, opts.token), CARGO_PUBLISH_TIMEOUT: '30' },
    timeoutMs: 180_000,
    redact: [...(opts.secrets ?? []), ...(opts.token !== undefined ? [opts.token] : [])],
    label: `cargo-${args[0] ?? 'cargo'}`,
    ...(opts.input !== undefined ? { input: opts.input } : {}),
  });
}

/** Publishes `crate` (a directory of its own under `work`) with the token in the environment;
 *  returns its marker and the sha256 of the `.crate` cargo uploaded. */
async function publishCrate(
  reg: Registry,
  crate: InstallableCrate,
): Promise<{ marker: string; cksum: string }> {
  const dir = path.join(reg.work, `src-${crate.crate}-${crate.version}`);
  await fs.mkdir(dir, { recursive: true });
  const { marker } = await renderInstallableCrate(dir, crate);
  const opts = { home: reg.home, cwd: dir, token: reg.token };
  // `cargo publish` leaves no .crate behind: package first, and read the bytes it would upload. (Online:
  // `--offline` cannot resolve a Repsy dependency, see tests/cargo/protocol-specific.spec.ts HC-2.)
  const packaged = await cargo(
    ['package', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
    opts,
  );
  expect(
    packaged.exitCode,
    `cargo package ${crate.crate}@${crate.version}: ${packaged.stderr}`,
  ).toBe(0);
  const bytes = await fs.readFile(
    path.join(dir, 'target', 'package', `${crate.crate}-${crate.version}.crate`),
  );
  const result = await cargo(
    ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
    opts,
  );
  expect(result.exitCode, `cargo publish ${crate.crate}@${crate.version}: ${result.stderr}`).toBe(
    0,
  );
  return { marker, cksum: sha256Hex(bytes) };
}

/** The command of the crate page's "Install Binary" (`cargo-crates-version-detail.component.ts`). */
function installArgs(crate: string, version: string | undefined, ...rest: string[]): string[] {
  return [
    'install',
    crate,
    ...(version !== undefined ? ['--version', version] : []),
    '--registry',
    'repsy',
    ...rest,
  ];
}

/** What an installed binary prints (`main.template.rs`): one `key=value` per line, the last newline trimmed by `run`. */
async function runInstalled(home: string, work: string, binary: string): Promise<string> {
  const result = await run(binary, [], { cwd: work, env: clientEnv(home) });
  expect(result.exitCode, `${binary}: ${result.stderr}`).toBe(0);
  return result.stdout;
}

/** The versions `cargo install --list` shows for `root` (`<crate> v<version> (...)`). */
async function installedList(reg: Registry, root?: string): Promise<string> {
  const result = await cargo(['install', '--list', ...(root ? ['--root', root] : [])], {
    home: reg.home,
    cwd: reg.work,
  });
  expect(result.exitCode, `cargo install --list: ${result.stderr}`).toBe(0);
  return result.stdout;
}

function crateName(reg: Registry, label: string): string {
  return `e2e_${reg.seeder.runId}_${label}`;
}

async function yank(reg: Registry, crate: string, version: string, undo = false): Promise<void> {
  const result = await cargo(
    ['yank', ...(undo ? ['--undo'] : []), '--registry', 'repsy', '--version', version, crate],
    { home: reg.home, cwd: reg.work, token: reg.token },
  );
  expect(result.exitCode, `cargo yank: ${result.stderr}`).toBe(0);
}

test.describe('cargo install and cargo add, as the panel advertises them', () => {
  test(
    'cargo > install: the panel command builds and runs a binary crate that depends on another Repsy crate',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const reg = await newRegistry(seeder, 'install');
      const leaf = crateName(reg, 'leaf');
      const bin = crateName(reg, 'bin');
      await publishCrate(reg, { crate: leaf, version: '1.0.0' });
      const published = await publishCrate(reg, {
        crate: bin,
        version: '1.0.0',
        bin: true,
        deps: [{ name: leaf, req: '^1' }],
      });

      // The index entry cargo resolves from: the dependency, the checksum of the uploaded bytes.
      const indexRes = await rawGetIndex(reg.repoName, adminCredential(), bin);
      const entry = parseIndex(indexRes.body).find((e) => e.vers === '1.0.0');
      expect(entry?.cksum, 'the index cksum is the sha256 of the uploaded .crate').toBe(
        published.cksum,
      );
      expect(entry?.yanked).toBe(false);
      expect(
        (entry?.deps as { name: string; req: string; kind: string }[]).map((d) => [
          d.name,
          d.req,
          d.kind,
        ]),
        'the index entry lists the Repsy dependency',
      ).toEqual([[leaf, '^1', 'normal']]);

      // Literally the panel's command: default CARGO_HOME, so the binary lands in $HOME/.cargo/bin.
      const installed = await cargo(installArgs(bin, '1.0.0'), {
        home: reg.home,
        cwd: reg.work,
        token: reg.token,
      });
      expect(installed.exitCode, `cargo install: ${installed.stderr}`).toBe(0);
      const binary = path.join(reg.home, '.cargo', 'bin', bin);
      expect(await runInstalled(reg.home, reg.work, binary)).toBe(
        `marker=${published.marker}\ndep=1.0.0`,
      );
      expect(await installedList(reg), 'cargo remembers the registry it installed from').toContain(
        `${bin} v1.0.0 (registry \`sparse+${reg.url}\`)`,
      );

      // The same crate by index URL (`--index`, the form of a project with no config file), the
      // token matched to the registry by its index.
      const root = path.join(reg.work, 'by-index');
      const byIndex = await cargo(
        ['install', bin, '--version', '1.0.0', '--index', `sparse+${reg.url}`, '--root', root],
        { home: reg.home, cwd: reg.work, token: reg.token },
      );
      expect(byIndex.exitCode, `cargo install --index: ${byIndex.stderr}`).toBe(0);
      expect(await runInstalled(reg.home, reg.work, path.join(root, 'bin', bin))).toContain(
        `marker=${published.marker}`,
      );
    },
  );

  test('cargo > install: a yanked dependency is skipped, unless the packaged Cargo.lock pins it', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'yanked-dep');
    const leaf = crateName(reg, 'leaf');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: leaf, version: '1.0.0' });
    await publishCrate(reg, { crate: leaf, version: '1.1.0' });
    // `cargo publish` packages the bin crate's Cargo.lock, which pins leaf 1.1.0 (the newest then).
    await publishCrate(reg, {
      crate: bin,
      version: '1.0.0',
      bin: true,
      deps: [{ name: leaf, req: '^1' }],
    });
    const depOf = async (root: string): Promise<string> =>
      (await runInstalled(reg.home, reg.work, path.join(root, 'bin', bin)))
        .split('\n')
        .find((line) => line.startsWith('dep='))!;
    const install = (root: string, ...rest: string[]): Promise<RunResult> =>
      cargo(installArgs(bin, undefined, '--root', root, ...rest), {
        home: reg.home,
        cwd: reg.work,
        token: reg.token,
      });

    // Control: nothing yanked, the highest version satisfying ^1 is chosen.
    const before = path.join(reg.work, 'before');
    expect((await install(before)).exitCode).toBe(0);
    expect(await depOf(before), 'the newest dependency version before the yank').toBe('dep=1.1.0');

    await yank(reg, leaf, '1.1.0');
    const yankedIndex = await rawGetIndex(reg.repoName, adminCredential(), leaf);
    expect(parseIndex(yankedIndex.body).map((e) => [e.vers, e.yanked])).toEqual([
      ['1.0.0', false],
      ['1.1.0', true],
    ]);

    // A fresh resolution reads `yanked` from the index and takes the previous version.
    const after = path.join(reg.work, 'after');
    expect((await install(after)).exitCode).toBe(0);
    expect(await depOf(after), 'the yanked dependency version is skipped').toBe('dep=1.0.0');

    // `--locked` uses the packaged Cargo.lock: a yanked entry stays, with a warning.
    const locked = path.join(reg.work, 'locked');
    const lockedRun = await install(locked, '--locked');
    expect(lockedRun.exitCode, `cargo install --locked: ${lockedRun.stderr}`).toBe(0);
    expect(lockedRun.stderr, 'cargo says the locked dependency is yanked').toContain('is yanked');
    expect(await depOf(locked), 'the packaged lock still pins the yanked version').toBe(
      'dep=1.1.0',
    );
  });

  test('cargo > install: a yanked version is refused in every spelling, the next one is installed, unyank restores it', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'yanked-bin');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: bin, version: '1.0.0', bin: true });
    const second = await publishCrate(reg, { crate: bin, version: '2.0.0', bin: true });
    const install = (version: string | undefined, root: string): Promise<RunResult> =>
      cargo(installArgs(bin, version, '--root', root), {
        home: reg.home,
        cwd: reg.work,
        token: reg.token,
      });

    await yank(reg, bin, '2.0.0');
    // Without a version: the highest one that is not yanked.
    const rootA = path.join(reg.work, 'a');
    expect((await install(undefined, rootA)).exitCode).toBe(0);
    expect(await installedList(reg, rootA)).toContain(`${bin} v1.0.0 `);

    // With the yanked version, exact (the panel's own form) or as a requirement: refused, nothing built.
    for (const version of ['2.0.0', '=2.0.0', '^2']) {
      const root = path.join(reg.work, `refused-${version.replace(/\W/g, '_')}`);
      const refused = await install(version, root);
      expect(refused.exitCode, `cargo install --version ${version} of a yanked version`).not.toBe(
        0,
      );
      expect(refused.stderr).toContain('it has been yanked');
      await expect(fs.stat(path.join(root, 'bin', bin))).rejects.toThrow();
    }

    await yank(reg, bin, '2.0.0', true);
    const rootB = path.join(reg.work, 'b');
    expect((await install('2.0.0', rootB)).exitCode).toBe(0);
    expect(await runInstalled(reg.home, reg.work, path.join(rootB, 'bin', bin))).toBe(
      `marker=${second.marker}`,
    );
  });

  test('cargo > install: a private repo needs a token, a wrong one is rejected, a public one needs none', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'private');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: bin, version: '1.0.0', bin: true });
    const user = await isolatedWorkDir('cargo-private-anon');
    await renderPanelCargoConfig(user.home, reg.repoName);

    // No token: cargo stops before it sends anything more (config.json says auth-required).
    const anonymous = await cargo(installArgs(bin, '1.0.0', '--root', path.join(reg.work, 'r1')), {
      home: user.home,
      cwd: user.work,
    });
    expect(anonymous.exitCode).not.toBe(0);
    expect(anonymous.stderr).toContain('no token found for `repsy`');
    expect(anonymous.stderr, 'the error names the command the panel tells users to run').toContain(
      'cargo login --registry repsy',
    );

    // A token the server does not know: a 401 from Repsy, shown by cargo.
    const wrong = await cargo(installArgs(bin, '1.0.0', '--root', path.join(reg.work, 'r2')), {
      home: user.home,
      cwd: user.work,
      token: 'not-a-deploy-token',
    });
    expect(wrong.exitCode).not.toBe(0);
    expect(wrong.stderr).toContain('token rejected for `repsy`');
    expect(wrong.stderr).toContain('got 401');

    // The public variant of the panel's config (no `[registry]` section), no token at all.
    const pub = await newRegistry(seeder, 'public', false);
    const pubPublished = await publishCrate(pub, { crate: bin, version: '1.0.0', bin: true });
    const pubUser = await isolatedWorkDir('cargo-public-anon');
    await renderPanelCargoConfig(pubUser.home, pub.repoName, false);
    const pubInstall = await cargo(installArgs(bin, '1.0.0'), {
      home: pubUser.home,
      cwd: pubUser.work,
    });
    expect(pubInstall.exitCode, `cargo install (public): ${pubInstall.stderr}`).toBe(0);
    expect(
      await runInstalled(pubUser.home, pubUser.work, path.join(pubUser.home, '.cargo', 'bin', bin)),
    ).toBe(`marker=${pubPublished.marker}`);
    await expect(
      fs.stat(path.join(pubUser.home, '.cargo', 'credentials.toml')),
      'a public install stores no credential',
    ).rejects.toThrow();

    // ...and by index URL, without any config file at all.
    const bare = await isolatedWorkDir('cargo-public-bare');
    const byIndex = await cargo(
      ['install', bin, '--version', '1.0.0', '--index', `sparse+${pub.url}`],
      { home: bare.home, cwd: bare.work },
    );
    expect(byIndex.exitCode, `cargo install --index (public): ${byIndex.stderr}`).toBe(0);
  });

  test('cargo > login: the argument form (deprecated by cargo) stores the deploy token that install and publish then use, logout drops it', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'login');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: bin, version: '1.0.0', bin: true });

    // A different user of the same repo: the config file from the panel, and nothing else.
    const user = await isolatedWorkDir('cargo-login-user');
    await renderPanelCargoConfig(user.home, reg.repoName);
    const credentials = path.join(user.home, '.cargo', 'credentials.toml');
    const opts = { home: user.home, cwd: user.work, secrets: [reg.token] };

    const login = await cargo(['login', '--registry', 'repsy', reg.token], opts);
    expect(login.exitCode, `cargo login: ${login.stderr}`).toBe(0);
    expect(login.stderr).toContain('Login token for `repsy` saved');
    const stored = await fs.readFile(credentials, 'utf8');
    expect(stored, 'the token is stored under the registry name, verbatim').toContain(
      `[registries.repsy]\ntoken = "${reg.token}"`,
    );
    expect((await fs.stat(credentials)).mode & 0o777, 'credentials.toml is owner-only').toBe(0o600);

    // No CARGO_REGISTRIES_REPSY_TOKEN any more: the stored token authenticates the read...
    const install = await cargo(
      installArgs(bin, '1.0.0', '--root', path.join(user.work, 'r')),
      opts,
    );
    expect(install.exitCode, `cargo install: ${install.stderr}`).toBe(0);

    // ...and a write: a new version published with the stored token only.
    const dir = path.join(user.work, 'v2');
    await fs.mkdir(dir, { recursive: true });
    await renderInstallableCrate(dir, { crate: bin, version: '2.0.0', bin: true });
    const publish = await cargo(
      ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
      { ...opts, cwd: dir },
    );
    expect(publish.exitCode, `cargo publish: ${publish.stderr}`).toBe(0);
    const index = await rawGetIndex(reg.repoName, adminCredential(), bin);
    expect(parseIndex(index.body).map((e) => e.vers)).toEqual(['1.0.0', '2.0.0']);

    // logout empties the file and the private repo is closed again.
    const logout = await cargo(['logout', '--registry', 'repsy'], opts);
    expect(logout.exitCode, `cargo logout: ${logout.stderr}`).toBe(0);
    expect(await fs.readFile(credentials, 'utf8')).not.toContain(reg.token);
    const afterLogout = await cargo(
      installArgs(bin, '1.0.0', '--root', path.join(user.work, 'r-after')),
      opts,
    );
    expect(afterLogout.exitCode).not.toBe(0);
    expect(afterLogout.stderr).toContain('no token found for `repsy`');
  });

  test('cargo > login: the panel command reads the token from stdin and stores it the same way, and it opens the search', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'login-stdin');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: bin, version: '1.0.0', bin: true });
    const user = await isolatedWorkDir('cargo-login-stdin-user');
    await renderPanelCargoConfig(user.home, reg.repoName);
    const opts = { home: user.home, cwd: user.work, secrets: [reg.token] };

    const login = await cargo(['login', '--registry', 'repsy'], {
      ...opts,
      input: `${reg.token}\n`,
    });
    expect(login.exitCode, `cargo login (stdin): ${login.stderr}`).toBe(0);
    expect(login.stderr, 'the stdin form is the non-deprecated one').not.toContain('deprecated');
    expect(await fs.readFile(path.join(user.home, '.cargo', 'credentials.toml'), 'utf8')).toContain(
      `token = "${reg.token}"`,
    );
    const search = await cargo(['search', bin, '--registry', 'repsy'], opts);
    expect(search.exitCode, `cargo search: ${search.stderr}`).toBe(0);
    expect(search.stdout).toContain(`${bin} = "1.0.0"`);
  });

  test('cargo > login: a read-only deploy token installs but cannot publish', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'login-ro');
    const bin = crateName(reg, 'bin');
    await publishCrate(reg, { crate: bin, version: '1.0.0', bin: true });
    const readOnly = await seeder.createToken(reg.repoName, { readOnly: true });
    const user = await isolatedWorkDir('cargo-login-ro-user');
    await renderPanelCargoConfig(user.home, reg.repoName);
    const opts = { home: user.home, cwd: user.work, secrets: [readOnly.token] };

    // cargo login never asks the server: it saves any token.
    expect((await cargo(['login', '--registry', 'repsy', readOnly.token], opts)).exitCode).toBe(0);
    const install = await cargo(
      installArgs(bin, '1.0.0', '--root', path.join(user.work, 'r')),
      opts,
    );
    expect(install.exitCode, `cargo install (read-only token): ${install.stderr}`).toBe(0);

    const dir = path.join(user.work, 'v2');
    await fs.mkdir(dir, { recursive: true });
    await renderInstallableCrate(dir, { crate: bin, version: '2.0.0', bin: true });
    const publish = await cargo(
      ['publish', '--registry', 'repsy', '--no-verify', '--allow-dirty'],
      {
        ...opts,
        cwd: dir,
      },
    );
    expect(publish.exitCode, 'a read-only token cannot publish').not.toBe(0);
    expect(publish.stderr).toContain('401');
    const index = await rawGetIndex(reg.repoName, adminCredential(), bin);
    expect(
      parseIndex(index.body).map((e) => e.vers),
      'the refused version was not stored',
    ).toEqual(['1.0.0']);
  });

  test('cargo > add: the panel command writes the dependency and the lockfile holds the published checksum', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'add');
    const lib = crateName(reg, 'lib');
    await publishCrate(reg, { crate: lib, version: '1.0.0', features: ['extra'] });
    const latest = await publishCrate(reg, { crate: lib, version: '1.1.0', features: ['extra'] });
    const project = path.join(reg.work, 'consumer');
    await fs.mkdir(project, { recursive: true });
    const opts = { home: reg.home, cwd: project, token: reg.token };
    expect(
      (await cargo(['init', '--lib', '--vcs', 'none', '--name', 'consumer'], opts)).exitCode,
    ).toBe(0);
    const manifest = path.join(project, 'Cargo.toml');

    // The crate page's "Add Dependency": the exact version, from the registry's index.
    const add = await cargo(['add', `${lib}@1.1.0`, '--registry', 'repsy'], opts);
    expect(add.exitCode, `cargo add: ${add.stderr}`).toBe(0);
    expect(add.stderr, 'cargo lists the features the index entry declares').toContain('- extra');
    expect(await fs.readFile(manifest, 'utf8')).toContain(
      `${lib} = { version = "1.1.0", registry = "repsy" }`,
    );

    const lock = await cargo(['generate-lockfile'], opts);
    expect(lock.exitCode, `cargo generate-lockfile: ${lock.stderr}`).toBe(0);
    const lockText = await fs.readFile(path.join(project, 'Cargo.lock'), 'utf8');
    const block = lockText.split('[[package]]').find((part) => part.includes(`name = "${lib}"`));
    expect(block, 'Cargo.lock has a package block for the crate').toBeDefined();
    expect(block).toContain('version = "1.1.0"');
    expect(block, 'the lock names the registry it resolved from').toContain(
      `source = "sparse+${reg.url}"`,
    );
    const index = await rawGetIndex(reg.repoName, adminCredential(), lib);
    const entry = parseIndex(index.body).find((e) => e.vers === '1.1.0');
    expect(entry?.cksum, 'the index cksum is the sha256 of the uploaded .crate').toBe(latest.cksum);
    expect(block, 'the lock checksum is the published cksum').toContain(
      `checksum = "${latest.cksum}"`,
    );

    // The build itself must be able to use what was locked: `cargo fetch` downloads the crate and
    // verifies its checksum against the lock.
    const fetch = await cargo(['fetch', '--locked'], opts);
    expect(fetch.exitCode, `cargo fetch --locked: ${fetch.stderr}`).toBe(0);
  });

  test('cargo > add: the newest version that is not yanked is added, and only features the index declares', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'add-yank');
    const lib = crateName(reg, 'lib');
    await publishCrate(reg, { crate: lib, version: '1.0.0', features: ['extra'] });
    await publishCrate(reg, { crate: lib, version: '1.1.0', features: ['extra'] });
    const project = path.join(reg.work, 'consumer');
    await fs.mkdir(project, { recursive: true });
    const opts = { home: reg.home, cwd: project, token: reg.token };
    await cargo(['init', '--lib', '--vcs', 'none', '--name', 'consumer'], opts);
    const manifest = path.join(project, 'Cargo.toml');

    // Control: no version given, the newest one.
    const control = await cargo(['add', lib, '--registry', 'repsy'], opts);
    expect(control.exitCode, `cargo add: ${control.stderr}`).toBe(0);
    expect(await fs.readFile(manifest, 'utf8')).toContain(`${lib} = { version = "1.1.0"`);
    await cargo(['remove', lib], opts);

    await yank(reg, lib, '1.1.0');
    const add = await cargo(['add', lib, '--registry', 'repsy', '--features', 'extra'], opts);
    expect(add.exitCode, `cargo add (1.1.0 yanked): ${add.stderr}`).toBe(0);
    expect(await fs.readFile(manifest, 'utf8'), 'the yanked version is skipped').toContain(
      `${lib} = { version = "1.0.0", registry = "repsy", features = ["extra"] }`,
    );

    // A feature the index entry does not list is refused by the client before anything is written.
    await cargo(['remove', lib], opts);
    const bad = await cargo(['add', lib, '--registry', 'repsy', '--features', 'nope'], opts);
    expect(bad.exitCode).not.toBe(0);
    expect(bad.stderr).toContain(`unrecognized feature for crate ${lib}: nope`);
    expect(await fs.readFile(manifest, 'utf8')).not.toContain(lib);
  });

  test('cargo > search: --limit pages the results and the client counts the rest from the envelope total', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'search');
    const prefix = crateName(reg, 'srch');
    const names = ['a', 'b', 'c'].map((suffix) => `${prefix}_${suffix}`);
    for (const name of names) {
      await publishCrate(reg, { crate: name, version: '0.1.0' });
    }
    const opts = { home: reg.home, cwd: reg.work, token: reg.token };
    const search = async (...limit: string[]): Promise<{ found: string[]; more?: string }> => {
      const result = await cargo(['search', prefix, '--registry', 'repsy', ...limit], opts);
      expect(result.exitCode, `cargo search: ${result.stderr}`).toBe(0);
      const lines = result.stdout.split('\n').filter((line) => line.length > 0);
      return {
        found: lines.filter((line) => line.startsWith(prefix)).map((line) => line.split(' ')[0]!),
        more: lines.find((line) => line.startsWith('... and')),
      };
    };

    // Every query: a subset of the crates, named as published.
    const all = await search();
    expect([...all.found].sort()).toEqual(names);
    expect(all.more, 'everything fits the default limit').toBeUndefined();
    const two = await search('--limit', '2');
    expect(two.found).toHaveLength(2);
    expect(two.found.every((name) => names.includes(name))).toBe(true);
    expect(two.more).toContain('... and 1 crates more');
    const one = await search('--limit', '1');
    expect(one.found).toHaveLength(1);
    expect(one.more).toContain('... and 2 crates more');
    const hundred = await search('--limit', '100');
    expect([...hundred.found].sort()).toEqual(names);
    expect(hundred.more).toBeUndefined();

    // No match: an empty result, not an error.
    const none = await cargo(['search', `zz_${prefix}`, '--registry', 'repsy'], opts);
    expect(none.exitCode).toBe(0);
    expect(none.stdout.trim()).toBe('');
  });

  test('cargo > search: the raw pages are disjoint and complete, the page size is clamped, garbage is a 400', async ({
    seeder,
  }) => {
    const reg = await newRegistry(seeder, 'search-raw');
    const prefix = crateName(reg, 'srch');
    const names = ['a', 'b', 'c'].map((suffix) => `${prefix}_${suffix}`);
    for (const name of names) {
      await publishCrate(reg, { crate: name, version: '0.1.0' });
    }
    const admin = adminCredential();
    const page = async (
      perPage: string,
      pageNo?: string,
    ): Promise<{ crates: string[]; total: number }> => {
      const res = await rawSearchPage(reg.repoName, admin, prefix, { perPage, page: pageNo });
      expect(res.status, `GET search per_page=${perPage} page=${pageNo}`).toBe(200);
      const parsed = parseSearch(res.body);
      return {
        crates: (parsed.crates as { name: string }[]).map((c) => c.name),
        total: parsed.meta.total,
      };
    };

    const first = await page('2', '1');
    const second = await page('2', '2');
    const third = await page('2', '3');
    expect([first.crates.length, second.crates.length, third.crates.length]).toEqual([2, 1, 0]);
    expect([first.total, second.total, third.total], 'meta.total is the whole match count').toEqual(
      [3, 3, 3],
    );
    expect([...first.crates, ...second.crates].sort(), 'two pages cover every crate once').toEqual(
      names,
    );

    // A page size above 100 is clamped, not refused: 3 crates fit, so they all come back.
    expect((await page('1000')).crates).toHaveLength(3);

    const garbage = await rawSearchPage(reg.repoName, admin, prefix, { perPage: 'many' });
    expect(garbage.status).toBe(400);
    expect(cargoErrorDetail(garbage.body), 'cargo error envelope').toBeDefined();
  });
});
