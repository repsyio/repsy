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
 * Transitive dependency resolution against Repsy-served metadata (RPS-1479, part Go, epic RPS-1473).
 * Everything else in the Go suite consumes ONE module at an exact version; here module A's `go.mod`
 * `require`s module B and the REAL `go` toolchain has to work out the graph from what Repsy serves
 * (`@v/list`, `@v/<v>.info`, `@v/<v>.mod`, `@v/<v>.zip`, `@latest`), the way an ordinary `go mod
 * tidy` does. Adapted from `publish-consume.spec.ts` (the hand-built module zips, `goEnv`, the TLS
 * shim for a credentialed consume); `buildModuleZip({ requires })` in `golang-raw.ts` writes the
 * `require` lines and a `hello.go` that imports the dependency.
 *
 *  - T1 A private repo, a read-only deploy token (through the TLS shim, the only way a real `go`
 *    sends credentials to this plain-http stack): B v1.0.0/v1.1.0/v1.2.0, A v1.0.0 (requires B
 *    v1.0.0) and A v1.1.0 (requires B v1.1.0). A consumer that only imports A gets, from `go mod
 *    tidy`, A v1.1.0 (`@latest`) and B v1.1.0 as `// indirect` -- the version A's go.mod NAMES, not
 *    B's own `@latest` (v1.2.0): minimal version selection never upgrades on its own. `go.sum`
 *    carries the dirhash of the bytes uploaded (this harness's `dirhashHash1`), `go list -m all`
 *    and `-versions` report the graph, and the built program prints B v1.1.0's marker.
 *  - T2 MVS picks the HIGHEST version anybody requires: a consumer that itself requires B v1.1.0
 *    while A v1.0.0 asks for v1.0.0 keeps v1.1.0; one that requires B v1.0.0 while A v1.1.0 asks
 *    for v1.1.0 is raised to v1.1.0 (`go mod tidy` rewrites its go.mod).
 *  - T3 A `/v2` major-path module (`<domain>/b/v2`, go.mod `module <domain>/b/v2`) is a different
 *    module from `<domain>/b` and is served, listed and resolved next to it in one repo; a consumer
 *    that imports both gets both.
 *  - T4 The raw wire: a dependency's go.mod is what `@v/<v>.mod` serves (the `require` block
 *    verbatim), `.info`/`@latest`/`@v/list` answer for it like for any module.
 *  - T5 A dependency that was never published fails `go mod tidy` in the CONSUMER, naming the
 *    dependency and the 404 Repsy answered.
 *
 * Live probing (see `README.md`, "Go runner"): a sumdb-less proxy needs nothing beyond what `goEnv`
 * already sets -- `GONOSUMDB=e2e.repsy.test` (every module here lives under that domain) and
 * `GOFLAGS` empty (no `-mod=mod`: `go mod tidy` writes go.mod/go.sum itself).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { goEnv } from '../../src/clients/golang.js';
import {
  adminCredential,
  type BuiltGoModule,
  buildModuleZip,
  type GoRequire,
  infoRelPath,
  latestRelPath,
  listRelPath,
  MODULE_DOMAIN,
  modRelPath,
  parseInfo,
  parseVersionList,
  rawGet,
  rawUpload,
} from '../../src/clients/golang-raw.js';
import { shimTraceSoFar } from '../../src/clients/golang-tls-shim.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

const TIMEOUT_MS = 180_000;

type Env = NodeJS.ProcessEnv;

/** Uploads one module with the real wire format (the raw PUT `golang.ts` mirrors with `curl -T`). */
async function publishModule(
  repoName: string,
  modulePath: string,
  version: string,
  requires?: readonly GoRequire[],
): Promise<BuiltGoModule> {
  const built = await buildModuleZip({ modulePath, version, requires });
  const res = await rawUpload(repoName, adminCredential(), built);
  expect(res.status, `upload ${modulePath}@${version}: ${res.status}`).toBe(200);
  return built;
}

