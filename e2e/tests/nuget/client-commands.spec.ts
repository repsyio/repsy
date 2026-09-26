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
 * Real `dotnet` commands of the .NET client that the rest of the suite reaches only through raw
 * requests or the push/restore pair (RPS-1486, epic RPS-1473):
 *
 *  - `dotnet add package <id> --version <v> ...` in an isolated project: the commands the panel
 *    shows on a version page (`nuget-packages-version-detail.component.ts`), with the credentials
 *    where the panel's "Option A" puts them (a user-level `NuGet.Config` with
 *    `packageSourceCredentials`, in the isolated HOME: never on the command line). What is asserted is
 *    the `PackageReference` the command writes into the csproj and the resolved graph in
 *    `obj/project.assets.json`, plus the bytes it restored.
 *  - Over plain HTTP (this stack, and any Repsy without TLS) `dotnet add package --source <url>` needs
 *    the source to be configured with `allowInsecureConnections="true"` (`NU1302` otherwise), so the
 *    panel's "no NuGet.Config needed" for the direct URL only holds over HTTPS; the direct-URL test
 *    below has the source in the user-level file for that reason.
 *  - `dotnet nuget delete` (NuGet's unlist command; the wire permission matrix runs it as a client
 *    cell for every credential, `nuget-manage.ts`'s `unlist-client`): here the effect on what a
 *    consumer sees: the version disappears from the listing (`listed: false`) but stays downloadable
 *    and resolvable, exactly as `unlist` is meant to work.
 *  - A symbol package: what a real `dotnet pack --include-symbols -p:SymbolPackageFormat=snupkg`
 *    produces and how the real `dotnet nuget push` treats the `.snupkg`.
 *
 * Probed live BEFORE the assertions were written (SDK 10.0.401, NuGet.Client 7.9):
 *
 *  - The panel's Option A snippet `dotnet add package <id> --version <v> --source repsy` FAILS with
 *    the real client: `error: NU1301: The local source '<cwd>/repsy' doesn't exist.`, exit 1. `dotnet
 *    add package --source` takes a URL or a folder, never the name of a configured source (`dotnet
 *    nuget push --source repsy` does resolve names). Nothing here pins that (it is a panel text
 *    bug, proposed as a story in the PR report); the tests run what works: the direct-URL snippet and
 *    the plain `dotnet add package <id> --version <v>` that uses the project's `NuGet.Config` source.
 *  - `dotnet nuget push x.snupkg --source repsy` exits 0 and sends NOTHING (no `PUT`, no message): the
 *    client pushes a symbol package only to a source whose service index advertises
 *    `SymbolPackagePublish/4.9.0` (probed: Repsy's has PackageBaseAddress, PackagePublish,
 *    RegistrationsBaseUrl and the search/autocomplete services, no symbol resource). `dotnet nuget push x.nupkg` of a `dotnet pack
 *    --include-symbols` output sends the `.nupkg` alone, the `.snupkg` next to it stays unpushed, with
 *    or without `--symbol-source`. So the real client never reaches the server's publish endpoint with
 *    a symbol package; a raw `PUT` of one is another matter (proposed as a backend story in the PR
 *    report, not asserted here).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run, type RunResult } from '../../src/clients/exec.js';
import {
  nugetEnv,
  renderConsumerProject,
  renderNugetConfig,
  userNugetConfigPath,
} from '../../src/clients/nuget.js';
import { dotnetNugetDelete } from '../../src/clients/nuget-manage.js';
import {
  adminCredential,
  buildNupkg,
  nugetApiKey,
  normalizeVersion,
  parseRegistrationIndex,
  parseServiceIndex,
  parseVersions,
  rawDownloadNupkg,
  rawGetRegistrationIndex,
  rawGetServiceIndex,
  rawGetVersions,
  rawPublish,
  rawUnlist,
  serviceIndexUrl,
  sha256Hex,
} from '../../src/clients/nuget-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

const CLIENT_TIMEOUT_MS = 180_000;
const ANONYMOUS: MaterializedCredential = {};

interface Layout {
  repoName: string;
  credential: MaterializedCredential;
}

