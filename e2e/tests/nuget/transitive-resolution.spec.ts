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
 * NuGet transitive dependency resolution against Repsy-served metadata (RPS-1479, epic RPS-1473):
 * package A declares a dependency on package B in its nuspec, both are pushed with the real
 * `dotnet nuget push`, and a REAL `dotnet restore` of a project that references only A must resolve
 * B by itself. What is asserted is the resolved graph (`obj/project.assets.json`: `targets`,
 * `libraries`, `projectFileDependencyGroups`), not just an exit code, plus the bytes the restore
 * fetched, and next to it the two places where Repsy serves the dependencies:
 *
 *  - the flat container `.nuspec` (`v3/package/<id>/<ver>/<id>.<ver>.nuspec`), which is what
 *    `dotnet restore` reads (`RemoteV3FindPackageByIdResource`, no registration request at all,
 *    H17 below), and
 *  - the registration leaf's `catalogEntry.dependencyGroups` (`NuGetResponseMapper.buildDependencyGroups`),
 *    what NuGet clients that resolve through the registration (Visual Studio, `nuget.exe`) read.
 *
 * Probed live BEFORE the assertions were written (the exact outputs are in this PR's description):
 *
 *  - H17 (confirmed): restore resolves through the flat container. Multi-targeted consumers work
 *    without a targeting pack from nuget.org because `DisableImplicitFrameworkReferences=true`
 *    keeps `Microsoft.NETCore.App.Ref`/`NETStandard.Library` out of the restore (the `<clear/>`
 *    source has nothing but Repsy), so ONE restore of `net10.0;netstandard2.0` checks both groups.
 *  - Target-framework groups (confirmed): `net10.0` and `.NETStandard2.0` (both spellings are
 *    accepted) each apply to their own consumer target only; a group without `targetFramework`
 *    applies to every target that has no nearer group.
 *  - Empty group (confirmed): a nuspec's empty `<group targetFramework="net10.0"/>` ("nothing for
 *    net10.0") is honoured by the restore, because it reads the nuspec. It is NOT in the
 *    registration's `dependencyGroups` (`buildDependencyGroups` only sees dependencies, and an
 *    empty group has none): the report of this PR proposes a story, so nothing here pins the
 *    registration's handling of an empty group.
 *  - Registration INDEX (probed, not asserted): the per-leaf documents (`v3/registration/<id>/
 *    <ver>.json`) carry `catalogEntry.dependencyGroups`, but the leaves inlined into the registration
 *    index's pages (`toLeafItem` over `toVersionInfo`, which ignores the dependencies) never do. The
 *    NuGet registration spec has them there too; proposed as a story in the report, so this file
 *    reads the per-leaf documents only.
 *  - Unlisted dependency (confirmed): `unlist` (WRITE) only flips `listed` on the registration; the
 *    flat container keeps offering the version and the client does not look at `listed`, so an
 *    unlisted B still satisfies `[1.0.0, )` (lowest applicable), as on nuget.org.
 *  - `semVerLevel` (confirmed): search hides a SemVer 2.0.0-only version (dot-separated
 *    pre-release label) from a client that sends no `semVerLevel` (RPS-1275), but the flat container
 *    and the registration have no such parameter, so resolving it as a dependency works.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run, type RunResult } from '../../src/clients/exec.js';
import { nugetEnv, renderNugetConfig } from '../../src/clients/nuget.js';
import {
  adminCredential,
  buildNupkg,
  nugetApiKey,
  normalizeVersion,
  parseLeafDependencyGroups,
  parseSearchResponse,
  rawDownloadNupkg,
  rawDownloadNuspec,
  rawGetRegistrationLeaf,
  rawSearch,
  rawUnlist,
  sha256Hex,
  type NuspecDependency,
} from '../../src/clients/nuget-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  /** Run-unique prefix of every package id of one test. */
  prefix: string;
  credential: MaterializedCredential;
}

/** A fresh private nuget repo and a fresh read-write deploy token for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  return {
    repoName: repo.name,
    prefix: `e2e-${seeder.runId}-${label}`,
    credential: {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    },
  };
}

/** Builds a nupkg (with the dependencies its nuspec declares) and pushes it with the real
 *  `dotnet nuget push`; returns the bytes it pushed. */
