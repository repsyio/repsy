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
 * RPS-1479 (Helm): dependency resolution against what Repsy serves. A chart's `Chart.yaml` declares
 * `dependencies:` (name, semver RANGE, repository); the real `helm dependency update` / `build` of a
 * LOCAL chart that names only A resolves them from Repsy alone, so the version list and the file
 * bytes have to come from Repsy's own metadata: `index.yaml` (classic, ChartMuseum protocol) or
 * `GET /v2/<repo>/<chart>/tags/list` (OCI, RPS-1219). Charts are hand-assembled (`helm-chart.ts`'s
 * `buildChart({ dependencies })`); classic ones are pushed with the real `helm cm-push`, OCI ones with
 * the real `helm push`, and the assertions read the resolved artefacts: `Chart.lock` (name, version,
 * repository, digest) and `charts/<dep>-<version>.tgz` (bytes equal to what Repsy serves), never just an
 * exit code.
 *
 * Probed live (Helm 4.3.0; each answer below is what a running stack served):
 *  - classic: `helm dependency update` reads `index.yaml` of the repository URL and downloads
 *    `charts/<name>-<version>.tgz` with the credentials of the `helm repo add` entry (the isolated
 *    `repositories.yaml`). A private repo needs that entry: without it the index fetch is a bare `401`.
 *    A public repo needs nothing (helm fetches "unmanaged" repositories itself). `@<alias>` works too.
 *  - the highest version in the range wins (`^1.0.0` -> 1.1.0, not 2.0.0; a prerelease only when the
 *    range asks for one). `Chart.lock`'s `digest` is `sha256` of the JSON of [requested, locked]
 *    dependencies, so it moves with the resolution. `build` replays the lock, `update` re-resolves.
 *  - OCI: `repository: oci://<host>/<repo>`, the versions come from `tags/list`, the layer from the
 *    manifest; a real `helm registry login` fronts it, and a private repo without a login answers
 *    "basic credential not found" (helm never gets past the challenge). `--plain-http` is needed on
 *    this http stack (helm has no localhost detection).
 *  - a chart pushed through the OCI API is listed in `index.yaml` and downloadable from the classic
 *    route (RPS-1217), so a classic `repository:` URL resolves it too.
 *  - `helm dependency update` does NOT recurse: the dependencies of a downloaded dependency are that
 *    chart's own business (A's `dependencies:` are stored in the chart, `helm show chart` prints them).
 *  - `helm cm-push` repackages the chart (see `helm-classic.ts`): the stored bytes are read back from
 *    Repsy, not taken from what the harness built.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { parse as parseYaml } from 'yaml';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { helmEnv, plainHttpFlag, renderHelmRegistryConfig } from '../../src/clients/helm.js';
import {
  buildChart,
  type BuiltChart,
  type ChartDependency,
  writeChartDir,
  writeChartFile,
} from '../../src/clients/helm-chart.js';
import {
  adminCredential,
  chartFileName,
  classicRepoUrl,
  indexEntry,
  ociRepoRef,
  parseIndex,
  rawDeleteChart,
  rawDownloadChart,
  rawGetIndex,
  rawGetTagsList,
  registryHost,
  sha256Hex,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const CLIENT_TIMEOUT_MS = 90_000;

interface Workspace {
  home: string;
  work: string;
}

interface Cred {
  username: string;
  token: string;
}

interface HelmRun {
  exitCode: number;
  stdout: string;
  stderr: string;
  command: string;
}

/** The real `helm` in one isolated HOME/config (credentials stay in there, never in the output). */
async function helm(
  ws: Workspace,
  label: string,
  args: string[],
  opts: { cred?: Cred; input?: string } = {},
): Promise<HelmRun> {
  const result = await run('helm', args, {
    cwd: ws.work,
    env: helmEnv(ws.home),
    timeoutMs: CLIENT_TIMEOUT_MS,
    redact: opts.cred ? [opts.cred.token] : [],
    label,
    input: opts.input,
  });
  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    stderr: result.stderr,
    command: result.command,
  };
}

function expectOk(result: HelmRun, what: string): void {
  expect(result.exitCode, `${what}\n${result.command}\n${result.stdout}\n${result.stderr}`).toBe(0);
}

