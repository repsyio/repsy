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
 * NuGet protocol-specific tests (step 5e, RPS-294), ported from `repsy-cloud`'s own e2e harness
 * (`protocols/nuget/unlist/test.ts`, `.../relist/test.ts`, `.../search/test.ts`, and the
 * "explicitly older version" case from `.../multi_version/test.ts`) into this harness's own
 * conventions: real `dotnet` binaries, hand-built `World`s, `clients/nuget.ts`/`nuget-raw.ts`
 * helpers -- never that harness's own `npx tsx subprocess`/`shelljs`/`process.argv` structure.
 *
 * Every credential here is `token-rw` (a fresh read-write deploy token), built by hand exactly
 * like `tests/cargo/protocol-specific.spec.ts`/`tests/nuget/publish-consume.spec.ts`'s dedicated
 * tests -- these are not catalog-loop scenarios, so there is no `world` fixture call;
 * `nuget.publish(world)`/`nuget.resolve(world)` are invoked directly. A raw-HTTP verification
 * probe uses `adminCredential()` instead, the same split every other protocol's dedicated tests
 * use for read-side checks.
 *
 * HN-1/HN-2/HN-3 were probed live against a running local stack BEFORE this file was written (see
 * this PR's own report for the exact commands/output):
 *
 *  - HN-1 (gating): the server source (`AbstractNuGetUnlistProtocolMethodHandler`/
 *    `AbstractNuGetRelistProtocolMethodHandler`, whose `UNLIST_PATTERN`/`RELIST_PATTERN` match
 *    exactly two path segments under `v3/package/`) was read FIRST, confirming unlist is a raw `DELETE
 *    /v3/package/<idLower>/<verLower>` and relist a raw `POST` of that exact same URL --
 *    `PackagePublish/2.0.0`'s own routes, never the `PackageDelete/2.0.0` type the service index used
 *    to also advertise (removed by RPS-1213). Confirmed live with raw `curl`: `DELETE` -> `204`, the
 *    registration leaf's top-level `listed` flips to `false`, the flat `v3/package/<id>/index.json`
 *    container keeps the version regardless; `POST` -> `200`, `listed` flips back to `true`. A real
 *    `dotnet restore`/`dotnet add package --version <exact>` of the exact unlisted version still
 *    succeeds (this suite's own "unlist"/"relist" tests are that live evidence, end to end with the
 *    real client, not just curl).
 *  - HN-2: `GET /v3/search?q=<id>&prerelease=true` and `GET /v3/autocomplete?q=<prefix>` were
 *    confirmed live: search answers `{"totalHits":1,"data":[{"id":"<idLower>","version":"...",
 *    "registration":"<url>",...}]}` (`NuGetSearchResponse`/`NuGetSearchData` -- `data[].id` is the
 *    stored, LOWERCASED spelling, matched case-insensitively here, same as every other nuget H8
 *    lowercase fact); autocomplete answers `{"totalHits":1,"data":["<idLower>"]}`
 *    (`NuGetAutocompleteResponse`).
 *  - HN-3 (RPS-1213 + RPS-1240, both fixed): a real `dotnet package search <id> --source repsy
 *    --configfile <cfg>` (.NET SDK 10.0.401, NuGet.Client 7.9.0) used to exit `0` with `error: The
 *    source does not have a Search service!` and never issue the `v3/search` request this suite's
 *    own "search and autocomplete" test proves works over raw HTTP. RPS-1213 fixed the first cause
 *    (`SearchQueryService/3.0.0` is not a recognised type) but replaced it with the bare
 *    `SearchQueryService`, which turned out to be recognised only by the docs, not by the client:
 *    NuGet.Client's `ServiceTypes.SearchQueryService` is `{"SearchQueryService/Versioned",
 *    "SearchQueryService/3.4.0", "SearchQueryService/3.0.0-beta"}` (and `SearchAutocompleteService`
 *    is `{"/Versioned", "/3.0.0-beta"}`) -- there is no bare form for either (only
 *    `RegistrationsBaseUrl` has one). RPS-1240 pinned this down live: served by an otherwise
 *    identical index, `SearchQueryService`, `/3.5.0` and `/3.0.0-rc` all reproduce "does not have a
 *    Search service!" while `/3.0.0-beta` and `/3.4.0` make the client issue `GET v3/search?q=...
 *    &semVerLevel=2.0.0`. The service index now advertises `SearchQueryService/3.0.0-beta` and
 *    `SearchAutocompleteService/3.0.0-beta` next to the bare types, and the test below runs the real
 *    client to completion.
 *  - HN-4 (RPS-1275, fixed): the client's `semVerLevel=2.0.0` was ignored, so a client that sends
 *    none (or a level below 2.0.0) was handed SemVer 2.0.0-only versions (a dot-separated
 *    pre-release label, build metadata) and packages that only have such versions. The search and
 *    autocomplete endpoints now leave them out unless `semVerLevel` is 2.0.0 or more, as the
 *    search/autocomplete docs prescribe. The registration index and flat container have no
 *    `semVerLevel` parameter in the docs, and keep listing every version.
 */
import { RepoType } from '../../src/api/panel-api.js';
import * as nuget from '../../src/clients/nuget.js';
import { nugetAdapter, nugetEnv, renderNugetConfig } from '../../src/clients/nuget.js';
import {
  adminCredential,
  normalizeVersion,
  parseAutocompleteResponse,
  parseRegistrationIndex,
  parseSearchResponse,
  parseServiceIndex,
  parseVersions,
  buildNupkg,
  rawAutocomplete,
  rawGetRegistrationIndex,
  rawGetServiceIndex,
  rawGetVersions,
  rawPublish,
  rawRelist,
  rawSearch,
  rawUnlist,
} from '../../src/clients/nuget-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, MaterializedCredential, World } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageId: string;
  credential: MaterializedCredential;
}

/** A fresh private nuget repo, a run-unique package id, and a fresh `token-rw` deploy token for
 *  it -- the credential every hand-built `World` in this file uses. */
async function newRepoWithToken(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const credential: MaterializedCredential = {
    transport: 'basic',
    username: token.username,
    password: token.token,
    kind: 'token',
  };
  return { repoName: repo.name, packageId: `e2e-${seeder.runId}-${label}`, credential };
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
    protocol: 'nuget',
    repoName: layout.repoName,
    credential: layout.credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

test(
  'nuget > unlist hides a version from the registration but keeps it installable',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'unlist');
    const version = nugetAdapter.version('release');
    const target: Coordinates = { packageName: layout.packageId, version };
    const world = buildWorld(layout, target, 'unlist');

    const published = await nuget.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; dotnet ` +
        `exit ${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `dotnet nuget push: ${published.command}`).toBe(0);

    const admin = adminCredential();
    const idLower = layout.packageId.toLowerCase();
    const verLower = normalizeVersion(version);

    const regBefore = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
    expect(regBefore.status, 'the registration index exists for the published package').toBe(200);
    const leafBefore = parseRegistrationIndex(regBefore.body).find(
      (l) => l.version.toLowerCase() === verLower,
    );
    expect(leafBefore, `a registration leaf for version "${verLower}"`).toBeDefined();
    expect(leafBefore?.listed, 'a freshly published version is listed').toBe(true);

    // Unlist via a raw DELETE /v3/package/<idLower>/<verLower> -- NuGet's own "unlist" convention
    // (HN-1, confirmed against AbstractNuGetUnlistProtocolMethodHandler's source before being
    // probed live).
    const unlistRes = await rawUnlist(layout.repoName, layout.credential, idLower, verLower);
    expect(unlistRes.status, `unlist DELETE: ${JSON.stringify(unlistRes.body.toString())}`).toBe(
      204,
    );

    const regAfter = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
    expect(regAfter.status).toBe(200);
    const leafAfter = parseRegistrationIndex(regAfter.body).find(
      (l) => l.version.toLowerCase() === verLower,
    );
    expect(leafAfter, `a registration leaf for version "${verLower}" after unlist`).toBeDefined();
    expect(leafAfter?.listed, 'the registration leaf reflects listed=false after unlist').toBe(
      false,
    );

    // NuGet spec: unlisted != deleted -- the flat container (PackageBaseAddress) keeps serving
    // every version, listed or not.
    const versionsRes = await rawGetVersions(layout.repoName, admin, idLower);
    expect(versionsRes.status, 'the flat version list still serves this package').toBe(200);
    expect(
      parseVersions(versionsRes.body),
      'the unlisted version is still present in the flat container',
    ).toContain(verLower);

    // A fresh real restore of the EXACT unlisted version must still succeed and get the exact
    // bytes that were pushed.
    const resolved = await nuget.resolve(world);
    expect(
      resolved.outcome,
      `resolve: the auth-only versions-list GET should still succeed (http ${resolved.httpStatus})`,
    ).toBe('ok');
    expect(resolved.clientExitCode, `dotnet restore: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the restored nupkg (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);

