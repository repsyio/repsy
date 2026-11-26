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
 * The nuget server's registry rules, pinned at the protocol level with raw HTTP PUTs/GETs (no
 * `dotnet` client), the nuget analogue of `tests/cargo/registry-rules.spec.ts`. Every status/detail
 * here was read from `AbstractNuGetProtocolFacade`/`NuGetPackageUtils`/`NuGetAuthPreProcessor` first
 * and then confirmed against a running instance (see `README.md`'s "NuGet runner" section for the raw
 * evidence):
 *
 *  - A duplicate-version publish with `allowOverride: false` is refused with a real `409 Conflict`
 *    (`"Version <v> of package <id> already exists."`), and -- unlike cargo's RPS-1124 -- the DB row
 *    and the storage write happen in the SAME publish call, after every refusal check, so nothing is
 *    left corrupted: `AbstractNuGetProtocolFacade.publish` L100-126.
 *  - `checkVersionAllowance` (releases/snapshots) runs BEFORE the override/conflict check, so a
 *    redeploy of an existing version under `releases: false`/`snapshots: false` is `422`, never `409`.
 *  - `X-NuGet-ApiKey` is only ever fed through the Bearer path (`NuGetAuthPreProcessor
 * .normalizeAuthHeader` prefixes `Bearer ` unless the value already starts with `Basic `/`Bearer `),
 *    so a deploy token works as an api key but a user/admin PASSWORD does not -- H7.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { expectHeadMirrorsGet } from '../../src/clients/head-parity.js';
import { nugetAdapter } from '../../src/clients/nuget.js';
import {
  adminCredential,
  buildNupkg,
  nugetErrorMessage,
  nugetReadHeaders,
  nupkgPath,
  nuspecPath,
  registrationIndexPath,
  versionsPath,
  normalizeVersion,
  parseRegistrationIndex,
  parseServiceIndex,
  parseVersions,
  rawDownloadNupkg,
  rawGetRegistrationIndex,
  rawGetServiceIndex,
  rawGetVersions,
  rawPublish,
  type RawResponse,
} from '../../src/clients/nuget-raw.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageId: string;
}

