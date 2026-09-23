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
 * The Ruby gem server's registry rules, pinned at the protocol level with raw HTTP POSTs/GETs (no
 * `gem`/`bundle` client), the ruby analogue of `tests/pypi/registry-rules.spec.ts`/
 * `tests/golang/registry-rules.spec.ts`. Every status/detail here was read from
 * `RubyGemServiceImpl`/`AbstractRubyProtocolFacade`/`GemspecParser`/`CompactIndexFormatter`/
 * `RubySpecsIndexWriter`/`RubyAuthPreProcessor` first and then confirmed against a running instance
 * (see `ruby-raw.ts`'s file header and `README.md`'s "Ruby runner" section for the raw evidence and
 * every H/RB-number these tests reference).
 *
 * Backend bugs confirmed live while building this suite (RPS-1235/RPS-1236/RPS-1237/RPS-1238 are now
 * fixed, their tests below no longer pinned; RPS-1233/RPS-1234 are each still their own open Jira
 * story, per this repo's e2e process; RB-2/RB-7/RB-8 are source/observation notes with no ticket,
 * see README.md's "Ruby runner" section for why):
 *  - **RPS-1233**: `quick/Marshal.4.8/<name>-<version>.gemspec.rz` has no backend route at all (`404
 *    unknownPath`) -- breaks `gem install`/`gem fetch`, though NOT `bundle install` (H1, see
 *    `ruby-raw.ts`'s file header).
 *  - **RB-2** (observation): `/info/<gem>` never advertises `ruby:`/`rubygems:` requirement keys,
 *    even though `required_ruby_version` is parsed and stored.
 *  - **RPS-1234**: `specs.4.8.gz`/`latest_specs.4.8.gz`/`prerelease_specs.4.8.gz` are zlib-deflated
 *    (RFC1950), not gzip (RFC1952).
 *  - **RPS-1235** (fixed): a yanked version is now omitted from `/info/<gem>`, as the compact-index
 *    spec requires, instead of being listed with a `-` prefix.
 *  - **RPS-1236** (fixed): a gem NAME containing a hyphen immediately followed by a digit (`foo-2fa`)
 *    now downloads by its own filename -- the download path resolves the filename against the DB
 *    instead of re-deriving the name from it.
 *  - **RPS-1237** (fixed): `HEAD` now mirrors the matching `GET` route's status (`200`/`404`)
 *    instead of always answering `200`.
 *  - **RPS-1238** (fixed): a yanked version's `.gem` file stays downloadable by exact URL, matching
 *    rubygems.org -- yank only unpublishes from the index.
 */
import zlib from 'node:zlib';

import { RepoType } from '../../src/api/panel-api.js';
import { rubyAdapter } from '../../src/clients/ruby.js';
import {
  adminCredential,
  authHeader,
  buildGem,
  buildRawGem,
  bundleHostKey,
  gemFilename,
  gemRelPath,
  gemspecRzRelPath,
  infoRelPath,
  md5Hex,
  msgIdOf,
  namesRelPath,
  parseInfo,
  parseNames,
  parseVersionsIndex,
  publishUrl,
  rawAuthHeaderFor,
  rawDownload,
  rawGet,
  rawHead,
  rawPublish,
  rawYank,
  specsRelPath,
  versionsRelPath,
  type RawResponse,
} from '../../src/clients/ruby-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
}

/** A fresh ruby repo (permissive defaults) and a run-unique gem name for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e_${seeder.runId}_${label}` };
}

function expectMsgId(res: RawResponse, status: number, msgId: string | undefined): void {
  expect(res.status, `answered ${res.status} (msgId ${msgIdOf(res.body) ?? 'none'})`).toBe(status);
  if (msgId !== undefined) {
    expect(msgIdOf(res.body), 'the error msgId').toBe(msgId);
  }
}

test.describe('ruby registry rules (raw HTTP)', () => {
  test(
    'a read-only deploy token publish is refused with a flat 401 (not 403), and the same token ' +
      'can still read /info (R1)',
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
      const version = rubyAdapter.version('release');
      const built = await buildGem({ name: layout.packageName, version });

      const res = await rawPublish(layout.repoName, credential, built.bytes);
      expect(res.status, 'a read-only token cannot publish').toBe(401);

      const seedRes = await rawPublish(layout.repoName, adminCredential(), built.bytes);
      expectMsgId(seedRes, 200, undefined);

      const readRes = await rawGet(layout.repoName, credential, infoRelPath(layout.packageName));
      expect(readRes.status, 'the read-only token can still READ').toBe(200);
    },
  );

  test(
    'anonymous READ works on a public repo; WRITE and READ both need a credential on a private ' +
      'one (R1)',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const publicRepo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
      const privateRepo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
      const name = `e2e_${seeder.runId}_anon`;
      const version = rubyAdapter.version('release');
      const built = await buildGem({ name, version });

      expectMsgId(
        await rawPublish(publicRepo.name, adminCredential(), built.bytes),
        200,
        undefined,
      );
      const anonReadPublic = await rawGet(publicRepo.name, {}, infoRelPath(name));
      expect(anonReadPublic.status, 'anonymous READ on a public repo').toBe(200);

      const anonReadPrivate = await rawGet(privateRepo.name, {}, infoRelPath(name));
      expect(anonReadPrivate.status, 'anonymous READ on a private repo').toBe(401);
      // A missing Authorization header is a bodyless 401 (RubyAuthPreProcessor), unlike an
      // authenticated-but-refused request, which carries the real JSON error envelope.
      expect(
        anonReadPrivate.body,
        'the 401 for a missing Authorization header is bodyless',
      ).toHaveLength(0);

      const anonWrite = await rawPublish(privateRepo.name, {}, built.bytes);
      expect(anonWrite.status, 'anonymous WRITE').toBe(401);
    },
  );

  test(
    'Authorization spellings: a raw deploy token, a Bearer-prefixed one, and Basic all work; a ' +
      'raw unprefixed password does not (R3, H3)',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'authspell');
      const token = await seeder.createToken(layout.repoName, { readOnly: false });
      const tokenCred = {
        transport: 'basic' as const,
        username: token.username,
        password: token.token,
        kind: 'token' as const,
      };
      const passwordCred = adminCredential();

      const rawTokenRes = await rawPublish(
        layout.repoName,
        tokenCred,
        (await buildGem({ name: layout.packageName, version: '0.1.0' })).bytes,
      );
      expectMsgId(rawTokenRes, 200, undefined);

      const bearerRes = await rawPublish(
        layout.repoName,
        tokenCred,
        (await buildGem({ name: layout.packageName, version: '0.2.0' })).bytes,
        { authorizationOverride: `Bearer ${token.token}` },
      );
      expectMsgId(bearerRes, 200, undefined);

      const basicRes = await rawPublish(
        layout.repoName,
        passwordCred,
        (await buildGem({ name: layout.packageName, version: '0.3.0' })).bytes,
        {
          authorizationOverride: rawAuthHeaderFor(passwordCred, { spelling: 'basic' })
            .Authorization,
        },
      );
      expectMsgId(basicRes, 200, undefined);

      const rawPasswordRes = await rawPublish(
        layout.repoName,
        passwordCred,
        (await buildGem({ name: layout.packageName, version: '0.4.0' })).bytes,
        { authorizationOverride: env.adminPassword },
      );
      expect(rawPasswordRes.status, 'a raw, unprefixed password is not accepted').toBe(401);
    },
  );

  test(
    'happy-path shape: /names, /versions, /info bodies, checksum, content types (R2, R9 latest/' +
      'prerelease split)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'shape');
      const admin = adminCredential();
      const built = await buildGem({ name: layout.packageName, version: '1.0.0' });
      const publishRes = await rawPublish(layout.repoName, admin, built.bytes);
      expectMsgId(publishRes, 200, undefined);
      expect(publishRes.contentType, 'publish is served as text/plain').toBe('text/plain');
      expect(publishRes.body.toString('utf8')).toBe(
        `Successfully registered gem: ${layout.packageName} (1.0.0)`,
      );

      const namesRes = await rawGet(layout.repoName, admin, namesRelPath());
      expect(parseNames(namesRes.body)).toContain(layout.packageName);

      const infoRes = await rawGet(layout.repoName, admin, infoRelPath(layout.packageName));
      const entries = parseInfo(infoRes.body);
      expect(entries).toHaveLength(1);
      expect(entries[0].version).toBe('1.0.0');
      expect(entries[0].checksum).toBe(built.sha256Hex);
      // RB-2 (observation): no ruby:/rubygems: requirement keys are ever emitted.
      expect(entries[0].dependenciesRaw).not.toContain('ruby:');
      expect(entries[0].dependenciesRaw).not.toContain('rubygems:');

      const versionsRes = await rawGet(layout.repoName, admin, versionsRelPath());
      const versionsIndex = parseVersionsIndex(versionsRes.body);
      expect(versionsIndex[layout.packageName]?.versionsCsv).toBe('1.0.0');
      expect(versionsIndex[layout.packageName]?.md5).toBe(md5Hex(infoRes.body));
    },
  );

  test(
    'the override rule is row-first: a refused re-publish under allowOverride:false changes ' +
      'nothing on disk (R4, RB-0/RPS-1060 re-verified)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();
      const builtA = await buildGem({ name: layout.packageName, version: '1.0.0', marker: 'A' });
      expectMsgId(await rawPublish(layout.repoName, admin, builtA.bytes), 200, undefined);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });

      const filename = gemFilename(layout.packageName, '1.0.0');
      const dlBefore = await rawDownload(layout.repoName, admin, filename);
      expect(dlBefore.status).toBe(200);

      const builtB = await buildGem({ name: layout.packageName, version: '1.0.0', marker: 'B' });
      expectMsgId(
        await rawPublish(layout.repoName, admin, builtB.bytes),
        409,
        'gemVersionAlreadyExists',
      );

      const dlAfter = await rawDownload(layout.repoName, admin, filename);
      expect(
        dlAfter.body.equals(builtA.bytes),
        'the refused override left the original bytes',
      ).toBe(true);

      // Now allow override: the SAME version is accepted and replaced in place.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: true,
      });
      expectMsgId(await rawPublish(layout.repoName, admin, builtB.bytes), 200, undefined);
      const dlReplaced = await rawDownload(layout.repoName, admin, filename);
      expect(
        dlReplaced.body.equals(builtB.bytes),
        'override:true replaces the bytes in place',
      ).toBe(true);
    },
  );

  test(
    'a malformed gem (no metadata.gz entry, or a metadata.gz that is not valid gemspec YAML) is ' +
      'refused with 400, nothing stored (R6)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'malformed');
      const admin = adminCredential();

      const noMetadata = buildRawGem([{ name: 'data.tar.gz', data: Buffer.from('not even gzip') }]);
      const res1 = await rawPublish(layout.repoName, admin, noMetadata.bytes);
      expectMsgId(res1, 400, 'invalidGemFile');

      const badGzip = buildRawGem([{ name: 'metadata.gz', data: Buffer.from('not gzip at all') }]);
      const res2 = await rawPublish(layout.repoName, admin, badGzip.bytes);
      expectMsgId(res2, 400, 'invalidGemFile');

      const notEvenATar = Buffer.from('this is not a tar file at all, just plain bytes');
      const res3 = await rawPublish(layout.repoName, admin, notEvenATar);
      expectMsgId(res3, 400, 'invalidGemFile');

      const namesRes = await rawGet(layout.repoName, admin, namesRelPath());
      expect(parseNames(namesRes.body)).toHaveLength(0);
    },
  );

  test(
    'gem yank: success, re-yank is refused, a read-only token/USER-role password cannot yank, a ' +
      'missing parameter is refused (R8)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'yank');
      const admin = adminCredential();
      const built = await buildGem({ name: layout.packageName, version: '1.0.0' });
      expectMsgId(await rawPublish(layout.repoName, admin, built.bytes), 200, undefined);

      const roToken = await seeder.createToken(layout.repoName, { readOnly: true });
      const roCred = {
        transport: 'basic' as const,
        username: roToken.username,
        password: roToken.token,
        kind: 'token' as const,
      };
      const roYank = await rawYank(layout.repoName, roCred, {
        gemName: layout.packageName,
        version: '1.0.0',
      });
      expect(roYank.status, 'a read-only token cannot yank (MANAGE)').toBe(401);

      const user = await seeder.createUser();
      const userCred = {
        transport: 'basic' as const,
        username: user.username,
        password: user.password,
        kind: 'password' as const,
      };
      const userYank = await rawYank(layout.repoName, userCred, {
        gemName: layout.packageName,
        version: '1.0.0',
      });
      expect(userYank.status, 'a USER-role password cannot yank (not MANAGE)').toBe(401);

      const okYank = await rawYank(layout.repoName, admin, {
        gemName: layout.packageName,
        version: '1.0.0',
      });
      expectMsgId(okYank, 200, undefined);

      const reyank = await rawYank(layout.repoName, admin, {
        gemName: layout.packageName,
        version: '1.0.0',
      });
      expectMsgId(reyank, 400, 'gemVersionAlreadyYanked');

      const unknown = await rawYank(layout.repoName, admin, {
        gemName: layout.packageName,
        version: '9.9.9',
      });
      expect(unknown.status, 'yanking a version that never existed').toBe(404);

      // Yank only unpublishes from the index; the .gem file stays downloadable by exact URL,
      // matching rubygems.org (RPS-1238).
      const dl = await rawDownload(
        layout.repoName,
        admin,
        gemFilename(layout.packageName, '1.0.0'),
      );
      expect(dl.status, 'a yanked gem file is still downloadable (RPS-1238)').toBe(200);
      expect(dl.body, 'the bytes are unchanged').toEqual(built.bytes);

      // A yanked version cannot be re-pushed even under allowOverride:true.
      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: true,
      });
      const rebuilt = await buildGem({
        name: layout.packageName,
        version: '1.0.0',
        marker: 'again',
      });
      const republish = await rawPublish(layout.repoName, admin, rebuilt.bytes);
      expectMsgId(republish, 409, 'gemVersionAlreadyExists');
    },
  );

  test(
    'a panel-deleted version (not yanked) can be re-published with 200 (R5/R16)',
    { tag: ['@negative'] },
    async ({ seeder, panelApi }) => {
      const layout = await newRepo(seeder, 'paneldelete');
      const admin = adminCredential();
      const built = await buildGem({ name: layout.packageName, version: '1.0.0' });
      expectMsgId(await rawPublish(layout.repoName, admin, built.bytes), 200, undefined);

      await panelApi.deleteRubyGemVersion(layout.repoName, layout.packageName, '1.0.0');

      const infoAfterDelete = await rawGet(layout.repoName, admin, infoRelPath(layout.packageName));
      expect(infoAfterDelete.status).toBe(404);

      const rebuilt = await buildGem({
        name: layout.packageName,
        version: '1.0.0',
        marker: 'again',
      });
      const republish = await rawPublish(layout.repoName, admin, rebuilt.bytes);
      expectMsgId(republish, 200, undefined);
    },
  );

  test(
    'specs.4.8.gz is zlib-deflated, not gzip (RPS-1234); the Marshal payload starts with the 4.8 ' +
      'header; prerelease/latest split by "contains a letter"',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'specsgz');
      const admin = adminCredential();
      const builtRelease = await buildGem({ name: layout.packageName, version: '1.0.0' });
      await rawPublish(layout.repoName, admin, builtRelease.bytes);
      const builtPrerelease = await buildGem({ name: layout.packageName, version: '2.0.0.pre1' });
      await rawPublish(layout.repoName, admin, builtPrerelease.bytes);

      const specsRes = await rawGet(layout.repoName, admin, specsRelPath('specs'));
      expect(specsRes.status).toBe(200);
      expect(() => zlib.gunzipSync(specsRes.body)).toThrow();
      const inflated = zlib.inflateSync(specsRes.body);
      expect(inflated.subarray(0, 2)).toEqual(Buffer.from([0x04, 0x08]));

      const latestRes = await rawGet(layout.repoName, admin, specsRelPath('latest_specs'));
      const latestInflated = zlib.inflateSync(latestRes.body);
      expect(latestInflated.length).toBeGreaterThan(0);

      const prereleaseRes = await rawGet(layout.repoName, admin, specsRelPath('prerelease_specs'));
      const prereleaseInflated = zlib.inflateSync(prereleaseRes.body);
      expect(prereleaseInflated.toString('latin1')).toContain('2.0.0.pre1');
      expect(prereleaseInflated.toString('latin1')).not.toContain('1.0.0\u0000');
    },
  );

  test(
    'quick/Marshal.4.8/*.gemspec.rz has no backend route at all (RPS-1233)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'gemspecrz');
      const admin = adminCredential();
      await rawPublish(
        layout.repoName,
        admin,
        (
          await buildGem({
            name: layout.packageName,
            version: '1.0.0',
          })
        ).bytes,
      );

      const res = await rawGet(
        layout.repoName,
        admin,
        gemspecRzRelPath(layout.packageName, '1.0.0'),
      );

      test.fail(
        true,
        'RPS-1233: no backend class extends AbstractRubyGemspecHandler -- the route falls through to ' +
          'the router’s catch-all, 404 unknownPath',
      );
      expect(res.status, 'the gemspec.rz route is implemented').toBe(200);
    },
  );

  test(
    'an unknown gem 404s; an empty repo’s /versions is just the preamble',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'unknown');
      const admin = adminCredential();

      expectMsgId(
        await rawGet(layout.repoName, admin, infoRelPath(layout.packageName)),
        404,
        'gemNotFound',
      );
      expect(
        (await rawDownload(layout.repoName, admin, gemFilename(layout.packageName, '1.0.0')))
          .status,
      ).toBe(404);

      const versionsRes = await rawGet(layout.repoName, admin, versionsRelPath());
      expect(parseVersionsIndex(versionsRes.body)).toEqual({});

      const namesRes = await rawGet(layout.repoName, admin, namesRelPath());
      expect(parseNames(namesRes.body)).toEqual([]);
    },
  );

  test('HEAD mirrors GET’s status (RPS-1237)', { tag: ['@negative'] }, async ({ seeder }) => {
    const layout = await newRepo(seeder, 'head');
    const admin = adminCredential();
    const built = await buildGem({ name: layout.packageName, version: '1.0.0' });
    expectMsgId(await rawPublish(layout.repoName, admin, built.bytes), 200, undefined);

    const unknownPath = await rawHead(layout.repoName, admin, 'this/path/never/existed');
    expect(unknownPath.status, 'HEAD of a path that was never published').toBe(404);

    const unpublishedGem = await rawHead(
      layout.repoName,
      admin,
      gemRelPath(gemFilename('never-published', '9.9.9')),
    );
    expect(unpublishedGem.status, 'HEAD of a .gem that was never published').toBe(404);

    const publishedGem = await rawHead(layout.repoName, admin, gemRelPath(built.filename));
    expect(publishedGem.status, 'HEAD of a published .gem').toBe(200);

    const versions = await rawHead(layout.repoName, admin, versionsRelPath());
    expect(versions.status, 'HEAD of /versions').toBe(200);
  });

  test(
    'a platform gem: filename/info-line shape, yank needs the explicit platform (R14)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'platform');
      const admin = adminCredential();
      const built = await buildGem({
        name: layout.packageName,
        version: '1.0.0',
        platform: 'java',
      });
      expectMsgId(await rawPublish(layout.repoName, admin, built.bytes), 200, undefined);

      expect(built.filename).toBe(`${layout.packageName}-1.0.0-java.gem`);
      const dl = await rawDownload(layout.repoName, admin, built.filename);
      expect(dl.status).toBe(200);

      const infoRes = await rawGet(layout.repoName, admin, infoRelPath(layout.packageName));
      const entries = parseInfo(infoRes.body);
      expect(entries[0].platform).toBe('java');

      // Yanking WITHOUT the platform targets the default "ruby" platform, which does not exist here.
      const wrongPlatform = await rawYank(layout.repoName, admin, {
        gemName: layout.packageName,
        version: '1.0.0',
      });
      expect(wrongPlatform.status, 'yank defaults to platform "ruby"').toBe(404);

      const rightPlatform = await rawYank(layout.repoName, admin, {
        gemName: layout.packageName,
        version: '1.0.0',
        platform: 'java',
      });
      expectMsgId(rightPlatform, 200, undefined);
    },
  );

  test(
    'releases/snapshots repo settings are never read: any version string publishes and is ' +
      'servable regardless (R15)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
      await seeder.setSettings(repo.name, {
        privateRepo: true,
        allowOverride: true,
        releases: false,
        snapshots: false,
      });
      const admin = adminCredential();
      const name = `e2e_${seeder.runId}_norulesetting`;

      for (const version of ['1.0.0', '1.0.0.pre1', '2.0.0.beta']) {
        const built = await buildGem({ name, version });
        expectMsgId(await rawPublish(repo.name, admin, built.bytes), 200, undefined);
        const dl = await rawDownload(repo.name, admin, gemFilename(name, version));
        expect(dl.status, `"${version}" is servable`).toBe(200);
      }
    },
  );

  test(
    'a gem name containing a hyphen immediately followed by a digit publishes and downloads by ' +
      'its own filename (RPS-1236)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
      const admin = adminCredential();
      const name = `e2e-${seeder.runId}-2fa`; // deliberately hyphenated, unlike packageName()
      const built = await buildGem({ name, version: '1.0.0' });

      expectMsgId(await rawPublish(repo.name, admin, built.bytes), 200, undefined);

      const infoRes = await rawGet(repo.name, admin, infoRelPath(name));
      expect(infoRes.status, '/info still finds it (looked up by DB row, not by filename)').toBe(
        200,
      );

      const res = await rawDownload(repo.name, admin, built.filename);
      expect(res.status, 'the gem downloads by its own filename').toBe(200);
      expect(res.body).toEqual(built.bytes);
    },
  );

  test(
    'bundleHostKey/packageName sanity: the host-key transform and the underscore-only name ' +
      'helper (sanity)',
    { tag: ['@smoke'] },
    async () => {
      expect(bundleHostKey('localhost')).toBe('BUNDLE_LOCALHOST');
      expect(bundleHostKey('127.0.0.1')).toBe('BUNDLE_127__0__0__1');
      expect(publishUrl('e2e-x')).toBe(`${env.repoBaseUrl}/e2e-x/api/v1/gems`);
    },
  );

  test(
    'the Authorization header is correctly spelled per credential kind',
    { tag: ['@smoke'] },
    async () => {
      const tokenCred = {
        transport: 'basic' as const,
        username: 'u',
        password: 'tok',
        kind: 'token' as const,
      };
      expect(authHeader({}).Authorization).toBeUndefined();
      expect(rawAuthHeaderFor(tokenCred).Authorization).toBe('tok');
    },
  );
});