/** A fresh nuget repo, and a read-write deploy token for it (private) or no credential (public). */
async function newRepo(seeder: Seeder, privateRepo: boolean): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo });
  if (!privateRepo) {
    return { repoName: repo.name, credential: ANONYMOUS };
  }
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

/** Seeds one hand-built package per version with a raw admin `PUT` (fast; the push is not what
 *  these tests are about). */
async function seedVersions(layout: Layout, packageId: string, versions: string[]): Promise<void> {
  for (const version of versions) {
    const res = await rawPublish(
      layout.repoName,
      adminCredential(),
      buildNupkg({ packageId, version }),
    );
    expect(res.status, `seed ${packageId}@${version}: ${res.body.toString('utf8')}`).toBe(201);
  }
}

const EMPTY_PROJECT =
  '<Project Sdk="Microsoft.NET.Sdk">\n' +
  '  <PropertyGroup>\n' +
  '    <TargetFramework>net10.0</TargetFramework>\n' +
  '    <NuGetAudit>false</NuGetAudit>\n' +
  '  </PropertyGroup>\n' +
  '</Project>\n';

interface ProjectAssets {
  targets: Record<string, Record<string, unknown>>;
  libraries: Record<string, { type?: string; path?: string }>;
  projectFileDependencyGroups: Record<string, string[]>;
}

async function readAssets(work: string): Promise<ProjectAssets> {
  return JSON.parse(
    await fs.readFile(path.join(work, 'obj', 'project.assets.json'), 'utf8'),
  ) as ProjectAssets;
}

/** What `dotnet add package` did to an isolated, empty net10.0 project. */
interface Added {
  run: RunResult;
  csproj: string;
  work: string;
  home: string;
}

/**
 * Runs `dotnet add package <id> [--version <v>] <extra...>` in a fresh project. `setup` decides where
 * the source and the credential are: the user-level `NuGet.Config` of the isolated HOME (the
 * credential, as the panel's "Option A" tells users) and/or the project's own `NuGet.Config`.
 */
async function dotnetAddPackage(
  layout: Layout,
  packageId: string,
  args: string[],
  setup: { user: 'none' | 'credentials' | 'full'; projectSource: boolean },
): Promise<Added> {
  const { home, work } = await isolatedWorkDir('nuget-add');
  await fs.writeFile(path.join(work, 'app.csproj'), EMPTY_PROJECT);
  if (setup.user !== 'none') {
    await renderNugetConfig(home, layout.repoName, layout.credential, {
      includeSource: setup.user === 'full',
      destination: userNugetConfigPath(home),
    });
  }
  if (setup.projectSource) {
    // Only the source. The panel's snippet does not `<clear/>` the machine-wide sources; this
    // harness's template does (no nuget.org in a runner without network).
    await renderNugetConfig(home, layout.repoName, layout.credential, {
      includeCredentials: false,
      destination: path.join(work, 'NuGet.Config'),
    });
  }
  const secret = layout.credential.password;
  const result = await run('dotnet', ['add', 'package', packageId, ...args], {
    cwd: work,
    env: nugetEnv(home),
    timeoutMs: CLIENT_TIMEOUT_MS,
    redact: secret ? [secret] : [],
    label: 'nuget-add-package',
  });
  return {
    run: result,
    csproj: await fs.readFile(path.join(work, 'app.csproj'), 'utf8'),
    work,
    home,
  };
}

function describeRun(what: string, r: RunResult): string {
  return `${what}: ${r.command}\n${r.stdout}\n${r.stderr}`;
}

