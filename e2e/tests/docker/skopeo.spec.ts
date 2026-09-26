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
 * What `skopeo` does beyond the catalog (`skopeo-catalog.spec.ts`) against a Repsy Docker repo
 * (RPS-1478 part B). Every behaviour was probed live before it was pinned:
 *
 *  - SK1 `skopeo copy docker://A docker://B` between two repositories of ONE Repsy: the destination
 *    ends up with the byte-identical manifest (same digest) and all its blobs. Repsy answers a blob
 *    mount request with the spec-allowed fallback `202` (RA5), so the bytes are uploaded again: the
 *    result, not the transport, is what is asserted.
 *  - SK2 `skopeo inspect`: the raw manifest is the stored one (its sha256 is the digest), the
 *    decoded form reports the config's platform and the layer list, by tag and by digest.
 *    `--no-tags` is needed: a plain `skopeo inspect <tag>` also lists the repository's tags and fails on
 *    Repsy's missing `tags/list` (RPS-1489, pinned in `client-tag-list.spec.ts`).
 *  - SK3 `skopeo delete`: asks scope `*` (`repository:<repo>/<image>:*`), which RPS-1434's rule accepts
 *    for the admin at the first request (no `insufficient_scope` round trip, unlike `regctl`); a tag
 *    reference is resolved to its digest first, so the manifest AND every tag of it go (`crane delete`
 *    of a tag removes the tag only, `tests/docker/crane-delete.spec.ts`, RPS-1440); a read-write deploy
 *    token is refused and nothing is removed (a delete is MANAGE).
 *  - SK4 a multi-platform index copied with `--all` keeps its digest and its children, and reads back
 *    into a layout, and `--override-arch` picks the right child through the served index.
 *  - `skopeo list-tags` (Repsy has no `tags/list`, RPS-1489) is in `client-tag-list.spec.ts`.
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
import { skopeoClient } from '../../src/clients/docker-skopeo.js';
import { skopeoTlsFlags } from '../../src/clients/docker-tls.js';
import {
  adminCredential,
  imageRef,
  rawGetManifest,
  rawHeadBlob,
  sha256Hex,
} from '../../src/clients/docker-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import { repoPath } from '../../src/repo-url.js';

const dockerUrl = (ref: string): string => `docker://${ref}`;

/** A private HOME logged in with `credential`, plus `push` of a fresh single-image layout. */
async function skopeoSession(
  credential: MaterializedCredential,
  label: string,
): Promise<ClientSession & { push(ref: string, marker: string): Promise<string> }> {
  const session = await openSession(skopeoClient, credential, label);
  return {
    ...session,
    async push(ref, marker) {
      const built = await buildImage({ dir: path.join(session.work, marker), marker });
      const result = await session.run(
        skopeoClient.pushArgs(built, ref),
        `${label}-push-${marker}`,
      );
      expect(result.exitCode, `skopeo copy: ${result.command}\n${result.stderr}`).toBe(0);
      return built.manifestDigest;
    },
  };
}

test(
  'docker > skopeo copy between two repos of one registry yields the identical image (SK1)',
  { tag: ['@skopeo'] },
  async ({ seeder }) => {
    const from = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-skcopy` };
    const to = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-skcopy2` };
    const admin = adminCredential();
    const session = await skopeoSession(admin, `docker-skopeo-sk1-${seeder.runId}`);

    const digest = await session.push(imageRef(from.repoName, from.image, 'v1'), 'sk1');
    const copied = await session.run(
      [
        'copy',
        '--insecure-policy',
        '--preserve-digests',
        ...skopeoTlsFlags('src'),
        ...skopeoTlsFlags('dest'),
        dockerUrl(imageRef(from.repoName, from.image, 'v1')),
        dockerUrl(imageRef(to.repoName, to.image, 'v1')),
      ],
      'docker-skopeo-sk1-copy',
    );
    expect(copied.exitCode, `skopeo copy A to B: ${copied.command}\n${copied.stderr}`).toBe(0);

    const copiedDigest = await expectSameImage(from, to, 'v1', 'skopeo copy across repos');
    expect(copiedDigest, 'the digest the layout was built with survives the copy').toBe(digest);
  },
);