/** `helm repo add <alias> <url>` with the credential (or none), the secret through stdin. */
async function repoAdd(ws: Workspace, alias: string, url: string, cred?: Cred): Promise<HelmRun> {
  const auth = cred ? ['--username', cred.username, '--password-stdin'] : [];
  return helm(ws, 'helm-dep-repo-add', ['repo', 'add', alias, url, ...auth], {
    cred,
    input: cred ? `${cred.token}\n` : undefined,
  });
}

interface Stored {
  built: BuiltChart;
  /** The bytes Repsy serves for the version (`cm-push` repackages, so not `built.tgzBytes`). */
  bytes: Buffer;
}

/** A chart pushed with the real `helm cm-push` (classic), read back from the download route. */
async function cmPush(
  ws: Workspace,
  repoName: string,
  cred: Cred,
  name: string,
  version: string,
  dependencies?: ChartDependency[],
): Promise<Stored> {
  const built = await buildChart({ name, version, marker: `${name}@${version}`, dependencies });
  const file = await writeChartFile(ws.work, built);
  const pushed = await helm(
    ws,
    'helm-dep-cm-push',
    [
      'cm-push',
      file,
      classicRepoUrl(repoName),
      '--username',
      cred.username,
      '--password',
      cred.token,
    ],
    { cred },
  );
  expectOk(pushed, `cm-push ${name} ${version}`);
  const dl = await rawDownloadChart(repoName, adminCredential(), name, version);
  expect(dl.status, `GET charts/${chartFileName(name, version)}`).toBe(200);
  return { built, bytes: dl.body };
}

/** A chart pushed with the real `helm push` (OCI, `--plain-http`): the layer is the file verbatim. */
async function ociPush(
  ws: Workspace,
  repoName: string,
  cred: Cred,
  name: string,
  version: string,
  dependencies?: ChartDependency[],
): Promise<BuiltChart> {
  const built = await buildChart({ name, version, marker: `${name}@${version}`, dependencies });
  const file = await writeChartFile(ws.work, built);
  await renderHelmRegistryConfig(ws.home, {
    transport: 'basic',
    username: cred.username,
    password: cred.token,
    kind: 'token',
  });
  const pushed = await helm(
    ws,
    'helm-dep-oci-push',
    ['push', file, ociRepoRef(repoName), ...plainHttpFlag()],
    {
      cred,
    },
  );
  expectOk(pushed, `helm push ${name} ${version}`);
  return built;
}

interface Lock {
  dependencies: { name: string; version: string; repository: string }[];
  digest: string;
}

async function readLock(chartDir: string): Promise<Lock> {
  return parseYaml(await fs.readFile(path.join(chartDir, 'Chart.lock'), 'utf8')) as Lock;
}

/** What helm writes as `Chart.lock`'s `digest` (`resolver.HashReq`): `sha256` of the JSON of
 *  [requested dependencies, locked dependencies]. */
function lockDigest(requested: ChartDependency[], locked: ChartDependency[]): string {
  const shape = (deps: ChartDependency[]) =>
    deps.map((d) => ({ name: d.name, version: d.version, repository: d.repository }));
  // Go's json.Marshal escapes <, > and & (`>=1.0.0` is hashed as `\u003e=1.0.0`).
  const json = JSON.stringify([shape(requested), shape(locked)])
    .replaceAll('<', '\\u003c')
    .replaceAll('>', '\\u003e')
    .replaceAll('&', '\\u0026');
  return `sha256:${createHash('sha256').update(json).digest('hex')}`;
}

async function chartsDirEntries(chartDir: string): Promise<string[]> {
  return (await fs.readdir(path.join(chartDir, 'charts')).catch(() => [])).sort();
}

async function fetched(chartDir: string, name: string, version: string): Promise<Buffer> {
  return fs.readFile(path.join(chartDir, 'charts', chartFileName(name, version)));
}

async function exists(file: string): Promise<boolean> {
  return fs.access(file).then(
    () => true,
    () => false,
  );
}

/** The local umbrella chart `x` (the consumer's project) that names only the given dependencies. */
async function consumerChart(
  ws: Workspace,
  dir: string,
  dependencies: ChartDependency[],
): Promise<string> {
  return writeChartDir(path.join(ws.work, 'consumer', dir), {
    name: 'umbrella',
    version: '0.1.0',
    dependencies,
  });
}

