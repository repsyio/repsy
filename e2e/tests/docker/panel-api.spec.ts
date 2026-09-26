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
 * RPS-1483: the panel API of Docker (`/api/docker/images/...`, all 12 operations), against what the REAL
 * `crane` pushed. Each response is validated against the response schema of `openapi-spec.yaml`
 * (`src/api/spec-contract.ts`: the schema, and no property the schema does not declare), and the values are
 * matched to the client-side facts: the manifest digest `crane digest` prints, the config and layer digests and
 * sizes of the manifest `crane manifest` prints, the config JSON `crane config` prints, the platforms of a
 * multi-arch index, the tags that were pushed.
 *
 * The delete operations follow the content-addressed model of AGENTS.md "Database": a manifest is one row per
 * image and digest, a tag is a pointer to it, and a manifest FILE is shared by the images of a repo that have the
 * digest and is deleted only when no row needs it. After each delete the WIRE is checked (manifest by tag and by
 * digest, `tags/list` as pinned by RPS-1489, blob HEAD, a real `crane pull`), and that a sibling image or tag
 * still pulls its exact digest.
 *
 * Copied from `tests/docker/image-lifecycle.spec.ts` (the crane session, the layout builder) and
 * `tests/pypi/panel-api.spec.ts` (the shared contract helpers). The paging sweeps seed their rows over raw HTTP
 * (`seedPackage`): the pages are the subject there, not the client.
 */
import { createHash } from 'node:crypto';
import path from 'node:path';

import {
  callOperation,
  expectContract,
  expectCovers,
  expectFailure,
  expectPagingSweep,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import { byDigest, newDockerRepo } from '../../src/clients/docker-client-tests.js';
import {
  buildImage,
  buildIndexImage,
  type BuiltImage,
  type BuiltIndex,
  type ImageContent,
} from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  rawGetManifest,
  rawHeadBlob,
  rawTagsList,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Docker panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'listDockerImages',
  'getDockerImageSummary',
  'getDockerImageDetail',
  'listDockerImageTags',
  'getDockerImageTag',
  'listDockerTagManifests',
  'getDockerImageManifest',
  'getDockerImageConfig',
  'deleteDockerTag',
  'deleteDockerImage',
  'deleteDockerUntaggedManifests',
  'deleteDockerOrphanLayers',
];

const CRANE_INSECURE = env.insecureRegistry ? ['--insecure'] : [];

interface Session {
  repoName: string;
  work: string;
  crane: (args: string[], label: string) => ReturnType<typeof run>;
  ref: (image: string, tag: string) => string;
}

async function newSession(seeder: Seeder, label: string): Promise<Session> {
  const repoName = await newDockerRepo(seeder);
  const { home, work } = await isolatedWorkDir(`${label}-${seeder.runId}`);
  await renderDockerConfig(home, adminCredential());
  return {
    repoName,
    work,
    crane: (args, runLabel) =>
      run('crane', [...args, ...CRANE_INSECURE], {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 60_000,
        label: runLabel,
      }),
    ref: (image, tag) => imageRef(repoName, image, tag),
  };
}

const sha256 = (text: string | Buffer): string =>
  `sha256:${createHash('sha256').update(text).digest('hex')}`;

/** What the manifest of one image pins: its config, and the bytes of the config and the layers. */
const contentBytes = (content: ImageContent): number =>
  content.configBytes.length + content.layerBytes.length;

async function craneOk(session: Session, args: string[], label: string): Promise<string> {
  const result = await session.crane(args, label);
  expect(result.exitCode, `${result.command}: ${result.stderr}`).toBe(0);
  return result.stdout;
}

/** `crane push <layout> <ref>`. */
async function push(session: Session, dir: string, image: string, tag: string): Promise<void> {
  await craneOk(session, ['push', dir, session.ref(image, tag)], `panel-push-${tag}`);
}

/** The manifest digest the REGISTRY reports for `<image>:<tag>` to `crane digest`. */
async function craneDigest(session: Session, image: string, tag: string): Promise<string> {
  return (
    await craneOk(session, ['digest', session.ref(image, tag)], `panel-digest-${tag}`)
  ).trim();
}

