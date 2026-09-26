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
 * The Go module proxy's registry rules, pinned at the protocol level with raw HTTP PUTs/GETs (no
 * `curl`/`go` client), the golang analogue of `tests/pypi/registry-rules.spec.ts`/
 * `tests/nuget/registry-rules.spec.ts`. Every status/detail here was read from
 * `AbstractGoProtocolFacade`/`GoVersionUtils`/`GoModFileValidator`/`GoModuleZipReader`/
 * `GoModuleServiceImpl`/`GolangAuthPreProcessor` first and then confirmed against a running instance
 * (see `golang-raw.ts`'s file header and `README.md`'s "Go runner" section for the raw evidence and
 * every H/G number these tests reference).
 *
 * Two backend bugs were found and confirmed live while building this suite; both are fixed
 * (RPS-1227, RPS-1228, #447), and the tests below pin the corrected behaviour:
 *  - **RPS-1227**: the version string was never validated -- `banana`/`v1`/`1.0.0` were all
 *    accepted, stored immutably, and listed by `@v/list`. It is now refused with a `400
 *    invalidModuleVersion`.
 *  - **RPS-1228**: the go.mod `module` directive was never compared against the URL's own module
 *    path -- a zip whose go.mod named a completely different module uploaded successfully (a real
 *    `go get` of the URL's own path then failed, `publish-consume.spec.ts`'s own test). It is now
 *    refused with a `400 goModModulePathMismatch`.
 *
 * A third, **RPS-1232** (module paths were lower-cased for storage/lookup, so `GoProbe` and
 * `goprobe` collided as the SAME module even though Go itself treats module paths as
 * case-sensitive), has since been fixed. The test below now pins the corrected, case-sensitive
 * behaviour instead of `test.fail()`-ing the collision.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { golangAdapter } from '../../src/clients/golang.js';
import {
  adminCredential,
  authHeader,
  buildModuleZip,
  buildModuleZipWithGoMod,
  infoRelPath,
  latestRelPath,
  listRelPath,
  MODULE_DOMAIN,
  modRelPath,
  msgIdOf,
  packageName as rawPackageName,
  parseInfo,
  parseVersionList,
  rawGet,
  rawHead,
  rawPut,
  rawUpload,
  sha256Hex,
  uploadUrl,
  zipRelPath,
  type GoRawResponse,
} from '../../src/clients/golang-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  modulePath: string;
}