test('nuget > relist restores listed=true', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'relist');
  const version = nugetAdapter.version('release');
  const target: Coordinates = { packageName: layout.packageId, version };
  const world = buildWorld(layout, target, 'relist');

  const published = await nuget.publish(world);
  expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
  expect(published.clientExitCode, `dotnet nuget push: ${published.command}`).toBe(0);

  const admin = adminCredential();
  const idLower = layout.packageId.toLowerCase();
  const verLower = normalizeVersion(version);

  // Baseline: unlist first, exactly like the "unlist" test above (relist is meaningless to test
  // starting from an already-listed version).
  const unlistRes = await rawUnlist(layout.repoName, layout.credential, idLower, verLower);
  expect(unlistRes.status, 'baseline unlist').toBe(204);

  const regUnlisted = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
  const leafUnlisted = parseRegistrationIndex(regUnlisted.body).find(
    (l) => l.version.toLowerCase() === verLower,
  );
  expect(leafUnlisted?.listed, 'baseline: unlisted').toBe(false);

  // Relist via a raw POST of the exact same URL -- NuGet's own "relist" convention.
  const relistRes = await rawRelist(layout.repoName, layout.credential, idLower, verLower);
  expect(relistRes.status, `relist POST: ${JSON.stringify(relistRes.body.toString())}`).toBe(200);

  const regAfter = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
  expect(regAfter.status).toBe(200);
  const leafAfter = parseRegistrationIndex(regAfter.body).find(
    (l) => l.version.toLowerCase() === verLower,
  );
  expect(leafAfter, `a registration leaf for version "${verLower}" after relist`).toBeDefined();
  expect(leafAfter?.listed, 'the registration leaf reflects listed=true again after relist').toBe(
    true,
  );

  const versionsRes = await rawGetVersions(layout.repoName, admin, idLower);
  expect(versionsRes.status).toBe(200);
  expect(
    parseVersions(versionsRes.body),
    'the relisted version stays in the flat container',
  ).toContain(verLower);

  // A fresh real restore must still succeed after the unlist/relist round trip.
  const resolved = await nuget.resolve(world);
  expect(resolved.outcome, `resolve: expected "ok" (http ${resolved.httpStatus})`).toBe('ok');
  expect(resolved.clientExitCode, `dotnet restore: ${resolved.command}`).toBe(0);
  expect(resolved.contentSha256, 'the restored nupkg matches the published one').toBe(
    published.contentSha256,
  );
});