async function pullExitCode(session: Session, image: string, tag: string): Promise<number> {
  const result = await session.crane(
    ['pull', session.ref(image, tag), path.join(session.work, `pull-${image}-${tag}.tar`)],
    `panel-pull-${tag}`,
  );
  return result.exitCode;
}

const manifestStatus = async (session: Session, image: string, reference: string) =>
  (await rawGetManifest(session.repoName, adminCredential(), image, reference)).status;

const blobStatus = async (session: Session, image: string, digest: string) =>
  (await rawHeadBlob(session.repoName, adminCredential(), image, digest)).status;

const values = (session: Session, imageName: string, more: Record<string, string> = {}) => ({
  repoName: session.repoName,
  imageName,
  ...more,
});

interface Tag {
  id: string;
  digest: string;
  configDigest?: string;
  imageName: string;
  name: string;
  platform: string;
  mediaType: string;
  createdAt: string;
  lastUpdatedAt: string;
}

interface Summary {
  name: string;
  size: number;
  digest?: string;
  updatedAt: string;
  tagCount: number;
  untaggedManifestCount: number;
  untaggedSize: number;
}

interface ManifestRow {
  name: string;
  digest: string;
  createdAt: string;
  platform: string;
  configDigest?: string;
}

async function summaryOf(session: Session, image: string): Promise<Summary> {
  return expectContract(
    'getDockerImageSummary',
    await callOperation('getDockerImageSummary', values(session, image)),
  ) as Summary;
}

async function tagNames(session: Session, image: string): Promise<string[]> {
  const page = expectContract(
    'listDockerImageTags',
    await callOperation('listDockerImageTags', values(session, image), { query: 'size=100' }),
  ) as { content: { name: string }[] };
  return page.content.map((tag) => tag.name).sort();
}

/** The two-platform OCI index every multi-arch case pushes. */
async function buildMulti(session: Session, name: string, marker: string): Promise<BuiltIndex> {
  return buildIndexImage({
    dir: path.join(session.work, name),
    marker,
    family: 'oci',
    platforms: [
      { os: 'linux', arch: 'amd64' },
      { os: 'linux', arch: 'arm64' },
    ],
  });
}

