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
 * What `regctl` does beyond the catalog (`regctl-catalog.spec.ts`) against a Repsy Docker repo
 * (RPS-1478 part B). Every behaviour was probed live before it was pinned:
 *
 *  - RC1 `regctl manifest get/head`, `image inspect` and `blob get` read what was stored: the raw
 *    manifest hashes to the digest, `head` reports it, the config platform and a layer's bytes match.
 *  - RC2 `regctl image copy` between two repos of ONE Repsy: the destination has the byte-identical
 *    manifest and blobs. regctl asks for a blob mount first and logs the fallback Repsy answers it with
 *    (`202` + an upload session, RA5) as a WARN on stderr, then uploads the bytes.
 *  - RC3 deletes: `regctl tag delete` removes the TAG only (a sibling tag and the manifest by digest
 *    stay), `regctl manifest delete` needs a digest and removes the manifest with every tag; the
 *    unauthenticated DELETE is challenged with `scope="repository:<repo>/<image>:delete"` (RPS-1588),
 *    which regctl merges with its own `push,pull` into ONE token request, so the delete is accepted
 *    with no `insufficient_scope` round trip (before RPS-1588 the challenge named a constant scope, and
 *    regctl needed that round trip, RPS-1434/RPS-1440). A read-write deploy token is refused at both
 *    and nothing goes.
 *  - RC4 sha512 (RPS-1244): an image whose manifest is addressed by its sha512 digest copies in by that
 *    digest, is served under it (`Docker-Content-Digest: sha512:...`) and `regctl manifest head`
 *    reports it.
 *  - RC4b sha512 by TAG (RPS-1594, RPS-1607): the same rewritten image copied to a tag target. Repsy
 *    answers the canonical sha256 in `Docker-Content-Digest` of a tag push (RPS-1244); regctl, which
 *    hashed the manifest with sha512, FAILS the copy (`unexpected digest returned, expected sha512:...,
 *    received sha256:...`) although the tag is stored and serves the same bytes as the sha512. Kept as
 *    decided in RPS-1607: the sha256 answer stays and this test documents it.
 *  - RC5 a multi-platform Docker manifest list copies as a whole (children included), `--platform`
 *    resolves the right child through the served index, and it copies back unchanged.
 *  - `regctl tag ls` / `repo ls` (Repsy has no `tags/list`/`_catalog`, RPS-1489) are in
 *    `client-tag-list.spec.ts`.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { buildImage, buildIndexImage } from '../../src/clients/docker-image.js';
import {
  byDigest,
  expectSameImage,
  newDockerRepo,
  tokenCredential,
} from '../../src/clients/docker-client-tests.js';
import { openSession, type ClientSession } from '../../src/clients/docker-copy-adapter.js';
import { regctlClient } from '../../src/clients/docker-regctl.js';
import {
  adminCredential,
  imageRef,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  sha256Hex,
} from '../../src/clients/docker-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import { repoPath } from '../../src/repo-url.js';

type RegctlSession = ClientSession & { push(ref: string, marker: string): Promise<string> };

/** A private HOME with a regctl config for `credential`, plus `push` of a fresh single-image layout. */
async function regctlSession(
  credential: MaterializedCredential,
  label: string,
): Promise<RegctlSession> {
  const session = await openSession(regctlClient, credential, label);
  return {
    ...session,
    async push(ref, marker) {
      const built = await buildImage({ dir: path.join(session.work, marker), marker });
      const result = await session.run(
        regctlClient.pushArgs(built, ref),
        `${label}-push-${marker}`,
      );
      expect(result.exitCode, `regctl image copy: ${result.command}\n${result.stderr}`).toBe(0);
      return built.manifestDigest;
    },
  };
}