test(
  'nuget > search and autocomplete return the published package',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'search');
    const version = nugetAdapter.version('release');
    const target: Coordinates = { packageName: layout.packageId, version };
    const world = buildWorld(layout, target, 'search');

    const published = await nuget.publish(world);
    expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
    expect(published.clientExitCode, `dotnet nuget push: ${published.command}`).toBe(0);

    const admin = adminCredential();
    const idLower = layout.packageId.toLowerCase();

    const searchRes = await rawSearch(layout.repoName, admin, layout.packageId, true);
    expect(searchRes.status, `raw search GET: ${searchRes.status}`).toBe(200);
    const searchBody = parseSearchResponse(searchRes.body);
    expect(
      searchBody.totalHits,
      `search for "${layout.packageId}" returned totalHits=0`,
    ).toBeGreaterThanOrEqual(1);
    const match = searchBody.data.find((d) => d.id.toLowerCase() === idLower);
    expect(
      match,
      `"${layout.packageId}" not in search results. Got: ${searchBody.data.map((d) => d.id).join(', ')}`,
    ).toBeDefined();
    expect(
      match?.registration,
      'the search result carries a non-empty registration URL',
    ).toBeTruthy();

    // A prefix a real client would autocomplete against -- everything this run seeded under this
    // one label's own package id family.
    const prefix = `e2e-${seeder.runId}`;
    const autoRes = await rawAutocomplete(layout.repoName, admin, prefix, true);
    expect(autoRes.status, `raw autocomplete GET: ${autoRes.status}`).toBe(200);
    const autoBody = parseAutocompleteResponse(autoRes.body);
    expect(
      autoBody.data.map((d) => d.toLowerCase()),
      `autocomplete for "${prefix}" did not include "${idLower}". Got: ${autoBody.data.join(', ')}`,
    ).toContain(idLower);
  },
);

