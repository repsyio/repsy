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
 * The parts of the Registry HTTP API V2 / OCI distribution spec that the Docker server does NOT
 * implement (and, since RPS-1489, `tags/list`, which it does), pinned at their CURRENT behaviour with raw HTTP (RPS-1478 part A; the second-client
 * family, skopeo/regctl/oras, follows in parts B and C). Nothing here is a `test.fail`: these are
 * documented gaps, not bugs with a ticket, and the backlog decision was to pin them until a backend
 * story implements them (the proposed story, with this file's evidence, is in the PR description).
 * A story that implements one of them flips that one test, on purpose.
 *
 * Every status below was probed live before it was pinned (README.md, "Docker registry API pins"):
 *
 *  - `GET /v2/<repo>/<image>/tags/list` IS served since RPS-1489 (RA1: the listing, `n`/`last` pagination
 *    with its `Link` header, the pull permission check; RA1b: the Helm handler's bare name against
 *    Docker's repository-qualified one), and `crane ls`, `skopeo list-tags` and `regctl tag ls` list a
 *    Repsy Docker repo (`client-tag-list.spec.ts`).
 *  - `GET /v2/_catalog` and `GET /v2/<repo>/<image>/referrers/<digest>`: no handler is registered, so the
 *    router's catch-all answers `404` + `NAME_UNKNOWN` / `unknownPath` for EVERY caller (admin, deploy
 *    token, read-only token, anonymous): before authentication, so not even a `401` challenge. The token
 *    hop still answers `200` (the endpoint never looks at the scope). Consequence for real clients:
 *    `crane catalog` and `regctl repo ls` fail against a Repsy Docker repo (they cannot enumerate its
 *    images; the panel is the only place an image list is available), and OCI-1.1-aware clients
 *    (`oras discover`, `regctl artifact tree`, `cosign tree`) must take their spec-mandated fallback, the
 *    referrers TAG schema (`sha256-<hex>` tag holding an image index), which the server serves like any
 *    other tag (RA4).
 *  - `POST /v2/<repo>/<image>/blobs/uploads/?mount=<digest>[&from=<repo>]`: `mount`/`from` are
 *    ignored. The server answers the spec-allowed fallback `202` + a fresh upload session (`Location`,
 *    `Docker-Upload-UUID`) whether the blob exists or not, in this repo or in another, and never the
 *    `201` a real mount would be, so a client that asked for a mount uploads the bytes after all
 *    (`skopeo copy`/`regctl image copy` between two repos of ONE Repsy do that transparently; the
 *    upload then completes normally). Nothing crosses a repo boundary: the blob is not visible in the
 *    destination repo until the `PUT` finishes (RA5). Within one repo the blob store is shared by all
 *    images (RA6), so a client's own `HEAD` before the `POST` (what `crane` does) already finds it.
 *
 * Real clients hit these routes too, so their consequences are noted next to each pin; the real
 * `skopeo`/`regctl` runs that prove them arrive with parts B and C.
 */
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { isolatedWorkDir } from '../../src/clients/exec.js';
import {
  adminCredential,
  ociErrorOf,
  rawCatalog,
  rawGetAnonymous,
  rawHeadBlob,
  rawMountUpload,
  rawPutManifest,
  rawFollowNextLink,
  rawGetManifest,
  rawReferrers,
  rawTagsList,
  rawToken,
  rawUploadBlob,
  type RawResponse,
} from '../../src/clients/docker-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';
import { repoPath } from '../../src/repo-url.js';

interface Layout {
  repoName: string;
  image: string;
}

type Built = Awaited<ReturnType<typeof buildImage>>;

async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
  return { repoName: repo.name, image: `e2e-${seeder.runId}-${label}` };
}

async function tokenCredential(
  seeder: Seeder,
  repoName: string,
  readOnly: boolean,
): Promise<MaterializedCredential> {
  const seeded = await seeder.createToken(repoName, { readOnly });
  return {
    transport: 'basic',
    username: seeded.username,
    password: seeded.token,
    kind: 'token',
  };
}

/** A fresh OCI layout in its own isolated work directory (never `crane`, never `docker build`). */
async function freshImage(label: string, marker: string): Promise<Built> {
  const { work } = await isolatedWorkDir(label);
  return buildImage({ dir: path.join(work, 'image'), marker });
}

