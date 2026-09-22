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
 * Docker multi-arch (multi-platform) tests (step 5g, RPS-294), ported from `repsy-cloud`'s own e2e
 * harness's docker "multi-platform" case (`protocols/docker/multi-platform/test.ts`) -- with one
 * deliberate, evidence-backed deviation from what that file actually does.
 *
 * `repsy-cloud`'s own "multi-platform" case does NOT use `crane index append` at all: it shells out
 * to `docker buildx build --platform linux/amd64,linux/arm64 --push` (its `util.ts`'s
 * `dockerBuildxAndPush`), fronted by `docker buildx create` with a `docker-container` driver -- a
 * full BuildKit daemon, needing exactly the `--privileged`/root-started-daemon/shared-image-store
 * machinery this harness's own README ("Why no daemon") already rejects for the whole Docker runner.
 * Porting that literally is a non-starter here.
 *
 * The implementation plan's own gating hypothesis (HD-1) asked instead whether the SAME daemon-free
 * `crane` binary this harness already uses for every other docker test (`clients/docker.ts`'s file
 * header) can build a genuine multi-arch index on its own, via its `index append` sub-command --
 * confirmed LIVE, before this file was written, against a running local stack (`./run.sh local up`,
 * see this PR's own report for the exact transcript):
 *
 *  - HD-1 (gating, CONFIRMED): `crane index append -m <ref1> -m <ref2> -t <indexRef>` is entirely
 *    daemon-free and needs nothing beyond the plain manifest-PUT wire calls this server already
 *    supports. Read FIRST from `AbstractDockerProtocolTxFacade.saveManifest`'s own `switch`
 *    (`repsy-protocols/docker`): `OCI_IMAGE_INDEX`/`DOCKER_MANIFEST_LIST` route to
 *    `createManifestList`, a genuinely distinct, purpose-built code path -- NOT the flat-500
 *    `default -> throw new IllegalArgumentException("unsupportedMediaType")` branch B4/RPS-1110
 *    already pins for a truly unknown `Content-Type`. `createManifestList`'s own
 *    `findPlatformManifests` (same file, directly below) is what makes "push every child by digest
 *    FIRST" a hard SERVER rule, not just client politeness: it `getResource()`s each
 *    `platformManifest.getDigest()` by file name and throws `ItemNotFoundException
 *    ("resourceNotFound")` if that digest was never separately stored -- an index referencing a
 *    dangling child would be REFUSED, not silently accepted.
 *
 *    Traced live with `crane -v index append`: it HEADs each platform ref's manifest (already pushed
 *    by TAG, in an earlier `crane push`), then RE-PUTs each one's exact bytes under its OWN digest as
 *    the manifest reference (`PUT .../manifests/sha256:<digest>`, `201` both times -- exactly the "a
 *    real client always pushes an index's children by digest first" comment
 *    `registry-rules.spec.ts`'s R13 already left for a hand-built RAW probe, now confirmed by a REAL
 *    client, unprompted), then `PUT`s the assembled index itself under the requested tag --
 *    `Content-Type: application/vnd.oci.image.index.v1+json` by default, or
 *    `application/vnd.docker.distribution.manifest.list.v2+json` with `--docker-empty-base` (both
 *    confirmed live below, "docker-family" test) -- `201`, `Docker-Content-Digest` echoing the
 *    index's own digest.
 *  - The round trip was verified with the real client too: `crane manifest <indexRef>` (no
 *    `--platform`, so it reads the top-level index unchanged) lists both `platform` entries with
 *    their exact `architecture`/`os`; `crane pull --platform=linux/arm64 --format=oci <indexRef>
 *    <dir>` resolves to the ARM64 child specifically (its OWN config's `architecture` field says so,
 *    not the index's).
 *
 * No new backend bug was found while building this suite: R13 (`registry-rules.spec.ts`) had already
 * pinned the raw-HTTP shape of an index push; RPS-946 ("Docker image manifest lookup by digest fails
 * for per-platform manifests of multi-platform tags", filed independently, status Done) is about the
 * DIFFERENT panel UI endpoint (`/api/docker/images/.../manifests/{reference}`), not the registry
 * protocol path `crane`/this file exercise -- its own description says the registry path "already
 * resolves such digests" (`ManifestService.findManifestByRepoIdAndImageNameAndDigest`), which is
 * exactly what the tests below confirm live, end to end, with a real client.
 *
 * Every credential here is `token-rw` (a fresh read-write deploy token), the same hand-built
 * convention `tests/cargo/protocol-specific.spec.ts`/`tests/nuget/protocol-specific.spec.ts` use --
 * these are not catalog-loop scenarios, so `crane push`/`crane index append` are run directly, never
 * through `dockerAdapter`/`scenarios/loop.ts`. A raw-HTTP verification probe (`docker-raw.ts`'s
 * `rawGetManifest`) uses `adminCredential()` instead, the same split every other protocol's dedicated
 * tests use for read-side checks.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { craneEnv, dockerAdapter, renderDockerConfig } from '../../src/clients/docker.js';
import { buildImage, type BuiltImage } from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  rawGetManifest,
  sha256Hex,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  image: string;
  credential: MaterializedCredential;
}

/** A fresh private docker repo, a run-unique image name, and a fresh `token-rw` deploy token for it
 *  -- the credential every test in this file uses, exactly like `docker-raw.ts`'s catalog-loop
 *  credential kinds. */
async function newRepoWithToken(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const credential: MaterializedCredential = {
    transport: 'basic',
    username: token.username,
    password: token.token,
    kind: 'token',
  };
  return { repoName: repo.name, image: `e2e-${seeder.runId}-${label}`, credential };
}

/** Builds a platform-specific OCI layout and `crane push`es it under its own tag -- deliberately
 *  never `dockerAdapter.publish()` (`docker.ts`'s `publishWithClient` always builds `linux/amd64`,
 *  no `os`/`arch` override), the same "call `buildImage` then run `crane push` directly" shape
 *  `publish-consume.spec.ts`'s "D1" test already uses for its own OCI-family case. */
async function pushPlatformImage(
  layout: Layout,
  home: string,
  work: string,
  tag: string,
  os: string,
  arch: string,
  marker: string,
): Promise<BuiltImage> {
  const built = await buildImage({ dir: path.join(work, tag), marker, os, arch });
  const ref = imageRef(layout.repoName, layout.image, tag);
  const pushResult = await run('crane', ['push', built.dir, ref], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: 60_000,
    label: `docker-multiarch-push-${tag}`,
  });
  expect(pushResult.exitCode, `crane push (${tag}, ${os}/${arch}): ${pushResult.command}`).toBe(0);
  return built;
}