test(
  'nuget > dotnet package search finds a published package through the service index (RPS-1240)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'pkgsearch');
    const version = nugetAdapter.version('release');
    const target: Coordinates = { packageName: layout.packageId, version };
    const world = buildWorld(layout, target, 'pkgsearch');

    const published = await nuget.publish(world);
    expect(published.outcome, `publish: expected "ok" (${published.command})`).toBe('ok');
    expect(published.clientExitCode, `dotnet nuget push: ${published.command}`).toBe(0);

    // Pins the fix side of RPS-1213/RPS-1240 inline, right before the dotnet invocation below: if
    // this ever regresses (the @type mismatch comes back), THIS assertion fails first and points
    // straight at the service index, instead of the generic "does not have a Search service!" the
    // dotnet assertions below would otherwise produce for either cause.
    const serviceIndexRes = await rawGetServiceIndex(layout.repoName);
    expect(serviceIndexRes.status, 'raw service index GET').toBe(200);
    expect(
      parseServiceIndex(serviceIndexRes.body).map((r) => r.type),
      'the service index advertises search/registration types NuGet.Client resolves',
    ).toEqual(
      expect.arrayContaining([
        'RegistrationsBaseUrl',
        'SearchQueryService/3.0.0-beta',
        'SearchAutocompleteService/3.0.0-beta',
      ]),
    );

    const { home, work } = await isolatedWorkDir(`nuget-pkgsearch-${seeder.runId}`);
    const cfgPath = await renderNugetConfig(home, layout.repoName, layout.credential);

    const searchResult = await run(
      'dotnet',
      ['package', 'search', layout.packageId, '--source', 'repsy', '--configfile', cfgPath],
      {
        cwd: work,
        env: nugetEnv(home),
        timeoutMs: 60_000,
        redact: layout.credential.password ? [layout.credential.password] : [],
        label: 'nuget-package-search',
      },
    );

    // HN-3 (this file's own header): the real client resolves the search resource through
    // NuGet.Client's ServiceTypes.SearchQueryService vocabulary ("/Versioned", "/3.4.0",
    // "/3.0.0-beta" -- no bare form), so it only reaches the v3/search route once the index
    // advertises one of those. "dotnet package search" reports a missing search service with exit 0,
    // so the package id in the output is what proves the search request was really made.
    expect(searchResult.exitCode, `dotnet package search: ${searchResult.command}`).toBe(0);
    expect(
      searchResult.stdout,
      'a real, working search would list the published package id in the command output',
    ).toContain(layout.packageId);
  },
);

test(
  'nuget > search honours semVerLevel: SemVer 2.0.0-only versions need the opt-in (RPS-1275)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'semver');
    const admin = adminCredential();
    const mixedId = `${layout.packageId}-mixed`;
    const onlyId = `${layout.packageId}-only`;

    // "mixed" has a SemVer 1.0.0 release and a SemVer 2.0.0-only pre-release (a dot-separated
    // label); "only" has nothing a SemVer 1.0.0 client can use.
    for (const [packageId, version] of [
      [mixedId, '1.0.0'],
      [mixedId, '2.0.0-beta.1'],
      [onlyId, '1.0.0-rc.1'],
    ] as const) {
      const bytes = buildNupkg({ packageId, version });
      const res = await rawPublish(layout.repoName, admin, bytes);
      expect(res.status, `raw publish ${packageId}@${version}: ${res.status}`).toBe(201);
    }

    // A client that predates SemVer 2.0.0 sends no semVerLevel (as does one that sends 1.0.0).
    for (const level of [undefined, '1.0.0']) {
      const res = await rawSearch(layout.repoName, admin, layout.packageId, true, level);
      expect(res.status, `raw search (semVerLevel=${level}): ${res.status}`).toBe(200);
      const body = parseSearchResponse(res.body);
      expect(
        body.data.map((d) => d.id),
        `semVerLevel=${level}: ids`,
      ).toEqual([mixedId.toLowerCase()]);
      expect(body.totalHits, `semVerLevel=${level}: totalHits`).toBe(1);
      expect(body.data[0]?.version, `semVerLevel=${level}: latest version`).toBe('1.0.0');
      expect(body.data[0]?.versions, `semVerLevel=${level}: versions`).toEqual(['1.0.0']);

      const auto = await rawAutocomplete(layout.repoName, admin, layout.packageId, true, level);
      expect(
        parseAutocompleteResponse(auto.body).data,
        `semVerLevel=${level}: autocomplete`,
      ).toEqual([mixedId.toLowerCase()]);
    }

    const optedIn = await rawSearch(layout.repoName, admin, layout.packageId, true, '2.0.0');
    expect(optedIn.status).toBe(200);
    const optedInBody = parseSearchResponse(optedIn.body);
    expect(optedInBody.totalHits).toBe(2);
    const mixed = optedInBody.data.find((d) => d.id === mixedId.toLowerCase());
    expect(mixed?.version, 'semVerLevel=2.0.0: the SemVer 2.0.0 pre-release is the latest').toBe(
      '2.0.0-beta.1',
    );
    expect(mixed?.versions).toEqual(['2.0.0-beta.1', '1.0.0']);
    expect(optedInBody.data.map((d) => d.id)).toContain(onlyId.toLowerCase());

    // The real client always sends semVerLevel=2.0.0, so it lists the SemVer 2.0.0 pre-release.
    const { home, work } = await isolatedWorkDir(`nuget-semver-${seeder.runId}`);
    const cfgPath = await renderNugetConfig(home, layout.repoName, layout.credential);
    const searchResult = await run(
      'dotnet',
      [
        'package',
        'search',
        mixedId,
        '--prerelease',
        '--exact-match',
        '--source',
        'repsy',
        '--configfile',
        cfgPath,
      ],
      {
        cwd: work,
        env: nugetEnv(home),
        timeoutMs: 60_000,
        redact: layout.credential.password ? [layout.credential.password] : [],
        label: 'nuget-package-search-semver',
      },
    );
    expect(searchResult.exitCode, `dotnet package search: ${searchResult.command}`).toBe(0);
    expect(searchResult.stdout, 'the SemVer 2.0.0 pre-release is listed').toContain('2.0.0-beta.1');
  },
);