/** A full raw publish under `tag` (both blobs, then the manifest), as `admin`. */
async function pushImage(layout: Layout, tag: string, marker: string): Promise<Built> {
  const admin = adminCredential();
  const built = await freshImage(`docker-api-${layout.repoName}-${tag}`, marker);
  for (const [bytes, digest] of [
    [built.configBytes, built.configDigest],
    [built.layerBytes, built.layerDigest],
  ] as const) {
    const res = await rawUploadBlob(layout.repoName, admin, layout.image, bytes, digest);
    expect(res.status, `blob ${digest} upload`).toBe(201);
  }
  const manifest = await rawPutManifest(
    layout.repoName,
    admin,
    layout.image,
    tag,
    built.manifestBytes,
    built.manifestMediaType,
  );
  expect(manifest.status, 'manifest push').toBe(201);
  return built;
}

/** The uniform "no such route" answer: `404`, the OCI envelope `NAME_UNKNOWN` / `unknownPath`, and NO
 *  authentication challenge (the router gives up before the auth pre-processor runs). */
function expectNoRoute(res: RawResponse & { wwwAuthenticate?: string }, what: string): void {
  expect(res.status, `${what} answered ${res.status}: ${res.body.toString('utf8')}`).toBe(404);
  const oci = ociErrorOf(res.body);
  expect(oci?.code, `${what}: the OCI error code`).toBe('NAME_UNKNOWN');
  expect(oci?.message, `${what}: the message`).toBe('unknownPath');
  expect(oci?.detail, `${what}: the detail`).toBe('unknownPath');
  expect(res.wwwAuthenticate, `${what}: no auth challenge on an unknown route`).toBeUndefined();
}