/** The `architecture`/`os` a pulled OCI layout's config blob actually carries -- proves a
 *  `--platform`-scoped pull resolved to the RIGHT child, not merely to some manifest the index
 *  happens to reference. */
async function pulledPlatform(pulledDir: string): Promise<{ os: string; architecture: string }> {
  const index = JSON.parse(await fs.readFile(path.join(pulledDir, 'index.json'), 'utf8')) as {
    manifests: { digest: string }[];
  };
  const manifestDigest = index.manifests[0]?.digest as string;
  const manifestBytes = await fs.readFile(
    path.join(pulledDir, 'blobs', 'sha256', manifestDigest.slice('sha256:'.length)),
  );
  const manifest = JSON.parse(manifestBytes.toString('utf8')) as { config: { digest: string } };
  const configDigest = manifest.config.digest;
  const configBytes = await fs.readFile(
    path.join(pulledDir, 'blobs', 'sha256', configDigest.slice('sha256:'.length)),
  );
  const config = JSON.parse(configBytes.toString('utf8')) as { os: string; architecture: string };
  return { os: config.os, architecture: config.architecture };
}

test(
  'docker > crane index append combines two platform manifests into a pullable multi-arch index (HD-1)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'multiarch');
    const { home, work } = await isolatedWorkDir(`docker-multiarch-${seeder.runId}`);
    await renderDockerConfig(home, layout.credential);

    const amd64 = await pushPlatformImage(
      layout,
      home,
      work,
      'amd64',
      'linux',
      'amd64',
      'multiarch-amd64',
    );
    const arm64 = await pushPlatformImage(
      layout,
      home,
      work,
      'arm64',
      'linux',
      'arm64',
      'multiarch-arm64',
    );

    const indexTag = dockerAdapter.version('release');
    const indexRef = imageRef(layout.repoName, layout.image, indexTag);
    const amd64Ref = imageRef(layout.repoName, layout.image, 'amd64');
    const arm64Ref = imageRef(layout.repoName, layout.image, 'arm64');

    const appendResult = await run(
      'crane',
      ['index', 'append', '-m', amd64Ref, '-m', arm64Ref, '-t', indexRef],
      { cwd: work, env: craneEnv(home), timeoutMs: 60_000, label: 'docker-multiarch-index-append' },
    );
    expect(appendResult.exitCode, `crane index append: ${appendResult.command}`).toBe(0);

    // "crane manifest" (no --platform) reads the top-level index unchanged -- both platform entries,
    // with their exact architecture/os, referencing the exact digests the two earlier pushes minted.
    const manifestResult = await run('crane', ['manifest', indexRef], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 30_000,
      label: 'docker-multiarch-manifest',
    });
    expect(manifestResult.exitCode, `crane manifest: ${manifestResult.command}`).toBe(0);
    const index = JSON.parse(manifestResult.stdout) as {
      mediaType: string;
      manifests: { digest: string; platform?: { architecture: string; os: string } }[];
    };
    expect(index.mediaType, "crane's own default index family is OCI, not Docker").toBe(
      'application/vnd.oci.image.index.v1+json',
    );
    expect(index.manifests, 'both platform entries are present').toHaveLength(2);
    const amd64Entry = index.manifests.find((m) => m.platform?.architecture === 'amd64');
    const arm64Entry = index.manifests.find((m) => m.platform?.architecture === 'arm64');
    expect(amd64Entry?.platform?.os, 'the amd64 entry carries linux/amd64').toBe('linux');
    expect(arm64Entry?.platform?.os, 'the arm64 entry carries linux/arm64').toBe('linux');
    expect(
      amd64Entry?.digest,
      "the amd64 entry's digest is the amd64 push's own manifest digest",
    ).toBe(amd64.manifestDigest);
    expect(
      arm64Entry?.digest,
      "the arm64 entry's digest is the arm64 push's own manifest digest",
    ).toBe(arm64.manifestDigest);

    // The index itself is servable by tag, with the OCI index content type, and its digest header
    // covers the exact bytes crane manifest just showed.
    const admin = adminCredential();
    const rawIndexRes = await rawGetManifest(layout.repoName, admin, layout.image, indexTag);
    expect(rawIndexRes.status, 'the index is servable by tag').toBe(200);
    expect(rawIndexRes.contentType?.split(';')[0], 'served with the OCI index content type').toBe(
      'application/vnd.oci.image.index.v1+json',
    );
    expect(rawIndexRes.digestHeader, 'Docker-Content-Digest matches the served index bytes').toBe(
      `sha256:${sha256Hex(rawIndexRes.body)}`,
    );

    // Each platform-specific child is independently pullable BY DIGEST -- the "children by digest
    // first" requirement this file's header explains (findPlatformManifests) means these were
    // already reachable this way before the index itself was ever pushed.
    const getAmd64ByDigest = await rawGetManifest(
      layout.repoName,
      admin,
      layout.image,
      amd64.manifestDigest,
    );
    expect(getAmd64ByDigest.status, 'the amd64 child is independently pullable by digest').toBe(
      200,
    );
    expect(sha256Hex(getAmd64ByDigest.body)).toBe(amd64.manifestDigest.slice('sha256:'.length));
    const getArm64ByDigest = await rawGetManifest(
      layout.repoName,
      admin,
      layout.image,
      arm64.manifestDigest,
    );
    expect(getArm64ByDigest.status, 'the arm64 child is independently pullable by digest').toBe(
      200,
    );
    expect(sha256Hex(getArm64ByDigest.body)).toBe(arm64.manifestDigest.slice('sha256:'.length));

    // A --platform-scoped real pull of the INDEX resolves to the right child, proven by the pulled
    // config's own architecture/os -- not merely "some manifest the index happens to reference".
    const pulledArm64Dir = path.join(work, 'pulled-arm64');
    const pullArm64 = await run(
      'crane',
      ['pull', '--platform=linux/arm64', '--format=oci', indexRef, pulledArm64Dir],
      { cwd: work, env: craneEnv(home), timeoutMs: 60_000, label: 'docker-multiarch-pull-arm64' },
    );
    expect(pullArm64.exitCode, `crane pull --platform=linux/arm64: ${pullArm64.command}`).toBe(0);
    const arm64Platform = await pulledPlatform(pulledArm64Dir);
    expect(arm64Platform, 'a linux/arm64-scoped pull resolves to the ARM64 child').toEqual({
      os: 'linux',
      architecture: 'arm64',
    });

    const pulledAmd64Dir = path.join(work, 'pulled-amd64');
    const pullAmd64 = await run(
      'crane',
      ['pull', '--platform=linux/amd64', '--format=oci', indexRef, pulledAmd64Dir],
      { cwd: work, env: craneEnv(home), timeoutMs: 60_000, label: 'docker-multiarch-pull-amd64' },
    );
    expect(pullAmd64.exitCode, `crane pull --platform=linux/amd64: ${pullAmd64.command}`).toBe(0);
    const amd64Platform = await pulledPlatform(pulledAmd64Dir);
    expect(amd64Platform, 'a linux/amd64-scoped pull resolves to the AMD64 child').toEqual({
      os: 'linux',
      architecture: 'amd64',
    });
  },
);

