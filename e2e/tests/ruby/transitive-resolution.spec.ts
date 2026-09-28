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
 * RPS-1479 (Ruby): transitive dependency resolution against metadata Repsy generates. A gem's
 * dependencies reach the resolver only through what Repsy serves: the `/info/<gem>` compact-index
 * lines (`CompactIndexFormatter.formatDeps`, built from the `Gem::Dependency` entries of the pushed
 * gemspec) and, for `gem install`, the `.gem` file itself. Every test publishes gems with declared
 * dependencies (`ruby-raw.ts`'s `buildGem({ dependencies })`) and consumes a Gemfile that names ONLY the
 * top gem with the REAL `bundle`/`gem`, asserting the resolved graph (`Gemfile.lock`, the installed
 * specifications, the cached `.gem` bytes), not just an exit code. The Gemfile's only source is the Repsy
 * repo, so a dependency can only come from Repsy's own `/info`.
 *
 * Graph used by most tests (all in one Repsy repo): A 1.0.0 -> B `~> 1.0` (plus a development
 * dependency); B 1.0.0 (none), 1.1.0 -> C `~> 1.0`, 2.0.0 (none); C 1.0.0, 1.3.0, 2.0.0.
 *
 * Probed live (each answer below is what a running stack served, not read from source):
 *  - `/info/<A>` carries `<B>:~> 1.0`; a multi-clause requirement is `>= 1.0&< 2.0`; a development
 *    dependency is never listed; a platform gem's line is `<version>-<platform> <deps>|checksum:...`.
 *  - `bundle install` locks A 1.0.0, B 1.1.0 (the highest `~> 1.0`, not 2.0.0) and C 1.3.0 (a dependency
 *    of B 1.1.0 only: per-version dependency lines are honoured), with the published sha256 of every gem
 *    in the lock's CHECKSUMS.
 *  - `gem install` of A installs the same graph. `quick/Marshal.4.8/*.gemspec.rz` carries the platform of
 *    a platform gem, and resolves a multi-segment platform such as `x86_64-linux` (RPS-1553, fixed):
 *    `gem install` of a platform gem, directly and as a dependency, is the last test of the `describe`
 *    block below. It also now carries the gem's real runtime dependencies (RPS-1554), and the legacy
 *    `GET /api/v1/dependencies` route `gem dependency --remote` calls has a route too (RPS-1554/RPS-1724)
 *    -- see this file's LAST `describe` block for both, never pinned in this one.
 *  - A yanked B version is skipped by a fresh resolution (RPS-1235). A lock that already names a
 *    yanked version cannot be installed on a fresh machine: Bundler looks locked versions up in the
 *    same compact index, which omits yanked versions, exactly as it does against rubygems.org.
 *  - The pinned toolchain resolves against the RUNNER's platform: `BUNDLE_FORCE_RUBY_PLATFORM=false`
 *    prefers a native platform gem, the harness default (true) the pure-ruby one.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { bundleEnv, gemEnv } from '../../src/clients/ruby.js';
import {
  adminCredential,
  type BuiltGem,
  buildGem,
  type GemDependencySpec,
  gemFilename,
  gemspecRzRelPath,
  infoRelPath,
  parseInfo,
  rawGet,
  rawGetDependencies,
  rawHead,
  rawPublish,
  rawYank,
} from '../../src/clients/ruby-raw.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

const CLIENT_TIMEOUT_MS = 90_000;

type Gems = Record<string, BuiltGem>;

/** One published gem: `dependencies` are what its gemspec (and so `/info`) declares. */
async function publishGem(
  repoName: string,
  name: string,
  version: string,
  dependencies: GemDependencySpec[] = [],
  platform?: string,
): Promise<BuiltGem> {
  const built = await buildGem({ name, version, dependencies, platform });
  const res = await rawPublish(repoName, adminCredential(), built.bytes);
  expect(res.status, `publish ${name} ${version}: ${res.body.toString('utf8')}`).toBe(200);
  return built;
}

interface Graph {
  a: string;
  b: string;
  c: string;
  gems: Gems;
}

/** The graph of the header comment, in the repo `repoName`. */
async function publishGraph(repoName: string, runId: string): Promise<Graph> {
  const a = `e2e_${runId}_tr_a`;
  const b = `e2e_${runId}_tr_b`;
  const c = `e2e_${runId}_tr_c`;
  const gems: Gems = {};
  for (const v of ['1.0.0', '1.3.0', '2.0.0']) {
    gems[`c${v}`] = await publishGem(repoName, c, v);
  }
  gems['b1.0.0'] = await publishGem(repoName, b, '1.0.0');
  gems['b1.1.0'] = await publishGem(repoName, b, '1.1.0', [{ name: c, requirement: '~> 1.0' }]);
  gems['b2.0.0'] = await publishGem(repoName, b, '2.0.0');
  gems['a1.0.0'] = await publishGem(repoName, a, '1.0.0', [
    { name: b, requirement: '~> 1.0' },
    { name: `e2e_${runId}_tr_devonly`, requirement: '>= 0', type: 'development' },
  ]);
  return { a, b, c, gems };
}

interface LockFile {
  specs: Record<string, { version: string; dependencies: Record<string, string> }>;
  platforms: string[];
  dependencies: string[];
  checksums: Record<string, string>;
}

/** `Gemfile.lock` -> the pieces the tests assert; the GEM section's `    name (version)` lines with
 *  their `      dep (requirement)` children, PLATFORMS, DEPENDENCIES and CHECKSUMS (keyed `name version`). */
function parseLock(text: string): LockFile {
  const lock: LockFile = { specs: {}, platforms: [], dependencies: [], checksums: {} };
  let section = '';
  let current: string | undefined;
  for (const line of text.split('\n')) {
    if (line.length === 0) {
      continue;
    }
    if (!line.startsWith(' ')) {
      section = line.trim();
      current = undefined;
      continue;
    }
    if (section === 'GEM') {
      const spec = /^ {4}(\S+) \((.+)\)$/.exec(line);
      const dep = /^ {6}(\S+) \((.+)\)$/.exec(line);
      if (spec) {
        current = spec[1];
        lock.specs[current] = { version: spec[2], dependencies: {} };
      } else if (dep && current) {
        lock.specs[current].dependencies[dep[1]] = dep[2];
      }
    } else if (section === 'PLATFORMS') {
      lock.platforms.push(line.trim());
    } else if (section === 'DEPENDENCIES') {
      lock.dependencies.push(line.trim());
    } else if (section === 'CHECKSUMS') {
      const sum = /^ {2}(\S+) \((.+)\) sha256=([0-9a-f]+)$/.exec(line);
      if (sum) {
        lock.checksums[`${sum[1]} ${sum[2]}`] = sum[3];
      }
    }
  }
  return lock;
}

interface Workspace {
  home: string;
  work: string;
}

interface BundleRun extends Workspace {
  exitCode: number;
  stdout: string;
  stderr: string;
  bundlePath: string;
}

async function bundle(
  label: string,
  repoName: string,
  opts: {
    gems?: string[];
    credential?: MaterializedCredential;
    workspace?: Workspace;
    args?: string[];
    extraEnv?: Record<string, string>;
  },
): Promise<BundleRun> {
  const ws = opts.workspace ?? (await isolatedWorkDir(label));
  if (opts.gems) {
    // The Repsy repo is the ONLY source: a dependency can only come from Repsy's own /info.
    const gemfile =
      `source "${repoUrl(repoName)}" do\n` +
      opts.gems.map((name) => `  gem "${name}"\n`).join('') +
      'end\n';
    await fs.writeFile(path.join(ws.work, 'Gemfile'), gemfile, 'utf8');
  }
  const credential = opts.credential ?? {};
  const processEnv = { ...bundleEnv(ws.home, ws.work, credential, repoName), ...opts.extraEnv };
  const result = await run('bundle', opts.args ?? ['install', '--verbose'], {
    cwd: ws.work,
    env: processEnv,
    timeoutMs: CLIENT_TIMEOUT_MS,
    redact: credential.password ? [credential.password] : [],
    label,
  });
  return {
    ...ws,
    exitCode: result.exitCode,
    stdout: result.stdout,
    stderr: result.stderr,
    bundlePath: processEnv.BUNDLE_PATH as string,
  };
}

async function readLock(work: string): Promise<LockFile> {
  return parseLock(await fs.readFile(path.join(work, 'Gemfile.lock'), 'utf8'));
}

/** A fresh machine that has only the lockfile of `from`. */
async function freshWorkspaceWithLock(label: string, from: Workspace): Promise<Workspace> {
  const ws = await isolatedWorkDir(label);
  await fs.copyFile(path.join(from.work, 'Gemfile.lock'), path.join(ws.work, 'Gemfile.lock'));
  return ws;
}

/** `name-version[-platform]` of every gem `bundle install` installed under BUNDLE_PATH. */
async function installedByBundler(bundlePath: string): Promise<string[]> {
  const rubyDir = path.join(bundlePath, 'ruby');
  const found: string[] = [];
  for (const abi of await fs.readdir(rubyDir).catch(() => [])) {
    for (const file of await fs
      .readdir(path.join(rubyDir, abi, 'specifications'))
      .catch(() => [])) {
      found.push(file.replace(/\.gemspec$/, ''));
    }
  }
  return found.sort();
}

async function cachedGem(bundlePath: string, filename: string): Promise<Buffer> {
  const rubyDir = path.join(bundlePath, 'ruby');
  const [abi] = await fs.readdir(rubyDir);
  return fs.readFile(path.join(rubyDir, abi, 'cache', filename));
}

async function localPlatform(): Promise<string> {
  const ws = await isolatedWorkDir('ruby-transitive-platform');
  const result = await run('ruby', ['-e', 'puts Gem::Platform.local.to_s'], {
    cwd: ws.work,
    env: gemEnv(ws.home, {}),
    timeoutMs: 30_000,
    label: 'ruby-local-platform',
  });
  expect(result.exitCode, result.command).toBe(0);
  return result.stdout.trim();
}

async function infoLines(repoName: string, credential: MaterializedCredential, gem: string) {
  const res = await rawGet(repoName, credential, infoRelPath(gem));
  expect(res.status, `GET /info/${gem}`).toBe(200);
  return parseInfo(res.body);
}

/** One entry of `/api/v1/dependencies`' decoded Marshal array (RPS-1554/RPS-1724): the gem's
 *  runtime dependencies only, as `[name, requirement]` pairs. */
interface DecodedDependencyEntry {
  name: string;
  number: string;
  platform: string;
  dependencies: [string, string][];
}

/** The minimal shape a `quick/Marshal.4.8/*.gemspec.rz` decodes to once `decodeMarshalViaRuby`
 *  (below) has extracted it from the real `Gem::Specification`. */
interface DecodedGemspec {
  name: string;
  version: string;
  platform: string;
  dependencies: [string, string][];
}

/**
 * Decodes a Ruby Marshal 4.8 byte string INSIDE the Ruby runner container -- never a hand-rolled TS
 * Marshal parser (`ruby-raw.ts`'s file header). The bytes are written to a file in the invocation's
 * own work dir and read back by a `ruby -e` one-liner that `Marshal.load`s them (`require
 * "rubygems"` first, so a `Gem::Specification` -- the gemspec.rz payload -- deserialises as itself,
 * not an opaque struct Marshal can't resolve) and prints the result as JSON, which this function
 * parses. A `Gem::Specification` is not directly JSON-able, so the script reduces it to
 * `{name, version, platform, dependencies}` first, `dependencies` being its RUNTIME `Gem::Dependency`
 * entries only, as `[name, requirement.to_s]` pairs -- the same shape `/api/v1/dependencies` itself
 * decodes to, so both callers below share one return type.
 */
async function decodeMarshalViaRuby(label: string, bytes: Buffer): Promise<unknown> {
  const ws = await isolatedWorkDir(label);
  const file = path.join(ws.work, 'marshal.bin');
  await fs.writeFile(file, bytes);
  const script = [
    'require "json"',
    'require "rubygems"',
    'data = Marshal.load(File.binread(ARGV[0]))',
    'if data.is_a?(Gem::Specification)',
    '  deps = data.dependencies.select { |d| d.type == :runtime }' +
      '.map { |d| [d.name, d.requirement.to_s] }',
    '  data = { name: data.name, version: data.version.to_s, ' +
      'platform: data.platform.to_s, dependencies: deps }',
    'end',
    'puts JSON.generate(data)',
  ].join('; ');
  const result = await run('ruby', ['-e', script, file], {
    cwd: ws.work,
    env: gemEnv(ws.home, {}),
    timeoutMs: 30_000,
    label,
  });
  expect(result.exitCode, `${label} (Marshal decode in the ruby runner): ${result.stderr}`).toBe(0);
  return JSON.parse(result.stdout.trim());
}

/** `GET /api/v1/dependencies` decoded: `rawGetDependencies` (`ruby-raw.ts`) then
 *  `decodeMarshalViaRuby`, asserting the `200 application/octet-stream` envelope first. */
async function getDependencies(
  repoName: string,
  credential: MaterializedCredential,
  gems: string[],
): Promise<DecodedDependencyEntry[]> {
  const res = await rawGetDependencies(repoName, credential, gems);
  expect(res.status, `GET /api/v1/dependencies?gems=${gems.join(',')}`).toBe(200);
  expect(res.contentType, 'served as application/octet-stream, not text/plain').toBe(
    'application/octet-stream',
  );
  return (await decodeMarshalViaRuby(
    'ruby-dependencies-decode',
    res.body,
  )) as DecodedDependencyEntry[];
}

/** `gem install` of `gemName` from the Repsy repo only (never rubygems.org); what got installed is read back. */
async function gemInstall(
  label: string,
  repoName: string,
  gemName: string,
  extraArgs: string[] = [],
) {
  const ws = await isolatedWorkDir(label);
  const result = await run(
    'gem',
    [
      'install',
      '--clear-sources',
      '--source',
      repoUrl(repoName),
      gemName,
      '--no-document',
      '--verbose',
      ...extraArgs,
    ],
    { cwd: ws.work, env: gemEnv(ws.home, {}), timeoutMs: CLIENT_TIMEOUT_MS, label },
  );
  const installed = await fs
    .readdir(path.join(ws.home, 'gems', 'gems'))
    .then((names) => names.sort())
    .catch(() => []);
  return { ...result, installed };
}

test.describe('ruby > transitive resolution (RPS-1479)', () => {
  test(
    'bundle install of A alone resolves B and C from Repsy /info and locks the whole graph',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      // A private repo and a read-only deploy token: every request for a DEPENDENCY's metadata
      // (info/B, info/C) and file has to carry the credential too.
      const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: true });
      const credential: MaterializedCredential = {
        transport: 'basic',
        username: token.username,
        password: token.token,
        kind: 'token',
      };
      const { a, b, c, gems } = await publishGraph(repo.name, seeder.runId);

      // What Repsy serves: the dependency lines resolvers read.
      const infoA = await infoLines(repo.name, credential, a);
      expect(infoA).toHaveLength(1);
      expect(
        infoA[0].dependenciesRaw,
        'the runtime dependency only, the development one is left out',
      ).toBe(`${b}:~> 1.0`);
      expect(infoA[0].checksum).toBe(gems['a1.0.0'].sha256Hex);
      const infoB = Object.fromEntries(
        (await infoLines(repo.name, credential, b)).map((l) => [l.version, l.dependenciesRaw]),
      );
      expect(infoB, 'a dependency line belongs to ONE version').toEqual({
        '1.0.0': '',
        '1.1.0': `${c}:~> 1.0`,
        '2.0.0': '',
      });

      const result = await bundle('ruby-transitive-install', repo.name, { gems: [a], credential });
      expect(result.exitCode, `bundle install\n${result.stderr}`).toBe(0);
      expect(result.stdout, 'B was looked up on Repsy').toContain(`/info/${b}`);
      expect(result.stdout, 'C, a dependency of a dependency, was too').toContain(`/info/${c}`);
      expect(result.stdout, 'no gemspec.rz request is needed to resolve').not.toContain(
        'gemspec.rz',
      );

      const lock = await readLock(result.work);
      expect(lock.dependencies, 'the Gemfile names A only').toEqual([`${a}!`]);
      expect(lock.platforms).toEqual(['ruby']);
      expect(
        lock.specs,
        'highest B matching ~> 1.0 is 1.1.0 (not 2.0.0), and it brings C 1.3.0',
      ).toEqual({
        [a]: { version: '1.0.0', dependencies: { [b]: '~> 1.0' } },
        [b]: { version: '1.1.0', dependencies: { [c]: '~> 1.0' } },
        [c]: { version: '1.3.0', dependencies: {} },
      });
      expect(lock.checksums).toEqual({
        [`${a} 1.0.0`]: gems['a1.0.0'].sha256Hex,
        [`${b} 1.1.0`]: gems['b1.1.0'].sha256Hex,
        [`${c} 1.3.0`]: gems['c1.3.0'].sha256Hex,
      });

      expect(await installedByBundler(result.bundlePath)).toEqual([
        `${a}-1.0.0`,
        `${b}-1.1.0`,
        `${c}-1.3.0`,
      ]);
      expect(
        (await cachedGem(result.bundlePath, gemFilename(b, '1.1.0'))).equals(gems['b1.1.0'].bytes),
        'the dependency Bundler downloaded is byte-identical to what was published',
      ).toBe(true);
    },
  );

  test('the lockfile is replayed: --frozen twice, on a fresh machine, and a newer B changes nothing until bundle update', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const { a, b, c, gems } = await publishGraph(repo.name, seeder.runId);

    const first = await bundle('ruby-transitive-first', repo.name, { gems: [a] });
    expect(first.exitCode, first.stderr).toBe(0);
    const locked = await readLock(first.work);

    // The second run of the same project, frozen: nothing may change.
    const frozen = await bundle('ruby-transitive-frozen', repo.name, {
      workspace: first,
      extraEnv: { BUNDLE_FROZEN: 'true' },
    });
    expect(frozen.exitCode, `bundle install --frozen\n${frozen.stderr}`).toBe(0);
    expect(await readLock(first.work), 'a frozen install leaves the lock as it was').toEqual(
      locked,
    );

    // A CI machine: only Gemfile and Gemfile.lock, an empty gem home, frozen.
    const ci = await freshWorkspaceWithLock('ruby-transitive-ci', first);
    const replay = await bundle('ruby-transitive-replay', repo.name, {
      gems: [a],
      workspace: ci,
      extraEnv: { BUNDLE_FROZEN: 'true' },
    });
    expect(replay.exitCode, `frozen install on a fresh machine\n${replay.stderr}`).toBe(0);
    expect(await installedByBundler(replay.bundlePath)).toEqual([
      `${a}-1.0.0`,
      `${b}-1.1.0`,
      `${c}-1.3.0`,
    ]);
    expect(
      (await cachedGem(replay.bundlePath, gemFilename(c, '1.3.0'))).equals(gems['c1.3.0'].bytes),
    ).toBe(true);

    // A newer B that satisfies A's requirement does not move a locked project ...
    await publishGem(repo.name, b, '1.2.0');
    const again = await bundle('ruby-transitive-again', repo.name, { workspace: first });
    expect(again.exitCode, again.stderr).toBe(0);
    expect((await readLock(first.work)).specs[b].version).toBe('1.1.0');

    // ... until bundle update asks for it, and B 1.2.0 (no dependency) drops C from the graph.
    const update = await bundle('ruby-transitive-update', repo.name, {
      workspace: first,
      args: ['update', b],
    });
    expect(update.exitCode, update.stderr).toBe(0);
    const updated = await readLock(first.work);
    expect(updated.specs[b].version).toBe('1.2.0');
    expect(updated.specs[b].dependencies, 'B 1.2.0 declares no dependency').toEqual({});
    expect(Object.keys(updated.specs).sort(), 'so C is no longer part of the graph').toEqual(
      [a, b].sort(),
    );
  });

  test('the requirement of a dependency selects the version: /info line and resolved version', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const b = `e2e_${seeder.runId}_rq_b`;
    for (const v of ['1.0.0', '1.1.0', '2.0.0', '3.0.0.pre1']) {
      await publishGem(repo.name, b, v);
    }

    // [requirement, /info spelling, resolved B]: multi-clause requirements are joined with '&'; a
    // prerelease (3.0.0.pre1) is never chosen unless asked for.
    const rows: [string | string[], string, string][] = [
      [['>= 1.0', '< 2.0'], '>= 1.0&< 2.0', '1.1.0'],
      ['= 2.0.0', '= 2.0.0', '2.0.0'],
      ['< 1.1', '< 1.1', '1.0.0'],
      ['~> 1.0.0', '~> 1.0.0', '1.0.0'],
      ['>= 1.1', '>= 1.1', '2.0.0'],
    ];
    for (const [index, [requirement, spelling, expected]] of rows.entries()) {
      const a = `e2e_${seeder.runId}_rq_a${index}`;
      await publishGem(repo.name, a, '1.0.0', [{ name: b, requirement }]);
      const [line] = await infoLines(repo.name, {}, a);
      expect(line.dependenciesRaw, `/info line of a gem requiring ${spelling}`).toBe(
        `${b}:${spelling}`,
      );

      const result = await bundle(`ruby-transitive-rq${index}`, repo.name, { gems: [a] });
      expect(result.exitCode, `${spelling}: ${result.stderr}`).toBe(0);
      const lock = await readLock(result.work);
      expect(lock.specs[b]?.version, `B resolved for "${spelling}"`).toBe(expected);
    }
  });

  test('an unsatisfiable or missing dependency fails the resolution and installs nothing', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const b = `e2e_${seeder.runId}_ng_b`;
    await publishGem(repo.name, b, '1.0.0');
    const tooNew = `e2e_${seeder.runId}_ng_a1`;
    const missing = `e2e_${seeder.runId}_ng_a2`;
    const absent = `e2e_${seeder.runId}_ng_absent`;
    await publishGem(repo.name, tooNew, '1.0.0', [{ name: b, requirement: '~> 3.0' }]);
    await publishGem(repo.name, missing, '1.0.0', [{ name: absent, requirement: '>= 0' }]);

    for (const [gem, dependency] of [
      [tooNew, b],
      [missing, absent],
    ]) {
      const result = await bundle(`ruby-transitive-neg-${gem}`, repo.name, { gems: [gem] });
      expect(result.exitCode, `${gem} cannot resolve`).not.toBe(0);
      expect(result.stderr, 'the message names the dependency that could not be found').toContain(
        dependency,
      );
      await expect(
        fs.stat(path.join(result.work, 'Gemfile.lock')),
        'no lock written',
      ).rejects.toThrow();
      expect(await installedByBundler(result.bundlePath), 'nothing installed').toEqual([]);
    }
  });

  test('a yanked B version is skipped by a fresh resolution; a lock naming it cannot be replayed (RPS-1235)', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const { a, b, c } = await publishGraph(repo.name, seeder.runId);
    const admin = adminCredential();

    const before = await bundle('ruby-transitive-pre-yank', repo.name, { gems: [a] });
    expect(before.exitCode, before.stderr).toBe(0);
    expect((await readLock(before.work)).specs[b].version).toBe('1.1.0');

    const yank = await rawYank(repo.name, admin, { gemName: b, version: '1.1.0' });
    expect(yank.status, yank.body.toString('utf8')).toBe(200);
    expect((await infoLines(repo.name, admin, b)).map((l) => l.version)).toEqual([
      '1.0.0',
      '2.0.0',
    ]);

    // A fresh resolution falls back to 1.0.0, which has no dependency: C leaves the graph.
    const fresh = await bundle('ruby-transitive-post-yank', repo.name, { gems: [a] });
    expect(fresh.exitCode, fresh.stderr).toBe(0);
    const lock = await readLock(fresh.work);
    expect(lock.specs[b].version).toBe('1.0.0');
    expect(Object.keys(lock.specs), 'C came with the yanked 1.1.0 only').not.toContain(c);
    expect(await installedByBundler(fresh.bundlePath)).toEqual([`${a}-1.0.0`, `${b}-1.0.0`]);

    // The lock made BEFORE the yank names 1.1.0: a machine without it installed cannot get it, as
    // Bundler looks locked versions up in the compact index that no longer lists it.
    const ci = await freshWorkspaceWithLock('ruby-transitive-stale-lock', before);
    const replay = await bundle('ruby-transitive-stale-replay', repo.name, {
      gems: [a],
      workspace: ci,
      extraEnv: { BUNDLE_FROZEN: 'true' },
    });
    expect(replay.exitCode, 'the yanked version is no longer resolvable from a lock').not.toBe(0);
    expect(replay.stderr).toContain(`${b} (1.1.0)`);
  });

  test('gem install of A alone installs B and C, and skips a yanked B', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const { a, b, c } = await publishGraph(repo.name, seeder.runId);
    const source = repoUrl(repo.name);

    const install = async (label: string) => {
      const ws = await isolatedWorkDir(label);
      // --clear-sources first: the Repsy repo is the only source, never rubygems.org.
      const result = await run(
        'gem',
        ['install', '--clear-sources', '--source', source, a, '--no-document', '--verbose'],
        {
          cwd: ws.work,
          env: gemEnv(ws.home, {}),
          timeoutMs: CLIENT_TIMEOUT_MS,
          label,
        },
      );
      const installed = await fs
        .readdir(path.join(ws.home, 'gems', 'gems'))
        .then((names) => names.sort())
        .catch(() => []);
      return { ...result, installed };
    };

    const first = await install('ruby-transitive-gem-install');
    expect(first.exitCode, `gem install: ${first.stderr}`).toBe(0);
    expect(first.stdout).toContain('3 gems installed');
    expect(first.installed, 'B ~> 1.0 resolves to 1.1.0, whose dependency C to 1.3.0').toEqual([
      `${a}-1.0.0`,
      `${b}-1.1.0`,
      `${c}-1.3.0`,
    ]);

    const yank = await rawYank(repo.name, adminCredential(), { gemName: b, version: '1.1.0' });
    expect(yank.status).toBe(200);
    const second = await install('ruby-transitive-gem-install-yanked');
    expect(second.exitCode, `gem install after the yank: ${second.stderr}`).toBe(0);
    expect(second.installed).toEqual([`${a}-1.0.0`, `${b}-1.0.0`]);
  });

  test('a platform gem is a candidate only where Bundler asks for the runner platform', async ({
    seeder,
  }) => {
    const platform = await localPlatform();
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const a = `e2e_${seeder.runId}_pf_a`;
    const b = `e2e_${seeder.runId}_pf_b`;
    const c = `e2e_${seeder.runId}_pf_c`;
    await publishGem(repo.name, b, '1.0.0');
    const native = await publishGem(repo.name, b, '1.5.0', [], platform);
    await publishGem(repo.name, a, '1.0.0', [{ name: b, requirement: '~> 1.0' }]);
    // A platform gem that itself has a dependency.
    await publishGem(repo.name, c, '1.0.0', [{ name: b, requirement: '= 1.0.0' }], platform);

    const infoB = await infoLines(repo.name, {}, b);
    expect(infoB.map((l) => `${l.version}-${l.platform}`).sort()).toEqual([
      '1.0.0-ruby',
      `1.5.0-${platform}`,
    ]);
    const [infoC] = await infoLines(repo.name, {}, c);
    expect(infoC).toMatchObject({ version: '1.0.0', platform, dependenciesRaw: `${b}:= 1.0.0` });

    // The harness default (BUNDLE_FORCE_RUBY_PLATFORM=true): pure-ruby gems only.
    const pure = await bundle('ruby-transitive-pure', repo.name, { gems: [a] });
    expect(pure.exitCode, pure.stderr).toBe(0);
    const pureLock = await readLock(pure.work);
    expect(pureLock.platforms).toEqual(['ruby']);
    expect(pureLock.specs[b].version).toBe('1.0.0');

    // The runner's own platform: the highest B that fits ~> 1.0 is the platform gem.
    const nativeRun = await bundle('ruby-transitive-native', repo.name, {
      gems: [a],
      extraEnv: { BUNDLE_FORCE_RUBY_PLATFORM: 'false' },
    });
    expect(nativeRun.exitCode, nativeRun.stderr).toBe(0);
    const nativeLock = await readLock(nativeRun.work);
    expect(nativeLock.platforms).toEqual([platform]);
    expect(nativeLock.specs[b].version).toBe(`1.5.0-${platform}`);
    expect(Object.keys(nativeLock.specs).sort()).toEqual([a, b].sort());
    expect(nativeRun.stdout).toContain(`Installing ${b} 1.5.0 (${platform})`);
    expect(
      (await cachedGem(nativeRun.bundlePath, gemFilename(b, '1.5.0', platform))).equals(
        native.bytes,
      ),
    ).toBe(true);

    // Asking for the platform gem that depends on B = 1.0.0 (pure ruby only for 1.0.0 here).
    const withDep = await bundle('ruby-transitive-platform-dep', repo.name, {
      gems: [c],
      extraEnv: { BUNDLE_FORCE_RUBY_PLATFORM: 'false' },
    });
    expect(withDep.exitCode, withDep.stderr).toBe(0);
    const depLock = await readLock(withDep.work);
    expect(depLock.specs[c].dependencies).toEqual({ [b]: '= 1.0.0' });
    expect(depLock.specs[b].version).toBe('1.0.0');
  });

  test('gem install of a multi-segment platform gem works directly and as a dependency (RPS-1553)', async ({
    seeder,
  }) => {
    const platform = await localPlatform();
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const a = `e2e_${seeder.runId}_gi_a`;
    const b = `e2e_${seeder.runId}_gi_b`;
    const c = `e2e_${seeder.runId}_gi_c`;
    // B: a pure 1.0.0 and, of the higher 1.5.0, a platform gem only. A needs B ~> 1.0 (so the
    // platform gem is the one `gem install` must fetch); C is a platform gem of its own.
    await publishGem(repo.name, b, '1.0.0');
    await publishGem(repo.name, b, '1.5.0', [], platform);
    await publishGem(repo.name, a, '1.0.0', [{ name: b, requirement: '~> 1.0' }]);
    await publishGem(repo.name, c, '1.0.0', [], platform);

    // A platform of another OS the runner is not: only the route and the bytes are probed for it.
    await publishGem(repo.name, c, '2.0.0', [], 'arm64-darwin');

    // The gemspec route answers for a multi-segment platform, and the gemspec carries it.
    for (const [version, p] of [
      ['1.0.0', platform],
      ['2.0.0', 'arm64-darwin'],
    ]) {
      const rel = `quick/Marshal.4.8/${c}-${version}-${p}.gemspec.rz`;
      const res = await rawGet(repo.name, {}, rel);
      expect(res.status, `GET ${rel}`).toBe(200);
      expect(zlib.inflateSync(res.body).toString('latin1'), `${p} in the gemspec`).toContain(p);
      expect((await rawHead(repo.name, {}, rel)).status, `HEAD ${rel}`).toBe(200);
    }

    const direct = await gemInstall('ruby-platform-gem-install', repo.name, c);
    expect(direct.exitCode, `gem install ${c}: ${direct.stderr}`).toBe(0);
    expect(direct.installed).toEqual([`${c}-1.0.0-${platform}`]);

    const asDependency = await gemInstall('ruby-platform-gem-install-dep', repo.name, a);
    expect(asDependency.exitCode, `gem install ${a}: ${asDependency.stderr}`).toBe(0);
    expect(asDependency.installed, 'B ~> 1.0 resolves to the platform 1.5.0').toEqual([
      `${a}-1.0.0`,
      `${b}-1.5.0-${platform}`,
    ]);
  });
});