/** The consumer module: imports A and prints its own marker and the markers of A's dependencies. */
async function writeConsumer(
  work: string,
  aModulePath: string,
  requires: readonly GoRequire[] = [],
  extraImports: readonly string[] = [],
): Promise<void> {
  const requireBlock =
    requires.length === 0
      ? ''
      : `\nrequire (\n${requires.map((r) => `\t${r.modulePath} ${r.version}\n`).join('')})\n`;
  await fs.writeFile(
    path.join(work, 'go.mod'),
    `module e2e.consumer\n\ngo 1.21\n${requireBlock}`,
    'utf8',
  );
  const extra = extraImports.map((imp, i) => `\textra${i} "${imp}"\n`).join('');
  const useExtra = extraImports.map((_, i) => `\tfmt.Println(extra${i}.Marker)\n`).join('');
  await fs.writeFile(
    path.join(work, 'main.go'),
    `package main

import (
\t"fmt"
\t"strings"

\ta "${aModulePath}"
${extra})

func main() {
\tfmt.Println(a.Marker)
\tfmt.Println(strings.Join(a.DepMarkers(), ","))
${useExtra}}
`,
    'utf8',
  );
}

async function go(
  args: string[],
  work: string,
  goEnvVars: Env,
  label: string,
  redact: string[] = [],
): Promise<{ exitCode: number; stdout: string; stderr: string; command: string }> {
  return run('go', args, {
    cwd: work,
    env: goEnvVars,
    timeoutMs: TIMEOUT_MS,
    redact,
    label,
  });
}

/** `go.sum` as `"<module> <version>[/go.mod]" -> "h1:..."`. */
async function readGoSum(work: string): Promise<Map<string, string>> {
  const text = await fs.readFile(path.join(work, 'go.sum'), 'utf8');
  const sums = new Map<string, string>();
  for (const line of text.split('\n').filter((l) => l.length > 0)) {
    const [modulePath, version, hash] = line.split(' ');
    sums.set(`${modulePath} ${version}`, hash);
  }
  return sums;
}

/** `go list -m all` as `module -> version` (the main module has no version). */
function parseModuleList(stdout: string): Map<string, string | undefined> {
  const modules = new Map<string, string | undefined>();
  for (const line of stdout.split('\n').filter((l) => l.length > 0)) {
    const [modulePath, version] = line.split(' ');
    modules.set(modulePath, version);
  }
  return modules;
}

/** What the TLS shim saw since `traceBefore` (the shim only exists for a credentialed request
 *  against a plain-http target; nothing to check against an https one): every request carried the
 *  credential, and the dependency's `go.mod` and version list were fetched. */
async function expectShimRequests(
  traceBefore: number,
  repoName: string,
  dependency: string,
): Promise<void> {
  if (new URL(env.repoBaseUrl).protocol !== 'http:') {
    return;
  }
  const requests = ((await shimTraceSoFar()) ?? []).slice(traceBefore);
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((r) => r.hasAuthorization)).toBe(true);
  const paths = requests.map((r) => r.path);
  expect(paths, 'the dependency go.mod is fetched').toContain(
    `/${repoPath(repoName)}/${dependency}/@v/v1.1.0.mod`,
  );
  expect(paths, "the dependency's version list is fetched").toContain(
    `/${repoPath(repoName)}/${dependency}/@v/list`,
  );
}