test('docker > skopeo inspect reads the stored manifest and config (SK2)', async ({ seeder }) => {
  const repoName = await newDockerRepo(seeder);
  const image = `e2e-${seeder.runId}-skinspect`;
  const ref = imageRef(repoName, image, 'v1');
  const session = await skopeoSession(adminCredential(), `docker-skopeo-sk2-${seeder.runId}`);
  const digest = await session.push(ref, 'sk2');
  const stored = await rawGetManifest(repoName, adminCredential(), image, 'v1');
  const layerDigest = (JSON.parse(stored.body.toString('utf8')) as { layers: { digest: string }[] })
    .layers[0]?.digest;

  const raw = await session.run(
    ['inspect', '--raw', ...skopeoTlsFlags('both'), dockerUrl(ref)],
    'docker-skopeo-sk2-raw',
  );
  expect(raw.exitCode, `skopeo inspect --raw: ${raw.command}\n${raw.stderr}`).toBe(0);
  expect(
    `sha256:${sha256Hex(Buffer.from(raw.stdout, 'utf8'))}`,
    'the raw manifest is the stored one',
  ).toBe(digest);

  for (const target of [ref, byDigest(ref, digest)]) {
    const inspected = await session.run(
      ['inspect', '--no-tags', ...skopeoTlsFlags('both'), dockerUrl(target)],
      'docker-skopeo-sk2-inspect',
    );
    expect(inspected.exitCode, `skopeo inspect ${target}: ${inspected.stderr}`).toBe(0);
    const info = JSON.parse(inspected.stdout) as {
      Digest: string;
      Architecture: string;
      Os: string;
      Layers: string[];
    };
    expect(info.Digest, 'the digest of the manifest').toBe(digest);
    expect(info.Architecture).toBe('amd64');
    expect(info.Os).toBe('linux');
    expect(info.Layers, 'the layers named by the manifest').toEqual([layerDigest]);
  }
});