test.describe('helm classic > dependency resolution (RPS-1479)', () => {
  test(
    'dependency update resolves the highest B in ^1.0.0 from index.yaml: Chart.lock and the fetched ' +
      'tgz match what Repsy serves (private repo, read-only token in the repo entry)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
      const writer = await seeder.createToken(repo.name, { readOnly: false });
      const reader = await seeder.createToken(repo.name, { readOnly: true });
      const url = classicRepoUrl(repo.name);
      const a = `e2e-${seeder.runId}-a`;
      const b = `e2e-${seeder.runId}-b`;

      const pub = await isolatedWorkDir('helm-dep-publish');
      const stored: Record<string, Stored> = {};
      for (const v of ['1.0.0', '1.1.0', '2.0.0', '1.2.0-rc.1']) {
        stored[v] = await cmPush(pub, repo.name, writer, b, v);
      }
      const depsOfA: ChartDependency[] = [{ name: b, version: '^1.0.0', repository: url }];
      await cmPush(pub, repo.name, writer, a, '1.0.0', depsOfA);

      // What the resolver has to work with: every B version, each entry naming its own digest.
      const index = parseIndex(
        (await rawGetIndex(repo.name, adminCredential())).body.toString('utf8'),
      );
      expect(index.entries[b]?.map((e) => e.version).sort()).toEqual(
        ['1.0.0', '1.1.0', '1.2.0-rc.1', '2.0.0'].sort(),
      );
      expect(indexEntry(index, b, '1.1.0')?.digest).toBe(
        `sha256:${sha256Hex(stored['1.1.0'].bytes)}`,
      );

      // The consumer: a local chart with A's dependency declaration, a read-only token in its own
      // repositories.yaml. Nothing of B is on its disk.
      const ws = await isolatedWorkDir('helm-dep-consumer');
      expectOk(await repoAdd(ws, 'repsy', url, reader), 'helm repo add');
      const chartDir = await consumerChart(ws, 'a', depsOfA);
      const update = await helm(ws, 'helm-dep-update', ['dependency', 'update', chartDir], {
        cred: reader,
      });
      expectOk(update, 'helm dependency update');
      expect(update.stdout, 'B is downloaded from the repo URL').toContain(
        `Downloading ${b} from repo ${url}`,
      );

      const lock = await readLock(chartDir);
      expect(lock.dependencies, '1.1.0: the highest in ^1.0.0, no 2.0.0, no prerelease').toEqual([
        { name: b, version: '1.1.0', repository: url },
      ]);
      expect(lock.digest).toBe(
        lockDigest(depsOfA, [{ name: b, version: '1.1.0', repository: url }]),
      );
      expect(
        await chartsDirEntries(chartDir),
        'only the direct dependency, only that version',
      ).toEqual([chartFileName(b, '1.1.0')]);
      const got = await fetched(chartDir, b, '1.1.0');
      expect(got.equals(stored['1.1.0'].bytes), 'the fetched tgz is what Repsy stores').toBe(true);
      expect(got.equals(stored['1.0.0'].bytes), 'and not another version').toBe(false);

      // `@<alias>` names the same repo entry; the lock records the URL behind it.
      const aliasDir = await consumerChart(ws, 'alias', [
        { name: b, version: '^1.0.0', repository: '@repsy' },
      ]);
      expectOk(
        await helm(ws, 'helm-dep-update-alias', ['dependency', 'update', aliasDir], {
          cred: reader,
        }),
        'helm dependency update with @repsy',
      );
      expect((await readLock(aliasDir)).dependencies).toEqual([
        { name: b, version: '1.1.0', repository: url },
      ]);

      // A itself is stored WITH its dependencies declaration (what `helm show chart` prints for a
      // consumer of A); resolving them is the consumer's `dependency update`, never the server's.
      const show = await helm(
        ws,
        'helm-dep-show',
        ['show', 'chart', 'repsy/' + a, '--version', '1.0.0'],
        {
          cred: reader,
        },
      );
      expectOk(show, 'helm show chart');
      expect(show.stdout).toContain('dependencies:');
      expect(show.stdout).toContain(`name: ${b}`);
      expect(show.stdout).toContain('version: ^1.0.0');
    },
  );

  test('the range decides: ~, >=, an interval, an exact version and a prerelease range (public repo, no repo add, no credentials)', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: false });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    const pub = await isolatedWorkDir('helm-dep-publish');
    const stored: Record<string, Stored> = {};
    for (const v of ['1.0.0', '1.1.0', '1.2.0-rc.1', '2.0.0']) {
      stored[v] = await cmPush(pub, repo.name, writer, b, v);
    }

    const cases: { range: string; expected: string }[] = [
      { range: '~1.0.0', expected: '1.0.0' },
      { range: '^1.0.0', expected: '1.1.0' },
      { range: '>=1.0.0', expected: '2.0.0' },
      { range: '>=1.0.0 <2.0.0', expected: '1.1.0' },
      { range: '1.0.0', expected: '1.0.0' },
      { range: '^1.0.0-0', expected: '1.2.0-rc.1' },
    ];
    // One machine with no repository entry at all: helm fetches the public repo itself.
    const ws = await isolatedWorkDir('helm-dep-consumer');
    let n = 0;
    for (const { range, expected } of cases) {
      const deps: ChartDependency[] = [{ name: b, version: range, repository: url }];
      const chartDir = await consumerChart(ws, `range-${n++}`, deps);
      const update = await helm(ws, `helm-dep-update-${range}`, ['dependency', 'update', chartDir]);
      expectOk(update, `range ${range}`);
      const lock = await readLock(chartDir);
      expect(lock.dependencies, `range "${range}"`).toEqual([
        { name: b, version: expected, repository: url },
      ]);
      expect(lock.digest, `range "${range}"`).toBe(
        lockDigest(deps, [{ name: b, version: expected, repository: url }]),
      );
      expect(await chartsDirEntries(chartDir), `range "${range}"`).toEqual([
        chartFileName(b, expected),
      ]);
      expect(
        (await fetched(chartDir, b, expected)).equals(stored[expected].bytes),
        `range "${range}"`,
      ).toBe(true);
    }
  });

  test('dependency build replays Chart.lock (a newer B changes nothing), update moves it, an edited Chart.yaml is refused', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    const pub = await isolatedWorkDir('helm-dep-publish');
    const b10 = await cmPush(pub, repo.name, writer, b, '1.0.0');
    const b11 = await cmPush(pub, repo.name, writer, b, '1.1.0');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    expectOk(await repoAdd(ws, 'repsy', url, writer), 'helm repo add');
    const deps: ChartDependency[] = [{ name: b, version: '^1.0.0', repository: url }];
    const chartDir = await consumerChart(ws, 'a', deps);
    expectOk(
      await helm(ws, 'helm-dep-update', ['dependency', 'update', chartDir], { cred: writer }),
      'first update',
    );
    const firstLock = await fs.readFile(path.join(chartDir, 'Chart.lock'), 'utf8');
    expect((await readLock(chartDir)).dependencies[0].version).toBe('1.1.0');

    // A newer B appears in the range. The lock still says 1.1.0.
    const b12 = await cmPush(pub, repo.name, writer, b, '1.2.0');
    await fs.rm(path.join(chartDir, 'charts'), { recursive: true });
    const build = await helm(ws, 'helm-dep-build', ['dependency', 'build', chartDir], {
      cred: writer,
    });
    expectOk(build, 'helm dependency build');
    expect(await chartsDirEntries(chartDir), 'build fetches what the lock names').toEqual([
      chartFileName(b, '1.1.0'),
    ]);
    expect((await fetched(chartDir, b, '1.1.0')).equals(b11.bytes)).toBe(true);
    expect(
      await fs.readFile(path.join(chartDir, 'Chart.lock'), 'utf8'),
      'build leaves the lock alone',
    ).toBe(firstLock);

    // update re-resolves: 1.2.0 now, and the digest follows.
    expectOk(
      await helm(ws, 'helm-dep-update-2', ['dependency', 'update', chartDir], { cred: writer }),
      'second update',
    );
    const lock = await readLock(chartDir);
    expect(lock.dependencies).toEqual([{ name: b, version: '1.2.0', repository: url }]);
    expect(lock.digest).toBe(lockDigest(deps, [{ name: b, version: '1.2.0', repository: url }]));
    expect(lock.digest).not.toBe(
      lockDigest(deps, [{ name: b, version: '1.1.0', repository: url }]),
    );
    expect(await chartsDirEntries(chartDir), 'the outdated 1.1.0 is removed').toEqual([
      chartFileName(b, '1.2.0'),
    ]);
    expect((await fetched(chartDir, b, '1.2.0')).equals(b12.bytes)).toBe(true);
    expect(b10.bytes.equals(b11.bytes)).toBe(false);

    // A range edited after the lock was written no longer matches it: build refuses, changes nothing.
    const chartYaml = path.join(chartDir, 'Chart.yaml');
    await fs.writeFile(
      chartYaml,
      (await fs.readFile(chartYaml, 'utf8')).replace('^1.0.0', '^2.0.0'),
    );
    const stale = await helm(ws, 'helm-dep-build-stale', ['dependency', 'build', chartDir], {
      cred: writer,
    });
    expect(stale.exitCode, 'build with an out-of-sync lock').not.toBe(0);
    expect(stale.stderr).toContain('the lock file (Chart.lock) is out of sync');
    expect(await chartsDirEntries(chartDir)).toEqual([chartFileName(b, '1.2.0')]);
  });

  test('a private repo refuses dependency resolution without its credential: 401, nothing is written; a wrong password cannot even be added', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    await cmPush(await isolatedWorkDir('helm-dep-publish'), repo.name, writer, b, '1.0.0');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    const chartDir = await consumerChart(ws, 'a', [
      { name: b, version: '^1.0.0', repository: url },
    ]);
    const anonymous = await helm(ws, 'helm-dep-anon', ['dependency', 'update', chartDir]);
    expect(anonymous.exitCode, 'no repository entry, no credential').not.toBe(0);
    expect(anonymous.stderr + anonymous.stdout).toContain('index.yaml : 401');

    const wrong = await repoAdd(ws, 'repsy', url, {
      username: writer.username,
      token: 'not-the-secret',
    });
    expect(wrong.exitCode, 'helm repo add with a wrong password').not.toBe(0);
    expect(wrong.stderr).toContain('index.yaml : 401');
    const stillNothing = await helm(ws, 'helm-dep-anon-2', ['dependency', 'update', chartDir]);
    expect(stillNothing.exitCode).not.toBe(0);

    expect(await exists(path.join(chartDir, 'Chart.lock')), 'no lock').toBe(false);
    expect(await chartsDirEntries(chartDir), 'nothing downloaded').toEqual([]);
  });

  test('an unsatisfiable range and a chart the repo does not have fail without a lock or a download', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    const pub = await isolatedWorkDir('helm-dep-publish');
    await cmPush(pub, repo.name, writer, b, '1.0.0');
    await cmPush(pub, repo.name, writer, b, '1.1.0');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    expectOk(await repoAdd(ws, 'repsy', url, writer), 'helm repo add');

    const unsat = await consumerChart(ws, 'unsat', [
      { name: b, version: '^3.0.0', repository: url },
    ]);
    const unsatRun = await helm(ws, 'helm-dep-unsat', ['dependency', 'update', unsat], {
      cred: writer,
    });
    expect(unsatRun.exitCode).not.toBe(0);
    expect(unsatRun.stderr).toContain(`can't get a valid version for 1 subchart(s): "${b}"`);
    expect(unsatRun.stderr).toContain('version "^3.0.0"');

    const missingName = `nope-${b}`;
    const missing = await consumerChart(ws, 'missing', [
      { name: missingName, version: '^1.0.0', repository: url },
    ]);
    const missingRun = await helm(ws, 'helm-dep-missing', ['dependency', 'update', missing], {
      cred: writer,
    });
    expect(missingRun.exitCode).not.toBe(0);
    expect(missingRun.stderr).toContain(`${missingName} chart not found in repo ${url}`);

    // One good and one missing dependency: all or nothing.
    const mixed = await consumerChart(ws, 'mixed', [
      { name: b, version: '^1.0.0', repository: url },
      { name: missingName, version: '^1.0.0', repository: url },
    ]);
    expect(
      (await helm(ws, 'helm-dep-mixed', ['dependency', 'update', mixed], { cred: writer }))
        .exitCode,
    ).not.toBe(0);

    for (const dir of [unsat, missing, mixed]) {
      expect(await exists(path.join(dir, 'Chart.lock')), `${dir}: no lock`).toBe(false);
      expect(await chartsDirEntries(dir), `${dir}: nothing downloaded`).toEqual([]);
    }
  });

  test('a locked version deleted from the repo cannot be rebuilt; update falls back to the next highest', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    const pub = await isolatedWorkDir('helm-dep-publish');
    const b10 = await cmPush(pub, repo.name, writer, b, '1.0.0');
    await cmPush(pub, repo.name, writer, b, '1.1.0');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    expectOk(await repoAdd(ws, 'repsy', url, writer), 'helm repo add');
    const chartDir = await consumerChart(ws, 'a', [
      { name: b, version: '^1.0.0', repository: url },
    ]);
    expectOk(
      await helm(ws, 'helm-dep-update', ['dependency', 'update', chartDir], { cred: writer }),
      'update',
    );
    expect((await readLock(chartDir)).dependencies[0].version).toBe('1.1.0');

    // Deleting a chart version is a MANAGE action: the admin credential, not a deploy token.
    const del = await rawDeleteChart(repo.name, adminCredential(), b, '1.1.0');
    expect(del.status, 'DELETE charts/<b>/1.1.0').toBe(200);
    await fs.rm(path.join(chartDir, 'charts'), { recursive: true });
    const build = await helm(ws, 'helm-dep-build-gone', ['dependency', 'build', chartDir], {
      cred: writer,
    });
    expect(build.exitCode, 'the lock names a version the index no longer lists').not.toBe(0);
    expect(build.stderr).toContain('no matching version');
    expect(await chartsDirEntries(chartDir)).toEqual([]);

    expectOk(
      await helm(ws, 'helm-dep-update-2', ['dependency', 'update', chartDir], { cred: writer }),
      'update after delete',
    );
    expect((await readLock(chartDir)).dependencies[0].version).toBe('1.0.0');
    expect((await fetched(chartDir, b, '1.0.0')).equals(b10.bytes)).toBe(true);
  });

  test('a chart pushed through the OCI API resolves through a classic repository URL too (RPS-1217)', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const url = classicRepoUrl(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    const pub = await isolatedWorkDir('helm-dep-publish');
    const b10 = await ociPush(pub, repo.name, writer, b, '1.0.0');
    const b11 = await ociPush(pub, repo.name, writer, b, '1.1.0');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    expectOk(await repoAdd(ws, 'repsy', url, writer), 'helm repo add');
    const deps: ChartDependency[] = [{ name: b, version: '^1.0.0', repository: url }];
    const chartDir = await consumerChart(ws, 'a', deps);
    expectOk(
      await helm(ws, 'helm-dep-update', ['dependency', 'update', chartDir], { cred: writer }),
      'update',
    );
    expect((await readLock(chartDir)).dependencies).toEqual([
      { name: b, version: '1.1.0', repository: url },
    ]);
    expect((await fetched(chartDir, b, '1.1.0')).equals(b11.tgzBytes), 'the OCI layer bytes').toBe(
      true,
    );
    expect(b10.tgzBytes.equals(b11.tgzBytes)).toBe(false);
  });
});