test(
  'golang > go mod tidy resolves the dependency of a dependency to the version its go.mod names, ' +
    'through a private repo and a read-only deploy token (RPS-1479 T1)',
  { tag: ['@smoke', '@auth'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: true });
    const credential: MaterializedCredential = {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    };

    const b = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t1-b`;
    const a = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t1-a`;
    const bV100 = await publishModule(repo.name, b, 'v1.0.0');
    const bV110 = await publishModule(repo.name, b, 'v1.1.0');
    const bV120 = await publishModule(repo.name, b, 'v1.2.0');
    await publishModule(repo.name, a, 'v1.0.0', [{ modulePath: b, version: 'v1.0.0' }]);
    const aV110 = await publishModule(repo.name, a, 'v1.1.0', [
      { modulePath: b, version: 'v1.1.0' },
    ]);

    const { home, work } = await isolatedWorkDir(`golang-transitive-${seeder.runId}`);
    await writeConsumer(work, a);
    const goEnvVars = await goEnv(home, credential, repo.name);
    const redact = [token.token];
    const traceBefore = (await shimTraceSoFar())?.length ?? 0;

    const tidy = await go(['mod', 'tidy'], work, goEnvVars, 'golang-transitive-tidy', redact);
    expect(tidy.exitCode, `go mod tidy: ${tidy.command}\n${tidy.stderr}`).toBe(0);

    // go.mod: A at its @latest, B pulled in as an indirect requirement at the version A NAMES
    // (v1.1.0), not B's own newest (v1.2.0) and not the older v1.0.0.
    const goMod = await fs.readFile(path.join(work, 'go.mod'), 'utf8');
    expect(goMod, 'A resolved to its @latest').toContain(`${a} v1.1.0`);
    expect(goMod, 'B is required at the version A names, marked indirect').toContain(
      `${b} v1.1.0 // indirect`,
    );
    expect(goMod).not.toContain(`${b} v1.2.0`);
    expect(goMod).not.toContain(`${b} v1.0.0`);

    // go.sum carries the dirhash of the very bytes uploaded, for the zip and the go.mod of both.
    const sums = await readGoSum(work);
    expect(sums.get(`${a} v1.1.0`), 'A zip hash').toBe(aV110.h1Zip);
    expect(sums.get(`${a} v1.1.0/go.mod`), 'A go.mod hash').toBe(aV110.h1Mod);
    expect(sums.get(`${b} v1.1.0`), 'B zip hash').toBe(bV110.h1Zip);
    expect(sums.get(`${b} v1.1.0/go.mod`), 'B go.mod hash').toBe(bV110.h1Mod);
    expect(sums.has(`${b} v1.0.0`), 'the older B zip was never needed').toBe(false);
    expect(sums.has(`${b} v1.2.0`), 'the newer B zip was never needed').toBe(false);

    const all = await go(['list', '-m', 'all'], work, goEnvVars, 'golang-transitive-all', redact);
    expect(all.exitCode, `go list -m all: ${all.stderr}`).toBe(0);
    const modules = parseModuleList(all.stdout);
    expect(modules.get('e2e.consumer'), 'the main module has no version').toBeUndefined();
    expect(modules.get(a)).toBe('v1.1.0');
    expect(modules.get(b)).toBe('v1.1.0');

    const versions = await go(
      ['list', '-m', '-versions', b],
      work,
      goEnvVars,
      'golang-transitive-versions',
      redact,
    );
    expect(versions.exitCode, `go list -m -versions: ${versions.stderr}`).toBe(0);
    expect(versions.stdout.trim(), "B's versions come from Repsy's @v/list").toBe(
      `${b} v1.0.0 v1.1.0 v1.2.0`,
    );

    // The program built from the resolved graph runs B v1.1.0's own code.
    const output = await go(['run', '.'], work, goEnvVars, 'golang-transitive-run', redact);
    expect(output.exitCode, `go run: ${output.stderr}`).toBe(0);
    expect(output.stdout.trim().split('\n')).toEqual([aV110.marker, bV110.marker]);
    expect(output.stdout).not.toContain(bV100.marker);
    expect(output.stdout).not.toContain(bV120.marker);

    // Every request the toolchain made for the graph carried the credential and hit A's and B's
    // metadata (the dependency's go.mod comes from `@v/v1.1.0.mod`, like any other).
    await expectShimRequests(traceBefore, repo.name, b);
  },
);