test('nuget > an explicitly older version restores', { tag: ['@smoke'] }, async ({ seeder }) => {
  const layout = await newRepoWithToken(seeder, 'multiver');

  const versionA = nugetAdapter.version('release');
  const targetA: Coordinates = { packageName: layout.packageId, version: versionA };
  const worldA = buildWorld(layout, targetA, 'multiver-a');
  const publishedA = await nuget.publish(worldA);
  expect(publishedA.outcome, `publish A: expected "ok" (${publishedA.command})`).toBe('ok');
  expect(publishedA.clientExitCode, `dotnet nuget push A: ${publishedA.command}`).toBe(0);

  const versionB = nugetAdapter.version('release');
  expect(versionB, 'the two versions are actually distinct').not.toBe(versionA);
  const targetB: Coordinates = { packageName: layout.packageId, version: versionB };
  const worldB = buildWorld(layout, targetB, 'multiver-b');
  const publishedB = await nuget.publish(worldB);
  expect(publishedB.outcome, `publish B: expected "ok" (${publishedB.command})`).toBe('ok');
  expect(publishedB.clientExitCode, `dotnet nuget push B: ${publishedB.command}`).toBe(0);
  expect(publishedB.contentSha256, 'the two versions carry independent bytes').not.toBe(
    publishedA.contentSha256,
  );

  const admin = adminCredential();
  const idLower = layout.packageId.toLowerCase();
  const versionsRes = await rawGetVersions(layout.repoName, admin, idLower);
  expect(versionsRes.status, 'the flat version list serves this package').toBe(200);
  const indexed = parseVersions(versionsRes.body);
  expect(indexed, 'version A is in the flat index').toContain(normalizeVersion(versionA));
  expect(indexed, 'version B is in the flat index').toContain(normalizeVersion(versionB));

  // Explicitly restore version A -- NOT the latest (B). renderConsumerProject/`nuget.resolve`
  // always pin an exact bracketed version ("[<version>]"), so this targets A specifically
  // regardless of B having been published after it.
  const resolvedA = await nuget.resolve(worldA);
  expect(
    resolvedA.outcome,
    `resolve A: the auth-only versions-list GET should still succeed (http ${resolvedA.httpStatus})`,
  ).toBe('ok');
  expect(resolvedA.clientExitCode, `dotnet restore A: ${resolvedA.command}`).toBe(0);
  expect(
    resolvedA.contentSha256,
    `the restored nupkg (${resolvedA.resolvedFile ?? 'none found'}) must be exactly A's bytes`,
  ).toBe(publishedA.contentSha256);
  expect(
    resolvedA.contentSha256,
    "the restored nupkg must NOT be B's bytes, even though B was published after A",
  ).not.toBe(publishedB.contentSha256);
});
