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
 * The npm server's registry rules, pinned at the protocol level with raw HTTP PUTs/GETs (no `npm`
 * client), the npm analogue of `tests/maven/upload-rules.spec.ts`. Every status/msgId here was read
 * from `AbstractNpmProtocolFacade.publish`/`PackageUtils.extractVersionNameFromPayload` first and
 * then confirmed against a running instance (see this repo's e2e run report for the raw evidence):
 *
 *  - `allowOverride: false` refuses re-publishing a version that already exists (403
 *    `packageVersionAlreadyExists`) and leaves the package's packument and every version's tarball
 *    byte-for-byte as they were; a NEW version of the SAME already-existing package is still
 *    accepted regardless of `allowOverride` (`AbstractNpmProtocolFacade.publish` only checks
 *    override for a version that is already present).
 *  - A malformed/invalid semver version string is refused with 400 `invalidPackageVersion`
 *    (`PackageUtils.extractVersionNameFromPayload`, before anything is read from the payload beyond
 *    the version name) and stores nothing.
 *  - RPS-1205 (fixed): a packument's own `dist.tarball` (computed by the registry, RPS-1333) and the tarball's real, canonical stored path
 *    (`<packagePath>/-/<tarballFilename>`) are fetched and compared directly, live -- both now serve
 *    the same, real bytes; `fixTarballUrl` used to splice the repo name into the path at an offset
 *    that assumed a cloud, multi-tenant URL shape Repsy OS does not have.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { repoUrl } from '../../src/repo-url.js';
import {
  adminCredential,
  buildPublishDocument,
  parsePackument,
  rawGetPackument,
  rawGetPath,
  rawGetTarballByUrl,
  rawGetTarballCanonical,
  rawPublish,
  sha256Hex,
  tarballPath,
  type RawResponse,
} from '../../src/clients/npm-raw.js';
import { npmAdapter } from '../../src/clients/npm.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
}

