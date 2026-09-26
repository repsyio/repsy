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
 * the implementation plan's own hypothesis numbering 1:1; R14 (RPS-1244) pins the sha512 manifest
 * digests and R15 (RPS-1216) the protocol `DELETE` of a manifest or a tag.
 */
import { createHash } from 'node:crypto';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { isolatedWorkDir } from '../../src/clients/exec.js';
import {
  adminCredential,
  deleteScope,
  ociErrorOf,
  parseBearerChallenge,
  pullScope,
  pushScope,
  rawDeleteManifest,
  rawGetAnonymous,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  rawPing,
  rawPutManifest,
  rawStartUpload,
  rawToken,
  rawUploadBlob,
  sha256Hex,
  v2Url,
  type RawResponse,
} from '../../src/clients/docker-raw.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  image: string;
}

/** `sha512:<hex>` of the bytes: a manifest's second digest, the one a client that hashes with
 *  sha512 names it by (RPS-1244). */
function sha512Digest(bytes: Buffer): string {
  return `sha512:${createHash('sha512').update(bytes).digest('hex')}`;
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
  test("R1: the ping challenge names this instance's own token endpoint and no scope", async () => {
    const ping = await rawPing();
    expect(ping.status, 'GET /v2/ without auth is a 401 challenge').toBe(401);
    const oci = ociErrorOf(ping.body);
    expect(oci?.code, 'an OCI UNAUTHORIZED body').toBe('UNAUTHORIZED');

    const parsed = parseBearerChallenge(ping.wwwAuthenticate ?? '');
    expect(parsed.realm, "realm names this instance's own /v2/token").toBe(v2Url('/token'));
    expect(parsed.service).toBe('repsy');
    expect(parsed.scope, 'the ping addresses no image, so it names no scope (RPS-1588)').toBe(
      undefined,
    );
  });

  test(
    'R1b: the challenge of a request that addresses an image names the scope that request needs',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const priv = await newRepo(seeder, 'challengescope');
      const pub = await newRepo(seeder, 'challengescopepub', { privateRepo: false });
      const base = (layout: Layout): string => `/${repoPath(layout.repoName)}/${layout.image}`;
      const cases: {
        label: string;
        layout: Layout;
        suffix: string;
        method: 'GET' | 'HEAD' | 'POST' | 'DELETE';
        scope: string;
      }[] = [
        {
          label: 'manifest pull, private repo',
          layout: priv,
          suffix: '/manifests/latest',
          method: 'GET',
          scope: pullScope(priv.repoName, priv.image),
        },
        {
          label: 'manifest check, private repo',
          layout: priv,
          suffix: '/manifests/latest',
          method: 'HEAD',
          scope: pullScope(priv.repoName, priv.image),
        },
        {
          label: 'blob check, private repo',
          layout: priv,
          suffix: `/blobs/sha256:${'0'.repeat(64)}`,
          method: 'HEAD',
          scope: pullScope(priv.repoName, priv.image),
        },
        {
          label: 'manifest pull, public repo (a pull still asks for a token)',
          layout: pub,
          suffix: '/manifests/latest',
          method: 'GET',
          scope: pullScope(pub.repoName, pub.image),
        },
        {
          label: 'blob upload start (a push also pulls)',
          layout: priv,
          suffix: '/blobs/uploads/',
          method: 'POST',
          scope: `repository:${repoPath(priv.repoName)}/${priv.image}:pull,push`,
        },
        {
          label: 'blob upload start on a public repo',
          layout: pub,
          suffix: '/blobs/uploads/',
          method: 'POST',
          scope: `repository:${repoPath(pub.repoName)}/${pub.image}:pull,push`,
        },
        {
          label: 'manifest delete',
          layout: priv,
          suffix: '/manifests/latest',
          method: 'DELETE',
          scope: deleteScope(priv.repoName, priv.image),
        },
      ];

      for (const c of cases) {
        const res = await rawGetAnonymous(`${base(c.layout)}${c.suffix}`, c.method);
        expect(res.status, `${c.label}: a 401 challenge`).toBe(401);
        const parsed = parseBearerChallenge(res.wwwAuthenticate ?? '');
        expect(parsed.realm, `${c.label}: the token endpoint`).toBe(`${env.repoBaseUrl}/v2/token`);
        expect(parsed.service, `${c.label}: the service`).toBe('repsy');
        expect(parsed.scope, `${c.label}: the scope this request needs`).toBe(c.scope);
      }
    },
  );

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
      expect(expiredTok.wwwAuthenticate, "the token endpoint's own Basic challenge").toContain(
        'Basic realm=',
      );
      // RPS-1435: the client prints "unauthorized: <message>", so the cause is in the body.
      expect(ociErrorOf(expiredTok.body)?.code, 'an OCI UNAUTHORIZED body').toBe('UNAUTHORIZED');
      expect(ociErrorOf(expiredTok.body)?.message, 'the expiry is named').toBe(
        'Deploy token expired.',
      );

      const wrongPassword = {
        transport: 'basic' as const,
        username: env.adminUsername,
        password: `${env.adminPassword}-wrong`,
        kind: 'password' as const,
      };
      const wrongTok = await rawToken(wrongPassword, pushScope(layout.repoName, layout.image));
      expect(wrongTok.status, 'a wrong password is refused at the token hop').toBe(401);
      // RPS-1435: a message instead of the empty "unauthorized: ", and the generic one -- an unknown
      // user must read the same, or the message would tell whether an account exists.
      const wrongOci = ociErrorOf(wrongTok.body);
      expect(wrongOci?.code, 'an OCI UNAUTHORIZED body').toBe('UNAUTHORIZED');
      expect(wrongOci?.message, 'a message the client can print').toMatch(/credentials/);
      const unknownUserTok = await rawToken(
        { ...wrongPassword, username: `${env.adminUsername}-nobody` },
        pushScope(layout.repoName, layout.image),
      );
      expect(unknownUserTok.status).toBe(401);
      expect(
        ociErrorOf(unknownUserTok.body),
        'an unknown user reads exactly like a wrong password',
      ).toEqual(wrongOci);

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

      // containerd and crane send one `scope` parameter per scope, the placeholder first because `*`
      // sorts before every letter. Every value is judged, not only the first (RPS-1588): the
      // placeholder next to a push scope, or next to a scope of a private repo, is not anonymous.
      const placeholder = 'repository:*:pull';
      const noAuthPlaceholderPush = await rawToken({}, [
        placeholder,
        pushScope(publicLayout.repoName, publicLayout.image),
      ]);
      expect(
        noAuthPlaceholderPush.status,
        'no credentials, the placeholder then a push scope of a PUBLIC repo -> 401',
      ).toBe(401);
      const noAuthPlaceholderPrivate = await rawToken({}, [
        placeholder,
        pullScope(layout.repoName, layout.image),
      ]);
      expect(
        noAuthPlaceholderPrivate.status,
        'no credentials, the placeholder then a pull scope of a PRIVATE repo -> 401',
      ).toBe(401);
      const noAuthPlaceholderPublic = await rawToken({}, [
        placeholder,
        pullScope(publicLayout.repoName, publicLayout.image),
      ]);
      expect(
        noAuthPlaceholderPublic.status,
        'no credentials, the placeholder then a pull scope of a PUBLIC repo -> 200 (anonymous)',
      ).toBe(200);
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
    'R4b: the digest-algorithm hint of a blob upload start is honoured (RPS-1594, OCI end-4c)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'blobalgo');
      const admin = adminCredential();
      const built = await freshImage('docker-r4b', 'r4b');

      // sha256 (the default) and sha512 both start a session, and the response is the usual one.
      for (const digestAlgorithm of ['sha256', 'sha512']) {
        const start = await rawStartUpload(layout.repoName, admin, layout.image, {
          digestAlgorithm,
        });
        expect(start.status, `start with digest-algorithm=${digestAlgorithm}`).toBe(202);
        expect(start.location, 'the session Location').toMatch(/\/blobs\/uploads\/[0-9a-f-]{36}$/);
      }

      // A sha512 blob, monolithic and chunked, is stored under its sha512 and served by it.
      const configSha512 = sha512Digest(built.configBytes);
      const monolithic = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.configBytes,
        configSha512,
        { mode: 'monolithic', digestAlgorithm: 'sha512' },
      );
      expect(monolithic.status, 'monolithic upload with the sha512 hint').toBe(201);
      expect(monolithic.digestHeader).toBe(configSha512);

      const layerSha512 = sha512Digest(built.layerBytes);
      const chunked = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.image,
        built.layerBytes,
        layerSha512,
        { mode: 'patch', digestAlgorithm: 'sha512' },
      );
      expect(chunked.status, 'chunked upload with the sha512 hint').toBe(201);
      expect(chunked.digestHeader).toBe(layerSha512);

      for (const digest of [configSha512, layerSha512]) {
        const head = await rawHeadBlob(layout.repoName, admin, layout.image, digest);
        expect(head.status, `HEAD of the blob by ${digest.slice(0, 15)}...`).toBe(200);
      }

      // An algorithm the registry cannot check is refused at the start, with no session to resume.
      for (const digestAlgorithm of ['md5', 'sha384', 'SHA512']) {
        const refused = await rawStartUpload(layout.repoName, admin, layout.image, {
          digestAlgorithm,
        });
        expectOci(refused, 400, 'DIGEST_INVALID');
        expect(refused.location, `no Location for digest-algorithm=${digestAlgorithm}`).toBe(
          undefined,
        );
      }
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

      // An unknown Content-Type: candidate B4 (RPS-1110, fixed) -- refused up front by
      // DockerManifestValidator.validate's default branch, before the image row is even looked up.
      const unknownType = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag1',
        built.manifestBytes,
        'text/plain',
      );
      expectOci(unknownType, 400, 'MANIFEST_INVALID');
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
      });
      const allowed = await rawPushImage(layout, admin, 'tag1', 'v3');
      expect(allowed.manifestRes.status, 'accepted once allowOverride is on').toBe(201);
      const getAfterAllowed = await rawGetManifest(layout.repoName, admin, layout.image, 'tag1');
      expect(sha256Hex(getAfterAllowed.body)).toBe(sha256Hex(allowed.built.manifestBytes));
    },
  );

  test(
    'R7/B2: overriding a tag keeps the PREVIOUS manifest pullable by digest (RPS-1216, fixed)',
    { tag: ['@settings'] },
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

      // A manifest is content-addressed: an override moves the tag pointer and leaves the manifest
      // it pointed at stored, pullable by its own digest.
      const getOldAfter = await rawGetManifest(layout.repoName, admin, layout.image, oldDigest);
      expect(getOldAfter.status, 'the old manifest is still pullable by its own digest').toBe(200);
      expect(sha256Hex(getOldAfter.body), 'byte-identical to what was pushed').toBe(
        sha256Hex(first.built.manifestBytes),
      );
      const headOldAfter = await rawHeadManifest(layout.repoName, admin, layout.image, oldDigest);
      expect(headOldAfter.status, 'HEAD by the old digest mirrors GET').toBe(200);

      // The tag itself now serves the new manifest.
      const getTag = await rawGetManifest(layout.repoName, admin, layout.image, 'tag1');
      expect(sha256Hex(getTag.body)).toBe(sha256Hex(second.built.manifestBytes));
      const getNewByDigest = await rawGetManifest(
        layout.repoName,
        admin,
        layout.image,
        second.built.manifestDigest,
      );
      expect(getNewByDigest.status, 'the new manifest is pullable by its digest as well').toBe(200);
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
    'R12/B5: a config blob missing os/architecture is refused with 400 MANIFEST_INVALID (RPS-1116)',
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
      // RPS-1116, fixed: extractPlatform now runs before the manifest is written, and a config
      // blob missing os/architecture (an image-config media type) is refused with a 4xx that names
      // the problem, not a flat 500.
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
    'R14: a manifest is addressable by sha256 AND sha512, and every response reports the ' +
      'algorithm the client used (RPS-1244)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'sha512');
      const admin = adminCredential();
      const { built, manifestRes } = await rawPushImage(layout, admin, 'latest', 'r14');
      const sha512 = sha512Digest(built.manifestBytes);

      // By tag: the canonical sha256, in the header and in the Location.
      expect(manifestRes.status).toBe(201);
      expect(manifestRes.digestHeader, 'a push by tag reports the sha256').toBe(
        built.manifestDigest,
      );
      expect(manifestRes.location, 'and locates the manifest by its sha256').toMatch(
        new RegExp(`/manifests/${built.manifestDigest}$`),
      );

      // By sha512 (GET and HEAD): the same bytes, the sha512 digest reported back.
      const getBySha512 = await rawGetManifest(layout.repoName, admin, layout.image, sha512);
      expect(getBySha512.status, 'GET by the sha512 of a tag-pushed manifest').toBe(200);
      expect(getBySha512.body.equals(built.manifestBytes)).toBe(true);
      expect(getBySha512.digestHeader, 'GET by sha512 reports the sha512').toBe(sha512);
      const headBySha512 = await rawHeadManifest(layout.repoName, admin, layout.image, sha512);
      expect(headBySha512.status).toBe(200);
      expect(headBySha512.digestHeader, 'HEAD by sha512 reports the sha512').toBe(sha512);

      // By sha256 and by tag: still the sha256.
      const getBySha256 = await rawGetManifest(
        layout.repoName,
        admin,
        layout.image,
        built.manifestDigest,
      );
      expect(getBySha256.digestHeader).toBe(built.manifestDigest);
      const getByTag = await rawGetManifest(layout.repoName, admin, layout.image, 'latest');
      expect(getByTag.digestHeader).toBe(built.manifestDigest);

      // Pushing the same bytes by their sha512 is accepted (no new tag) and answered in sha512.
      const putBySha512 = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        sha512,
        built.manifestBytes,
        built.manifestMediaType,
      );
      expect(putBySha512.status).toBe(201);
      expect(putBySha512.digestHeader, 'a push by sha512 reports the sha512').toBe(sha512);
      expect(putBySha512.location).toMatch(new RegExp(`/manifests/${sha512}$`));

      // A different manifest pushed ONLY by its sha512 is pullable by both digests, never a tag.
      const other = await freshImage('docker-r14-other', 'r14-other');
      await uploadBlobs(layout, admin, other);
      const otherSha512 = sha512Digest(other.manifestBytes);
      const otherPut = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        otherSha512,
        other.manifestBytes,
        other.manifestMediaType,
      );
      expect(otherPut.status, 'a bare sha512 push').toBe(201);
      const otherBySha256 = await rawGetManifest(
        layout.repoName,
        admin,
        layout.image,
        other.manifestDigest,
      );
      expect(otherBySha256.status, 'pullable by its sha256').toBe(200);
      expect(otherBySha256.digestHeader).toBe(other.manifestDigest);
      const otherBySha512 = await rawGetManifest(layout.repoName, admin, layout.image, otherSha512);
      expect(otherBySha512.status, 'pullable by its sha512').toBe(200);
      expect(otherBySha512.digestHeader).toBe(otherSha512);

      // A wrong sha512 reference stays a digest mismatch (RPS-1242); an unknown one is a 404.
      const wrong = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        `sha512:${'0'.repeat(128)}`,
        other.manifestBytes,
        other.manifestMediaType,
      );
      expectOci(wrong, 400, 'DIGEST_INVALID');
      const unknown = await rawGetManifest(
        layout.repoName,
        admin,
        layout.image,
        `sha512:${'0'.repeat(128)}`,
      );
      expect(unknown.status).toBe(404);
    },
  );

  test(
    'R15: DELETE by digest removes the manifest and its tags, DELETE by tag only the tag, and ' +
      'both need MANAGE (RPS-1216)',
    { tag: ['@settings', '@auth'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'delete', { privateRepo: false });
      const admin = adminCredential();
      const a = await rawPushImage(layout, admin, 'tag-a', 'r15-a');
      expect(a.manifestRes.status).toBe(201);
      const aAgain = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag-a2',
        a.built.manifestBytes,
        a.built.manifestMediaType,
      );
      expect(aAgain.status, 'a second tag on the same manifest').toBe(201);
      const b = await rawPushImage(layout, admin, 'tag-b', 'r15-b');
      expect(b.manifestRes.status).toBe(201);
      const sha512 = sha512Digest(a.built.manifestBytes);

      // Deleting needs MANAGE: neither a deploy token (read-write or read-only) nor anonymous.
      for (const readOnly of [false, true]) {
        const token = await seeder.createToken(layout.repoName, { readOnly });
        const cred = {
          transport: 'basic' as const,
          username: token.username,
          password: token.token,
          kind: 'token' as const,
        };
        const byToken = await rawDeleteManifest(
          layout.repoName,
          cred,
          layout.image,
          a.built.manifestDigest,
        );
        expect(byToken.hop, 'issuance is not scope-checked: refused at the request hop').toBe(
          'request',
        );
        expectOci(byToken, 401, 'UNAUTHORIZED');
        expect(byToken.wwwAuthenticate, 'and challenged').toMatch(/^Bearer realm=/);
      }
      const anonymous = await rawToken({}, deleteScope(layout.repoName, layout.image));
      expect(anonymous.status, 'no anonymous token for a delete scope, even on a public repo').toBe(
        401,
      );
      const stillThere = await rawGetManifest(
        layout.repoName,
        admin,
        layout.image,
        a.built.manifestDigest,
      );
      expect(stillThere.status, 'nothing was deleted by the refused requests').toBe(200);

      // RPS-1434: a token is only as good as the scope it was asked for, an administrator's too.
      for (const scope of [
        pullScope(layout.repoName, layout.image),
        pushScope(layout.repoName, layout.image),
      ]) {
        const tooLittle = await rawDeleteManifest(
          layout.repoName,
          admin,
          layout.image,
          a.built.manifestDigest,
          scope,
        );
        expect(tooLittle.hop, `issuance is not scope-checked (${scope})`).toBe('request');
        expectOci(tooLittle, 401, 'UNAUTHORIZED');
        expect(tooLittle.wwwAuthenticate, 'the challenge names the scope to ask for').toContain(
          `scope="repository:${repoPath(layout.repoName)}/${layout.image}:delete"`,
        );
        expect(tooLittle.wwwAuthenticate).toContain('error="insufficient_scope"');
      }
      expect(
        (await rawGetManifest(layout.repoName, admin, layout.image, a.built.manifestDigest)).status,
        'nothing was deleted by the token asked for less than delete',
      ).toBe(200);

      // By tag: the pointer only; the manifest stays pullable by digest and by its other tag.
      const byTag = await rawDeleteManifest(layout.repoName, admin, layout.image, 'tag-a2');
      expect(byTag.status, 'DELETE by tag').toBe(202);
      expect(byTag.body, 'with no body').toHaveLength(0);
      expectOci(
        await rawGetManifest(layout.repoName, admin, layout.image, 'tag-a2'),
        404,
        'MANIFEST_UNKNOWN',
      );
      expect((await rawGetManifest(layout.repoName, admin, layout.image, 'tag-a')).status).toBe(
        200,
      );
      expect(
        (await rawGetManifest(layout.repoName, admin, layout.image, a.built.manifestDigest)).status,
        'the manifest stays pullable by its digest',
      ).toBe(200);
      expectOci(
        await rawDeleteManifest(layout.repoName, admin, layout.image, 'tag-a2'),
        404,
        'MANIFEST_UNKNOWN',
      );

      // By digest (sha512 here; sha256 is the same route): the manifest and its remaining tag go.
      const byDigest = await rawDeleteManifest(layout.repoName, admin, layout.image, sha512);
      expect(byDigest.status, 'DELETE by sha512 digest').toBe(202);
      for (const ref of [a.built.manifestDigest, sha512, 'tag-a']) {
        const res = await rawGetManifest(layout.repoName, admin, layout.image, ref);
        expectOci(res, 404, 'MANIFEST_UNKNOWN');
      }
      expect(
        (await rawHeadManifest(layout.repoName, admin, layout.image, a.built.manifestDigest))
          .status,
      ).toBe(404);
      expectOci(
        await rawDeleteManifest(layout.repoName, admin, layout.image, a.built.manifestDigest),
        404,
        'MANIFEST_UNKNOWN',
      );

      // The other manifest is untouched, and a deleted one can be pushed again.
      const bAfter = await rawGetManifest(layout.repoName, admin, layout.image, 'tag-b');
      expect(bAfter.body.equals(b.built.manifestBytes)).toBe(true);
      const again = await rawPutManifest(
        layout.repoName,
        admin,
        layout.image,
        'tag-a',
        a.built.manifestBytes,
        a.built.manifestMediaType,
      );
      expect(again.status, 'the deleted manifest is pushed again').toBe(201);
      expect(
        (await rawGetManifest(layout.repoName, admin, layout.image, a.built.manifestDigest)).status,
      ).toBe(200);

      // Malformed references and unknown names.
      expectOci(
        await rawDeleteManifest(layout.repoName, admin, layout.image, 'sha256:short'),
        400,
        'DIGEST_INVALID',
      );
      expectOci(
        await rawDeleteManifest(layout.repoName, admin, layout.image, '-not-a-tag'),
        400,
        'TAG_INVALID',
      );
      expectOci(
        await rawDeleteManifest(layout.repoName, admin, 'no-such-image', 'tag-a'),
        404,
        'NAME_UNKNOWN',
      );
    },
  );

  test(
    'a PUBLIC repo still requires real credentials to write (refused at the token hop, with the ' +
      "token endpoint's own OCI body and Basic challenge)",
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
      // RPS-1435: the token endpoint's own failure carries an OCI envelope (it was empty, and the
      // client printed "unauthorized: "), with a Basic challenge instead of an operation-hop 401's
      // Bearer one.
      expect(ociErrorOf(upload.body)?.code, "the token hop's 401 has an OCI body").toBe(
        'UNAUTHORIZED',
      );
    },
  );
});