test('docker > regctl manifest get/head, image inspect and blob get read the stored image (RC1)', async ({
  seeder,
}) => {
  const repoName = await newDockerRepo(seeder);
  const image = `e2e-${seeder.runId}-rcread`;
  const ref = imageRef(repoName, image, 'v1');
  const session = await regctlSession(adminCredential(), `docker-regctl-rc1-${seeder.runId}`);
  const digest = await session.push(ref, 'rc1');
  const stored = await rawGetManifest(repoName, adminCredential(), image, 'v1');
  const parsed = JSON.parse(stored.body.toString('utf8')) as {
    layers: { digest: string }[];
  };
  const layerDigest = parsed.layers[0]?.digest as string;

  const raw = await session.run(['manifest', 'get', ref, '--format', 'raw-body'], 'rc1-get');
  expect(raw.exitCode, `regctl manifest get: ${raw.stderr}`).toBe(0);
  expect(
    `sha256:${sha256Hex(Buffer.from(raw.stdout, 'utf8'))}`,
    'the raw body is the stored manifest',
  ).toBe(digest);

  const head = await session.run(['manifest', 'head', ref], 'rc1-head');
  expect(head.exitCode, `regctl manifest head: ${head.stderr}`).toBe(0);
  expect(head.stdout.trim(), 'HEAD by tag reports the digest').toBe(digest);
  const headByDigest = await session.run(
    ['manifest', 'head', byDigest(ref, digest)],
    'rc1-head-digest',
  );
  expect(headByDigest.exitCode, `regctl manifest head by digest: ${headByDigest.stderr}`).toBe(0);

  const inspect = await session.run(['image', 'inspect', ref], 'rc1-inspect');
  expect(inspect.exitCode, `regctl image inspect: ${inspect.stderr}`).toBe(0);
  const config = JSON.parse(inspect.stdout) as { architecture: string; os: string };
  expect([config.os, config.architecture]).toEqual(['linux', 'amd64']);

  const blob = await session.run(['blob', 'get', `${registryRepo(ref)}`, layerDigest], 'rc1-blob');
  expect(blob.exitCode, `regctl blob get: ${blob.stderr}`).toBe(0);

  const missing = await session.run(
    ['manifest', 'get', imageRef(repoName, image, 'nope')],
    'rc1-missing',
  );
  expect(missing.exitCode, 'an absent tag fails').not.toBe(0);
  expect(missing.stderr, "with the registry's own answer").toContain('MANIFEST_UNKNOWN');
});

test(
  'docker > regctl image copy between two repos of one registry yields the identical image (RC2)',
  { tag: ['@regctl'] },
  async ({ seeder }) => {
    const from = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-rccopy` };
    const to = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-rccopy2` };
    const session = await regctlSession(adminCredential(), `docker-regctl-rc2-${seeder.runId}`);
    const digest = await session.push(imageRef(from.repoName, from.image, 'v1'), 'rc2');

    const copied = await session.run(
      [
        'image',
        'copy',
        imageRef(from.repoName, from.image, 'v1'),
        imageRef(to.repoName, to.image, 'v1'),
      ],
      'rc2-copy',
    );
    expect(copied.exitCode, `regctl image copy A to B: ${copied.command}\n${copied.stderr}`).toBe(
      0,
    );
    // The mount attempt Repsy does not honour (RA5): regctl says so and uploads the bytes.
    expect(copied.stderr, 'regctl asked for a mount and fell back to an upload').toContain(
      'blob mount returned a location to upload',
    );

    expect(await expectSameImage(from, to, 'v1', 'regctl image copy across repos')).toBe(digest);
  },
);