/**
 * RPS-1724 ("happy flow 1"): the LEGACY `GET /api/v1/dependencies` route (`RubyMarshalWriter
 * .dumpDependencies`, `AbstractRubyDependenciesHandler`), which `gem dependency --remote` and some
 * third-party tools still call -- never Bundler 2.x, which resolves through the compact index
 * (`/info`, proven above) and never this route. Repsy Cloud implements the SAME route with its own,
 * separate handler (`RubyGemDependenciesStubProtocolMethodHandler`); this file is the OS-side ground
 * truth a later Cloud-repo batch diffs Cloud's implementation against, so every assertion here was
 * probed against a running stack, never assumed from source.
 *
 * Probed live:
 *  - `gem dependency <gem> --remote` prints the runtime dependency only; it has NO `--development`
 *    flag at all (`gem help dependency`: only `--version`/`-v`, `--platform`, `--prerelease`,
 *    `--reverse-dependencies`/`-R`, `--pipe`), so a development dependency is never requestable
 *    through it, by any flag; `-v <version>` narrows to that one version's own dependencies.
 *  - The route's Marshal payload is an Array of `{name:, number:, platform:, dependencies:}`
 *    Hashes, one per NON-YANKED published version across the requested names -- RUNTIME
 *    dependencies only, `[[depName, depRequirement]]`. A multi-clause requirement is spelled with
 *    Ruby's own comma-space join (`">= 1.0, < 2.0"`), NOT `/info`'s `&`-joined form
 *    (`CompactIndexFormatter` replaces `", "` with `"&"`; the legacy route's `RubyMarshalWriter`
 *    does not).
 *  - An unknown gem name, an empty `?gems=`, and no `gems` param at all each contribute nothing:
 *    `200`, an empty Marshal array -- never an error.
 *  - A yanked version is omitted, same as `/info`.
 *  - Served as `200 application/octet-stream` (never `text/plain`).
 *  - A private repo without a credential answers `401` (`RubyAuthPreProcessor`, same as every other
 *    read route: skipped only for a public-repo READ).
 *  - `quick/Marshal.4.8/<gem>-<version>.gemspec.rz` (RPS-1554) now carries the gem's real runtime
 *    `Gem::Dependency` entries too (development ones left out), where before it always said
 *    `dependencies = []`.
 */