test(
  'docker > crane index append --docker-empty-base produces a Docker-family manifest list, also servable',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'multiarchdocker');
    const { home, work } = await isolatedWorkDir(`docker-multiarch-dkr-${seeder.runId}`);
    await renderDockerConfig(home, layout.credential);

    const amd64 = await pushPlatformImage(
      layout,
      home,
      work,
      'amd64',
      'linux',
      'amd64',
      'multiarch-dkr-amd64',
    );
    const arm64 = await pushPlatformImage(
      layout,
      home,
      work,
      'arm64',
      'linux',
      'arm64',
      'multiarch-dkr-arm64',
    );

    const indexTag = dockerAdapter.version('release');
    const indexRef = imageRef(layout.repoName, layout.image, indexTag);
    const amd64Ref = imageRef(layout.repoName, layout.image, 'amd64');
    const arm64Ref = imageRef(layout.repoName, layout.image, 'arm64');

    // --docker-empty-base: crane's own flag to build the Docker manifest-list media type instead of
    // its default OCI image-index one -- exercises AbstractDockerProtocolTxFacade.saveManifest's
    // OTHER matching case (DOCKER_MANIFEST_LIST), not just OCI_IMAGE_INDEX (the first test above).
    const appendResult = await run(
      'crane',
      ['index', 'append', '--docker-empty-base', '-m', amd64Ref, '-m', arm64Ref, '-t', indexRef],
      {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 60_000,
        label: 'docker-multiarch-index-append-docker',
      },
    );
    expect(
      appendResult.exitCode,
      `crane index append --docker-empty-base: ${appendResult.command}`,
    ).toBe(0);

    const admin = adminCredential();
    const rawIndexRes = await rawGetManifest(layout.repoName, admin, layout.image, indexTag);
    expect(rawIndexRes.status, 'the docker-family index is servable by tag').toBe(200);
    expect(
      rawIndexRes.contentType?.split(';')[0],
      'served with the Docker manifest-list content type',
    ).toBe('application/vnd.docker.distribution.manifest.list.v2+json');
    const parsed = JSON.parse(rawIndexRes.body.toString('utf8')) as {
      manifests: { digest: string; platform?: { architecture: string } }[];
    };
    expect(parsed.manifests, 'both platform entries are present').toHaveLength(2);
    expect(
      parsed.manifests.map((m) => m.digest).sort(),
      'the two children are exactly the earlier two pushes, no more, no fewer',
    ).toEqual([amd64.manifestDigest, arm64.manifestDigest].sort());
  },
);