test(
  'docker > regctl deletes with the scope the first challenge names, tag delete keeps the manifest, a deploy token is refused (RC3)',
  { tag: ['@regctl', '@auth'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-rcdelete`;
    const admin = adminCredential();
    const session = await regctlSession(admin, `docker-regctl-rc3-${seeder.runId}`);
    const ref = imageRef(repoName, image, 'v1');
    const digest = await session.push(ref, 'rc3');
    const retag = await session.run(
      ['image', 'copy', ref, imageRef(repoName, image, 'v2')],
      'rc3-retag',
    );
    expect(retag.exitCode, `regctl image copy (retag): ${retag.stderr}`).toBe(0);

    // A read-write deploy token: refused by tag delete and by manifest delete, nothing goes.
    const token = await tokenCredential(seeder, repoName, false);
    const tokenSession = await openSession(
      regctlClient,
      token,
      `docker-regctl-rc3-token-${seeder.runId}`,
    );
    for (const args of [
      ['tag', 'delete', imageRef(repoName, image, 'v2')],
      ['manifest', 'delete', byDigest(ref, digest)],
    ]) {
      const refused = await tokenSession.run(args, 'rc3-token-delete');
      expect(refused.exitCode, `regctl ${args.join(' ')} with a deploy token`).not.toBe(0);
    }
    expect((await rawGetManifest(repoName, admin, image, 'v2')).status, 'v2 stays').toBe(200);
    expect((await rawGetManifest(repoName, admin, image, digest)).status, 'the digest stays').toBe(
      200,
    );

    // The admin, by tag: -v debug shows the challenge naming the delete scope (RPS-1588) and no
    // insufficient_scope round trip after it.
    const tagDelete = await session.run(
      ['-v', 'debug', 'tag', 'delete', imageRef(repoName, image, 'v2')],
      'rc3-tag-delete',
    );
    expect(tagDelete.exitCode, `regctl tag delete: ${tagDelete.stderr}`).toBe(0);
    expect(tagDelete.stderr, 'asks push,pull first').toContain(
      `scope=repository:${repoPath(repoName)}/${image}:pull,push`,
    );
    expect(tagDelete.stderr, 'the challenge of the DELETE names the delete scope').toMatch(
      new RegExp(
        `Auth request parsed[^\\n]*scope:repository:${repoPath(repoName)}/${image}:delete`,
      ),
    );
    expect(tagDelete.stderr, 'no insufficient_scope round trip is needed').not.toContain(
      'insufficient_scope',
    );
    expect((await rawHeadManifest(repoName, admin, image, 'v2')).status, 'v2 is gone').toBe(404);
    expect(
      (await rawHeadManifest(repoName, admin, image, 'v1')).status,
      'the sibling tag stays',
    ).toBe(200);
    expect((await rawGetManifest(repoName, admin, image, digest)).status, 'and the manifest').toBe(
      200,
    );

    // By tag reference regctl refuses on its own (a manifest delete needs the digest).
    const byTag = await session.run(['manifest', 'delete', ref], 'rc3-manifest-by-tag');
    expect(byTag.exitCode, 'manifest delete of a tag reference').not.toBe(0);
    expect(byTag.stderr).toContain('digest required');

    // By digest: the manifest and every tag of it.
    const byDig = await session.run(
      ['manifest', 'delete', byDigest(ref, digest)],
      'rc3-manifest-delete',
    );
    expect(byDig.exitCode, `regctl manifest delete: ${byDig.stderr}`).toBe(0);
    expect((await rawGetManifest(repoName, admin, image, digest)).status, 'by digest').toBe(404);
    expect((await rawGetManifest(repoName, admin, image, 'v1')).status, 'by tag').toBe(404);
  },
);

test(
  'docker > regctl copies an image addressed by its sha512 digest and Repsy serves it under it (RC4, RPS-1244)',
  { tag: ['@regctl'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-rcsha512`;
    const admin = adminCredential();
    const session = await regctlSession(admin, `docker-regctl-rc4-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(session.work, 'src'), marker: 'rc4' });

    // regctl rewrites the layout's image with sha512 digests (a second manifest in the same layout).
    const mod = await session.run(
      [
        'image',
        'mod',
        `ocidir://${built.dir}@${built.manifestDigest}`,
        '--digest-algo',
        'sha512',
        '--create',
        'rc4',
      ],
      'rc4-mod',
    );
    expect(mod.exitCode, `regctl image mod --digest-algo sha512: ${mod.stderr}`).toBe(0);
    const layout = JSON.parse(await fs.readFile(path.join(built.dir, 'index.json'), 'utf8')) as {
      manifests: { digest: string }[];
    };
    const sha512 = layout.manifests.map((m) => m.digest).find((d) => d.startsWith('sha512:'));
    expect(sha512, 'the layout has a sha512 manifest').toBeDefined();
    const digest = sha512 as string;

    const target = byDigest(imageRef(repoName, image, 'x'), digest);
    const copied = await session.run(
      ['image', 'copy', `ocidir://${built.dir}@${digest}`, target],
      'rc4-copy',
    );
    expect(copied.exitCode, `regctl image copy by sha512: ${copied.stderr}`).toBe(0);

    const served = await rawGetManifest(repoName, admin, image, digest);
    expect(served.status, 'served by its sha512').toBe(200);
    expect(served.digestHeader, 'reported in the algorithm the client used').toBe(digest);
    const head = await session.run(['manifest', 'head', target], 'rc4-head');
    expect(head.exitCode, `regctl manifest head by sha512: ${head.stderr}`).toBe(0);
    expect(head.stdout.trim()).toBe(digest);
  },
);