test(
  'golang > minimal version selection: the highest version anyone requires wins, never a newer ' +
    'one nobody asked for (RPS-1479 T2)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const b = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t2-b`;
    const a = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t2-a`;
    for (const version of ['v1.0.0', 'v1.1.0', 'v1.2.0']) {
      await publishModule(repo.name, b, version);
    }
    await publishModule(repo.name, a, 'v1.0.0', [{ modulePath: b, version: 'v1.0.0' }]);
    await publishModule(repo.name, a, 'v1.1.0', [{ modulePath: b, version: 'v1.1.0' }]);

    const cases = [
      // The consumer's own requirement is higher than A's: it stays.
      {
        id: 'consumer-higher',
        requires: [
          { modulePath: a, version: 'v1.0.0' },
          { modulePath: b, version: 'v1.1.0' },
        ],
        expected: { a: 'v1.0.0', b: 'v1.1.0' },
      },
      // A's requirement is higher than the consumer's: tidy raises the consumer's go.mod.
      {
        id: 'dependency-higher',
        requires: [
          { modulePath: a, version: 'v1.1.0' },
          { modulePath: b, version: 'v1.0.0' },
        ],
        expected: { a: 'v1.1.0', b: 'v1.1.0' },
      },
      // Nobody names v1.2.0: it is never selected although it is B's @latest.
      {
        id: 'older-dependency',
        requires: [{ modulePath: a, version: 'v1.0.0' }],
        expected: { a: 'v1.0.0', b: 'v1.0.0' },
      },
    ];

    for (const c of cases) {
      const { home, work } = await isolatedWorkDir(`golang-mvs-${c.id}-${seeder.runId}`);
      await writeConsumer(work, a, c.requires);
      const goEnvVars = await goEnv(home, {}, repo.name);

      const tidy = await go(['mod', 'tidy'], work, goEnvVars, `golang-mvs-${c.id}-tidy`);
      expect(tidy.exitCode, `[${c.id}] go mod tidy: ${tidy.command}\n${tidy.stderr}`).toBe(0);
      const all = await go(['list', '-m', 'all'], work, goEnvVars, `golang-mvs-${c.id}-all`);
      expect(all.exitCode, `[${c.id}] go list -m all: ${all.stderr}`).toBe(0);
      const modules = parseModuleList(all.stdout);
      expect(modules.get(a), `[${c.id}] A`).toBe(c.expected.a);
      expect(modules.get(b), `[${c.id}] B`).toBe(c.expected.b);
      const goMod = await fs.readFile(path.join(work, 'go.mod'), 'utf8');
      expect(goMod, `[${c.id}] the rewritten go.mod`).toContain(`${b} ${c.expected.b}`);
    }
  },
);

test(
  'golang > a /v2 major-path dependency is a module of its own next to /v1 in one repo (RPS-1479 T3)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const b1 = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t3-b`;
    const b2 = `${b1}/v2`;
    const a = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t3-a`;
    const c = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t3-c`;

    await publishModule(repo.name, b1, 'v1.0.0');
    const b1V110 = await publishModule(repo.name, b1, 'v1.1.0');
    const b2V200 = await publishModule(repo.name, b2, 'v2.0.0');
    const b2V210 = await publishModule(repo.name, b2, 'v2.1.0');
    // A needs the /v2 module only, C (below) needs both majors.
    const aBuilt = await publishModule(repo.name, a, 'v1.0.0', [
      { modulePath: b2, version: 'v2.1.0' },
    ]);
    const cBuilt = await publishModule(repo.name, c, 'v1.0.0', [
      { modulePath: b1, version: 'v1.1.0' },
      { modulePath: b2, version: 'v2.0.0' },
    ]);

    // Repsy serves the two major versions as separate modules: separate lists, separate @latest.
    const admin = adminCredential();
    const list1 = await rawGet(repo.name, admin, listRelPath(b1));
    expect(parseVersionList(list1.body), 'the /v1 module lists only /v1 versions').toEqual([
      'v1.0.0',
      'v1.1.0',
    ]);
    const list2 = await rawGet(repo.name, admin, listRelPath(b2));
    expect(parseVersionList(list2.body), 'the /v2 module lists only /v2 versions').toEqual([
      'v2.0.0',
      'v2.1.0',
    ]);
    const latest2 = await rawGet(repo.name, admin, latestRelPath(b2));
    expect(parseInfo(latest2.body).Version, '@latest of the /v2 path').toBe('v2.1.0');

    const { home, work } = await isolatedWorkDir(`golang-v2-${seeder.runId}`);
    await writeConsumer(work, a);
    const goEnvVars = await goEnv(home, {}, repo.name);
    const tidy = await go(['mod', 'tidy'], work, goEnvVars, 'golang-v2-tidy');
    expect(tidy.exitCode, `go mod tidy: ${tidy.command}\n${tidy.stderr}`).toBe(0);

    const sums = await readGoSum(work);
    expect(sums.get(`${b2} v2.1.0`), 'the /v2 zip hash').toBe(b2V210.h1Zip);
    expect(sums.get(`${b2} v2.1.0/go.mod`), 'the /v2 go.mod hash').toBe(b2V210.h1Mod);
    const goMod = await fs.readFile(path.join(work, 'go.mod'), 'utf8');
    expect(goMod, 'the /v2 dependency is required under its /v2 path').toContain(
      `${b2} v2.1.0 // indirect`,
    );
    const run1 = await go(['run', '.'], work, goEnvVars, 'golang-v2-run');
    expect(run1.exitCode, `go run: ${run1.stderr}`).toBe(0);
    expect(run1.stdout.trim().split('\n')).toEqual([aBuilt.marker, b2V210.marker]);

    const versions = await go(['list', '-m', '-versions', b2], work, goEnvVars, 'golang-v2-vers');
    expect(versions.exitCode, `go list -m -versions: ${versions.stderr}`).toBe(0);
    expect(versions.stdout.trim()).toBe(`${b2} v2.0.0 v2.1.0`);

    // A consumer of C gets BOTH majors of B, side by side, at the versions C's go.mod names.
    const second = await isolatedWorkDir(`golang-v2-both-${seeder.runId}`);
    await writeConsumer(second.work, c);
    const secondEnv = await goEnv(second.home, {}, repo.name);
    const tidy2 = await go(['mod', 'tidy'], second.work, secondEnv, 'golang-v2-both-tidy');
    expect(tidy2.exitCode, `go mod tidy (both majors): ${tidy2.command}\n${tidy2.stderr}`).toBe(0);
    const all = await go(['list', '-m', 'all'], second.work, secondEnv, 'golang-v2-both-all');
    expect(all.exitCode, `go list -m all: ${all.stderr}`).toBe(0);
    const modules = parseModuleList(all.stdout);
    expect(modules.get(c)).toBe('v1.0.0');
    expect(modules.get(b1)).toBe('v1.1.0');
    expect(modules.get(b2)).toBe('v2.0.0');
    const run2 = await go(['run', '.'], second.work, secondEnv, 'golang-v2-both-run');
    expect(run2.exitCode, `go run (both majors): ${run2.stderr}`).toBe(0);
    expect(run2.stdout.trim().split('\n')).toEqual([
      cBuilt.marker,
      `${b1V110.marker},${b2V200.marker}`,
    ]);
  },
);