/** The checks every successful `dotnet add package <id> --version <v>` shares (its exit code aside). */
async function expectAdded(added: Added, layout: Layout, packageId: string, version: string) {
  // The PackageReference the panel's own snippet shows (`packageReferenceCommand`).
  expect(added.csproj, 'the csproj carries the PackageReference').toContain(
    `<PackageReference Include="${packageId}" Version="${version}" />`,
  );
  const password = layout.credential.password;
  if (password !== undefined) {
    expect(added.csproj, 'no credential ends up in the project file').not.toContain(password);
  }

  const assets = await readAssets(added.work);
  expect(assets.libraries[`${packageId}/${version}`]?.type, 'the assets file resolves it').toBe(
    'package',
  );
  expect(assets.projectFileDependencyGroups['net10.0'], 'as a direct dependency').toContain(
    `${packageId} >= ${version}`,
  );

  // The bytes restored into the global-packages folder are the bytes Repsy serves.
  const idLower = packageId.toLowerCase();
  const verLower = normalizeVersion(version);
  const restored = await fs.readFile(
    path.join(added.home, 'nuget-packages', idLower, verLower, `${idLower}.${verLower}.nupkg`),
  );
  const served = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, verLower);
  expect(served.status).toBe(200);
  expect(sha256Hex(restored), 'the restored nupkg is the served one').toBe(sha256Hex(served.body));
}