/** A fresh golang repo (permissive defaults) and a run-unique module path for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
  return { repoName: repo.name, modulePath: `${MODULE_DOMAIN}/e2e-${seeder.runId}-${label}` };
}

function expectMsgId(res: GoRawResponse, status: number, msgId: string | undefined): void {
  expect(res.status, `answered ${res.status} (msgId ${msgIdOf(res.body) ?? 'none'})`).toBe(status);
  if (msgId !== undefined) {
    expect(msgIdOf(res.body), 'the error msgId').toBe(msgId);
  }
}

test.describe('golang registry rules (raw HTTP)', () => {
  test(
    'a read-only deploy token publish is refused with a flat 401 (not 403), and the same token ' +
      'can still read @v/list (R1)',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'roauth');
      const token = await seeder.createToken(layout.repoName, { readOnly: true });
      const credential = {
        transport: 'basic' as const,
        username: token.username,
        password: token.token,
        kind: 'token' as const,
      };
      const built = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });

      const res = await rawUpload(layout.repoName, credential, built);
      expectMsgId(res, 401, undefined);
      // RPS-1435: the go command prints a 401 body only when it is text/plain, so that is what the
      // refusal is (it was the panel's JSON envelope, or empty when no credentials were sent).
      expect(res.contentType, 'a text/plain refusal the go command can print').toMatch(
        /^text\/plain/,
      );
      expect(res.body.toString('utf8'), 'the generic message').toMatch(/credentials/);

      const seedRes = await rawUpload(layout.repoName, adminCredential(), built);
      expectMsgId(seedRes, 200, undefined);

      const readRes = await rawGet(layout.repoName, credential, listRelPath(layout.modulePath));
      expect(readRes.status, 'the read-only token can still READ @v/list').toBe(200);
      expect(parseVersionList(readRes.body)).toContain('v0.0.1');
    },
  );

  test(
    'the upload URL accepts no suffix, a .zip suffix, and (G6) even a .mod/.info suffix with a ' +
      'zip body (R2)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'suffixes');
      const admin = adminCredential();

      const noSuffix = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, noSuffix, { urlSuffix: '' }),
        200,
        undefined,
      );

      const zipSuffix = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.2' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, zipSuffix, { urlSuffix: '.zip' }),
        200,
        undefined,
      );

      // G6: a PUT to a ".mod"-suffixed URL is STILL treated as a zip-body upload -- the suffix is
      // only used to strip the version out of the last path segment, nothing else looks at it.
      const modSuffix = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.3' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, modSuffix, { urlSuffix: '.mod' }),
        200,
        undefined,
      );

      const listRes = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      expect(parseVersionList(listRes.body)).toEqual(['v0.0.1', 'v0.0.2', 'v0.0.3']);

      // The ".mod"-suffixed PUT really did store a working module: its own .zip route serves the
      // zip body, and .mod carries the go.mod EXTRACTED from that zip, not the raw PUT body.
      const zipRes = await rawGet(layout.repoName, admin, zipRelPath(layout.modulePath, 'v0.0.3'));
      expect(sha256Hex(zipRes.body)).toBe(modSuffix.sha256Hex);
    },
  );

  test(
    'Content-Sha256 is verified when present, ignored when absent (R3)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'sha256');
      const admin = adminCredential();

      const correct = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(layout.repoName, admin, correct), 200, undefined);

      const wrong = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.2' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, wrong, { contentSha256: 'deadbeef' }),
        400,
        'sha256Mismatch',
      );

      // An upper-case hex digest is lower-cased before comparison.
      const upper = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.3' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, upper, {
          contentSha256: upper.sha256Hex.toUpperCase(),
        }),
        200,
        undefined,
      );

      // No header at all: skipped, never computed.
      const absent = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.4' });
      expectMsgId(
        await rawUpload(layout.repoName, admin, absent, { contentSha256: null }),
        200,
        undefined,
      );

      const listRes = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      expect(parseVersionList(listRes.body)).toEqual(['v0.0.1', 'v0.0.3', 'v0.0.4']);
    },
  );

  test(
    'a duplicate version is refused with a REAL, UNCONDITIONAL 409 under both allowOverride ' +
      'settings, and the refused re-PUT never changes what is stored (R4/H9)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      for (const allowOverride of [false, true]) {
        const layout = await newRepo(seeder, `dup${allowOverride ? 'on' : 'off'}`);
        await seeder.setSettings(layout.repoName, {
          privateRepo: true,
          allowOverride,
        });
        const admin = adminCredential();

        const original = await buildModuleZip({
          modulePath: layout.modulePath,
          version: 'v0.0.1',
          marker: 'original',
        });
        expectMsgId(await rawUpload(layout.repoName, admin, original), 200, undefined);

        const modBefore = await rawGet(
          layout.repoName,
          admin,
          modRelPath(layout.modulePath, 'v0.0.1'),
        );
        const zipBefore = await rawGet(
          layout.repoName,
          admin,
          zipRelPath(layout.modulePath, 'v0.0.1'),
        );

        const duplicate = await buildModuleZip({
          modulePath: layout.modulePath,
          version: 'v0.0.1',
          marker: 'duplicate',
        });
        expectMsgId(
          await rawUpload(layout.repoName, admin, duplicate),
          409,
          'goModuleVersionAlreadyExists',
        );

        const modAfter = await rawGet(
          layout.repoName,
          admin,
          modRelPath(layout.modulePath, 'v0.0.1'),
        );
        const zipAfter = await rawGet(
          layout.repoName,
          admin,
          zipRelPath(layout.modulePath, 'v0.0.1'),
        );
        expect(
          sha256Hex(modAfter.body),
          `allowOverride:${allowOverride} -- go.mod is unchanged by the refused re-PUT`,
        ).toBe(sha256Hex(modBefore.body));
        expect(
          sha256Hex(zipAfter.body),
          `allowOverride:${allowOverride} -- the zip is unchanged by the refused re-PUT`,
        ).toBe(sha256Hex(zipBefore.body));
      }
    },
  );

  test(
    'zip validation errors, confirming nothing is stored after each (R5)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'zipvalidation');
      const admin = adminCredential();

      // A non-zip body: no zip entries at all -> goModNotFoundInZip.
      const notAZip = Buffer.from('not a zip file');
      expectMsgId(
        await rawPut(layout.repoName, admin, `${layout.modulePath}/@v/v0.1.0`, notAZip),
        400,
        'goModNotFoundInZip',
      );

      // A zip whose go.mod entry is missing entirely (wrong version in the entry name).
      const built = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.1.0' });
      const wrongVersionUrl = uploadUrl(layout.repoName, layout.modulePath, 'v0.2.0');
      const wrongVersionRes = await fetch(wrongVersionUrl, {
        method: 'PUT',
        headers: authHeader(admin),
        body: new Uint8Array(built.bytes),
      });
      expect(wrongVersionRes.status, 'go.mod entry name must match the URL version exactly').toBe(
        400,
      );

      for (const relPath of [
        infoRelPath(layout.modulePath, 'v0.1.0'),
        modRelPath(layout.modulePath, 'v0.1.0'),
        zipRelPath(layout.modulePath, 'v0.1.0'),
        infoRelPath(layout.modulePath, 'v0.2.0'),
        modRelPath(layout.modulePath, 'v0.2.0'),
        zipRelPath(layout.modulePath, 'v0.2.0'),
      ]) {
        const res = await rawGet(layout.repoName, admin, relPath);
        expect(res.status, `nothing was stored for "${relPath}"`).toBe(404);
      }

      const listRes = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      // A module without versions is a 404 with a text/plain reason (RPS-1428), not an empty list.
      expect(listRes.status, 'nothing is listed').toBe(404);
    },
  );

  test(
    'a go.mod whose module directive names a different module than the URL path is refused with ' +
      '400 goModModulePathMismatch (RPS-1228)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'mismatch');
      const admin = adminCredential();
      const version = 'v0.1.0';

      const goModText = `module ${MODULE_DOMAIN}/completely-different\n\ngo 1.21\n`;
      const built = buildModuleZipWithGoMod({ modulePath: layout.modulePath, version, goModText });
      const res = await rawPut(
        layout.repoName,
        admin,
        `${layout.modulePath}/@v/${version}`,
        built.bytes,
      );

      expectMsgId(res, 400, 'goModModulePathMismatch');
    },
  );

  test(
    'a non-semver version string is refused with 400 invalidModuleVersion (RPS-1227)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'nonsemver');
      const admin = adminCredential();

      const built = await buildModuleZip({ modulePath: layout.modulePath, version: 'banana' });
      const res = await rawUpload(layout.repoName, admin, built);

      expectMsgId(res, 400, 'invalidModuleVersion');
    },
  );

  test(
    '@v/list is a sorted, newline-joined text/plain body; an unknown module is a text/plain 404, ' +
      'so the go command tries the next GOPROXY entry (R8, RPS-1428)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'list');
      const admin = adminCredential();

      const unknownRes = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      expect(unknownRes.status, 'an unknown module is not found, not an empty list').toBe(404);
      expect(unknownRes.contentType).toBe('text/plain');
      expect(new TextDecoder().decode(unknownRes.body)).toContain('not found');

      for (const version of ['v0.2.0', 'v0.1.0', 'v0.10.0']) {
        const built = await buildModuleZip({ modulePath: layout.modulePath, version });
        expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);
      }

      const res = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      // Real semver order, not lexicographic: v0.2.0 < v0.10.0.
      expect(parseVersionList(res.body)).toEqual(['v0.1.0', 'v0.2.0', 'v0.10.0']);
    },
  );

  test(
    '@latest serves the highest published version’s .info; an unknown module 404s (R9)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'latest');
      const admin = adminCredential();

      const unknownRes = await rawGet(layout.repoName, admin, latestRelPath(layout.modulePath));
      expect(unknownRes.status, 'an unknown module 404s on @latest').toBe(404);

      for (const version of ['v0.1.0', 'v0.3.0', 'v0.2.0']) {
        const built = await buildModuleZip({ modulePath: layout.modulePath, version });
        expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);
      }

      const res = await rawGet(layout.repoName, admin, latestRelPath(layout.modulePath));
      expect(res.status).toBe(200);
      expect(res.contentType).toBe('application/json');
      expect(parseInfo(res.body).Version).toBe('v0.3.0');
    },
  );

  test(
    'module paths are case-sensitive for storage/lookup: a mixed-case upload does not collide ' +
      "with its lower-case spelling -- each is Go's own, distinct module (RPS-1232, fixed)",
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'mixedcase');
      const admin = adminCredential();

      const lower = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(layout.repoName, admin, lower), 200, undefined);

      const mixedCasePath = layout.modulePath.replace(/e2e-/, 'E2E-');
      const mixed = await buildModuleZip({ modulePath: mixedCasePath, version: 'v0.0.2' });
      expectMsgId(await rawUpload(layout.repoName, admin, mixed), 200, undefined);

      const listRes = await rawGet(layout.repoName, admin, listRelPath(layout.modulePath));
      expect(parseVersionList(listRes.body), 'the mixed-case upload is a DISTINCT module').toEqual([
        'v0.0.1',
      ]);

      const mixedListRes = await rawGet(layout.repoName, admin, listRelPath(mixedCasePath));
      expect(
        parseVersionList(mixedListRes.body),
        'the mixed-case module keeps its own, separate version history',
      ).toEqual(['v0.0.2']);
    },
  );

  test(
    'a malformed module path (no /@v/ segment) is a bodyless 400 (R11)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'malformed');
      const admin = adminCredential();

      const res = await fetch(`${env.repoBaseUrl}/${layout.repoName}/${layout.modulePath}`, {
        method: 'PUT',
        headers: authHeader(admin),
        body: new Uint8Array(Buffer.from('irrelevant')),
      });
      expect(res.status, 'no "/@v/" segment at all').toBe(400);
    },
  );

  test(
    'sumdb/supported 404s on both the protocol port and the API port (R12/G9)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'sumdb');
      const admin = adminCredential();

      const protocolRes = await rawGet(layout.repoName, admin, 'sumdb/supported');
      expect(
        protocolRes.status,
        'the go command lands here and gets a plain storage-miss 404',
      ).toBe(404);

      const apiRes = await fetch(
        `${env.apiBaseUrl}/api/go/modules/${layout.repoName}/sumdb/supported`,
      );
      expect(apiRes.status, 'the API port answers 404 deliberately (checkSumdbSupported)').toBe(
        404,
      );
    },
  );

  test(
    'an over-long module path/version is refused before the body is even read (R13)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'overlong');
      const admin = adminCredential();

      // > 512 chars after the domain -- MAX_MODULE_PATH_LENGTH.
      const longPath = `${MODULE_DOMAIN}/${'x'.repeat(520)}`;
      const longPathRes = await fetch(
        `${env.repoBaseUrl}/${layout.repoName}/${longPath}/@v/v0.0.1`,
        {
          method: 'PUT',
          headers: authHeader(admin),
          body: new Uint8Array(Buffer.from('irrelevant')),
        },
      );
      expectMsgId(
        { status: longPathRes.status, body: Buffer.from(await longPathRes.arrayBuffer()) },
        400,
        'modulePathTooLong',
      );

      // > 100 chars -- MAX_VERSION_LENGTH.
      const longVersion = `v0.0.${'1'.repeat(100)}`;
      const longVersionRes = await fetch(
        `${env.repoBaseUrl}/${layout.repoName}/${layout.modulePath}/@v/${longVersion}`,
        {
          method: 'PUT',
          headers: authHeader(admin),
          body: new Uint8Array(Buffer.from('irrelevant')),
        },
      );
      expectMsgId(
        { status: longVersionRes.status, body: Buffer.from(await longVersionRes.arrayBuffer()) },
        400,
        'moduleVersionTooLong',
      );
    },
  );

  test(
    'a deleted version can be re-uploaded cleanly (never a 410 -- the GONE path is dead) ' +
      '(R14/RPS-1230)',
    { tag: ['@negative'] },
    async ({ seeder, panelApi }) => {
      const layout = await newRepo(seeder, 'deletereupload');
      const admin = adminCredential();

      const original = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(layout.repoName, admin, original), 200, undefined);

      await panelApi.deleteGolangModuleVersion(layout.repoName, layout.modulePath, 'v0.0.1');

      const afterDeleteRes = await rawGet(
        layout.repoName,
        admin,
        infoRelPath(layout.modulePath, 'v0.0.1'),
      );
      expect(afterDeleteRes.status, 'the deleted version is gone entirely, not merely "GONE"').toBe(
        404,
      );

      const reupload = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(layout.repoName, admin, reupload), 200, undefined);

      const afterReuploadRes = await rawGet(
        layout.repoName,
        admin,
        zipRelPath(layout.modulePath, 'v0.0.1'),
      );
      expect(afterReuploadRes.status).toBe(200);
      expect(sha256Hex(afterReuploadRes.body)).toBe(reupload.sha256Hex);
    },
  );

  test(
    'deleting the last version removes the module, the wire answers a 404 list and a 404 latest, ' +
      'and a republish brings the module back (RPS-1288, RPS-1428)',
    { tag: ['@negative'] },
    async ({ seeder, panelApi }) => {
      const layout = await newRepo(seeder, 'lastversion');
      const admin = adminCredential();
      const list = listRelPath(layout.modulePath);
      const latest = latestRelPath(layout.modulePath);

      for (const version of ['v0.0.1', 'v0.0.2']) {
        const built = await buildModuleZip({ modulePath: layout.modulePath, version });
        expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);
      }

      // A version is left: the module stays, and serves what is left.
      await panelApi.deleteGolangModuleVersion(layout.repoName, layout.modulePath, 'v0.0.1');
      const oneLeft = await rawGet(layout.repoName, admin, list);
      expect(oneLeft.status).toBe(200);
      expect(parseVersionList(oneLeft.body)).toEqual(['v0.0.2']);
      const stillLatest = await rawGet(layout.repoName, admin, latestRelPath(layout.modulePath));
      expect(stillLatest.status).toBe(200);
      expect(parseInfo(stillLatest.body).Version).toBe('v0.0.2');

      // The last one goes: the module goes with it. The wire answers as it does for a module that
      // never existed: no list and no latest version, both 404 (RPS-1428).
      await panelApi.deleteGolangModuleVersion(layout.repoName, layout.modulePath, 'v0.0.2');
      const goneList = await rawGet(layout.repoName, admin, list);
      expect(goneList.status, 'a module without versions has no @v/list').toBe(404);
      expect((await rawGet(layout.repoName, admin, latest)).status).toBe(404);
      expect(
        (await rawGet(layout.repoName, admin, infoRelPath(layout.modulePath, 'v0.0.2'))).status,
      ).toBe(404);
      await expect(
        panelApi.deleteGolangModuleVersion(layout.repoName, layout.modulePath, 'v0.0.2'),
        'the module is gone, so a second delete finds nothing',
      ).rejects.toMatchObject({ status: 404 });

      // Publishing again creates the module again.
      const again = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.2' });
      expectMsgId(await rawUpload(layout.repoName, admin, again), 200, undefined);
      const republished = await rawGet(layout.repoName, admin, list);
      expect(parseVersionList(republished.body)).toEqual(['v0.0.2']);
      const latestAgain = await rawGet(layout.repoName, admin, latest);
      expect(latestAgain.status).toBe(200);
      expect(parseInfo(latestAgain.body).Version).toBe('v0.0.2');
    },
  );

  test(
    'HEAD on any path (existing or not) is 404 -- no handler supports it (R15/H17)',
    {
      tag: ['@negative'],
    },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'head');
      const admin = adminCredential();

      const built = await buildModuleZip({ modulePath: layout.modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);

      const existingRes = await rawHead(
        layout.repoName,
        admin,
        infoRelPath(layout.modulePath, 'v0.0.1'),
      );
      expect(existingRes.status, 'HEAD of a path that DOES exist').toBe(404);

      const missingRes = await rawHead(
        layout.repoName,
        admin,
        infoRelPath(layout.modulePath, 'v9.9.9'),
      );
      expect(missingRes.status, 'HEAD of a path that does not exist').toBe(404);
    },
  );

  test(
    'releases/snapshots are not go repo settings (the settings PUT refuses them, RPS-1210): a ' +
      'module publishes regardless of version kind (R16)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
      await seeder.setSettings(repo.name, {
        privateRepo: true,
        allowOverride: true,
      });
      const admin = adminCredential();
      const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-norulesetting`;

      const built = await buildModuleZip({ modulePath, version: 'v0.0.1' });
      expectMsgId(await rawUpload(repo.name, admin, built), 200, undefined);

      const res = await rawGet(repo.name, admin, zipRelPath(modulePath, 'v0.0.1'));
      expect(res.status).toBe(200);
    },
  );

  test(
    'the packageName helper is already the full module path, and HTML/JSON route paths compose ' +
      'as expected (sanity)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const name = rawPackageName(seeder.runId, {
        id: 'sanity-check',
        tags: [],
        repo: { privateRepo: true },
        credential: 'admin-password',
        expect: { publish: 'ok', consume: 'ok' },
      });
      expect(name.startsWith(`${MODULE_DOMAIN}/`)).toBe(true);
      expect(listRelPath(name)).toBe(`${name}/@v/list`);
      expect(modRelPath(name, 'v0.0.1')).toBe(`${name}/@v/v0.0.1.mod`);
      expect(golangAdapter.protocol).toBe('golang');
    },
  );
});
