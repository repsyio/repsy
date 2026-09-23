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
 * The docker server's registry rules, pinned at the protocol level with raw HTTP (no `crane`
 * client), the docker analogue of `tests/nuget/registry-rules.spec.ts`. Every status/detail here was
 * read from `AbstractDockerProtocolTxFacade`/`DockerAuthComponent`/`DockerHeaderPreProcessor` first
 * and then confirmed against a running instance (see `docker-raw.ts`'s file header and README.md's
 * "Docker runner" section for the raw evidence and the full H1-H14 write-up). Sections R1-R13 mirror
 * the implementation plan's own hypothesis numbering 1:1.
 */
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { isolatedWorkDir } from '../../src/clients/exec.js';
import {
  adminCredential,
  ociErrorOf,
  parseBearerChallenge,
  pullScope,
  pushScope,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  rawPing,
  rawPutManifest,
  rawStartUpload,
  rawToken,
  rawUploadBlob,
  sha256Hex,
  type RawResponse,
} from '../../src/clients/docker-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  image: string;
}

async function newRepo(
  seeder: Seeder,
  label: string,
  opts: { privateRepo?: boolean } = {},
): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: opts.privateRepo ?? true });
  return { repoName: repo.name, image: `e2e-${seeder.runId}-${label}` };
}

/** A fresh OCI layout in its own isolated work directory -- every raw test below builds its own,
 *  never `crane`, never `docker build` (`docker-image.ts`'s file header). */
async function freshImage(
  label: string,
  marker: string,
): Promise<Awaited<ReturnType<typeof buildImage>>> {
  const { work } = await isolatedWorkDir(label);
  return buildImage({ dir: path.join(work, 'image'), marker });
}

/** Uploads both blobs of a freshly built image, start to finish (raw, no `crane`). */
async function uploadBlobs(
  layout: Layout,
  credential: Parameters<typeof rawUploadBlob>[1],
  built: Awaited<ReturnType<typeof buildImage>>,
): Promise<void> {
  const configRes = await rawUploadBlob(
    layout.repoName,
    credential,
    layout.image,
    built.configBytes,
    built.configDigest,
  );
  expect(configRes.status, 'config blob upload').toBe(201);
  const layerRes = await rawUploadBlob(
    layout.repoName,
    credential,
    layout.image,
    built.layerBytes,
    built.layerDigest,
  );
  expect(layerRes.status, 'layer blob upload').toBe(201);
}

/** Uploads both blobs, then pushes the manifest under `tag` -- a full raw publish, no `crane`. */
async function rawPushImage(
  layout: Layout,
  credential: Parameters<typeof rawUploadBlob>[1],
  tag: string,
  marker: string,
): Promise<{
  built: Awaited<ReturnType<typeof buildImage>>;
  manifestRes: Awaited<ReturnType<typeof rawPutManifest>>;
}> {
  const built = await freshImage(`docker-raw-${layout.repoName}-${tag}`, marker);
  await uploadBlobs(layout, credential, built);
  const manifestRes = await rawPutManifest(
    layout.repoName,
    credential,
    layout.image,
    tag,
    built.manifestBytes,
    built.manifestMediaType,
  );
  return { built, manifestRes };
}

function expectOci(res: RawResponse, status: number, code: string | undefined): void {
  const oci = ociErrorOf(res.body);
  expect(res.status, `answered ${res.status} ${oci?.message ?? ''}`).toBe(status);
  if (code !== undefined) {
    expect(oci?.code, 'the OCI error code').toBe(code);
  }
}