test.describe('nuget > dotnet add package (the panel install snippets)', () => {
  test('the direct-URL snippet, the source and the credential in the user-level NuGet.Config', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-add-url`;
    await seedVersions(layout, packageId, ['1.2.3']);

    const added = await dotnetAddPackage(
      layout,
      packageId,
      ['--version', '1.2.3', '--source', serviceIndexUrl(layout.repoName)],
      { user: 'full', projectSource: false },
    );
    expect(added.run.exitCode, describeRun('dotnet add package', added.run)).toBe(0);
    await expectAdded(added, layout, packageId, '1.2.3');
  });

  test('the NuGet.Config setup: source in the project file, credential in the user-level file', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-add-config`;
    await seedVersions(layout, packageId, ['2.0.0']);

    // No --source: the project's NuGet.Config names it (the panel's "Option A" layout).
    const added = await dotnetAddPackage(layout, packageId, ['--version', '2.0.0'], {
      user: 'credentials',
      projectSource: true,
    });
    expect(added.run.exitCode, describeRun('dotnet add package', added.run)).toBe(0);
    await expectAdded(added, layout, packageId, '2.0.0');
  });

  test('a public repository needs no credential', async ({ seeder }) => {
    const layout = await newRepo(seeder, false);
    const packageId = `e2e-${seeder.runId}-add-public`;
    await seedVersions(layout, packageId, ['1.0.0']);

    const added = await dotnetAddPackage(
      layout,
      packageId,
      ['--version', '1.0.0', '--source', serviceIndexUrl(layout.repoName)],
      { user: 'none', projectSource: true },
    );
    expect(added.run.exitCode, describeRun('dotnet add package', added.run)).toBe(0);
    await expectAdded(added, layout, packageId, '1.0.0');
  });

  test(
    'a private repository without a credential fails and leaves the project untouched',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, true);
      const packageId = `e2e-${seeder.runId}-add-nocred`;
      await seedVersions(layout, packageId, ['1.0.0']);

      const added = await dotnetAddPackage(
        layout,
        packageId,
        ['--version', '1.0.0', '--source', serviceIndexUrl(layout.repoName)],
        { user: 'none', projectSource: true },
      );
      expect(added.run.exitCode, describeRun('dotnet add package', added.run)).not.toBe(0);
      expect(`${added.run.stdout}\n${added.run.stderr}`, 'the client reports the 401').toMatch(
        /401|Unauthorized/,
      );
      expect(added.csproj, 'a failed add leaves the project as it was').toBe(EMPTY_PROJECT);
    },
  );

  test('without --version the highest stable version wins, --prerelease admits pre-releases', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-add-latest`;
    await seedVersions(layout, packageId, ['1.0.0', '1.1.0', '2.0.0-beta.1']);
    const setup = { user: 'credentials' as const, projectSource: true };

    const stable = await dotnetAddPackage(layout, packageId, [], setup);
    expect(stable.run.exitCode, describeRun('dotnet add package', stable.run)).toBe(0);
    expect(stable.csproj, 'the highest stable version').toContain(
      `<PackageReference Include="${packageId}" Version="1.1.0" />`,
    );

    const pre = await dotnetAddPackage(layout, packageId, ['--prerelease'], setup);
    expect(pre.run.exitCode, describeRun('dotnet add package --prerelease', pre.run)).toBe(0);
    expect(pre.csproj, 'the newest version, pre-releases included').toContain(
      `<PackageReference Include="${packageId}" Version="2.0.0-beta.1" />`,
    );
  });

  test(
    'a version that does not exist fails and leaves the project untouched',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, true);
      const packageId = `e2e-${seeder.runId}-add-missing`;
      await seedVersions(layout, packageId, ['1.0.0']);

      const added = await dotnetAddPackage(layout, packageId, ['--version', '9.9.9'], {
        user: 'credentials',
        projectSource: true,
      });
      expect(added.run.exitCode, describeRun('dotnet add package', added.run)).not.toBe(0);
      expect(added.run.stdout + added.run.stderr, 'NuGet names the missing version').toContain(
        'NU1102',
      );
      expect(added.csproj, 'a failed add leaves the project as it was').toBe(EMPTY_PROJECT);
    },
  );
});

/** `dotnet restore` of the standard consumer project for exactly `[version]`. */
async function restoreExact(
  layout: Layout,
  packageId: string,
  version: string,
): Promise<{ result: RunResult; work: string; home: string }> {
  const { home, work } = await isolatedWorkDir('nuget-restore-exact');
  const csproj = await renderConsumerProject(work, packageId, version);
  const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
  const result = await run(
    'dotnet',
    [
      'restore',
      csproj,
      '--configfile',
      cfg,
      '--packages',
      path.join(home, 'nuget-packages'),
      '--no-http-cache',
      '--disable-build-servers',
      '-p:NuGetAudit=false',
      '-v',
      'minimal',
    ],
    {
      cwd: work,
      env: nugetEnv(home),
      timeoutMs: CLIENT_TIMEOUT_MS,
      redact: layout.credential.password ? [layout.credential.password] : [],
      label: 'nuget-restore-exact',
    },
  );
  return { result, work, home };
}

test.describe('nuget > dotnet nuget delete (unlist)', () => {
  test('unlists the version: hidden from the listing, still served and still restorable', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-delete`;
    await seedVersions(layout, packageId, ['1.0.0', '1.1.0']);
    const idLower = packageId.toLowerCase();
    const before = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, '1.1.0');
    expect(before.status).toBe(200);

    const deleted = await dotnetNugetDelete(layout.repoName, layout.credential, packageId, '1.1.0');
    expect(deleted.exitCode, describeRun('dotnet nuget delete', deleted)).toBe(0);
    expect(deleted.stdout, 'the client says what it did').toContain(
      `${packageId} 1.1.0 was deleted successfully.`,
    );

    // The registration (what search and the panel list) marks only that version unlisted.
    const registration = await rawGetRegistrationIndex(layout.repoName, adminCredential(), idLower);
    expect(registration.status).toBe(200);
    expect(
      Object.fromEntries(
        parseRegistrationIndex(registration.body).map((l) => [l.version, l.listed]),
      ),
    ).toEqual({ '1.0.0': true, '1.1.0': false });

    // "Deleted" is a misnomer of the client: the version is still in the flat container, byte for byte.
    const versions = await rawGetVersions(layout.repoName, adminCredential(), idLower);
    expect(parseVersions(versions.body)).toEqual(['1.0.0', '1.1.0']);
    const after = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, '1.1.0');
    expect(after.status).toBe(200);
    expect(sha256Hex(after.body)).toBe(sha256Hex(before.body));

    // A restore that names the unlisted version exactly still resolves it (as on nuget.org).
    const restored = await restoreExact(layout, packageId, '1.1.0');
    expect(restored.result.exitCode, describeRun('dotnet restore', restored.result)).toBe(0);
    const assets = await readAssets(restored.work);
    expect(assets.libraries[`${packageId}/1.1.0`]?.type).toBe('package');
  });

  test('a version that was never pushed fails', { tag: ['@negative'] }, async ({ seeder }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-delete-missing`;
    await seedVersions(layout, packageId, ['1.0.0']);

    const deleted = await dotnetNugetDelete(layout.repoName, layout.credential, packageId, '9.9.9');
    expect(deleted.exitCode, describeRun('dotnet nuget delete', deleted)).not.toBe(0);
    const raw = await rawUnlist(
      layout.repoName,
      layout.credential,
      packageId.toLowerCase(),
      '9.9.9',
    );
    expect(raw.status, 'the wire status behind the exit code').toBe(404);

    const registration = await rawGetRegistrationIndex(
      layout.repoName,
      adminCredential(),
      packageId.toLowerCase(),
    );
    expect(parseRegistrationIndex(registration.body).map((l) => l.listed)).toEqual([true]);
  });
});

/** A classlib whose `dotnet pack --include-symbols` writes a `.nupkg` and a `.snupkg`. */
async function packWithSymbols(
  layout: Layout,
  packageId: string,
  version: string,
): Promise<{ nupkg: string; snupkg: string; work: string; home: string }> {
  const { home, work } = await isolatedWorkDir('nuget-pack');
  await fs.writeFile(
    path.join(work, 'Lib.csproj'),
    '<Project Sdk="Microsoft.NET.Sdk">\n' +
      '  <PropertyGroup>\n' +
      '    <TargetFramework>net10.0</TargetFramework>\n' +
      `    <PackageId>${packageId}</PackageId>\n` +
      `    <Version>${version}</Version>\n` +
      '    <Authors>repsy-e2e</Authors>\n' +
      '    <Description>e2e symbol package</Description>\n' +
      '    <NuGetAudit>false</NuGetAudit>\n' +
      '  </PropertyGroup>\n' +
      '</Project>\n',
  );
  await fs.writeFile(
    path.join(work, 'Greeter.cs'),
    'namespace E2e { public static class Greeter { public static string Hello() => "hello from repsy"; } }\n',
  );
  const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
  const out = path.join(work, 'out');
  const packed = await run(
    'dotnet',
    [
      'pack',
      '--include-symbols',
      '-p:SymbolPackageFormat=snupkg',
      '--configfile',
      cfg,
      '--disable-build-servers',
      '-o',
      out,
    ],
    { cwd: work, env: nugetEnv(home), timeoutMs: CLIENT_TIMEOUT_MS, label: 'nuget-pack' },
  );
  expect(packed.exitCode, describeRun('dotnet pack', packed)).toBe(0);
  return {
    nupkg: path.join(out, `${packageId}.${version}.nupkg`),
    snupkg: path.join(out, `${packageId}.${version}.snupkg`),
    work,
    home,
  };
}

/** `dotnet nuget push <files> --source repsy` with the credential of `layout`. */
async function dotnetPush(
  layout: Layout,
  work: string,
  home: string,
  file: string,
  extra: string[] = [],
): Promise<RunResult> {
  const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
  const apiKey = nugetApiKey(layout.credential) ?? '';
  return run(
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
      '--timeout',
      '60',
      '--api-key',
      apiKey,
      ...extra,
    ],
    {
      cwd: work,
      env: nugetEnv(home),
      timeoutMs: CLIENT_TIMEOUT_MS,
      redact: [apiKey, layout.credential.password ?? ''],
      label: 'nuget-push-symbols',
    },
  );
}

test.describe('nuget > symbol packages (.snupkg)', () => {
  test('a real dotnet pack output round-trips: only the .nupkg is pushed, and it restores and compiles', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-symbols`;
    const version = '1.0.0';
    const packed = await packWithSymbols(layout, packageId, version);
    const nupkgBytes = await fs.readFile(packed.nupkg);
    expect((await fs.stat(packed.snupkg)).size, 'pack wrote a symbol package').toBeGreaterThan(0);

    // Symbols are not turned off (no --no-symbols): the client still sends the .nupkg alone.
    const pushed = await dotnetPush(layout, packed.work, packed.home, packed.nupkg);
    expect(pushed.exitCode, describeRun('dotnet nuget push', pushed)).toBe(0);
    expect(pushed.stdout).toContain('Your package was pushed.');
    expect(pushed.stdout, 'no symbol package was sent').not.toContain('.snupkg');

    const idLower = packageId.toLowerCase();
    const served = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, version);
    expect(served.status).toBe(200);
    expect(sha256Hex(served.body), 'the served nupkg is the packed one').toBe(
      sha256Hex(nupkgBytes),
    );

    // A real package (with its OPC parts and a compiled assembly), consumed by a real build.
    const { home, work } = await isolatedWorkDir('nuget-consume-real');
    await fs.writeFile(
      path.join(work, 'App.csproj'),
      '<Project Sdk="Microsoft.NET.Sdk">\n' +
        '  <PropertyGroup>\n' +
        '    <TargetFramework>net10.0</TargetFramework>\n' +
        '    <OutputType>Library</OutputType>\n' +
        '    <NuGetAudit>false</NuGetAudit>\n' +
        '  </PropertyGroup>\n' +
        `  <ItemGroup><PackageReference Include="${packageId}" Version="[${version}]" /></ItemGroup>\n` +
        '</Project>\n',
    );
    await fs.writeFile(
      path.join(work, 'Use.cs'),
      'namespace App { public static class Use { public static string Run() => E2e.Greeter.Hello(); } }\n',
    );
    const cfg = await renderNugetConfig(home, layout.repoName, layout.credential);
    const secret = layout.credential.password ?? '';
    const restored = await run(
      'dotnet',
      ['restore', '--configfile', cfg, '--disable-build-servers', '-p:NuGetAudit=false'],
      {
        cwd: work,
        env: nugetEnv(home),
        timeoutMs: CLIENT_TIMEOUT_MS,
        redact: [secret],
        label: 'nuget-restore-real',
      },
    );
    expect(restored.exitCode, describeRun('dotnet restore', restored)).toBe(0);
    const built = await run('dotnet', ['build', '--no-restore', '--disable-build-servers'], {
      cwd: work,
      env: nugetEnv(home),
      timeoutMs: CLIENT_TIMEOUT_MS,
      label: 'nuget-build-real',
    });
    expect(built.exitCode, describeRun('dotnet build', built)).toBe(0);
  });

  test('dotnet nuget push of a .snupkg sends nothing and changes nothing', async ({ seeder }) => {
    const layout = await newRepo(seeder, true);
    const packageId = `e2e-${seeder.runId}-snupkg`;
    const version = '1.0.0';
    const packed = await packWithSymbols(layout, packageId, version);
    const idLower = packageId.toLowerCase();

    // Why: the service index has no symbol-publish resource, so the client has nowhere to send it.
    const index = await rawGetServiceIndex(layout.repoName);
    expect(index.status).toBe(200);
    expect(
      parseServiceIndex(index.body).map((r) => r.type),
      'the service index advertises no SymbolPackagePublish',
    ).not.toContainEqual(expect.stringContaining('SymbolPackagePublish'));

    // A symbol package of an id Repsy has never seen: exit 0, no request, nothing stored.
    const first = await dotnetPush(layout, packed.work, packed.home, packed.snupkg);
    expect(first.exitCode, describeRun('dotnet nuget push .snupkg', first)).toBe(0);
    expect(first.stdout, 'the client never says it pushed anything').not.toContain('Pushing');
    const none = await rawGetVersions(layout.repoName, adminCredential(), idLower);
    expect(none.status, 'no version of the package exists').toBe(404);

    // Once the .nupkg is there, a .snupkg push leaves it byte for byte as it was.
    const pushed = await dotnetPush(layout, packed.work, packed.home, packed.nupkg);
    expect(pushed.exitCode, describeRun('dotnet nuget push .nupkg', pushed)).toBe(0);
    const stored = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, version);
    expect(stored.status).toBe(200);

    const second = await dotnetPush(layout, packed.work, packed.home, packed.snupkg);
    expect(second.exitCode, describeRun('dotnet nuget push .snupkg', second)).toBe(0);
    const after = await rawDownloadNupkg(layout.repoName, adminCredential(), idLower, version);
    expect(sha256Hex(after.body), 'the stored nupkg is unchanged').toBe(sha256Hex(stored.body));
  });
});