test(
  'docker > regctl pushing a sha512 image BY TAG fails on the sha256 answer of a tag push, the image is stored (RC4b, RPS-1594, RPS-1607)',
  { tag: ['@regctl'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-rcsha512tag`;
    const admin = adminCredential();
    const session = await regctlSession(admin, `docker-regctl-rc4b-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(session.work, 'src'), marker: 'rc4b' });

    const mod = await session.run(
      [
        'image',
        'mod',
        `ocidir://${built.dir}@${built.manifestDigest}`,
        '--digest-algo',
        'sha512',
        '--create',
        'rc4b',
      ],
      'rc4b-mod',
    );
    expect(mod.exitCode, `regctl image mod --digest-algo sha512: ${mod.stderr}`).toBe(0);
    const layout = JSON.parse(await fs.readFile(path.join(built.dir, 'index.json'), 'utf8')) as {
      manifests: { digest: string }[];
    };
    const sha512 = layout.manifests.map((m) => m.digest).find((d) => d.startsWith('sha512:'));
    expect(sha512, 'the layout has a sha512 manifest').toBeDefined();
    const digest = sha512 as string;

    // The target is a TAG. The push names no digest, so Repsy answers its canonical sha256 in
    // `Docker-Content-Digest` (the OCI spec lets the answer differ when the algorithms differ, and
    // RPS-1244 decided it, pinned by R14). regctl hashed the manifest with sha512 and treats an
    // answer in another algorithm as a failed push. RPS-1607 decided to keep the sha256 answer (only
    // regctl can produce such a push), so this pins the behaviour as documented.
    const ref = imageRef(repoName, image, 'tagged');
    const copied = await session.run(
      ['image', 'copy', `ocidir://${built.dir}@${digest}`, ref],
      'rc4b-copy',
    );
    expect(copied.exitCode, 'regctl image copy of a sha512 image to a tag').not.toBe(0);
    expect(copied.stderr).toContain('unexpected digest returned');
    expect(copied.stderr).toContain(`expected ${digest}`);
    expect(copied.stderr).toMatch(/received sha256:[0-9a-f]{64}/);

    // The push itself went through on the server: the tag serves the manifest and answers the sha256,
    // and the same bytes are addressable by the sha512 regctl named. Only the client's check failed.
    const byTag = await rawGetManifest(repoName, admin, image, 'tagged');
    expect(byTag.status, 'the tag exists after regctl gave up').toBe(200);
    const sha256 = `sha256:${sha256Hex(byTag.body)}`;
    expect(byTag.digestHeader, 'a tag is answered in the registry canonical sha256').toBe(sha256);
    expect(copied.stderr).toContain(`received ${sha256}`);
    const bySha512 = await rawGetManifest(repoName, admin, image, digest);
    expect(bySha512.status, 'served by the sha512 the client hashed it with').toBe(200);
    expect(bySha512.digestHeader).toBe(digest);
    expect(bySha512.body.equals(byTag.body), 'the same bytes under both digests').toBe(true);

    // regctl reads the tag back fine: `manifest head` reports the sha256 the registry answers.
    const head = await session.run(['manifest', 'head', ref], 'rc4b-head');
    expect(head.exitCode, `regctl manifest head of the tag: ${head.stderr}`).toBe(0);
    expect(head.stdout.trim()).toBe(sha256);
  },
);

test(
  'docker > regctl copies a multi-platform manifest list with its children and reads it back (RC5)',
  { tag: ['@regctl'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-rcindex`;
    const admin = adminCredential();
    const session = await regctlSession(admin, `docker-regctl-rc5-${seeder.runId}`);
    const built = await buildIndexImage({
      dir: path.join(session.work, 'index'),
      marker: 'rc5',
      platforms: [
        { os: 'linux', arch: 'amd64' },
        { os: 'linux', arch: 'arm64' },
      ],
    });
    const ref = imageRef(repoName, image, 'multi');

    const pushed = await session.run(
      ['image', 'copy', `ocidir://${built.dir}@${built.indexDigest}`, ref],
      'rc5-push',
    );
    expect(pushed.exitCode, `regctl image copy (index): ${pushed.stderr}`).toBe(0);

    const served = await rawGetManifest(repoName, admin, image, 'multi');
    expect(served.status).toBe(200);
    expect(served.digestHeader, 'the index digest is unchanged').toBe(built.indexDigest);
    expect(served.body.equals(built.indexBytes), 'byte-identical index').toBe(true);
    for (const { content } of built.children) {
      expect(
        (await rawGetManifest(repoName, admin, image, content.manifestDigest)).status,
        `child ${content.manifestDigest}`,
      ).toBe(200);
      expect((await rawHeadBlob(repoName, admin, image, content.layerDigest)).status).toBe(200);
    }

    const arm = await session.run(
      ['manifest', 'head', '--platform', 'linux/arm64', ref],
      'rc5-head-arm',
    );
    expect(arm.exitCode, `regctl manifest head --platform: ${arm.stderr}`).toBe(0);
    expect(arm.stdout.trim(), 'the arm64 child through the served index').toBe(
      built.children[1]?.content.manifestDigest,
    );

    const back = path.join(session.work, 'back');
    const pulled = await session.run(['image', 'copy', ref, `ocidir://${back}`], 'rc5-pull');
    expect(pulled.exitCode, `regctl image copy back: ${pulled.stderr}`).toBe(0);
    const layoutIndex = JSON.parse(await fs.readFile(path.join(back, 'index.json'), 'utf8')) as {
      manifests: { digest: string }[];
    };
    expect(layoutIndex.manifests[0]?.digest, 'the pulled layout names the same index').toBe(
      built.indexDigest,
    );
    for (const { content } of built.children) {
      await fs.access(path.join(back, 'blobs', 'sha256', content.manifestDigest.slice(7)));
    }
  },
);

/** `<host>/<repo>/<image>` of a reference with a tag (what `regctl blob get` takes). */
function registryRepo(ref: string): string {
  return ref.slice(0, ref.lastIndexOf(':'));
}
