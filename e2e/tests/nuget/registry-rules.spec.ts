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
import { nugetAdapter } from '../../src/clients/nuget.js';
import {
  adminCredential,
  authHeader,
  buildNupkg,
  nugetErrorMessage,
  normalizeVersion,
  parseServiceIndex,
  parseVersions,
  publishUrl,
  rawDownloadNupkg,
  rawGetServiceIndex,
  rawGetVersions,
  rawPublish,
  sha256Hex,
  type RawResponse,
} from '../../src/clients/nuget-raw.js';
import { env } from '../../src/env.js';
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
    'a duplicate-version publish is refused with 409 when allowOverride is off, leaving ' +
      'storage untouched',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'nooverride');
      const admin = adminCredential();
      const v1 = nugetAdapter.version('release');

      const bytesA = buildNupkg({ packageId: layout.packageId, version: v1, marker: 'v1' });
      expectPublish(await rawPublish(layout.repoName, admin, bytesA), 201, undefined);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });

      const idLower = layout.packageId.toLowerCase();
      const verLower = normalizeVersion(v1);
      const dlBefore = await rawDownloadNupkg(layout.repoName, admin, idLower, verLower);
      expect(dlBefore.status, 'the seeded version downloads').toBe(200);
      expect(sha256Hex(dlBefore.body), 'the seeded version is bytesA').toBe(sha256Hex(bytesA));

      const bytesB = buildNupkg({ packageId: layout.packageId, version: v1, marker: 'v1-again' });
      expectPublish(await rawPublish(layout.repoName, admin, bytesB), 409, 'already exists');

      // Unlike cargo's RPS-1124, the refused duplicate must NOT have touched storage: the DB check
      // and the storage write happen in the same publish call, after the conflict check throws.
      const dlAfter = await rawDownloadNupkg(layout.repoName, admin, idLower, verLower);
      expect(dlAfter.status, 'the stored nupkg is unchanged after the refused duplicate').toBe(200);
      expect(sha256Hex(dlAfter.body), 'the stored nupkg is still bytesA, not bytesB').toBe(
        sha256Hex(bytesA),
      );
    },
  );

  test(
    'a new version is accepted regardless of allowOverride; an existing one is replaced only ' +
      'when allowOverride is on',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();
      const v1 = nugetAdapter.version('release');

      const bytesA = buildNupkg({ packageId: layout.packageId, version: v1, marker: 'v1' });
      expectPublish(await rawPublish(layout.repoName, admin, bytesA), 201, undefined);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });

      const v2 = nugetAdapter.version('release');
      const bytesV2 = buildNupkg({ packageId: layout.packageId, version: v2, marker: 'v2' });
      expectPublish(await rawPublish(layout.repoName, admin, bytesV2), 201, undefined);

      const idLower = layout.packageId.toLowerCase();
      const versionsRes = await rawGetVersions(layout.repoName, admin, idLower);
      expect(parseVersions(versionsRes.body).sort()).toEqual(
        [normalizeVersion(v1), normalizeVersion(v2)].sort(),
      );

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: true,
      });

      const bytesAReplaced = buildNupkg({
        packageId: layout.packageId,
        version: v1,
        marker: 'v1-2',
      });
      expectPublish(await rawPublish(layout.repoName, admin, bytesAReplaced), 201, undefined);

      const verLower = normalizeVersion(v1);
      const dlAfter = await rawDownloadNupkg(layout.repoName, admin, idLower, verLower);
      expect(dlAfter.status).toBe(200);
      expect(sha256Hex(dlAfter.body), 'allowOverride: true replaced the stored bytes').toBe(
        sha256Hex(bytesAReplaced),
      );
    },
  );

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
    'invalid version strings and non-package bodies are refused with 400, storing nothing',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badinput');
      const admin = adminCredential();

      // 5 numeric parts: the server's own version pattern allows at most 4.
      expectPublish(
        await rawPublish(
          layout.repoName,
          admin,
          buildNupkg({ packageId: layout.packageId, version: '1.2.3.4.5' }),
        ),
        400,
        'Invalid NuGet version format',
      );
      expectPublish(
        await rawPublish(
          layout.repoName,
          admin,
          buildNupkg({ packageId: layout.packageId, version: 'not-a-version' }),
        ),
        400,
        'Invalid NuGet version format',
      );

      // A non-zip body: the .nuspec extraction fails before any version/id check runs.
      expectPublish(
        await rawPublish(layout.repoName, admin, Buffer.from('not a zip file at all')),
        400,
        'not a valid NuGet package',
      );

      // A non-multipart PUT (Content-Type not multipart/form-data at all).
      const res = await fetch(publishUrl(layout.repoName), {
        method: 'PUT',
        headers: { ...authHeader(admin), 'Content-Type': 'application/octet-stream' },
        body: Buffer.from('irrelevant'),
      });
      const bytes = Buffer.from(await res.arrayBuffer());
      expectPublish(
        { status: res.status, body: bytes },
        400,
        'Content-Type must be multipart/form-data',
      );

      const idLower = layout.packageId.toLowerCase();
      expect((await rawGetVersions(layout.repoName, admin, idLower)).status, 'nothing stored').toBe(
        404,
      );
    },
  );

  test(
    'version normalization: 1.0.0.0 is stored/served as 1.0.0',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'normalize');
      const admin = adminCredential();

      expectPublish(
        await rawPublish(
          layout.repoName,
          admin,
          buildNupkg({ packageId: layout.packageId, version: '1.0.0.0' }),
        ),
        201,
        undefined,
      );

      const idLower = layout.packageId.toLowerCase();
      const versionsRes = await rawGetVersions(layout.repoName, admin, idLower);
      expect(parseVersions(versionsRes.body)).toEqual(['1.0.0']);

      const dl = await rawDownloadNupkg(layout.repoName, admin, idLower, '1.0.0');
      expect(dl.status, 'downloadable under the normalized version').toBe(200);
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

      expect(byType.get('PackageBaseAddress/3.0.0')).toBe(
        `${env.repoBaseUrl}/${repo.name}/v3/package`,
      );
      expect(byType.get('PackagePublish/2.0.0')).toBe(`${env.repoBaseUrl}/${repo.name}/v3/package`);
      expect(byType.get('RegistrationsBaseUrl')).toBe(
        `${env.repoBaseUrl}/${repo.name}/v3/registration`,
      );

      // RPS-1213/RPS-1240 (fixed): NuGet.Client's ServiceTypes.cs resolves "RegistrationsBaseUrl" as
      // a bare, unversioned type (among "/Versioned", "/3.0.0-beta", "/3.0.0-rc", "/3.4.0",
      // "/3.6.0"), but has NO bare form for search: SearchQueryService is only "/Versioned",
      // "/3.4.0" or "/3.0.0-beta", SearchAutocompleteService only "/Versioned" or "/3.0.0-beta".
      // This server therefore advertises the bare types (as the service-index docs list them) AND
      // "/3.0.0-beta" for both, at the same URL -- the earliest recognised version, since nothing
      // beyond the base semantics is implemented (not "RegistrationsBaseUrl/3.6.0"'s SemVer2
      // registration semantics either). The invented "PackageDelete/2.0.0" is no longer advertised
      // -- unlist/relist live under PackagePublish/2.0.0's own PUT/DELETE, per the NuGet API docs.
      expect(byType.get('SearchQueryService/3.0.0-beta')).toBe(
        `${env.repoBaseUrl}/${repo.name}/v3/search`,
      );
      expect(byType.get('SearchAutocompleteService/3.0.0-beta')).toBe(
        `${env.repoBaseUrl}/${repo.name}/v3/autocomplete`,
      );
      expect(resources.map((r) => r.type)).toContain('SearchQueryService');
      expect(resources.map((r) => r.type)).toContain('SearchAutocompleteService');
      expect(resources.map((r) => r.type)).not.toContain('PackageDelete/2.0.0');
    },
  );

  test(
    'X-NuGet-ApiKey accepts a deploy token, rejects a user password, and accepts a ' +
      'Basic-prefixed value (H7)',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const packageId = `e2e-${seeder.runId}-apikey`;
      const version = nugetAdapter.version('release');

      // A deploy token works as an api key (the panel's "Option B" for a token credential).
      const tokenRes = await rawPublish(
        repo.name,
        { transport: 'basic', kind: 'token', username: token.username, password: token.token },
        buildNupkg({ packageId, version, marker: 'by-token' }),
      );
      expectPublish(tokenRes, 201, undefined);

      // RPS-1214: NuGetAuthPreProcessor.extractAuthHeader feeds
      // X-NuGet-ApiKey through the SAME Bearer path a raw deploy token uses
      // (normalizeAuthHeader prefixes "Bearer " unless the value already starts with "Basic "/
      // "Bearer "), and a user/admin PASSWORD is never a valid bearer credential. This is the
      // intended, documented contract, not an open bug: the panel's "Option B" text
      // (nuget-config.component.ts) used to tell users to pass their password as --api-key, but
      // has been corrected to the deploy token only (a password is unattributable in a
      // single-string header with no username, so honouring it would require testing it against
      // every user -- a credential oracle). This 401 pins that the server keeps refusing a
      // password there.
      const admin = adminCredential();
      const v2 = nugetAdapter.version('release');
      const form2 = new FormData();
      form2.append(
        'package',
        new Blob([new Uint8Array(buildNupkg({ packageId, version: v2, marker: 'by-password' }))]),
        'package.nupkg',
      );
      const passwordPublish = await fetch(publishUrl(repo.name), {
        method: 'PUT',
        headers: { 'X-NuGet-ApiKey': admin.password ?? '' },
        body: form2,
      });
      expect(
        passwordPublish.status,
        'RPS-1214: X-NuGet-ApiKey never authenticates a user password (it is fed through the ' +
          'Bearer path); the panel’s Option B text now documents the deploy token only',
      ).toBe(401);

      // A "Basic <base64>" value in X-NuGet-ApiKey is dispatched unchanged to the Basic path
      // (normalizeAuthHeader leaves it alone since it already starts with "Basic ") -- documents
      // why `--api-key "Basic <base64(user:pass)>"` is the workaround for a password credential.
      const v3 = nugetAdapter.version('release');
      const basicValue = `Basic ${Buffer.from(`${env.adminUsername}:${env.adminPassword}`).toString('base64')}`;
      const form3 = new FormData();
      form3.append(
        'package',
        new Blob([
          new Uint8Array(buildNupkg({ packageId, version: v3, marker: 'by-basic-apikey' })),
        ]),
        'package.nupkg',
      );
      const basicApiKeyRes = await fetch(publishUrl(repo.name), {
        method: 'PUT',
        headers: { 'X-NuGet-ApiKey': basicValue },
        body: form3,
      });
      expect(basicApiKeyRes.status, 'a Basic-prefixed X-NuGet-ApiKey value authenticates').toBe(
        201,
      );
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
});