test.describe('docker registry API gaps (raw HTTP, pinned at current behaviour)', () => {
  test(
    'RA1: tags/list lists the tags of an image in lexical order, with n/last pagination and a Link header (RPS-1489)',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'tagslist');
      await pushImage(layout, 'v2', 'tagslist-2');
      await pushImage(layout, 'v10', 'tagslist-10');
      await pushImage(layout, 'v1', 'tagslist');
      const admin = adminCredential();
      const rw = await tokenCredential(seeder, layout.repoName, false);
      const ro = await tokenCredential(seeder, layout.repoName, true);
      const qualified = `${repoPath(layout.repoName)}/${layout.image}`;

      // Every credential that may pull may list: admin, a read-write and a read-only deploy token.
      for (const [who, credential] of [
        ['admin', admin],
        ['a read-write deploy token', rw],
        ['a read-only deploy token', ro],
      ] as const) {
        const res = await rawTagsList(layout.repoName, credential, layout.image);
        expect(res.hop, `${who}: the token hop itself is fine`).toBe('request');
        expect(res.status, `${who} tags/list: ${res.body.toString('utf8')}`).toBe(200);
        expect(res.contentType, `${who}: the content type`).toMatch(/^application\/json/);
        expect(res.link, `${who}: one page, no Link`).toBeUndefined();
        // The repository-qualified name, and the tags in lexical (byte) order: `v10` before `v2`.
        expect(JSON.parse(res.body.toString('utf8')), `${who}: the listing`).toEqual({
          name: qualified,
          tags: ['v1', 'v10', 'v2'],
        });
      }

      // Pagination: `n` limits the page, `last` is where it starts (exclusive), and the Link of a page
      // that has more names `n` and the last tag of the page.
      const first = await rawTagsList(layout.repoName, admin, layout.image, 'n=1');
      expect(JSON.parse(first.body.toString('utf8')).tags).toEqual(['v1']);
      expect(first.link, 'a page with more after it links to the next one').toBe(
        `</v2/${qualified}/tags/list?n=1&last=v1>; rel="next"`,
      );

      // A client that follows the Link visits every tag once and stops when the Link is gone.
      const seen: string[] = ['v1'];
      let link = first.link;
      while (link) {
        const page = await rawFollowNextLink(layout.repoName, admin, layout.image, link);
        expect(page.status, `following ${link}`).toBe(200);
        seen.push(...JSON.parse(page.body.toString('utf8')).tags);
        link = page.link;
      }
      expect(seen, 'walking n=1 pages').toEqual(['v1', 'v10', 'v2']);

      const rest = await rawTagsList(layout.repoName, admin, layout.image, 'n=5&last=v1');
      expect(JSON.parse(rest.body.toString('utf8')).tags, 'last is exclusive').toEqual([
        'v10',
        'v2',
      ]);
      expect(rest.link, 'the rest fits the page: no Link').toBeUndefined();
      const beyond = await rawTagsList(layout.repoName, admin, layout.image, 'last=zzz');
      expect(beyond.status, 'a last beyond the end is not an error').toBe(200);
      expect(JSON.parse(beyond.body.toString('utf8')).tags).toEqual([]);
      const zero = await rawTagsList(layout.repoName, admin, layout.image, 'n=0');
      expect(zero.status).toBe(200);
      expect(JSON.parse(zero.body.toString('utf8')).tags, 'n=0').toEqual([]);
      expect(zero.link, 'n=0: no Link').toBeUndefined();

      // An `n` that is no number is refused with the distribution error code.
      for (const n of ['abc', '-1']) {
        const bad = await rawTagsList(layout.repoName, admin, layout.image, `n=${n}`);
        expect(bad.status, `n=${n}: ${bad.body.toString('utf8')}`).toBe(400);
        expect(ociErrorOf(bad.body)?.code, `n=${n}: the OCI error code`).toBe(
          'PAGINATION_NUMBER_INVALID',
        );
      }

      // An image that was never pushed is NAME_UNKNOWN / imageNotFound (after authentication, so a
      // caller who may not read the repo learns nothing); a repo that does not exist is the router's
      // uniform 404.
      const noImage = await rawTagsList(layout.repoName, admin, `${layout.image}-none`);
      expect(noImage.status, 'tags/list of an unknown image').toBe(404);
      expect(ociErrorOf(noImage.body)?.code).toBe('NAME_UNKNOWN');
      expect(ociErrorOf(noImage.body)?.detail).toBe('imageNotFound');
      const noRepo = await rawTagsList(`${layout.repoName}-none`, admin, layout.image);
      expectNoRoute(noRepo, 'tags/list of an unknown repo');

      // Anonymous, on a private repo: a 401 with the Bearer challenge for the pull scope (a known
      // route now), for a real image and a made-up one alike, so it does not tell them apart.
      for (const image of [layout.image, `${layout.image}-none`]) {
        const anon = await rawGetAnonymous(`/${repoPath(layout.repoName)}/${image}/tags/list`);
        expect(anon.status, `anonymous tags/list of ${image}`).toBe(401);
        expect(anon.wwwAuthenticate ?? '', 'the challenge').toMatch(/^Bearer /);
        expect(anon.wwwAuthenticate ?? '', 'names the pull scope').toContain(
          `scope="repository:${repoPath(layout.repoName)}/${image}:pull"`,
        );
      }
      // HEAD is not part of the spec for this route: the router has no handler for it.
      const anonHead = await rawGetAnonymous(
        `/${repoPath(layout.repoName)}/${layout.image}/tags/list`,
        'HEAD',
      );
      expect(anonHead.status, 'anonymous HEAD tags/list').toBe(404);
    },
  );

  test('RA1b: control: the Helm OCI handler answers tags/list on the same port with the repository-qualified name, like the Docker one (RPS-1557)', async ({
    seeder,
  }) => {
    const helmRepo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const dockerLayout = await newRepo(seeder, 'tagslist-helm');
    await pushImage(dockerLayout, 'v1', 'tagslist-helm');
    const admin = adminCredential();

    const helm = await rawTagsList(helmRepo.name, admin, 'somechart');
    expect(helm.status, 'Helm OCI tags/list is served (RPS-1219)').toBe(200);
    const body = JSON.parse(helm.body.toString('utf8')) as { name?: string; tags?: unknown };
    expect(body.name, 'the repository-qualified chart name, as the spec wants (RPS-1557)').toBe(
      `${repoPath(helmRepo.name)}/somechart`,
    );
    expect(Array.isArray(body.tags), 'a tags array').toBe(true);

    const docker = await rawTagsList(dockerLayout.repoName, admin, dockerLayout.image);
    expect(docker.status, 'a Docker repo tags/list').toBe(200);
    expect(JSON.parse(docker.body.toString('utf8')).name).toBe(
      `${repoPath(dockerLayout.repoName)}/${dockerLayout.image}`,
    );
  });

  test('RA2: _catalog is no route (crane catalog, regctl repo ls cannot enumerate the registry)', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, 'catalog');
    await pushImage(layout, 'v1', 'catalog');
    const admin = adminCredential();

    const token = await rawToken(admin, 'registry:catalog:*');
    expect(token.status, 'the token endpoint issues a catalog-scope token, unread').toBe(200);

    const res = await rawCatalog(admin);
    expect(res.hop).toBe('request');
    expectNoRoute(res, 'GET /v2/_catalog');
    const paged = await rawCatalog(admin, 'n=5');
    expectNoRoute(paged, 'GET /v2/_catalog?n=5');

    const anon = await rawGetAnonymous('/_catalog');
    expectNoRoute(anon, 'anonymous GET /v2/_catalog');

    // A per-repo catalog is no route either.
    const perRepo = await rawGetAnonymous(`/${repoPath(layout.repoName)}/_catalog`);
    expectNoRoute(perRepo, `GET /v2/${repoPath(layout.repoName)}/_catalog`);
  });

  test(
    'RA3: the referrers API is no route (oras discover, regctl artifact tree take the tag-schema fallback)',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'referrers');
      const built = await pushImage(layout, 'v1', 'referrers');
      const admin = adminCredential();
      const ro = await tokenCredential(seeder, layout.repoName, true);

      const res = await rawReferrers(layout.repoName, admin, layout.image, built.manifestDigest);
      expect(res.hop).toBe('request');
      expectNoRoute(res, 'referrers of a stored manifest');

      const filtered = await rawReferrers(
        layout.repoName,
        admin,
        layout.image,
        built.manifestDigest,
        'application/vnd.e2e.test',
      );
      expectNoRoute(filtered, 'referrers?artifactType=');

      const readOnly = await rawReferrers(layout.repoName, ro, layout.image, built.manifestDigest);
      expectNoRoute(readOnly, 'referrers with a read-only token');

      // A digest the repo does not hold is the same 404: the spec says an empty index, 200.
      const unknown = await rawReferrers(
        layout.repoName,
        admin,
        layout.image,
        `sha256:${'0'.repeat(64)}`,
      );
      expectNoRoute(unknown, 'referrers of an unknown digest');

      const anon = await rawGetAnonymous(
        `/${repoPath(layout.repoName)}/${layout.image}/referrers/${built.manifestDigest}`,
      );
      expectNoRoute(anon, 'anonymous referrers');
    },
  );

  test('RA4: the referrers tag-schema fallback works as an ordinary tag (what oras/regctl need after RA3)', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, 'refstag');
    const built = await pushImage(layout, 'v1', 'refstag');
    const admin = adminCredential();
    const fallbackTag = built.manifestDigest.replace(':', '-');

    // A client that got RA3's 404 reads `sha256-<hex>`: absent (a plain tag miss, not "no route").
    const missing = await rawGetManifest(layout.repoName, admin, layout.image, fallbackTag);
    expect(missing.status, 'the fallback tag is absent until a referrer is attached').toBe(404);
    const missingOci = ociErrorOf(missing.body);
    expect(missingOci?.code).toBe('MANIFEST_UNKNOWN');
    expect(missingOci?.detail).toBe('tagNotFound');

    // ... then writes the referrers index under it: a plain OCI image index, accepted like any tag.
    const index = Buffer.from(
      JSON.stringify({
        schemaVersion: 2,
        mediaType: 'application/vnd.oci.image.index.v1+json',
        manifests: [],
      }),
      'utf8',
    );
    const put = await rawPutManifest(
      layout.repoName,
      admin,
      layout.image,
      fallbackTag,
      index,
      'application/vnd.oci.image.index.v1+json',
    );
    expect(put.status, 'PUT of the referrers index under the fallback tag').toBe(201);

    const read = await rawGetManifest(layout.repoName, admin, layout.image, fallbackTag);
    expect(read.status, 'the fallback tag reads back').toBe(200);
    expect(read.contentType).toBe('application/vnd.oci.image.index.v1+json');
    expect(read.body.equals(index), 'the index bytes come back unchanged').toBe(true);
  });

  test(
    'RA5: mount= is not honoured: 202 + a fresh upload session (never 201), the blob does not cross repos, and the upload completes',
    { tag: ['@auth'] },
    async ({ seeder }) => {
      const source = await newRepo(seeder, 'mount-src');
      const built = await pushImage(source, 'v1', 'mount-src');
      const destination = await newRepo(seeder, 'mount-dst');
      const rw = await tokenCredential(seeder, destination.repoName, false);
      const ro = await tokenCredential(seeder, destination.repoName, true);
      const digest = built.layerDigest;

      // The blob exists in the source, not in the destination.
      const admin = adminCredential();
      expect((await rawHeadBlob(source.repoName, admin, source.image, digest)).status).toBe(200);
      const before = await rawHeadBlob(destination.repoName, rw, destination.image, digest);
      expect(before.status, 'not in the destination repo yet').toBe(404);

      // A cross-repo mount request, exactly what `docker push`/`skopeo copy`/`regctl image copy`
      // send when both ends are one registry.
      const mount = await rawMountUpload(destination.repoName, rw, destination.image, {
        digest,
        fromRepo: source.repoName,
        fromImage: source.image,
      });
      expect(mount.status, 'the spec fallback, never a 201 mount').toBe(202);
      expect(mount.hop).toBe('request');
      expect(mount.location, 'an upload session Location').toContain(
        `/v2/${repoPath(destination.repoName)}/${destination.image}/blobs/uploads/`,
      );
      expect(mount.uploadUuid, 'Docker-Upload-UUID names the session').toBeTruthy();
      expect(mount.location, 'the session id is the UUID').toContain(mount.uploadUuid ?? '?');

      const afterMount = await rawHeadBlob(destination.repoName, rw, destination.image, digest);
      expect(afterMount.status, 'nothing was mounted: still absent from the destination').toBe(404);

      // The client then uploads the bytes after all, and that completes normally.
      const uploaded = await rawMountUpload(
        destination.repoName,
        rw,
        destination.image,
        { digest, fromRepo: source.repoName, fromImage: source.image },
        built.layerBytes,
      );
      expect(uploaded.status).toBe(202);
      expect(uploaded.finalizeStatus, 'PUT ?digest= after a refused mount').toBe(201);
      expect(uploaded.finalizeDigest).toBe(digest);
      const after = await rawHeadBlob(destination.repoName, rw, destination.image, digest);
      expect(after.status, 'present after the upload').toBe(200);

      // The same 202 for the other mount shapes: no `from`, a digest nobody holds, a malformed one.
      const noFrom = await rawMountUpload(destination.repoName, rw, destination.image, { digest });
      expect(noFrom.status, 'mount without from').toBe(202);
      const unknown = await rawMountUpload(destination.repoName, rw, destination.image, {
        digest: `sha256:${'0'.repeat(64)}`,
        fromRepo: source.repoName,
      });
      expect(unknown.status, 'mount of a digest that exists nowhere').toBe(202);
      const malformed = await rawMountUpload(destination.repoName, rw, destination.image, {
        digest: 'not-a-digest',
        fromRepo: source.repoName,
      });
      expect(malformed.status, 'mount of a malformed digest').toBe(202);

      // The write permission is still checked for a mount: a read-only token is refused at the
      // request hop (its token hop answers 200, R3).
      const refused = await rawMountUpload(destination.repoName, ro, destination.image, {
        digest,
        fromRepo: source.repoName,
      });
      expect(refused.hop, 'the refusal comes from the request, not the token endpoint').toBe(
        'request',
      );
      expect(refused.status, 'read-only token cannot start an upload, mount or not').toBe(401);
    },
  );

  test('RA6: the blob store is per repo, not per image, so a mount inside one repo is never needed', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, 'mount-same');
    const built = await pushImage(layout, 'v1', 'mount-same');
    const admin = adminCredential();
    const otherImage = `${layout.image}-other`;

    // A blob pushed for one image is already there for another image of the same repo, so a client's
    // HEAD-before-upload finds it and never asks for a mount.
    const head = await rawHeadBlob(layout.repoName, admin, otherImage, built.layerDigest);
    expect(head.status, 'a blob of image A is visible under image B of the same repo').toBe(200);

    // And an explicit same-repo mount request is still only the fallback session.
    const mount = await rawMountUpload(layout.repoName, admin, otherImage, {
      digest: built.layerDigest,
      fromRepo: layout.repoName,
      fromImage: layout.image,
    });
    expect(mount.status, 'same-repo mount is the 202 fallback too').toBe(202);
  });
});