test.describe('docker registry rules (raw HTTP)', () => {
  test("R1: the ping challenge names this instance's own token endpoint", async () => {
    const ping = await rawPing();
    expect(ping.status, 'GET /v2/ without auth is a 401 challenge').toBe(401);
    const oci = ociErrorOf(ping.body);
    expect(oci?.code, 'an OCI UNAUTHORIZED body').toBe('UNAUTHORIZED');

    const parsed = parseBearerChallenge(ping.wwwAuthenticate ?? '');
    expect(parsed.realm, "realm names this instance's own /v2/token").toBe(
      `${env.repoBaseUrl}/v2/token`,
    );
    expect(parsed.service).toBe('repsy');
    expect(parsed.scope, 'the constant scope a real client ignores in favour of its own').toBe(
      'repository:*:pull',
    );
  });

  test(
    'R2: the token endpoint matrix (issuance is never scope-checked)',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'tokenmatrix');
      const publicLayout = await newRepo(seeder, 'tokenmatrixpub', { privateRepo: false });
      const admin = adminCredential();

      const adminPush = await rawToken(admin, pushScope(layout.repoName, layout.image));
      expect(adminPush.status, 'admin, push scope').toBe(200);
      expect(adminPush.token).toBeTruthy();

      const rw = await seeder.createToken(layout.repoName, { readOnly: false });
      const rwCred = {
        transport: 'basic' as const,
        username: rw.username,
        password: rw.token,
        kind: 'token' as const,
      };
      const rwTok = await rawToken(rwCred, pushScope(layout.repoName, layout.image));
      expect(rwTok.status, 'rw deploy token, push scope').toBe(200);

      const ro = await seeder.createToken(layout.repoName, { readOnly: true });
      const roCred = {
        transport: 'basic' as const,
        username: ro.username,
        password: ro.token,
        kind: 'token' as const,
      };
      // Issuance is NOT scope-checked: a read-only token still gets a token for a push scope.
      const roTok = await rawToken(roCred, pushScope(layout.repoName, layout.image));
      expect(roTok.status, 'ro deploy token issuance still succeeds for a push scope').toBe(200);

      const otherRepo = await newRepo(seeder, 'tokenmatrixother');
      const otherTokenSeed = await seeder.createToken(otherRepo.repoName, { readOnly: false });
      const otherCred = {
        transport: 'basic' as const,
        username: otherTokenSeed.username,
        password: otherTokenSeed.token,
        kind: 'token' as const,
      };
      const otherTok = await rawToken(otherCred, pushScope(layout.repoName, layout.image));
      expect(otherTok.status, 'a token of a DIFFERENT repo still gets issued a token here').toBe(
        200,
      );

      const expired = await seeder.createToken(layout.repoName, {
        readOnly: false,
        expirationDate: new Date(Date.now() - 24 * 60 * 60 * 1000),
      });
      const expiredCred = {
        transport: 'basic' as const,
        username: expired.username,
        password: expired.token,
        kind: 'token' as const,
      };
      const expiredTok = await rawToken(expiredCred, pushScope(layout.repoName, layout.image));
      expect(expiredTok.status, 'an expired token is refused at the TOKEN hop').toBe(401);
      expect(
        expiredTok.wwwAuthenticate,
        "the token endpoint's own bodyless Basic challenge",
      ).toContain('Basic realm=');

      const wrongPassword = {
        transport: 'basic' as const,
        username: env.adminUsername,
        password: `${env.adminPassword}-wrong`,
        kind: 'password' as const,
      };
      const wrongTok = await rawToken(wrongPassword, pushScope(layout.repoName, layout.image));
      expect(wrongTok.status, 'a wrong password is refused at the token hop').toBe(401);

      const noAuthPush = await rawToken({}, pushScope(layout.repoName, layout.image));
      expect(noAuthPush.status, 'no credentials, a push scope -> 401').toBe(401);

      const noAuthPullPrivate = await rawToken({}, pullScope(layout.repoName, layout.image));
      expect(
        noAuthPullPrivate.status,
        'no credentials, a pull scope on a PRIVATE repo -> 401',
      ).toBe(401);

      const noAuthPullPublic = await rawToken(
        {},
        pullScope(publicLayout.repoName, publicLayout.image),
      );
      expect(
        noAuthPullPublic.status,
        'no credentials, a pull scope on a PUBLIC repo -> 200 (anonymous)',
      ).toBe(200);
      expect(noAuthPullPublic.token).toBeTruthy();
    },
  );

  test(
    "R3: a read-only token's own token-endpoint issuance succeeds, but its first WRITE is refused " +
      'at the operation hop; the same token still reads fine',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'rowrite', { privateRepo: false });
      const ro = await seeder.createToken(layout.repoName, { readOnly: true });
      const roCred = {
        transport: 'basic' as const,
        username: ro.username,
        password: ro.token,
        kind: 'token' as const,
      };

      const uploadRes = await rawStartUpload(layout.repoName, roCred, layout.image);
      expect(uploadRes.hop, 'refused at the OPERATION hop, not the token hop').toBe('request');
      expectOci(uploadRes, 401, 'UNAUTHORIZED');

      const readRes = await rawGetManifest(layout.repoName, roCred, layout.image, 'doesnotexist');
      expect(
        readRes.status,
        'a READ with the same ro token still succeeds (404: no such tag)',
      ).toBe(404);
    },
  );

  test(
    'R4: blob upload flows (monolithic, chunked, wrong digest, dedup)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'blobflows');
      const admin = adminCredential();
      const built = await freshImage('docker-r4', 'r4');

      const monolithic = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.configBytes,
        built.configDigest,
        { mode: 'monolithic' },
      );
      expect(monolithic.status, 'monolithic upload').toBe(201);
      expect(monolithic.digestHeader).toBe(built.configDigest);

      const chunked = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.layerBytes,
        built.layerDigest,
        { mode: 'patch' },
      );
      expect(chunked.status, 'chunked (PATCH) upload').toBe(201);
      expect(chunked.digestHeader).toBe(built.layerDigest);

      const wrongDigest = 'sha256:' + '0'.repeat(64);
      const wrong = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.layerBytes,
        wrongDigest,
        { mode: 'monolithic' },
      );
      expectOci(wrong, 400, 'DIGEST_INVALID');

      const dedup = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.configBytes,
        built.configDigest,
        { mode: 'monolithic' },
      );
      expect(dedup.status, 're-uploading an existing digest is accepted again (dedup)').toBe(201);

      const head = await rawHeadBlob(layout.repoName, admin, layout.image, built.configDigest);
      expect(head.status).toBe(200);
      expect(
        head.contentType,
        'every blob (incl. config) is stored with the DOCKER_LAYER media type',
      ).toBe('application/vnd.docker.image.rootfs.diff.tar.gzip');
    },
  );

  test(
    'R5: manifest push validation (unknown blob digest, wrong sha256: reference, missing/unknown ' +
      'Content-Type)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'manifestvalidation');
      const admin = adminCredential();
      const built = await freshImage('docker-r5', 'r5');

      // No blobs uploaded at all: the manifest push refuses with a missing-blob error.
      const missingBlob = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag1',
        built.manifestBytes,
        built.manifestMediaType,
      );
      expectOci(missingBlob, 404, 'MANIFEST_BLOB_UNKNOWN');

      await uploadBlobs(layout, admin, built);

      // A wrong digest in a `sha256:<hex>` reference push.
      const wrongRef = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        `sha256:${'0'.repeat(64)}`,
        built.manifestBytes,
        built.manifestMediaType,
      );
      expectOci(wrongRef, 400, 'DIGEST_INVALID');

      // An unknown Content-Type: candidate B4, confirmed live -- a flat 500, not a 400.
      const unknownType = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag1',
        built.manifestBytes,
        'text/plain',
      );
      test.fail(
        true,
        'RPS-1110 (B4): an unknown manifest Content-Type answers a flat 500 UNKNOWN ' +
          '(IllegalArgumentException("unsupportedMediaType") has no ErrorHandler mapping), not a ' +
          "4xx -- confirmed live. The repo's Image row for this image name is also already created " +
          'by this point (findOrCreateImage runs before the Content-Type switch).',
      );
      expectOci(unknownType, 400, 'UNSUPPORTED');
    },
  );

  test(
    'R6: the override rule, and an orphan blob after a refused override',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();

      const first = await rawPushImage(layout, admin, 'tag1', 'v1');
      expect(first.manifestRes.status, 'first push of a fresh tag').toBe(201);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: true,
      });

      const refused = await rawPushImage(layout, admin, 'tag1', 'v2');
      expectOci(refused.manifestRes, 403, 'DENIED');
      const refusedOci = ociErrorOf(refused.manifestRes.body);
      expect(refusedOci?.detail).toBe('packageOverrideDisabled');

      // The tag is unchanged after the refusal.
      const getAfterRefused = await rawGetManifest(layout.repoName, admin, layout.image, 'tag1');
      expect(getAfterRefused.status).toBe(200);
      expect(sha256Hex(getAfterRefused.body)).toBe(sha256Hex(first.built.manifestBytes));

      // R6 observation (not a bug -- protocol-inherent, blobs go up before the manifest): the refused
      // push's own blobs are stored regardless.
      const headOrphan = await rawHeadBlob(
        layout.repoName,
        admin,
        layout.image,
        refused.built.layerDigest,
      );
      expect(headOrphan.status, "the refused push's blob is stored anyway (orphaned)").toBe(200);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: true,
        releases: true,
        snapshots: true,
      });
      const allowed = await rawPushImage(layout, admin, 'tag1', 'v3');
      expect(allowed.manifestRes.status, 'accepted once allowOverride is on').toBe(201);
      const getAfterAllowed = await rawGetManifest(layout.repoName, admin, layout.image, 'tag1');
      expect(sha256Hex(getAfterAllowed.body)).toBe(sha256Hex(allowed.built.manifestBytes));
    },
  );

  test(
    'R7/B2: overriding a tag makes the PREVIOUS manifest unpullable by digest',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'overridedigest');
      const admin = adminCredential();

      const first = await rawPushImage(layout, admin, 'tag1', 'v1');
      expect(first.manifestRes.status).toBe(201);
      const oldDigest = first.built.manifestDigest;

      const getOldBefore = await rawGetManifest(layout.repoName, admin, layout.image, oldDigest);
      expect(getOldBefore.status, 'pullable by digest right after the first push').toBe(200);

      const second = await rawPushImage(layout, admin, 'tag1', 'v2');
      expect(second.manifestRes.status, 'allowOverride defaults to true').toBe(201);

      const getOldAfter = await rawGetManifest(layout.repoName, admin, layout.image, oldDigest);
      test.fail(
        true,
        'RPS-1216 (B2): overriding a tag reuses/overwrites its one Manifest row in ' +
          'place (ManifestTxService.updateManifestProperties), so the PREVIOUS manifest becomes ' +
          'unpullable by digest even though nothing ever explicitly deleted it -- confirmed live.',
      );
      expect(getOldAfter.status, 'the old manifest is still pullable by its own digest').toBe(200);
    },
  );

  test(
    'R8/B1: HEAD by digest mirrors GET by digest (RPS-1215, fixed)',
    {
      tag: ['@negative'],
    },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'headbydigest');
      const admin = adminCredential();
      const pushed = await rawPushImage(layout, admin, 'tag1', 'r8');
      expect(pushed.manifestRes.status).toBe(201);
      const digest = pushed.built.manifestDigest;

      const get = await rawGetManifest(layout.repoName, admin, layout.image, digest);
      expect(get.status, 'GET by digest').toBe(200);

      const head = await rawHeadManifest(layout.repoName, admin, layout.image, digest);
      expect(head.status, 'HEAD by digest should also be 200').toBe(200);
    },
  );

  test(
    'R9: retagging (pushing the same digest under a second tag)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'retag');
      const admin = adminCredential();
      const built = await freshImage('docker-r9', 'r9');
      await uploadBlobs(layout, admin, built);

      const pushA = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tagA',
        built.manifestBytes,
        built.manifestMediaType,
      );
      expect(pushA.status, 'push tagA').toBe(201);
      const pushB = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tagB',
        built.manifestBytes,
        built.manifestMediaType,
      );
      expect(pushB.status, 'push the SAME digest under tagB').toBe(201);

      const getA = await rawGetManifest(layout.repoName, admin, layout.image, 'tagA');
      const getB = await rawGetManifest(layout.repoName, admin, layout.image, 'tagB');
      // Observation: both tags still resolve fine via a plain GET (byte-identical content, since the
      // digest -- and therefore the manifest bytes -- is the same either way). A deeper repro of
      // candidate B3 (deleting one tag breaking the other) was time-boxed out per the plan; not forced.
      expect(getA.status, 'tagA still resolves after tagB is pushed with the same digest').toBe(
        200,
      );
      expect(getB.status).toBe(200);
      expect(getA.body.equals(getB.body)).toBe(true);
    },
  );

  test(
    'R12/B5: a config blob missing os/architecture crashes the manifest push (500)',
    {
      tag: ['@negative'],
    },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badconfig');
      const admin = adminCredential();

      // A config JSON missing both `os` and `architecture` -- `extractPlatform`'s `org.json.getString`
      // throws a bare JSONException, which has no ErrorHandler mapping either.
      const configBytes = Buffer.from(JSON.stringify({ config: {} }), 'utf8');
      const configDigest = `sha256:${sha256Hex(configBytes)}`;
      const built = await freshImage('docker-r12', 'r12');

      const configRes = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        configBytes,
        configDigest,
      );
      expect(configRes.status).toBe(201);
      const layerRes = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.layerBytes,
        built.layerDigest,
      );
      expect(layerRes.status).toBe(201);

      const manifestObj = {
        schemaVersion: 2,
        mediaType: built.manifestMediaType,
        config: {
          mediaType: built.configMediaType,
          digest: configDigest,
          size: configBytes.length,
        },
        layers: [
          {
            mediaType: built.layerMediaType,
            digest: built.layerDigest,
            size: built.layerBytes.length,
          },
        ],
      };
      const manifestBytes = Buffer.from(JSON.stringify(manifestObj), 'utf8');
      const res = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag1',
        manifestBytes,
        built.manifestMediaType,
      );
      test.fail(
        true,
        'RPS-1116 (B5): a config blob missing os/architecture crashes the manifest push ' +
          "with a flat 500 (org.json's getString() throws inside extractPlatform, no ErrorHandler " +
          'mapping) -- confirmed live, the sibling of B4.',
      );
      expectOci(res, 400, 'MANIFEST_INVALID');
    },
  );

  test(
    'R13: a multi-arch index referencing a digest-pushed child manifest',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'index');
      const admin = adminCredential();
      const child = await rawPushImage(layout, admin, 'unused-child-tag', 'r13-child');
      // Pushed by TAG above; also push it by DIGEST so an index can reference it (see this file's
      // header: a real client always pushes an index's children by digest first).
      const byDigest = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        child.built.manifestDigest,
        child.built.manifestBytes,
        child.built.manifestMediaType,
      );
      expect(byDigest.status, 'pushing the child again, by its own digest').toBe(201);

      const indexObj = {
        schemaVersion: 2,
        mediaType: 'application/vnd.docker.distribution.manifest.list.v2+json',
        manifests: [
          {
            mediaType: child.built.manifestMediaType,
            digest: child.built.manifestDigest,
            size: child.built.manifestBytes.length,
            platform: { architecture: 'amd64', os: 'linux' },
          },
        ],
      };
      const indexBytes = Buffer.from(JSON.stringify(indexObj), 'utf8');
      const indexRes = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'multiarch',
        indexBytes,
        indexObj.mediaType,
      );
      expect(indexRes.status, 'the index, referencing a digest-pushed child').toBe(201);

      const getIndex = await rawGetManifest(layout.repoName, admin, layout.image, 'multiarch');
      expect(getIndex.status).toBe(200);
      expect(getIndex.contentType?.split(';')[0]).toBe(indexObj.mediaType);
    },
  );

  test(
    'a PUBLIC repo still requires real credentials to write (refused at the token hop, no OCI ' +
      'body -- unlike an operation-hop 401)',
    {
      tag: ['@auth', '@negative'],
    },
    async ({ seeder }) => {
      const publicLayout = await newRepo(seeder, 'publicwrite', { privateRepo: false });
      const upload = await rawStartUpload(publicLayout.repoName, {}, publicLayout.image);
      expect(
        upload.hop,
        'refused at the TOKEN hop: the push scope alone requires real credentials, even on a ' +
          'public repo',
      ).toBe('token');
      expect(upload.status).toBe(401);
      // The token endpoint's own failure carries NO OCI envelope at all (unlike an operation-hop
      // 401) -- confirmed live, see docker-raw.ts's file header.
      expect(ociErrorOf(upload.body), "the token hop's 401 has no OCI body").toBeUndefined();
    },
  );
});