test.describe('ruby > legacy /api/v1/dependencies route (RPS-1724)', () => {
  /** Runs the real `gem dependency <gemName> --remote` against `source`, `--clear-sources` so
   *  rubygems.org is never consulted; `extraArgs` for `-v <version>` (there is no `--development`
   *  flag to pass -- this file's header). */
  async function gemDependency(
    label: string,
    source: string,
    gemName: string,
    extraArgs: string[] = [],
  ) {
    const ws = await isolatedWorkDir(label);
    return run(
      'gem',
      ['dependency', gemName, '--remote', '--clear-sources', '--source', source, ...extraArgs],
      { cwd: ws.work, env: gemEnv(ws.home, {}), timeoutMs: CLIENT_TIMEOUT_MS, label },
    );
  }

  test('gem dependency --remote prints the runtime dependency, never the development one, and -v narrows to one version', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const { a, b, c } = await publishGraph(repo.name, seeder.runId);
    const source = repoUrl(repo.name);

    // FINDING (confirmed live): the pinned toolchain's `gem dependency` has NO `--development` flag
    // at all (`Gem::OptionParser::InvalidOption`; `gem help dependency` lists only --version/-v,
    // --platform, --prerelease, --reverse-dependencies/-R, --pipe). A development dependency is
    // therefore never requestable through this legacy command by any flag, not just left out by
    // default -- and the raw Marshal payload (the next test) can't show one either:
    // `RubyMarshalWriter.dumpDependencies` only ever serialises `GemCompactEntry
    // .runtimeDependencies`, which is pre-filtered to runtime already.
    const runtimeOnly = await gemDependency('ruby-gem-dependency-runtime', source, a);
    expect(runtimeOnly.exitCode, `gem dependency ${a}: ${runtimeOnly.stderr}`).toBe(0);
    expect(runtimeOnly.stdout, 'the runtime dependency on B is printed').toContain(b);
    expect(
      runtimeOnly.stdout,
      'the development dependency is never listed (no --development flag exists to ask for it)',
    ).not.toContain(`e2e_${seeder.runId}_tr_devonly`);

    // B 1.1.0 alone: its own dependency (C ~> 1.0) is printed; B 1.0.0 (no dependency) is not asked for.
    const oneVersion = await gemDependency('ruby-gem-dependency-version', source, b, [
      '-v',
      '1.1.0',
    ]);
    expect(oneVersion.exitCode, `gem dependency ${b} -v 1.1.0: ${oneVersion.stderr}`).toBe(0);
    expect(oneVersion.stdout, "B 1.1.0's own dependency on C is printed").toContain(c);
  });

  test('the decoded Marshal payload lists runtime-only dependencies, a multi-clause requirement spelling, and a platform gem entry', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const platform = await localPlatform();
    const d = `e2e_${seeder.runId}_ad_d`;
    const e = `e2e_${seeder.runId}_ad_e`;
    const devonly = `e2e_${seeder.runId}_ad_devonly`;

    await publishGem(repo.name, d, '1.0.0', [
      { name: e, requirement: ['>= 1.0', '< 2.0'] },
      { name: devonly, requirement: '>= 0', type: 'development' },
    ]);
    await publishGem(repo.name, e, '1.0.0');
    await publishGem(repo.name, e, '1.1.0');
    await publishGem(repo.name, e, '1.5.0', [], platform);

    const entries = await getDependencies(repo.name, {}, [d, e, `${d}_unknown`]);

    const byNameVersion = new Map(entries.map((entry) => [`${entry.name} ${entry.number}`, entry]));
    expect(byNameVersion.get(`${d} 1.0.0`), 'D 1.0.0 is one entry').toMatchObject({
      platform: 'ruby',
    });
    expect(
      byNameVersion.get(`${d} 1.0.0`)?.dependencies,
      'the runtime dependency only, not the development one, as [name, requirement]',
    ).toEqual([[e, '>= 1.0, < 2.0']]);

    expect(byNameVersion.get(`${e} 1.0.0`)?.dependencies, 'E 1.0.0 has no dependency').toEqual([]);
    expect(byNameVersion.get(`${e} 1.1.0`)?.dependencies, 'E 1.1.0 has no dependency').toEqual([]);
    expect(
      byNameVersion.get(`${e} 1.5.0`),
      'the platform gem entry carries the native platform string',
    ).toMatchObject({ platform, dependencies: [] });

    expect(
      [...byNameVersion.keys()].some((key) => key.startsWith(`${d}_unknown`)),
      'an unknown gem name contributes no entry of its own',
    ).toBe(false);
  });

  test('a yanked version is omitted from the decoded Marshal payload', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const g = `e2e_${seeder.runId}_yk_g`;
    await publishGem(repo.name, g, '1.0.0');
    await publishGem(repo.name, g, '1.1.0');

    const before = await getDependencies(repo.name, {}, [g]);
    expect(before.map((entry) => entry.number).sort()).toEqual(['1.0.0', '1.1.0']);

    const yank = await rawYank(repo.name, adminCredential(), { gemName: g, version: '1.1.0' });
    expect(yank.status, yank.body.toString('utf8')).toBe(200);

    const after = await getDependencies(repo.name, {}, [g]);
    expect(
      after.map((entry) => entry.number),
      'the yanked version is omitted',
    ).toEqual(['1.0.0']);
  });

  test('an unknown gem, an empty ?gems=, and no gems param at all each answer an empty array with 200', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });

    expect(await getDependencies(repo.name, {}, [`e2e_${seeder.runId}_never_published`])).toEqual(
      [],
    );
    expect(await getDependencies(repo.name, {}, [])).toEqual([]);

    const noParamAtAll = await rawGet(repo.name, {}, 'api/v1/dependencies');
    expect(noParamAtAll.status, 'GET /api/v1/dependencies with no gems param at all').toBe(200);
    expect(await decodeMarshalViaRuby('ruby-dependencies-no-param', noParamAtAll.body)).toEqual([]);
  });

  test('a private repo without a credential answers 401', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
    const res = await rawGetDependencies(repo.name, {}, [`e2e_${seeder.runId}_private_probe`]);
    expect(res.status, 'GET /api/v1/dependencies on a private repo, no credential').toBe(401);
  });

  test('quick/Marshal.4.8/<gem>-<version>.gemspec.rz now carries the runtime dependency, not the development one (RPS-1554)', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const h = `e2e_${seeder.runId}_gs_h`;
    const i = `e2e_${seeder.runId}_gs_i`;
    const devonly = `e2e_${seeder.runId}_gs_devonly`;
    await publishGem(repo.name, i, '2.0.0');
    await publishGem(repo.name, h, '1.0.0', [
      { name: i, requirement: '~> 2.0' },
      { name: devonly, requirement: '>= 0', type: 'development' },
    ]);

    const rel = gemspecRzRelPath(h, '1.0.0');
    const res = await rawGet(repo.name, {}, rel);
    expect(res.status, `GET ${rel}`).toBe(200);

    const spec = (await decodeMarshalViaRuby(
      'ruby-gemspec-deps-decode',
      zlib.inflateSync(res.body),
    )) as DecodedGemspec;
    expect(spec).toMatchObject({ name: h, version: '1.0.0', platform: 'ruby' });
    expect(spec.dependencies, 'the runtime dependency, not the development one').toEqual([
      [i, '~> 2.0'],
    ]);
  });
});