async function pushPackage(
  layout: Layout,
  pkg: {
    id: string;
    version: string;
    dependencies?: NuspecDependency[];
    emptyGroups?: string[];
  },
): Promise<Buffer> {
  const nupkg = buildNupkg({
    packageId: pkg.id,
    version: pkg.version,
    ...(pkg.dependencies === undefined ? {} : { dependencies: pkg.dependencies }),
    ...(pkg.emptyGroups === undefined ? {} : { emptyGroups: pkg.emptyGroups }),
  });
  const label = `nuget-transitive-push-${pkg.version}`;
  const { home, work } = await isolatedWorkDir(label);
  const file = path.join(work, 'package.nupkg');
  await fs.writeFile(file, nupkg);
  const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
  const apiKey = nugetApiKey(layout.credential) ?? '';
  const pushed = await run(
    'dotnet',
    [
      'nuget',
      'push',
      file,
      '--source',
      'repsy',
      '--configfile',
      cfg,
      '--allow-insecure-connections',
      '--no-symbols',
      '--timeout',
      '60',
      '--api-key',
      apiKey,
    ],
    {
      cwd: work,
      env: nugetEnv(home),
      timeoutMs: 120_000,
      redact: [apiKey, layout.credential.password ?? ''],
      label,
    },
  );
  expect(pushed.exitCode, `dotnet nuget push ${pkg.id}@${pkg.version}: ${pushed.command}`).toBe(0);
  return nupkg;
}

/** The parts of `obj/project.assets.json` these tests read. */
interface ProjectAssets {
  version: number;
  targets: Record<string, Record<string, { type?: string; dependencies?: Record<string, string> }>>;
  libraries: Record<string, { type?: string }>;
  projectFileDependencyGroups: Record<string, string[]>;
}

interface Restored {
  run: RunResult;
  /** `undefined` when the restore did not get as far as writing one. */
  assets: ProjectAssets | undefined;
  packagesDir: string;
}

/**
 * A real `dotnet restore` of a fresh classlib that references exactly `refs` (`Version` is a NuGet
 * version range) for `targetFrameworks`, against the repo only (`<clear/>` config).
 * `DisableImplicitFrameworkReferences` keeps the targeting packs (nothing but Repsy is a source)
 * out of the restore, so `net10.0;netstandard2.0` restores offline.
 */
async function restoreConsumer(
  layout: Layout,
  targetFrameworks: string[],
  refs: { id: string; version: string }[],
): Promise<Restored> {
  const { home, work } = await isolatedWorkDir('nuget-transitive-restore');
  const csproj = path.join(work, 'consumer.csproj');
  const references = refs
    .map((r) => `    <PackageReference Include="${r.id}" Version="${r.version}" />`)
    .join('\n');
  await fs.writeFile(
    csproj,
    '<Project Sdk="Microsoft.NET.Sdk">\n' +
      '  <PropertyGroup>\n' +
      `    <TargetFrameworks>${targetFrameworks.join(';')}</TargetFrameworks>\n` +
      '    <NuGetAudit>false</NuGetAudit>\n' +
      '    <DisableImplicitFrameworkReferences>true</DisableImplicitFrameworkReferences>\n' +
      '  </PropertyGroup>\n' +
      `  <ItemGroup>\n${references}\n  </ItemGroup>\n` +
      '</Project>\n',
  );
  const packagesDir = path.join(home, 'nuget-packages');
  const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
  const restored = await run(
    'dotnet',
    [
      'restore',
      csproj,
      '--configfile',
      cfg,
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
      timeoutMs: 120_000,
      redact: [layout.credential.password ?? ''],
      label: 'nuget-transitive-restore',
    },
  );
  let assets: ProjectAssets | undefined;
  try {
    assets = JSON.parse(
      await fs.readFile(path.join(work, 'obj', 'project.assets.json'), 'utf8'),
    ) as ProjectAssets;
  } catch {
    assets = undefined;
  }
  return { run: restored, assets, packagesDir };
}