test(
  'docker > skopeo delete asks scope * and removes the manifest with every tag; a deploy token is refused (SK3)',
  { tag: ['@skopeo', '@auth'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-skdelete`;
    const admin = adminCredential();
    const session = await skopeoSession(admin, `docker-skopeo-sk3-${seeder.runId}`);
    const ref = imageRef(repoName, image, 'v1');
    const digest = await session.push(ref, 'sk3');
    const retag = await session.run(
      [
        'copy',
        '--insecure-policy',
        '--preserve-digests',
        ...skopeoTlsFlags('src'),
        ...skopeoTlsFlags('dest'),
        dockerUrl(ref),
        dockerUrl(imageRef(repoName, image, 'v2')),
      ],
      'docker-skopeo-sk3-retag',
    );
    expect(retag.exitCode, `skopeo copy (retag): ${retag.stderr}`).toBe(0);

    // A read-write deploy token: it copies (see the catalog) but a delete is MANAGE, so the delete is
    // refused, by tag and by digest, and nothing goes.
    const token = await tokenCredential(seeder, repoName, false);
    const tokenSession = await openSession(
      skopeoClient,
      token,
      `docker-skopeo-sk3-token-${seeder.runId}`,
    );
    for (const target of [imageRef(repoName, image, 'v2'), byDigest(ref, digest)]) {
      const refused = await tokenSession.run(
        ['delete', ...skopeoTlsFlags('both'), dockerUrl(target)],
        'docker-skopeo-sk3-token-delete',
      );
      expect(refused.exitCode, `skopeo delete ${target} with a deploy token`).not.toBe(0);
      expect(refused.stderr.toLowerCase(), 'the registry said why').toContain('unauthorized');
    }
    expect((await rawGetManifest(repoName, admin, image, 'v2')).status, 'v2 stays').toBe(200);
    expect((await rawGetManifest(repoName, admin, image, digest)).status, 'the digest stays').toBe(
      200,
    );

    // The admin: the delete of a TAG reference goes by digest (`--debug` shows the request), asks scope
    // `*` and needs no second token request.
    const deleted = await session.run(
      ['--debug', 'delete', ...skopeoTlsFlags('both'), dockerUrl(imageRef(repoName, image, 'v2'))],
      'docker-skopeo-sk3-delete',
    );
    expect(deleted.exitCode, `skopeo delete: ${deleted.command}\n${deleted.stderr}`).toBe(0);
    const log = decodeURIComponent(deleted.stderr);
    expect(log, 'skopeo asks for every action on the repository').toContain(
      `scope=repository:${repoPath(repoName)}/${image}:*`,
    );
    expect(log, 'no insufficient_scope round trip').not.toContain('insufficient_scope');
    expect(log, 'the tag was resolved and the DELETE went to the digest').toMatch(
      new RegExp(`DELETE \\S+/v2/${repoPath(repoName)}/${image}/manifests/${digest}`),
    );
    expect(
      (await rawGetManifest(repoName, admin, image, digest)).status,
      'the manifest is gone',
    ).toBe(404);
    expect((await rawGetManifest(repoName, admin, image, 'v1')).status, 'and every tag of it').toBe(
      404,
    );
    expect((await rawGetManifest(repoName, admin, image, 'v2')).status).toBe(404);
  },
);

test(
  'docker > skopeo copies a multi-platform index with --all and reads it back (SK4)',
  { tag: ['@skopeo'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-skindex`;
    const admin = adminCredential();
    const session = await skopeoSession(admin, `docker-skopeo-sk4-${seeder.runId}`);
    const built = await buildIndexImage({
      dir: path.join(session.work, 'index'),
      marker: 'sk4',
      family: 'oci',
      platforms: [
        { os: 'linux', arch: 'amd64' },
        { os: 'linux', arch: 'arm64' },
      ],
    });
    const ref = imageRef(repoName, image, 'multi');

    const pushed = await session.run(
      [
        'copy',
        '--all',
        '--insecure-policy',
        '--preserve-digests',
        ...skopeoTlsFlags('dest'),
        `oci:${built.dir}`,
        dockerUrl(ref),
      ],
      'docker-skopeo-sk4-push',
    );
    expect(pushed.exitCode, `skopeo copy --all: ${pushed.command}\n${pushed.stderr}`).toBe(0);

    const served = await rawGetManifest(repoName, admin, image, 'multi');
    expect(served.status).toBe(200);
    expect(served.digestHeader, 'the index digest survives --preserve-digests').toBe(
      built.indexDigest,
    );
    expect(served.body.equals(built.indexBytes), 'byte-identical index').toBe(true);
    for (const { content } of built.children) {
      expect(
        (await rawGetManifest(repoName, admin, image, content.manifestDigest)).status,
        `child ${content.manifestDigest}`,
      ).toBe(200);
      expect(
        (await rawHeadBlob(repoName, admin, image, content.layerDigest)).status,
        'the child layer',
      ).toBe(200);
    }

    // The real client picks the child of the asked platform through the served index.
    const arm = await session.run(
      [
        'inspect',
        '--no-tags',
        '--override-arch',
        'arm64',
        ...skopeoTlsFlags('both'),
        dockerUrl(ref),
      ],
      'docker-skopeo-sk4-inspect-arm',
    );
    expect(arm.exitCode, `skopeo inspect --override-arch arm64: ${arm.stderr}`).toBe(0);
    const armInfo = JSON.parse(arm.stdout) as {
      Digest: string;
      Architecture: string;
      Layers: string[];
    };
    expect(armInfo.Architecture).toBe('arm64');
    expect(armInfo.Layers, 'the arm64 child layer').toEqual([
      built.children[1]?.content.layerDigest,
    ]);
    expect(armInfo.Digest, 'the digest of the top-level manifest').toBe(built.indexDigest);

    // And the whole index comes back into a layout, unchanged.
    const back = path.join(session.work, 'back');
    const pulled = await session.run(
      [
        'copy',
        '--all',
        '--insecure-policy',
        ...skopeoTlsFlags('src'),
        dockerUrl(ref),
        `oci:${back}`,
      ],
      'docker-skopeo-sk4-pull',
    );
    expect(pulled.exitCode, `skopeo copy --all back: ${pulled.stderr}`).toBe(0);
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