test.describe('the Docker panel API against what crane pushed', () => {
  test.setTimeout(300_000);

  test('names every operation of the Docker panel API', () => {
    expectCovers(EXERCISED, '/api/docker/');
  });

  test('lists, gets and describes what crane pushed, and every digest matches the client', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'docker-panel-read');
    const image = `e2e-${seeder.runId}-read`;
    const single = await buildImage({ dir: path.join(session.work, 'single'), marker: 'read-a' });
    const other = await buildImage({ dir: path.join(session.work, 'other'), marker: 'read-c' });
    const multi = await buildMulti(session, 'multi', 'read-idx');

    await push(session, single.dir, image, 'v1');
    // A second tag of the SAME manifest, made by the client (`crane tag`): one manifest, two pointers.
    await craneOk(session, ['tag', session.ref(image, 'v1'), 'v1b'], 'panel-tag-v1b');
    await push(session, other.dir, image, 'latest');
    await push(session, multi.dir, image, 'multi');

    // What the client says the registry holds.
    const digests = {
      v1: await craneDigest(session, image, 'v1'),
      v1b: await craneDigest(session, image, 'v1b'),
      latest: await craneDigest(session, image, 'latest'),
      multi: await craneDigest(session, image, 'multi'),
    };
    expect(digests.v1).toBe(single.manifestDigest);
    expect(digests.v1b, 'crane tag points the second tag at the same digest').toBe(digests.v1);
    expect(digests.latest).toBe(other.manifestDigest);
    expect(digests.multi).toBe(multi.indexDigest);

    // GET /images/{repo}: the image with its tag count and what its tags reach.
    const listed = expectContract(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName: session.repoName }),
    ) as { content: Summary[]; page: { totalElements: number } };
    expect(listed.page.totalElements).toBe(1);
    // Every manifest the four tags reach once: the single-arch images and the two children of the index.
    const reached = [single, other, ...multi.children.map((child) => child.content)];
    expect(listed.content[0]).toMatchObject({
      name: image,
      tagCount: 4,
      untaggedManifestCount: 0,
      untaggedSize: 0,
      size: reached.reduce((sum, content) => sum + contentBytes(content), 0),
      digest: digests.multi,
    });

    // GET .../summary is the same row.
    expect(await summaryOf(session, image)).toEqual(listed.content[0]);

    // GET .../tags: every pushed tag with the platform the client's config says.
    const tags = expectContract(
      'listDockerImageTags',
      await callOperation('listDockerImageTags', values(session, image)),
    ) as { content: { name: string; platform: string; lastUpdatedAt: string }[] };
    expect(tags.content.map((tag) => tag.name).sort()).toEqual(['latest', 'multi', 'v1', 'v1b']);
    const platformOf = Object.fromEntries(tags.content.map((tag) => [tag.name, tag.platform]));
    expect(platformOf).toEqual({
      v1: 'linux/amd64',
      v1b: 'linux/amd64',
      latest: 'linux/amd64',
      multi: 'Multiplatform',
    });

    // GET .../tags/{tag}: the tag's manifest and config digests are the client's.
    for (const [name, content] of [
      ['v1', single],
      ['v1b', single],
      ['latest', other],
    ] as [string, BuiltImage][]) {
      const tag = expectContract(
        'getDockerImageTag',
        await callOperation('getDockerImageTag', values(session, image, { tagName: name })),
      ) as Tag;
      expect(tag, name).toMatchObject({
        name,
        imageName: image,
        digest: content.manifestDigest,
        configDigest: content.configDigest,
        platform: 'linux/amd64',
        mediaType: content.manifestMediaType,
      });
      expect(Date.parse(tag.createdAt)).toBeLessThanOrEqual(Date.parse(tag.lastUpdatedAt));
    }
    const multiTag = expectContract(
      'getDockerImageTag',
      await callOperation('getDockerImageTag', values(session, image, { tagName: 'multi' })),
    ) as Tag;
    expect(multiTag).toMatchObject({
      name: 'multi',
      digest: multi.indexDigest,
      platform: 'Multiplatform',
      mediaType: multi.indexMediaType,
    });
    expect(multiTag.configDigest, 'an index has no config').toBeUndefined();

    // GET /images/{repo}/{image}: the tag `latest`.
    const detail = expectContract(
      'getDockerImageDetail',
      await callOperation('getDockerImageDetail', values(session, image)),
    ) as Tag;
    expect(detail).toMatchObject({ name: 'latest', digest: other.manifestDigest });

    // GET .../tags/{tag}/manifests: one row for a single-arch tag ...
    const single1 = expectContract(
      'listDockerTagManifests',
      await callOperation('listDockerTagManifests', values(session, image, { tagName: 'v1' })),
    ) as { content: ManifestRow[] };
    expect(single1.content).toHaveLength(1);
    expect(single1.content[0]).toMatchObject({
      name: 'v1',
      digest: single.manifestDigest,
      platform: 'linux/amd64',
      configDigest: single.configDigest,
    });
    // ... and the index row plus one row per platform child for the multi-arch tag, taken from what
    // `crane manifest` prints for the index.
    const indexJson = JSON.parse(
      await craneOk(session, ['manifest', session.ref(image, 'multi')], 'panel-manifest-multi'),
    ) as { manifests: { digest: string; platform: { os: string; architecture: string } }[] };
    const children = expectContract(
      'listDockerTagManifests',
      await callOperation('listDockerTagManifests', values(session, image, { tagName: 'multi' })),
    ) as { content: ManifestRow[]; page: { totalElements: number } };
    expect(children.page.totalElements).toBe(3);
    const byDigestRow = new Map(children.content.map((row) => [row.digest, row]));
    expect(byDigestRow.get(multi.indexDigest)).toMatchObject({
      name: 'multi',
      platform: 'Multiplatform',
    });
    for (const entry of indexJson.manifests) {
      const child = multi.children.find((c) => c.content.manifestDigest === entry.digest);
      expect(child, `the index names ${entry.digest}`).toBeDefined();
      expect(byDigestRow.get(entry.digest), entry.digest).toMatchObject({
        name: entry.digest,
        platform: `${entry.platform.os}/${entry.platform.architecture}`,
        configDigest: child?.content.configDigest,
      });
    }

    // GET .../manifests/{reference}: the bytes the registry stores, by tag and by digest.
    for (const reference of ['v1', single.manifestDigest]) {
      const manifest = expectContract(
        'getDockerImageManifest',
        await callOperation('getDockerImageManifest', values(session, image, { reference })),
      ) as string;
      expect(sha256(manifest), `manifest ${reference}`).toBe(single.manifestDigest);
      expect(JSON.parse(manifest)).toMatchObject({
        config: { digest: single.configDigest, size: single.configBytes.length },
        layers: [{ digest: single.layerDigest, size: single.layerBytes.length }],
      });
    }
    const indexManifest = expectContract(
      'getDockerImageManifest',
      await callOperation('getDockerImageManifest', values(session, image, { reference: 'multi' })),
    ) as string;
    expect(sha256(indexManifest)).toBe(multi.indexDigest);
    expect(JSON.parse(indexManifest)).toEqual(indexJson);

    // GET .../configs/{digest}: the config `crane config` prints, of a single image and of an index child.
    for (const [tag, content] of [
      ['v1', single],
      ['latest', other],
    ] as [string, BuiltImage][]) {
      const config = expectContract(
        'getDockerImageConfig',
        await callOperation(
          'getDockerImageConfig',
          values(session, image, { digest: content.configDigest }),
        ),
      ) as string;
      expect(sha256(config), `config of ${tag} is the blob its digest names`).toBe(
        content.configDigest,
      );
      const printed = await craneOk(
        session,
        ['config', session.ref(image, tag)],
        `panel-config-${tag}`,
      );
      expect(JSON.parse(config)).toEqual(JSON.parse(printed));
    }
    const arm = multi.children[1]?.content as ImageContent;
    const armConfig = expectContract(
      'getDockerImageConfig',
      await callOperation(
        'getDockerImageConfig',
        values(session, image, { digest: arm.configDigest }),
      ),
    ) as string;
    expect(sha256(armConfig)).toBe(arm.configDigest);
    expect(JSON.parse(armConfig)).toMatchObject({ architecture: 'arm64', os: 'linux' });
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'docker-panel-fail');
    const image = `e2e-${seeder.runId}-fail`;
    const built = await buildImage({ dir: path.join(session.work, 'one'), marker: 'fail' });
    await push(session, built.dir, image, 'v1');
    const missingImage = values(session, 'no-such-image');
    const missingTag = values(session, image, { tagName: 'no-such-tag' });

    expectFailure(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName: session.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getDockerImageSummary',
      await callOperation('getDockerImageSummary', missingImage),
      404,
      'imageNotFound',
    );
    expectFailure(
      'getDockerImageDetail',
      await callOperation('getDockerImageDetail', values(session, image)),
      404,
      'tagNotFound',
    );
    expectFailure(
      'getDockerImageTag',
      await callOperation('getDockerImageTag', missingTag),
      404,
      'tagNotFound',
    );
    expectFailure(
      'listDockerTagManifests',
      await callOperation('listDockerTagManifests', missingTag),
      404,
      'tagNotFound',
    );
    expectFailure(
      'getDockerImageConfig',
      await callOperation(
        'getDockerImageConfig',
        values(session, image, { digest: sha256('not a config') }),
      ),
      404,
      'layerNotFound',
    );
    expectFailure(
      'deleteDockerTag',
      await callOperation('deleteDockerTag', missingTag),
      404,
      'tagNotFound',
    );
    expectFailure(
      'deleteDockerImage',
      await callOperation('deleteDockerImage', missingImage),
      404,
      'imageNotFound',
    );
    expectFailure(
      'deleteDockerUntaggedManifests',
      await callOperation('deleteDockerUntaggedManifests', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'deleteDockerOrphanLayers',
      await callOperation('deleteDockerOrphanLayers', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );

    // An image that does not exist: 404 `imageNotFound` wherever the operation reads or deletes one.
    expectFailure(
      'getDockerImageTag',
      await callOperation('getDockerImageTag', values(session, 'no-such-image', { tagName: 'v1' })),
      404,
      'imageNotFound',
    );
    expectFailure(
      'listDockerTagManifests',
      await callOperation(
        'listDockerTagManifests',
        values(session, 'no-such-image', { tagName: 'v1' }),
      ),
      404,
      'imageNotFound',
    );
    expectFailure(
      'deleteDockerTag',
      await callOperation('deleteDockerTag', values(session, 'no-such-image', { tagName: 'v1' })),
      404,
      'imageNotFound',
    );
    expectFailure(
      'deleteDockerUntaggedManifests',
      await callOperation(
        'deleteDockerUntaggedManifests',
        { repoName: session.repoName },
        { query: 'image=no-such-image' },
      ),
      404,
      'imageNotFound',
    );
    // The tags of an image that does not exist: 404 `imageNotFound` like every other image route, not an
    // empty page (RPS-1579).
    expectFailure(
      'listDockerImageTags',
      await callOperation('listDockerImageTags', missingImage),
      404,
      'imageNotFound',
    );
    // The manifest route names what is missing: the image, the tag, or the digest the image does not store.
    expectFailure(
      'getDockerImageManifest',
      await callOperation(
        'getDockerImageManifest',
        values(session, 'no-such-image', { reference: 'v1' }),
      ),
      404,
      'imageNotFound',
    );
    expectFailure(
      'getDockerImageManifest',
      await callOperation(
        'getDockerImageManifest',
        values(session, image, { reference: 'no-such-tag' }),
      ),
      404,
      'tagNotFound',
    );
    expectFailure(
      'getDockerImageManifest',
      await callOperation(
        'getDockerImageManifest',
        values(session, image, { reference: sha256('no such manifest') }),
      ),
      404,
      'manifestNotFound',
    );

    // A delete of a tag or an image that does not exist deleted nothing: the image has its one tag and
    // still pulls (the shape of RPS-1573 for Maven: a delete of a missing item must not cascade).
    expect(await tagNames(session, image)).toEqual(['v1']);
    expect(await summaryOf(session, image)).toMatchObject({
      tagCount: 1,
      untaggedManifestCount: 0,
    });
    expect(await craneDigest(session, image, 'v1')).toBe(built.manifestDigest);
  });

  test('pages, sorts and narrows the image, tag and manifest lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const repoName = await newDockerRepo(seeder);
    const repo = { name: repoName, type: RepoType.DOCKER };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'listDockerImages',
      values: { repoName },
      total: 5,
      keyOf: (row) => row.name,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const narrowed = expectContract(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName }, { query: 'q=pkg-3' }),
    ) as { content: { name: string }[] };
    expect(narrowed.content.map((row) => row.name)).toEqual([`e2e-${seeder.runId}-pkg-3`]);

    // The tags of one image, the second list operation with paging.
    const image = `e2e-${seeder.runId}-tags`;
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage(repo, seeder, { name: image, version });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'listDockerImageTags',
      values: { repoName, imageName: image },
      total: 5,
      keyOf: (row) => row.name,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const oneTag = expectContract(
      'listDockerImageTags',
      await callOperation(
        'listDockerImageTags',
        { repoName, imageName: image },
        { query: 'q=1.0.4' },
      ),
    ) as { content: { name: string }[] };
    expect(oneTag.content.map((row) => row.name)).toEqual(['1.0.4']);
  });

  test('deleting a tag removes the pointer only; the manifest a sibling tag or image shares stays', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'docker-panel-tag');
    const imageA = `e2e-${seeder.runId}-tag-a`;
    const imageB = `e2e-${seeder.runId}-tag-b`;
    const shared = await buildImage({
      dir: path.join(session.work, 'shared'),
      marker: 'tag-shared',
    });
    const other = await buildImage({ dir: path.join(session.work, 'other'), marker: 'tag-other' });
    await push(session, shared.dir, imageA, 'v1');
    await craneOk(session, ['tag', session.ref(imageA, 'v1'), 'v1b'], 'panel-tag-v1b');
    await push(session, other.dir, imageA, 'latest');
    await push(session, shared.dir, imageB, 'x');
    const tagsListBefore = await rawTagsList(session.repoName, adminCredential(), imageA);
    // tags/list is not served (RPS-1489 adds it, and flips this pin with `registry-api.spec.ts`).
    expect(tagsListBefore.status).toBe(404);

    // Delete `v1`: the tag is gone on the wire and for the client; `v1b` (same digest), the digest itself and
    // the other image still serve the exact bytes.
    expectContract(
      'deleteDockerTag',
      await callOperation('deleteDockerTag', values(session, imageA, { tagName: 'v1' })),
    );
    expect(await manifestStatus(session, imageA, 'v1')).toBe(404);
    expect(await pullExitCode(session, imageA, 'v1'), 'crane pull of the deleted tag').not.toBe(0);
    expect(await craneDigest(session, imageA, 'v1b'), 'the sibling tag').toBe(
      shared.manifestDigest,
    );
    expect(await manifestStatus(session, imageA, shared.manifestDigest)).toBe(200);
    expect(await pullExitCode(session, imageA, 'v1b')).toBe(0);
    expect(await tagNames(session, imageA)).toEqual(['latest', 'v1b']);
    expectFailure(
      'getDockerImageTag',
      await callOperation('getDockerImageTag', values(session, imageA, { tagName: 'v1' })),
      404,
      'tagNotFound',
    );
    expectFailure(
      'deleteDockerTag',
      await callOperation('deleteDockerTag', values(session, imageA, { tagName: 'v1' })),
      404,
      'tagNotFound',
    );
    expect(await summaryOf(session, imageA)).toMatchObject({
      tagCount: 2,
      untaggedManifestCount: 0,
    });
    expect((await rawTagsList(session.repoName, adminCredential(), imageA)).status).toBe(404);

    // Delete the last tag of that manifest: the manifest stays, untagged, pullable by digest only.
    expectContract(
      'deleteDockerTag',
      await callOperation('deleteDockerTag', values(session, imageA, { tagName: 'v1b' })),
    );
    expect(await manifestStatus(session, imageA, 'v1b')).toBe(404);
    expect(
      await manifestStatus(session, imageA, shared.manifestDigest),
      'untagged, by digest',
    ).toBe(200);
    expect(await summaryOf(session, imageA)).toMatchObject({
      tagCount: 1,
      untaggedManifestCount: 1,
      untaggedSize: contentBytes(shared),
    });
    const untaggedByDigest = await session.crane(
      ['manifest', byDigest(session.ref(imageA, 'v1b'), shared.manifestDigest)],
      'panel-manifest-by-digest',
    );
    expect(untaggedByDigest.exitCode, untaggedByDigest.stderr).toBe(0);
    expect(sha256(untaggedByDigest.stdout.trimEnd())).toBe(shared.manifestDigest);

    // The other image and the other tag were not touched.
    expect(await craneDigest(session, imageB, 'x')).toBe(shared.manifestDigest);
    expect(await craneDigest(session, imageA, 'latest')).toBe(other.manifestDigest);
    expect(await pullExitCode(session, imageB, 'x')).toBe(0);
    expect(await summaryOf(session, imageB)).toMatchObject({
      tagCount: 1,
      untaggedManifestCount: 0,
    });
  });

  test('deleting the untagged manifests drops only the manifests no tag reaches, and the layers only they used', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'docker-panel-untagged');
    const imageA = `e2e-${seeder.runId}-un-a`;
    const imageB = `e2e-${seeder.runId}-un-b`;
    const shared = await buildImage({
      dir: path.join(session.work, 'shared'),
      marker: 'un-shared',
    });
    const other = await buildImage({ dir: path.join(session.work, 'other'), marker: 'un-other' });
    const multi = await buildMulti(session, 'multi', 'un-idx');
    const [amd, arm] = multi.children.map((child) => child.content) as [ImageContent, ImageContent];

    // Image A: `shared` (tag v1), `other` (tag latest) and an index (tag multi) whose amd64 child is ALSO
    // tagged `amd` by the client. Image B holds `shared` too.
    await push(session, shared.dir, imageA, 'v1');
    await push(session, other.dir, imageA, 'latest');
    await push(session, multi.dir, imageA, 'multi');
    await craneOk(
      session,
      ['tag', byDigest(session.ref(imageA, 'multi'), amd.manifestDigest), 'amd'],
      'panel-tag-amd',
    );
    await push(session, shared.dir, imageB, 'x');

    // Nothing is untagged yet: the cleanup deletes nothing, whole repo and one image alike.
    const none = expectContract(
      'deleteDockerUntaggedManifests',
      await callOperation('deleteDockerUntaggedManifests', { repoName: session.repoName }),
    );
    expect(none).toEqual({
      deletedManifests: 0,
      freedManifestBytes: 0,
      orphanLayersScheduled: 0,
      orphanLayerBytes: 0,
    });

    // Untag everything of A but `amd` and `latest`: `shared` (shared with B) and the index become untagged.
    for (const tag of ['v1', 'multi']) {
      expectContract(
        'deleteDockerTag',
        await callOperation('deleteDockerTag', values(session, imageA, { tagName: tag })),
      );
    }
    // The index reaches `arm` only through itself; `amd` stays reached by its own tag.
    expect(await summaryOf(session, imageA)).toMatchObject({
      tagCount: 2,
      untaggedManifestCount: 3,
      untaggedSize: contentBytes(shared) + contentBytes(arm),
    });

    // Scoped to image B: it has nothing untagged, so nothing of A goes.
    const scopedB = expectContract(
      'deleteDockerUntaggedManifests',
      await callOperation(
        'deleteDockerUntaggedManifests',
        { repoName: session.repoName },
        { query: `image=${imageB}` },
      ),
    ) as { deletedManifests: number };
    expect(scopedB.deletedManifests).toBe(0);
    expect(await manifestStatus(session, imageA, multi.indexDigest)).toBe(200);

    // Scoped to image A: three manifest rows go (the `shared` one, the index, the arm64 child).
    const result = expectContract(
      'deleteDockerUntaggedManifests',
      await callOperation(
        'deleteDockerUntaggedManifests',
        { repoName: session.repoName },
        { query: `image=${imageA}` },
      ),
    ) as {
      deletedManifests: number;
      freedManifestBytes: number;
      orphanLayersScheduled: number;
      orphanLayerBytes: number;
    };
    expect(result).toEqual({
      deletedManifests: 3,
      // `shared`'s manifest FILE is still needed by image B, so only the index's and the arm64 child's
      // manifest bytes are freed.
      freedManifestBytes: multi.indexBytes.length + arm.manifestBytes.length,
      // The arm64 child's config and layer are used by nothing else. `shared`'s blobs are used by B.
      orphanLayersScheduled: 2,
      orphanLayerBytes: contentBytes(arm),
    });

    // The wire: gone where nothing needs it, kept where a sibling does.
    expect(await manifestStatus(session, imageA, multi.indexDigest), 'the index').toBe(404);
    expect(await manifestStatus(session, imageA, arm.manifestDigest), 'the arm64 child').toBe(404);
    expect(await manifestStatus(session, imageA, shared.manifestDigest), 'A no longer has it').toBe(
      404,
    );
    expect(await manifestStatus(session, imageB, shared.manifestDigest), 'B still has it').toBe(
      200,
    );
    expect(await manifestStatus(session, imageA, amd.manifestDigest), 'the tagged child').toBe(200);
    await expect
      .poll(() => blobStatus(session, imageA, arm.layerDigest), { message: 'arm64 layer blob' })
      .toBe(404);
    expect(await blobStatus(session, imageA, arm.configDigest), 'arm64 config blob').toBe(404);
    expect(await blobStatus(session, imageB, shared.layerDigest), 'shared layer, still B').toBe(
      200,
    );
    expect(await blobStatus(session, imageA, amd.layerDigest), 'amd64 layer, still tagged').toBe(
      200,
    );

    // The siblings pull their exact digests with the real client.
    expect(await craneDigest(session, imageB, 'x')).toBe(shared.manifestDigest);
    expect(await pullExitCode(session, imageB, 'x')).toBe(0);
    expect(await craneDigest(session, imageA, 'amd')).toBe(amd.manifestDigest);
    expect(await pullExitCode(session, imageA, 'amd')).toBe(0);
    expect(await craneDigest(session, imageA, 'latest')).toBe(other.manifestDigest);
    expect(await summaryOf(session, imageA)).toMatchObject({
      tagCount: 2,
      untaggedManifestCount: 0,
      untaggedSize: 0,
    });
    expect(await summaryOf(session, imageB)).toMatchObject({
      tagCount: 1,
      untaggedManifestCount: 0,
    });

    // Asking again finds nothing.
    expect(
      expectContract(
        'deleteDockerUntaggedManifests',
        await callOperation('deleteDockerUntaggedManifests', { repoName: session.repoName }),
      ),
    ).toEqual({
      deletedManifests: 0,
      freedManifestBytes: 0,
      orphanLayersScheduled: 0,
      orphanLayerBytes: 0,
    });
  });

  test('deleting an image keeps a manifest a sibling image has, and the orphan-layers sweep removes the blobs nothing uses', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'docker-panel-image');
    const imageA = `e2e-${seeder.runId}-img-a`;
    const imageB = `e2e-${seeder.runId}-img-b`;
    const shared = await buildImage({
      dir: path.join(session.work, 'shared'),
      marker: 'img-shared',
    });
    const own = await buildImage({ dir: path.join(session.work, 'own'), marker: 'img-own' });
    await push(session, shared.dir, imageA, 'v1');
    await push(session, own.dir, imageA, 'own');
    await push(session, shared.dir, imageB, 'x');

    // Delete image A: both its tags and manifests go; B keeps the `shared` manifest file and answers the digest.
    expectContract(
      'deleteDockerImage',
      await callOperation('deleteDockerImage', values(session, imageA)),
    );
    expect(await manifestStatus(session, imageA, 'v1')).toBe(404);
    expect(await manifestStatus(session, imageA, 'own')).toBe(404);
    expect(await manifestStatus(session, imageA, own.manifestDigest)).toBe(404);
    expect(await pullExitCode(session, imageA, 'v1'), 'crane pull of a deleted image').not.toBe(0);
    expectFailure(
      'getDockerImageSummary',
      await callOperation('getDockerImageSummary', values(session, imageA)),
      404,
      'imageNotFound',
    );
    expectFailure(
      'deleteDockerImage',
      await callOperation('deleteDockerImage', values(session, imageA)),
      404,
      'imageNotFound',
    );
    const listed = expectContract(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName: session.repoName }),
    ) as { content: { name: string }[] };
    expect(listed.content.map((row) => row.name)).toEqual([imageB]);
    expect(
      await manifestStatus(session, imageB, shared.manifestDigest),
      'the shared manifest',
    ).toBe(200);
    expect(await craneDigest(session, imageB, 'x')).toBe(shared.manifestDigest);
    expect(await pullExitCode(session, imageB, 'x')).toBe(0);

    // The image delete left the blobs: they are swept by the orphan-layers operation, and only the ones
    // no remaining manifest names.
    expect(await blobStatus(session, imageB, own.layerDigest), 'orphan until swept').toBe(200);
    expectContract(
      'deleteDockerOrphanLayers',
      await callOperation('deleteDockerOrphanLayers', { repoName: session.repoName }),
    );
    await expect
      .poll(() => blobStatus(session, imageB, own.layerDigest), { message: 'orphan layer blob' })
      .toBe(404);
    expect(await blobStatus(session, imageB, own.configDigest)).toBe(404);
    expect(await blobStatus(session, imageB, shared.layerDigest), 'the shared layer').toBe(200);
    expect(await blobStatus(session, imageB, shared.configDigest), 'the shared config').toBe(200);
    expect(await pullExitCode(session, imageB, 'x'), 'B still pulls after the sweep').toBe(0);

    // Deleting the last image of the shared manifest removes its file too.
    expectContract(
      'deleteDockerImage',
      await callOperation('deleteDockerImage', values(session, imageB)),
    );
    expect(await manifestStatus(session, imageB, shared.manifestDigest)).toBe(404);
    expect(await pullExitCode(session, imageB, 'x')).not.toBe(0);
    const empty = expectContract(
      'listDockerImages',
      await callOperation('listDockerImages', { repoName: session.repoName }),
    ) as { content: unknown[] };
    expect(empty.content).toEqual([]);
  });
});