test.describe('helm OCI > dependency resolution (RPS-1479)', () => {
  test(
    'after helm registry login, dependency update resolves the highest B in ^1.0.0 from tags/list: ' +
      'Chart.lock and the layer bytes match what was pushed',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
      const writer = await seeder.createToken(repo.name, { readOnly: false });
      const reader = await seeder.createToken(repo.name, { readOnly: true });
      const ref = ociRepoRef(repo.name);
      const a = `e2e-${seeder.runId}-a`;
      const b = `e2e-${seeder.runId}-b`;
      const pub = await isolatedWorkDir('helm-dep-publish');
      const pushed: Record<string, BuiltChart> = {};
      for (const v of ['1.0.0', '1.1.0', '2.0.0', '1.2.0-rc.1']) {
        pushed[v] = await ociPush(pub, repo.name, writer, b, v);
      }
      const deps: ChartDependency[] = [{ name: b, version: '^1.0.0', repository: ref }];
      await ociPush(pub, repo.name, writer, a, '1.0.0', deps);

      // The version list the resolver reads (RPS-1219).
      const tags = await rawGetTagsList(repo.name, adminCredential(), b);
      expect(tags.status).toBe(200);
      expect((JSON.parse(tags.body.toString('utf8')) as { tags: string[] }).tags.sort()).toEqual(
        ['1.0.0', '1.1.0', '1.2.0-rc.1', '2.0.0'].sort(),
      );

      const ws = await isolatedWorkDir('helm-dep-consumer');
      const login = await helm(
        ws,
        'helm-dep-login',
        [
          'registry',
          'login',
          registryHost(),
          '-u',
          reader.username,
          '--password-stdin',
          ...plainHttpFlag(),
        ],
        { cred: reader, input: `${reader.token}\n` },
      );
      expectOk(login, 'helm registry login');
      const chartDir = await consumerChart(ws, 'a', deps);
      const update = await helm(
        ws,
        'helm-dep-update',
        ['dependency', 'update', chartDir, ...plainHttpFlag()],
        {
          cred: reader,
        },
      );
      expectOk(update, 'helm dependency update (oci)');
      expect(update.stdout).toContain(`Downloading ${b} from repo ${ref}`);

      const lock = await readLock(chartDir);
      expect(lock.dependencies, '1.1.0: not 2.0.0, not the prerelease').toEqual([
        { name: b, version: '1.1.0', repository: ref },
      ]);
      expect(lock.digest).toBe(lockDigest(deps, [{ name: b, version: '1.1.0', repository: ref }]));
      expect(await chartsDirEntries(chartDir)).toEqual([chartFileName(b, '1.1.0')]);
      expect(
        (await fetched(chartDir, b, '1.1.0')).equals(pushed['1.1.0'].tgzBytes),
        'byte-identical to the pushed chart (helm push sends the file verbatim)',
      ).toBe(true);

      // build replays the lock: a newer B does not move it.
      await ociPush(pub, repo.name, writer, b, '1.3.0');
      await fs.rm(path.join(chartDir, 'charts'), { recursive: true });
      expectOk(
        await helm(ws, 'helm-dep-build', ['dependency', 'build', chartDir, ...plainHttpFlag()], {
          cred: reader,
        }),
        'helm dependency build (oci)',
      );
      expect(await chartsDirEntries(chartDir)).toEqual([chartFileName(b, '1.1.0')]);
      expect((await readLock(chartDir)).digest).toBe(lock.digest);
      expectOk(
        await helm(
          ws,
          'helm-dep-update-2',
          ['dependency', 'update', chartDir, ...plainHttpFlag()],
          { cred: reader },
        ),
        'second update',
      );
      expect((await readLock(chartDir)).dependencies[0].version).toBe('1.3.0');
    },
  );

  test('a private repo without a login, an unsatisfiable range and a missing chart fail without a lock or a download', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const writer = await seeder.createToken(repo.name, { readOnly: false });
    const ref = ociRepoRef(repo.name);
    const b = `e2e-${seeder.runId}-b`;
    await ociPush(await isolatedWorkDir('helm-dep-publish'), repo.name, writer, b, '1.0.0');

    // No `helm registry login` on this machine.
    const anon = await isolatedWorkDir('helm-dep-anon');
    const anonDir = await consumerChart(anon, 'a', [
      { name: b, version: '^1.0.0', repository: ref },
    ]);
    const anonRun = await helm(anon, 'helm-dep-anon', [
      'dependency',
      'update',
      anonDir,
      ...plainHttpFlag(),
    ]);
    expect(anonRun.exitCode).not.toBe(0);
    expect(anonRun.stderr).toContain(`could not retrieve list of tags for repository ${ref}`);
    expect(anonRun.stderr).toContain('basic credential not found');

    const ws = await isolatedWorkDir('helm-dep-consumer');
    expectOk(
      await helm(
        ws,
        'helm-dep-login',
        [
          'registry',
          'login',
          registryHost(),
          '-u',
          writer.username,
          '--password-stdin',
          ...plainHttpFlag(),
        ],
        { cred: writer, input: `${writer.token}\n` },
      ),
      'helm registry login',
    );
    const unsat = await consumerChart(ws, 'unsat', [
      { name: b, version: '^3.0.0', repository: ref },
    ]);
    const unsatRun = await helm(
      ws,
      'helm-dep-unsat',
      ['dependency', 'update', unsat, ...plainHttpFlag()],
      {
        cred: writer,
      },
    );
    expect(unsatRun.exitCode).not.toBe(0);
    expect(unsatRun.stderr).toContain(
      'could not locate a version matching provided version string ^3.0.0',
    );

    const missingName = `nope-${b}`;
    const missing = await consumerChart(ws, 'missing', [
      { name: missingName, version: '^1.0.0', repository: ref },
    ]);
    const missingRun = await helm(
      ws,
      'helm-dep-missing',
      ['dependency', 'update', missing, ...plainHttpFlag()],
      {
        cred: writer,
      },
    );
    expect(missingRun.exitCode).not.toBe(0);
    expect(missingRun.stderr).toContain('unable to locate any tags in provided repository');

    for (const dir of [anonDir, unsat, missing]) {
      expect(await exists(path.join(dir, 'Chart.lock')), `${dir}: no lock`).toBe(false);
      expect(await chartsDirEntries(dir), `${dir}: nothing downloaded`).toEqual([]);
    }
  });
});