/**
 * RPS-1724: Ruby pre-release versions and real-client flows. Pre-releases follow RubyGems
 * convention: a version containing a letter (e.g. `1.0.0.pre`, `1.0.0.rc1`) is a pre-release.
 * A pre-release is NOT selected by a plain `gem install <name>` or `bundle install` resolving a
 * dependency, but IS installable with `gem install <name> --pre`. The `/info` endpoint includes
 * pre-releases in its version list alongside releases.
 *
 * Probed live:
 *  - A pre-release version publishes with `200`, like any other version.
 *  - `/info/<gem>` lists pre-releases (one line per version, released or pre-release alike).
 *  - `gem install <gem>` (without version) installs the latest RELEASE, not pre-release.
 *  - `gem install <gem> <pre-version>` (without `--pre`) fails to find the pre-release.
 *  - `gem install <gem> <pre-version> --pre` succeeds and installs the pre-release.
 *  - The `/info` endpoint returns versions in publish order, not version-sorted order.
 */
test.describe('ruby > pre-release versions and real-client flows (RPS-1724)', () => {
  test('a pre-release version publishes, is NOT installed without --pre, is installed with --pre', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const name = `e2e_${seeder.runId}_pr_gem`;

    // Publish a release and a pre-release.
    const release = await publishGem(repo.name, name, '1.0.0');
    expect(release.sha256Hex).toBeDefined();
    const prerelease = await publishGem(repo.name, name, '1.1.0.pre');
    expect(prerelease.sha256Hex).toBeDefined();

    // `/info` lists both: one line per version, no omission of pre-releases.
    const infoOutput = await infoLines(repo.name, {}, name);
    expect(infoOutput.map((l) => l.version).sort()).toEqual(['1.0.0', '1.1.0.pre']);

    // `gem install <name>` (no version) installs the latest RELEASE, not the pre-release.
    const installLatestRelease = await gemInstall(
      'ruby-prerelease-latest-release',
      repo.name,
      name,
    );
    expect(installLatestRelease.exitCode, `gem install ${name}: ${installLatestRelease.stderr}`).toBe(
      0,
    );
    expect(installLatestRelease.installed).toEqual([`${name}-1.0.0`]);

    // `gem install <name> <pre-version>` (no `--pre`) fails to find the pre-release.
    const installPreNoFlag = await gemInstall(
      'ruby-prerelease-no-flag',
      repo.name,
      name,
      ['1.1.0.pre'],
    );
    expect(installPreNoFlag.exitCode, 'gem install with a pre-release version, no flag').not.toBe(0);
    expect(installPreNoFlag.stderr).toContain(name);

    // `gem install <name> <pre-version> --pre` succeeds.
    const installPreWithFlag = await gemInstall(
      'ruby-prerelease-with-flag',
      repo.name,
      name,
      ['1.1.0.pre', '--pre'],
    );
    expect(
      installPreWithFlag.exitCode,
      `gem install ${name} 1.1.0.pre --pre: ${installPreWithFlag.stderr}`,
    ).toBe(0);
    expect(installPreWithFlag.installed).toEqual([`${name}-1.1.0.pre`]);
  });

  test('the /info endpoint returns versions in publish order, not version-sorted order', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const name = `e2e_${seeder.runId}_info_order`;

    // Publish in a deliberate order: 1.0.0, 2.0.0, 1.5.0 (not sorted).
    await publishGem(repo.name, name, '1.0.0');
    await publishGem(repo.name, name, '2.0.0');
    await publishGem(repo.name, name, '1.5.0');

    const infoOutput = await infoLines(repo.name, {}, name);
    const versions = infoOutput.map((l) => l.version);

    // The order should match publish order (first, second, third), not version-sorted order.
    // This is consistent with how RubyGems itself serves /info: newer publishes are appended.
    expect(versions).toEqual(['1.0.0', '2.0.0', '1.5.0']);
  });
});