/** A fresh nuget repo (permissive defaults) and a run-unique package id for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
  return { repoName: repo.name, packageId: `e2e-${seeder.runId}-${label}` };
}

function expectPublish(
  res: RawResponse,
  status: number,
  messageContains: string | undefined,
): void {
  const message = nugetErrorMessage(res.body);
  expect(res.status, `PUT answered ${res.status} ${message ?? ''}`).toBe(status);
  if (messageContains !== undefined) {
    expect(message, 'the error message').toContain(messageContains);
  }
}

test.describe('nuget registry rules (raw HTTP)', () => {
  test(
    'releases/snapshots switches refuse publish by version kind (422), including a redeploy ' +
      'of an existing version (before the override/conflict check)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const admin = adminCredential();

      const releasesOff = await newRepo(seeder, 'releasesoff');
      await seeder.setSettings(releasesOff.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: false,
        snapshots: true,
      });
      const release = nugetAdapter.version('release');
      expectPublish(
        await rawPublish(
          releasesOff.repoName,
          admin,
          buildNupkg({ packageId: releasesOff.packageId, version: release }),
        ),
        422,
        'Release packages are not allowed',
      );
      const idLower1 = releasesOff.packageId.toLowerCase();
      expect(
        (await rawGetVersions(releasesOff.repoName, admin, idLower1)).status,
        'nothing stored',
      ).toBe(404);

      const snapshotsOff = await newRepo(seeder, 'snapshotsoff');
      await seeder.setSettings(snapshotsOff.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: false,
      });
      const prerelease = `${nugetAdapter.version('release')}-pre`;
      expectPublish(
        await rawPublish(
          snapshotsOff.repoName,
          admin,
          buildNupkg({ packageId: snapshotsOff.packageId, version: prerelease }),
        ),
        422,
        'Pre-release packages are not allowed',
      );
      const idLower2 = snapshotsOff.packageId.toLowerCase();
      expect(
        (await rawGetVersions(snapshotsOff.repoName, admin, idLower2)).status,
        'nothing stored',
      ).toBe(404);

      // A redeploy of an EXISTING version under releases:false is 422, never 409: the kind check
      // runs before the override/conflict check.
      const redeploy = await newRepo(seeder, 'redeployreleasesoff');
      const v1 = nugetAdapter.version('release');
      expectPublish(
        await rawPublish(
          redeploy.repoName,
          admin,
          buildNupkg({ packageId: redeploy.packageId, version: v1 }),
        ),
        201,
        undefined,
      );
      await seeder.setSettings(redeploy.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: false,
        snapshots: true,
      });
      expectPublish(
        await rawPublish(
          redeploy.repoName,
          admin,
          buildNupkg({ packageId: redeploy.packageId, version: v1, marker: 'again' }),
        ),
        422,
        'Release packages are not allowed',
      );
    },
  );

  test(
    "service index is unauthenticated on a private repo and advertises this instance's own " +
      'URLs, using @type strings the official client resolves (H6)',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
      const res = await rawGetServiceIndex(repo.name);
      expect(res.status, 'the service index is served unauthenticated even on a private repo').toBe(
        200,
      );

      const resources = parseServiceIndex(res.body);
      const byType = new Map(resources.map((r) => [r.type, r.id]));

      expect(byType.get('PackageBaseAddress/3.0.0')).toBe(repoUrl(repo.name, 'v3/package'));
      expect(byType.get('PackagePublish/2.0.0')).toBe(repoUrl(repo.name, 'v3/package'));
      expect(byType.get('RegistrationsBaseUrl')).toBe(repoUrl(repo.name, 'v3/registration'));

      // RPS-1213/RPS-1240 (fixed): NuGet.Client's ServiceTypes.cs resolves "RegistrationsBaseUrl" as
      // a bare, unversioned type (among "/Versioned", "/3.0.0-beta", "/3.0.0-rc", "/3.4.0",
      // "/3.6.0"), but has NO bare form for search: SearchQueryService is only "/Versioned",
      // "/3.4.0" or "/3.0.0-beta", SearchAutocompleteService only "/Versioned" or "/3.0.0-beta".
      // This server therefore advertises the bare types (as the service-index docs list them) AND
      // "/3.0.0-beta" for both, at the same URL -- the earliest recognised version, since nothing
      // beyond the base semantics is implemented (not "RegistrationsBaseUrl/3.6.0"'s SemVer2
      // registration semantics either). The invented "PackageDelete/2.0.0" is no longer advertised
      // -- unlist/relist live under PackagePublish/2.0.0's own PUT/DELETE, per the NuGet API docs.
      expect(byType.get('SearchQueryService/3.0.0-beta')).toBe(repoUrl(repo.name, 'v3/search'));
      expect(byType.get('SearchAutocompleteService/3.0.0-beta')).toBe(
        repoUrl(repo.name, 'v3/autocomplete'),
      );
      expect(resources.map((r) => r.type)).toContain('SearchQueryService');
      expect(resources.map((r) => r.type)).toContain('SearchAutocompleteService');
      expect(resources.map((r) => r.type)).not.toContain('PackageDelete/2.0.0');
    },
  );

  test(
    '0.0.<Date.now()> (a version no real NuGet client could construct) is accepted (H12)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'bignumber');
      const admin = adminCredential();
      const version = `0.0.${Date.now()}`;

      // The server's own version parser uses BigInteger per numeric part (NuGetPackageUtils
      // .compareVersions), unlike the real NuGetVersion client parser (int32-bounded) -- so this is
      // accepted server-side even though no real `dotnet nuget push` could ever construct it (see
      // this story's design-decision on why coordinates.ts's boundedSemverVersion is still needed:
      // the bound is for the CLIENT, not the server).
      expectPublish(
        await rawPublish(
          layout.repoName,
          admin,
          buildNupkg({ packageId: layout.packageId, version }),
        ),
        201,
        undefined,
      );
      const idLower = layout.packageId.toLowerCase();
      expect(parseVersions((await rawGetVersions(layout.repoName, admin, idLower)).body)).toEqual([
        version,
      ]);
    },
  );

  test(
    'a HEAD answers the status and headers of its GET, for the package files and the JSON routes, ' +
      'and 404 for what is missing (RPS-1465)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'head');
      const admin = adminCredential();
      const version = nugetAdapter.version('release');
      const bytes = buildNupkg({ packageId: layout.packageId, version, marker: 'head' });
      expectPublish(await rawPublish(layout.repoName, admin, bytes), 201, undefined);
      const idLower = layout.packageId.toLowerCase();
      const verLower = normalizeVersion(version);
      const headers = nugetReadHeaders(admin);
      const at = (relPath: string): string => repoUrl(layout.repoName, relPath);

      await expectHeadMirrorsGet('the .nupkg', at(nupkgPath(idLower, verLower)), headers, {
        contentLength: true,
      });
      await expectHeadMirrorsGet('the .nuspec', at(nuspecPath(idLower, verLower)), headers, {
        contentLength: true,
      });
      await expectHeadMirrorsGet('the version list', at(versionsPath(idLower)), headers, {
        contentLength: false,
      });
      await expectHeadMirrorsGet(
        'the registration index',
        at(registrationIndexPath(idLower)),
        headers,
        { contentLength: false },
      );
      // The service index needs no credential, HEAD or GET (a private repo included).
      await expectHeadMirrorsGet(
        'the service index',
        at('v3/index.json'),
        {},
        {
          contentLength: false,
        },
      );

      for (const missing of [
        nupkgPath(idLower, '9.9.9'),
        nuspecPath(idLower, '9.9.9'),
        versionsPath(`${idLower}-missing`),
        registrationIndexPath(`${idLower}-missing`),
      ]) {
        const res = await fetch(at(missing), { method: 'HEAD', headers });
        expect(res.status, `HEAD ${missing} of something never published`).toBe(404);
        expect(res.headers.get('content-disposition'), 'not named f.txt').toBeNull();
      }

      // The same credential rules as the GET: no credentials on a private repo is a 401.
      const anonymous = await fetch(at(nupkgPath(idLower, verLower)), { method: 'HEAD' });
      expect(anonymous.status, 'HEAD of a private package without credentials').toBe(401);
    },
  );

  test(
    'floating versions resolve to the highest matching version (e.g., 1.0.* matches latest 1.0.x)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'floating');
      const admin = adminCredential();
      const packageId = layout.packageId;

      // Seed multiple versions: 1.0.0, 1.0.1, 1.1.0, 2.0.0
      const versions = ['1.0.0', '1.0.1', '1.1.0', '2.0.0'];
      for (const version of versions) {
        const bytes = buildNupkg({ packageId, version });
        expectPublish(await rawPublish(layout.repoName, admin, bytes), 201, undefined);
      }

      const idLower = packageId.toLowerCase();

      // Test: the version list should have all versions
      const versionsList = await rawGetVersions(layout.repoName, admin, idLower);
      expect(versionsList.status, 'versions list is served').toBe(200);
      const allVersions = parseVersions(versionsList.body).sort();
      expect(allVersions).toEqual(versions.sort());

      // Test: the registration index includes all versions with proper structure for resolution
      const regIndex = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
      expect(regIndex.status, 'registration index is served').toBe(200);
      const indexLeaves = parseRegistrationIndex(regIndex.body);
      expect(indexLeaves.map((l) => l.version).sort()).toEqual(versions.sort());
      expect(
        indexLeaves.every((l) => l.listed === true),
        'all versions are listed',
      ).toBe(true);

      // Each version should be downloadable and have a valid packageContent URL
      for (const version of versions) {
        const verLower = normalizeVersion(version);
        const dl = await rawDownloadNupkg(layout.repoName, admin, idLower, verLower);
        expect(dl.status, `version ${version} is downloadable`).toBe(200);

        const leaf = indexLeaves.find((l) => l.version === version);
        expect(leaf?.packageContent, `version ${version} has packageContent URL`).toBeTruthy();
      }
    },
  );

  test(
    'registration index pages when a package has more than 64 versions, and the page structure follows NuGet V3 spec',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      // 70 publishes and 70 downloads, one request each and in sequence: about 140 round trips, which over the
      // network to Dev took more than the 120 s default (RPS-2726).
      test.setTimeout(300_000);

      const layout = await newRepo(seeder, 'paging');
      const admin = adminCredential();
      const packageId = layout.packageId;

      // Seed 70 versions to force paging (page size is 64)
      const versions = Array.from({ length: 70 }, (_, i) => `1.0.${i}`);
      for (const version of versions) {
        const bytes = buildNupkg({ packageId, version });
        expectPublish(await rawPublish(layout.repoName, admin, bytes), 201, undefined);
      }

      const idLower = packageId.toLowerCase();

      // Fetch the registration index
      const regIndex = await rawGetRegistrationIndex(layout.repoName, admin, idLower);
      expect(regIndex.status, 'registration index is served').toBe(200);

      // Parse the raw JSON to check pagination structure
      const indexJson = JSON.parse(regIndex.body.toString('utf8')) as {
        items?: { '@id'?: string; items?: unknown[] }[];
      };

      // With 70 versions and page size 64, we should have 2 pages
      expect(indexJson.items?.length, 'registration index has multiple pages').toBeGreaterThan(1);

      // Each page should have an @id URL
      const pageUrls = indexJson.items
        ?.map((page) => page['@id'])
        .filter((id): id is string => typeof id === 'string');
      expect(pageUrls?.length, 'all pages have @id URLs').toBeGreaterThan(1);

      // The page URLs should be distinct and follow the pattern /page/N.json
      expect(new Set(pageUrls).size, 'page URLs are unique').toBe(pageUrls?.length ?? 0);

      // Verify all versions are represented across pages
      const allLeaves = parseRegistrationIndex(regIndex.body);
      expect(allLeaves.length, 'all 70 versions are in the index').toBe(70);
      expect(allLeaves.map((l) => l.version).sort()).toEqual(versions.sort());

      // Verify each version is downloadable
      for (const version of versions) {
        const verLower = normalizeVersion(version);
        const dl = await rawDownloadNupkg(layout.repoName, admin, idLower, verLower);
        expect(dl.status, `version ${version} is downloadable`).toBe(200);
      }
    },
  );
});