test(
  'golang > the go.mod of a dependency is served verbatim at @v/<v>.mod, next to .info, @latest ' +
    'and @v/list (RPS-1479 T4)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const b = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t4-b`;
    const a = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t4-a`;
    await publishModule(repo.name, b, 'v1.0.0');
    await publishModule(repo.name, b, 'v1.1.0');
    const aBuilt = await publishModule(repo.name, a, 'v1.0.0', [
      { modulePath: b, version: 'v1.0.0' },
      { modulePath: `${b}/v2`, version: 'v2.0.0' },
    ]);
    await publishModule(repo.name, `${b}/v2`, 'v2.0.0');

    const admin = adminCredential();
    const modRes = await rawGet(repo.name, admin, modRelPath(a, 'v1.0.0'));
    expect(modRes.status).toBe(200);
    expect(modRes.contentType).toBe('text/plain');
    expect(modRes.body.toString('utf8'), 'the require block is served exactly as uploaded').toBe(
      `module ${a}\n\ngo 1.21\n\nrequire (\n\t${b} v1.0.0\n\t${b}/v2 v2.0.0\n)\n`,
    );
    expect(modRes.body.equals(aBuilt.goMod), 'the served .mod equals the zip’s go.mod').toBe(true);

    const info = await rawGet(repo.name, admin, infoRelPath(b, 'v1.1.0'));
    expect(info.status).toBe(200);
    expect(parseInfo(info.body).Version).toBe('v1.1.0');
    const latest = await rawGet(repo.name, admin, latestRelPath(b));
    expect(latest.status).toBe(200);
    expect(parseInfo(latest.body).Version, "B's @latest is its highest version").toBe('v1.1.0');
    const list = await rawGet(repo.name, admin, listRelPath(b));
    expect(parseVersionList(list.body)).toEqual(['v1.0.0', 'v1.1.0']);
  },
);

test(
  'golang > go mod tidy fails in the consumer when a dependency was never published (RPS-1479 T5)',
  { tag: ['@negative'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const b = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t5-b`;
    const a = `${MODULE_DOMAIN}/e2e-${seeder.runId}-t5-a`;
    await publishModule(repo.name, a, 'v1.0.0', [{ modulePath: b, version: 'v1.0.0' }]);

    const { home, work } = await isolatedWorkDir(`golang-missingdep-${seeder.runId}`);
    await writeConsumer(work, a);
    const goEnvVars = await goEnv(home, {}, repo.name);
    const tidy = await go(['mod', 'tidy'], work, goEnvVars, 'golang-missingdep-tidy');
    expect(tidy.exitCode, `go mod tidy: ${tidy.command}`).toBe(1);
    expect(tidy.stderr, 'the message names the missing dependency').toContain(b);
    // The proxy's 404 for B's go.mod makes `go` fall through to the next GOPROXY entry, `off`.
    expect(tidy.stderr, 'the proxy list is exhausted').toContain(
      'module lookup disabled by GOPROXY=off',
    );
    const modRes = await rawGet(repo.name, adminCredential(), modRelPath(b, 'v1.0.0'));
    expect(modRes.status, "Repsy answers 404 for the unpublished dependency's go.mod").toBe(404);
  },
);