/** A fresh npm repo (permissive defaults) and a run-unique, unscoped package name for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e-${seeder.runId}-${label}` };
}

function fakeTarball(seed: string): Buffer {
  // Repsy never validates that a stored "tarball" is actually a valid tar+gzip stream (the same way
  // maven's raw seeds in upload-rules.spec.ts use plain strings as "jar" bytes), so arbitrary bytes
  // are enough for a raw-only probe.
  return Buffer.from(`fake npm tarball ${seed}`, 'utf8');
}

function expectPut(
  res: RawResponse,
  status: number,
  msgId: string | undefined,
  what: string,
): void {
  expect(res.status, `${what}: PUT answered ${res.status} ${res.msgId ?? ''}`).toBe(status);
  expect(res.msgId, `${what}: the error message id`).toBe(msgId);
}

test.describe('npm registry rules (raw HTTP)', () => {
  test(
    'allowOverride:false refuses an existing version, never a new one of the same package',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();
      const v1 = npmAdapter.version('release');

      const firstBytes = fakeTarball('v1');
      const firstDoc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version: v1,
        tarballBytes: firstBytes,
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, firstDoc),
        200,
        undefined,
        'seed v1',
      );

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
      });

      const packumentBefore = await rawGetPackument(layout.repoName, admin, layout.packageName);
      const tarballBefore = await rawGetTarballCanonical(
        layout.repoName,
        admin,
        layout.packageName,
        v1,
      );

      // Re-publishing the SAME version, even with different bytes, is refused and changes nothing.
      const overrideBytes = fakeTarball('v1-again');
      const overrideDoc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version: v1,
        tarballBytes: overrideBytes,
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, overrideDoc),
        403,
        'packageVersionAlreadyExists',
        'republish of an existing version',
      );

      const packumentAfter = await rawGetPackument(layout.repoName, admin, layout.packageName);
      const tarballAfter = await rawGetTarballCanonical(
        layout.repoName,
        admin,
        layout.packageName,
        v1,
      );
      expect(packumentAfter.body.toString('utf8'), 'the packument is unchanged').toBe(
        packumentBefore.body.toString('utf8'),
      );
      expect(sha256Hex(tarballAfter.body), 'the stored tarball is unchanged').toBe(
        sha256Hex(tarballBefore.body),
      );

      // A NEW version of the same already-existing package is still accepted: only re-publishing an
      // existing version is judged by allowOverride.
      const v2 = npmAdapter.version('release');
      const v2Doc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version: v2,
        tarballBytes: fakeTarball('v2'),
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, v2Doc),
        200,
        undefined,
        'a new version of the same package',
      );

      const finalPackument = await rawGetPackument(layout.repoName, admin, layout.packageName);
      const { versions } = parsePackument(finalPackument.body);
      expect(Object.keys(versions).sort()).toEqual([v1, v2].sort());
    },
  );

  test(
    'an invalid version string is refused and stores nothing',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badversion');
      const admin = adminCredential();

      const doc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version: 'not-a-semver-version',
        tarballBytes: fakeTarball('bad'),
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, doc),
        400,
        'invalidPackageVersion',
        'malformed version string',
      );

      const packument = await rawGetPackument(layout.repoName, admin, layout.packageName);
      expect(packument.status, 'nothing was ever published for this package').toBe(404);
    },
  );

  test(
    'RPS-1209: a revoked deploy token sent as Bearer is answered unAuthorized like a wrong password',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'revoked');
      const token = await seeder.createToken(layout.repoName);
      const credential = {
        transport: 'basic' as const,
        kind: 'token' as const,
        password: token.token,
      };

      // Alive, the same Bearer secret is accepted: the packument just does not exist yet.
      const alive = await rawGetPackument(layout.repoName, credential, layout.packageName);
      expect(alive.status, 'a live deploy token is authenticated (404, not 401)').toBe(404);

      await seeder.revokeNow(layout.repoName, token.id);

      // A Bearer value that is no live deploy token and no protocol JWT is a wrong credential: the
      // same `unAuthorized` a wrong Basic password gets (it used to be `accessNotAllowed`), counted
      // against the auth throttle like one. A server whose throttle this harness cannot tune
      // (a remote target) reserves one failure slot for this `@negative` test.
      const revoked = await rawGetPackument(layout.repoName, credential, layout.packageName);
      expect(revoked.status, 'a revoked deploy token as Bearer').toBe(401);
      expect(revoked.msgId, 'the error message id of a revoked Bearer token').toBe('unAuthorized');
    },
  );

  test(
    'RPS-1205 (fixed): the packument dist.tarball URL matches the canonical stored path',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'tarball');
      const admin = adminCredential();
      const version = npmAdapter.version('release');
      const bytes = fakeTarball('rps-1205');

      const doc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version,
        tarballBytes: bytes,
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, doc),
        200,
        undefined,
        'publish',
      );

      const packument = await rawGetPackument(layout.repoName, admin, layout.packageName);
      expect(packument.status, 'GET packument').toBe(200);
      const { versions } = parsePackument(packument.body);
      const distTarballUrl = versions[version]?.dist?.tarball;
      expect(distTarballUrl, 'the packument names a dist.tarball for this version').toBeDefined();

      const canonical = await rawGetPath(
        layout.repoName,
        admin,
        tarballPath(layout.packageName, version),
      );
      expect(canonical.status, 'the canonical stored path always serves the real bytes').toBe(200);
      expect(
        sha256Hex(canonical.body),
        'the canonical path serves exactly what was published',
      ).toBe(sha256Hex(bytes));

      // RPS-1205 (fixed): the packument's own dist.tarball used to be corrupted by
      // PackageUtils.fixTarballUrl, which spliced the repo name into the path at an offset that
      // assumed a cloud, multi-tenant URL shape Repsy OS does not have, so it always answered 404
      // `itemNotFound` here instead of the real bytes -- even though the canonical path above always
      // had them (a URL-construction bug, not a storage one). fixTarballUrl now rebuilds only the
      // filename after the last `/-/` from the version's own name/version, which is a no-op for the
      // URL a real npm client already computes, so dist.tarball is servable too.
      const viaDistTarball = await rawGetTarballByUrl(distTarballUrl as string, admin);
      expect(viaDistTarball.status, `dist.tarball ("${distTarballUrl}") is now servable`).toBe(200);
      expect(
        sha256Hex(viaDistTarball.body),
        'dist.tarball serves exactly what was published, same as the canonical path',
      ).toBe(sha256Hex(bytes));
    },
  );

  test(
    'RPS-1333 (fixed): dist.tarball is the registry address, not the one the publisher sent',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'tarball-host');
      const admin = adminCredential();
      const version = npmAdapter.version('release');
      const bytes = fakeTarball('rps-1333');

      // What libnpmpublish sends for an HTTPS registry reached under another address: a foreign
      // host and an `http://` scheme.
      const doc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version,
        tarballBytes: bytes,
        tarballUrl: `http://publisher.invalid:9090/${tarballPath(layout.packageName, version)}`,
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, doc),
        200,
        undefined,
        'publish',
      );

      const packument = await rawGetPackument(layout.repoName, admin, layout.packageName);
      expect(packument.status, 'GET packument').toBe(200);
      const served = parsePackument(packument.body).versions[version]?.dist?.tarball;

      expect(served, 'the registry names itself, whatever the publisher sent').toBe(
        repoUrl(layout.repoName, tarballPath(layout.packageName, version)),
      );

      const viaDistTarball = await rawGetTarballByUrl(served as string, admin);
      expect(viaDistTarball.status, `dist.tarball ("${served}") is servable`).toBe(200);
      expect(sha256Hex(viaDistTarball.body)).toBe(sha256Hex(bytes));
    },
  );
});