/** `{ id: version }` of every package the restore resolved for one target framework. */
function resolvedFor(restored: Restored, target: string): Record<string, string> {
  expect(
    restored.run.exitCode,
    `dotnet restore: ${restored.run.command}\n${restored.run.stdout}\n${restored.run.stderr}`,
  ).toBe(0);
  const targets = restored.assets?.targets[target];
  expect(targets, `project.assets.json has a "${target}" target`).toBeDefined();
  const out: Record<string, string> = {};
  for (const key of Object.keys(targets ?? {})) {
    const slash = key.lastIndexOf('/');
    out[key.slice(0, slash)] = key.slice(slash + 1);
  }
  return out;
}

async function restoredNupkgSha(restored: Restored, id: string, version: string): Promise<string> {
  const idLower = id.toLowerCase();
  const verLower = normalizeVersion(version);
  const bytes = await fs.readFile(
    path.join(restored.packagesDir, idLower, verLower, `${idLower}.${verLower}.nupkg`),
  );
  return sha256Hex(bytes);
}

test.describe('nuget transitive dependency resolution (real dotnet restore)', () => {
  test(
    'nuget > a restore of A alone resolves its dependency B to the lowest version its range allows',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const l = await newRepo(seeder, 'ranges');
      const b = `${l.prefix}-b`;
      const bytesOfB: Record<string, Buffer> = {};
      for (const version of ['1.0.0', '1.1.0', '2.0.0']) {
        bytesOfB[version] = await pushPackage(l, { id: b, version });
      }

      // One A per range form; the lowest published B the range admits is what NuGet picks.
      const cases = [
        { name: 'open', range: '[1.0.0, )', asAssets: '1.0.0', expected: '1.0.0' },
        { name: 'bare', range: '1.1.0', asAssets: '1.1.0', expected: '1.1.0' },
        { name: 'exclusive', range: '(1.0.0, )', asAssets: '(1.0.0, )', expected: '1.1.0' },
        { name: 'exact', range: '[2.0.0]', asAssets: '[2.0.0]', expected: '2.0.0' },
        { name: 'capped', range: '[1.1.0, 2.0.0)', asAssets: '[1.1.0, 2.0.0)', expected: '1.1.0' },
      ];
      for (const c of cases) {
        await pushPackage(l, {
          id: `${l.prefix}-a-${c.name}`,
          version: '1.0.0',
          dependencies: [{ id: b, range: c.range, targetFramework: 'net10.0' }],
        });
      }

      for (const c of cases) {
        const a = `${l.prefix}-a-${c.name}`;
        const restored = await restoreConsumer(l, ['net10.0'], [{ id: a, version: '1.0.0' }]);
        const resolved = resolvedFor(restored, 'net10.0');
        expect(resolved, `range ${c.range}: the resolved graph`).toEqual({
          [a]: '1.0.0',
          [b]: c.expected,
        });
        expect(Object.keys(restored.assets?.libraries ?? {}).sort(), `range ${c.range}`).toEqual(
          [`${a}/1.0.0`, `${b}/${c.expected}`].sort(),
        );
        expect(
          restored.assets?.targets['net10.0']?.[`${a}/1.0.0`]?.dependencies,
          `range ${c.range}: what the assets file says A depends on (the range NuGet read out of the served nuspec)`,
        ).toEqual({ [b]: c.asAssets });
        expect(
          restored.assets?.projectFileDependencyGroups['net10.0'],
          `range ${c.range}: the project itself references A only`,
        ).toEqual([`${a} >= 1.0.0`]);
        // B's bytes came from Repsy, unaltered.
        expect(
          await restoredNupkgSha(restored, b, c.expected),
          `range ${c.range}: the restored B nupkg`,
        ).toBe(sha256Hex(bytesOfB[c.expected] as Buffer));
      }
    },
  );

  test(
    'nuget > Repsy serves the declared dependencies in the flat-container nuspec and the registration leaf',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const l = await newRepo(seeder, 'metadata');
      const a = `${l.prefix}-a`;
      const b = `${l.prefix}-b`;
      const c = `${l.prefix}-c`;
      await pushPackage(l, { id: b, version: '1.0.0' });
      await pushPackage(l, { id: c, version: '1.0.0' });
      await pushPackage(l, {
        id: a,
        version: '1.0.0',
        dependencies: [
          { id: b, range: '[1.0.0, )', targetFramework: 'net10.0' },
          { id: c, range: '[1.0.0, 2.0.0)', targetFramework: 'net10.0' },
          { id: c, range: '1.0.0', targetFramework: '.NETStandard2.0' },
          { id: b },
        ],
      });

      const admin = adminCredential();
      const nuspec = await rawDownloadNuspec(l.repoName, admin, a, '1.0.0');
      expect(nuspec.status, 'GET flat-container .nuspec').toBe(200);
      const nuspecText = nuspec.body.toString('utf8');
      expect(nuspecText).toContain('<group targetFramework="net10.0">');
      expect(nuspecText).toContain(`<dependency id="${b}" version="[1.0.0, )" />`);
      expect(nuspecText).toContain(`<dependency id="${c}" version="[1.0.0, 2.0.0)" />`);

      const leaf = await rawGetRegistrationLeaf(l.repoName, admin, a, '1.0.0');
      expect(leaf.status, 'GET registration leaf').toBe(200);
      const groups = parseLeafDependencyGroups(leaf.body);
      expect(groups, "the registration leaf's dependencyGroups").toEqual([
        {
          targetFramework: 'net10.0',
          dependencies: [
            { id: b, range: '[1.0.0, )' },
            { id: c, range: '[1.0.0, 2.0.0)' },
          ],
        },
        { targetFramework: '.NETStandard2.0', dependencies: [{ id: c, range: '1.0.0' }] },
        // A dependency with no version attribute is a dependency without a range, in a group
        // without a target framework.
        { dependencies: [{ id: b }] },
      ]);
    },
  );

  test(
    'nuget > each target-framework group of A applies to its own target only',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const l = await newRepo(seeder, 'tfm');
      const b = `${l.prefix}-b`;
      const c = `${l.prefix}-c`;
      const d = `${l.prefix}-d`;
      for (const id of [b, c, d]) {
        await pushPackage(l, { id, version: '1.0.0' });
      }

      // A: net10.0 -> B, netstandard2.0 -> C, nothing for the other targets.
      const a = `${l.prefix}-a`;
      await pushPackage(l, {
        id: a,
        version: '1.0.0',
        dependencies: [
          { id: b, range: '[1.0.0, )', targetFramework: 'net10.0' },
          { id: c, range: '[1.0.0, )', targetFramework: '.NETStandard2.0' },
        ],
      });
      const both = await restoreConsumer(
        l,
        ['net10.0', 'netstandard2.0'],
        [{ id: a, version: '[1.0.0]' }],
      );
      expect(resolvedFor(both, 'net10.0'), 'net10.0 target').toEqual({
        [a]: '1.0.0',
        [b]: '1.0.0',
      });
      expect(resolvedFor(both, 'netstandard2.0'), 'netstandard2.0 target').toEqual({
        [a]: '1.0.0',
        [c]: '1.0.0',
      });

      // E: a group without targetFramework applies to every target ("any"), and the empty
      // net10.0 group says "nothing for net10.0" -- the nuspec, which restore reads, is honoured
      // (the registration drops an empty group, see the file header).
      const e = `${l.prefix}-e`;
      await pushPackage(l, {
        id: e,
        version: '1.0.0',
        dependencies: [{ id: d, range: '[1.0.0, )' }],
      });
      const anyGroup = await restoreConsumer(
        l,
        ['net10.0', 'netstandard2.0'],
        [{ id: e, version: '[1.0.0]' }],
      );
      expect(resolvedFor(anyGroup, 'net10.0'), 'any group, net10.0').toEqual({
        [e]: '1.0.0',
        [d]: '1.0.0',
      });
      expect(resolvedFor(anyGroup, 'netstandard2.0'), 'any group, netstandard2.0').toEqual({
        [e]: '1.0.0',
        [d]: '1.0.0',
      });

      const f = `${l.prefix}-f`;
      await pushPackage(l, {
        id: f,
        version: '1.0.0',
        dependencies: [{ id: c, range: '[1.0.0, )', targetFramework: '.NETStandard2.0' }],
        emptyGroups: ['net10.0'],
      });
      const emptyGroup = await restoreConsumer(
        l,
        ['net10.0', 'netstandard2.0'],
        [{ id: f, version: '[1.0.0]' }],
      );
      expect(resolvedFor(emptyGroup, 'net10.0'), 'empty net10.0 group').toEqual({ [f]: '1.0.0' });
      expect(resolvedFor(emptyGroup, 'netstandard2.0'), 'netstandard2.0 group').toEqual({
        [f]: '1.0.0',
        [c]: '1.0.0',
      });
    },
  );

  test('nuget > an unlisted version of B still satisfies the dependency (unlist only flips the registration)', async ({
    seeder,
  }) => {
    const l = await newRepo(seeder, 'unlist');
    const a = `${l.prefix}-a`;
    const b = `${l.prefix}-b`;
    await pushPackage(l, { id: b, version: '1.0.0' });
    await pushPackage(l, { id: b, version: '1.1.0' });
    await pushPackage(l, {
      id: a,
      version: '1.0.0',
      dependencies: [{ id: b, range: '[1.0.0, )', targetFramework: 'net10.0' }],
    });

    const unlist = await rawUnlist(l.repoName, l.credential, b, '1.0.0');
    expect(unlist.status, `unlist B 1.0.0: ${unlist.body.toString()}`).toBe(204);

    // The flat container still serves the unlisted nupkg, and the restore does not look at
    // `listed`: the lowest applicable version is still 1.0.0.
    const restored = await restoreConsumer(l, ['net10.0'], [{ id: a, version: '[1.0.0]' }]);
    expect(resolvedFor(restored, 'net10.0'), 'B 1.0.0 is unlisted').toEqual({
      [a]: '1.0.0',
      [b]: '1.0.0',
    });
    const served = await rawDownloadNupkg(l.repoName, adminCredential(), b, '1.0.0');
    expect(sha256Hex(served.body), 'the restored B is what the flat container serves').toBe(
      await restoredNupkgSha(restored, b, '1.0.0'),
    );
  });

  test('nuget > a SemVer 2.0.0-only dependency resolves although search hides it from a client without semVerLevel (RPS-1275)', async ({
    seeder,
  }) => {
    const l = await newRepo(seeder, 'semver2');
    const a = `${l.prefix}-a`;
    const b = `${l.prefix}-b`;
    // A dot-separated pre-release label is SemVer 2.0.0-only; the range names it, so NuGet
    // admits a pre-release (a range whose lower bound is stable never picks one).
    await pushPackage(l, { id: b, version: '1.0.0-rc.1' });
    await pushPackage(l, {
      id: a,
      version: '1.0.0',
      dependencies: [{ id: b, range: '[1.0.0-rc.1, )', targetFramework: 'net10.0' }],
    });

    const admin = adminCredential();
    const withoutLevel = parseSearchResponse((await rawSearch(l.repoName, admin, b, true)).body);
    expect(
      withoutLevel.data.map((d) => d.id),
      'search without semVerLevel does not list the SemVer 2.0.0-only B',
    ).toEqual([]);
    const withLevel = parseSearchResponse(
      (await rawSearch(l.repoName, admin, b, true, '2.0.0')).body,
    );
    expect(
      withLevel.data.map((d) => d.id),
      'search with semVerLevel=2.0.0 lists it',
    ).toEqual([b]);

    const restored = await restoreConsumer(l, ['net10.0'], [{ id: a, version: '[1.0.0]' }]);
    expect(resolvedFor(restored, 'net10.0')).toEqual({ [a]: '1.0.0', [b]: '1.0.0-rc.1' });
  });

  test('nuget > a dependency no published version satisfies fails the restore and names it', async ({
    seeder,
  }) => {
    const l = await newRepo(seeder, 'unsatisfied');
    const a = `${l.prefix}-a`;
    const b = `${l.prefix}-b`;
    await pushPackage(l, { id: b, version: '1.0.0' });
    await pushPackage(l, {
      id: a,
      version: '1.0.0',
      dependencies: [{ id: b, range: '[3.0.0, )', targetFramework: 'net10.0' }],
    });

    const restored = await restoreConsumer(l, ['net10.0'], [{ id: a, version: '[1.0.0]' }]);
    expect(restored.run.exitCode, 'dotnet restore must fail').not.toBe(0);
    const output = `${restored.run.stdout}\n${restored.run.stderr}`;
    expect(output, 'the message names the unsatisfiable dependency').toContain(b);
    expect(output, 'the message shows the range').toContain('3.0.0');
  });
});
