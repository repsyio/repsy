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
 * What `oras` (the OCI artifact client) does against a Repsy Docker repo (RPS-1478 part C). Every
 * behaviour was probed live with oras v1.3.4 before it was pinned.
 *
 *  - OR1 `oras push` of an OCI artifact (a file layer with its own media type, an `artifactType`, the
 *    empty-JSON config) is stored as sent (raw manifest, `oras manifest fetch`, `oras blob fetch`) and
 *    `oras pull` writes the identical bytes, by tag and by digest; `--config` with a custom config media
 *    type works and becomes the artifact type; an absent tag fails.
 *  - OR2 `oras attach` (an SBOM-like blob) to an image. The referrers API is not implemented (RA3, `404
 *    NAME_UNKNOWN`), and oras does NOT probe it for an attach: the `201` of the referrer's manifest PUT
 *    carries no `OCI-Subject` header, which oras-go reads as "no referrers API" and takes the spec's
 *    FALLBACK, the referrers TAG schema: it reads `GET manifests/sha256-<subject hex>` (`404` the first
 *    time) and `PUT`s an OCI image index under that tag listing the referrer. The tag is stored like any
 *    other (RA4): readable through raw HTTP and through `crane` byte-identically.
 *  - OR3 `oras discover` is where the missing API bites: it asks `GET .../referrers/<digest>`, gets `404`
 *    WITH the `NAME_UNKNOWN` code, and oras-go reads that code as "the repository does not exist" (not as
 *    "API unsupported"), so it FAILS instead of falling back. `--distribution-spec v1.1-referrers-tag`
 *    (the tag schema forced) works: `--format json` lists every referrer, `--artifact-type` filters, a
 *    read-write deploy token can attach, a read-only one is refused and changes nothing.
 *  - OR4 `oras copy` between two repos of one Repsy: the artifact arrives byte-identical (the mount attempt
 *    is answered with the `202` fallback, RA5, and oras uploads the bytes); `-r` (referrers) fails like
 *    `discover` unless both sides are forced to the tag schema, which then copies the referrer AND its
 *    index tag.
 *  - OR5 deletes: `oras manifest delete` of a tag reference deletes the manifest by digest (every tag of
 *    it goes), asked with the `delete` scope up front (oras adds the scope hint of its own delete command,
 *    seen at the token endpoint through a logging proxy; no `insufficient_scope` round trip, unlike
 *    crane/regctl), refused for a read-write deploy token. Deleting a REFERRER fails on the same
 *    referrers-API probe unless the tag schema is forced, which then also removes its entry from the index.
 *  - OR6 (RPS-1490, `test.fail`) a manifest whose config digest is also one of its layer digests, or
 *    that lists the same layer twice, is answered `404 MANIFEST_BLOB_UNKNOWN / layerNotFound` on PUT.
 *    That is what `oras push` and `oras attach` produce when they have no files (`{}` as the config AND
 *    the one layer), so an SBOM-less annotation attach and an empty artifact cannot be stored.
 *  - OR7 `oras repo tags` / `repo ls` fail on the missing `tags/list` / `_catalog` (RA1/RA2, RPS-1489).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import {
  expectSameImage,
  newDockerRepo,
  tokenCredential,
} from '../../src/clients/docker-client-tests.js';
import { secretsOf } from '../../src/clients/docker-copy-adapter.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { openOrasSession, REFERRERS_TAG, type OrasSession } from '../../src/clients/docker-oras.js';
import {
  adminCredential,
  imageRef,
  rawGetBlob,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  rawPutManifest,
  rawReferrers,
  rawUploadBlob,
  registryHost,
  sha256Hex,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const OCI_MANIFEST = 'application/vnd.oci.image.manifest.v1+json';
const OCI_INDEX = 'application/vnd.oci.image.index.v1+json';
const EMPTY_MEDIA_TYPE = 'application/vnd.oci.empty.v1+json';
/** The digest of `{}`, the OCI "empty descriptor" oras uses as a config and as the layer of a file-less push. */
const EMPTY_DIGEST = `sha256:${sha256Hex(Buffer.from('{}'))}`;
const CRANE_INSECURE = env.insecureRegistry ? ['--insecure'] : [];

interface Descriptor {
  mediaType: string;
  digest: string;
  size: number;
  artifactType?: string;
  annotations?: Record<string, string>;
}
interface ArtifactManifest {
  mediaType: string;
  artifactType?: string;
  config: Descriptor;
  layers: Descriptor[];
  subject?: Descriptor;
  annotations?: Record<string, string>;
}
interface ImageIndex {
  mediaType: string;
  manifests: Descriptor[];
}

const asManifest = (body: Buffer) => JSON.parse(body.toString('utf8')) as ArtifactManifest;
const asIndex = (body: Buffer) => JSON.parse(body.toString('utf8')) as ImageIndex;

/** The `Digest: sha256:...` line oras prints after a push, attach, pull or tag. */
function digestOf(stdout: string): string {
  const match = /^Digest: (sha\d+:[0-9a-f]+)$/m.exec(stdout);
  expect(match, `oras printed no Digest line:\n${stdout}`).not.toBeNull();
  return (match as RegExpExecArray)[1] as string;
}

/** The referrers-tag-schema tag of `digest`: `sha256-<hex>`. */
function referrersTag(digest: string): string {
  return digest.replace(':', '-');
}

async function writeWork(session: OrasSession, name: string, content: string): Promise<void> {
  await fs.writeFile(path.join(session.work, name), content, 'utf8');
}

/** An image in the registry, pushed by `crane` (a real container image, not an artifact). */
async function pushImage(
  repoName: string,
  image: string,
  tag: string,
  marker: string,
): Promise<string> {
  const admin = adminCredential();
  const { home, work } = await isolatedWorkDir(`docker-oras-image-${marker}`);
  await renderDockerConfig(home, admin);
  const built = await buildImage({ dir: path.join(work, 'image'), marker });
  const pushed = await run(
    'crane',
    ['push', built.dir, imageRef(repoName, image, tag), ...CRANE_INSECURE],
    {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 120_000,
      redact: secretsOf(admin),
      label: `oras-image-push-${marker}`,
    },
  );
  expect(pushed.exitCode, `crane push: ${pushed.stderr}`).toBe(0);
  return built.manifestDigest;
}

/** `oras attach`, returning the referrer's digest. */
async function attach(
  session: OrasSession,
  ref: string,
  artifactType: string,
  file: string,
  mediaType: string,
): Promise<string> {
  const attached = await session.run(
    ['attach', ...session.common, '--artifact-type', artifactType, ref, `${file}:${mediaType}`],
    `oras-attach-${artifactType}`,
  );
  expect(attached.exitCode, `oras attach: ${attached.command}\n${attached.stderr}`).toBe(0);
  return digestOf(attached.stdout);
}

/** The referrers tag of `subject` as the admin's raw GET reads it. */
async function rawReferrersIndex(repoName: string, image: string, subject: string) {
  return rawGetManifest(repoName, adminCredential(), image, referrersTag(subject));
}

test(
  'docker > oras push of an OCI artifact and pull give the identical bytes (OR1)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasart`;
    const ref = imageRef(repoName, image, 'v1');
    const admin = adminCredential();
    const session = await openOrasSession(admin, `docker-oras-or1-${seeder.runId}`);
    const content = `oras artifact ${seeder.runId}\nline two\n`;
    await writeWork(session, 'report.txt', content);

    const layerType = 'application/vnd.e2e.report.layer.v1+txt';
    const artifactType = 'application/vnd.e2e.report.v1';
    const pushed = await session.run(
      ['push', ...session.common, '--artifact-type', artifactType, ref, `report.txt:${layerType}`],
      'or1-push',
    );
    expect(pushed.exitCode, `oras push: ${pushed.command}\n${pushed.stderr}`).toBe(0);
    const digest = digestOf(pushed.stdout);

    // Stored as sent: the raw manifest hashes to the digest oras printed and says what oras built.
    const stored = await rawGetManifest(repoName, admin, image, 'v1');
    expect(stored.status).toBe(200);
    expect(`sha256:${sha256Hex(stored.body)}`, 'the manifest hashes to the digest').toBe(digest);
    expect(stored.digestHeader).toBe(digest);
    expect(stored.contentType).toBe(OCI_MANIFEST);
    const manifest = asManifest(stored.body);
    expect(manifest.artifactType, 'the artifact type').toBe(artifactType);
    expect(manifest.config.mediaType, 'the empty-JSON config').toBe(EMPTY_MEDIA_TYPE);
    expect(manifest.config.digest).toBe(EMPTY_DIGEST);
    expect(manifest.layers).toHaveLength(1);
    const layer = manifest.layers[0] as Descriptor;
    expect(layer.mediaType, 'the custom layer media type').toBe(layerType);
    expect(layer.digest).toBe(`sha256:${sha256Hex(Buffer.from(content))}`);
    expect(layer.annotations?.['org.opencontainers.image.title'], 'the file name').toBe(
      'report.txt',
    );
    expect(
      (await rawHeadBlob(repoName, admin, image, layer.digest)).status,
      'the layer blob is stored',
    ).toBe(200);
    expect((await rawGetBlob(repoName, admin, image, layer.digest)).body.toString('utf8')).toBe(
      content,
    );

    // manifest fetch: the same bytes, and the descriptor form.
    const fetched = path.join(session.work, 'fetched.json');
    const fetch = await session.run(
      ['manifest', 'fetch', ...session.common, '--output', fetched, ref],
      'or1-manifest-fetch',
    );
    expect(fetch.exitCode, `oras manifest fetch: ${fetch.stderr}`).toBe(0);
    expect(
      (await fs.readFile(fetched)).equals(stored.body),
      'manifest fetch is byte-identical',
    ).toBe(true);
    const descriptor = await session.run(
      ['manifest', 'fetch', ...session.common, '--descriptor', ref],
      'or1-manifest-descriptor',
    );
    expect(JSON.parse(descriptor.stdout)).toEqual({
      mediaType: OCI_MANIFEST,
      digest,
      size: stored.body.length,
    });

    // pull: by tag and by digest, the file is identical.
    for (const [what, pullRef] of [
      ['tag', ref],
      ['digest', `${registryHost()}/${repoPath(repoName)}/${image}@${digest}`],
    ] as const) {
      const out = path.join(session.work, `pulled-${what}`);
      const pulled = await session.run(
        ['pull', ...session.common, '--output', out, pullRef],
        `or1-pull-${what}`,
      );
      expect(pulled.exitCode, `oras pull by ${what}: ${pulled.stderr}`).toBe(0);
      expect(digestOf(pulled.stdout), `pull by ${what} names the manifest`).toBe(digest);
      expect(
        await fs.readFile(path.join(out, 'report.txt'), 'utf8'),
        `pull by ${what}: identical bytes`,
      ).toBe(content);
    }

    // blob fetch by digest.
    const blobOut = path.join(session.work, 'blob.txt');
    const blob = await session.run(
      [
        'blob',
        'fetch',
        ...session.common,
        '--output',
        blobOut,
        `${registryHost()}/${repoPath(repoName)}/${image}@${layer.digest}`,
      ],
      'or1-blob-fetch',
    );
    expect(blob.exitCode, `oras blob fetch: ${blob.stderr}`).toBe(0);
    expect(await fs.readFile(blobOut, 'utf8')).toBe(content);

    // An absent tag fails.
    const missing = await session.run(
      [
        'pull',
        ...session.common,
        '--output',
        path.join(session.work, 'none'),
        imageRef(repoName, image, 'nope'),
      ],
      'or1-pull-missing',
    );
    expect(missing.exitCode, 'pull of an absent tag').not.toBe(0);
    expect(missing.stderr.toLowerCase()).toContain('not found');
  },
);

test(
  'docker > oras push --config with a custom config media type stores the config and takes it as the artifact type (OR1b)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orascfg`;
    const ref = imageRef(repoName, image, 'v1');
    const admin = adminCredential();
    const session = await openOrasSession(admin, `docker-oras-or1b-${seeder.runId}`);
    const config = JSON.stringify({ name: 'e2e', run: seeder.runId });
    await writeWork(session, 'config.json', config);
    await writeWork(session, 'data.bin', `data ${seeder.runId}`);
    const configType = 'application/vnd.e2e.config.v1+json';

    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--config',
        `config.json:${configType}`,
        ref,
        'data.bin:application/octet-stream',
      ],
      'or1b-push',
    );
    expect(pushed.exitCode, `oras push --config: ${pushed.command}\n${pushed.stderr}`).toBe(0);
    const stored = asManifest((await rawGetManifest(repoName, admin, image, 'v1')).body);
    expect(stored.config.mediaType).toBe(configType);
    expect(stored.config.digest).toBe(`sha256:${sha256Hex(Buffer.from(config))}`);
    expect(
      (await rawGetBlob(repoName, admin, image, stored.config.digest)).body.toString('utf8'),
      'the config blob is stored',
    ).toBe(config);
    expect(pushed.stdout, 'oras reports the config media type as the artifact type').toContain(
      `ArtifactType: ${configType}`,
    );
  },
);

test(
  'docker > oras attach takes the referrers tag-schema fallback and the index tag reads back through raw and crane (OR2)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasattach`;
    const admin = adminCredential();
    const ref = imageRef(repoName, image, 'v1');
    const subject = await pushImage(repoName, image, 'v1', 'or2');
    const session = await openOrasSession(admin, `docker-oras-or2-${seeder.runId}`);
    const sbom = JSON.stringify({ bomFormat: 'CycloneDX', specVersion: '1.6', run: seeder.runId });
    await writeWork(session, 'sbom.cdx.json', sbom);
    const sbomType = 'application/vnd.cyclonedx+json';

    // Before the attach: the API is absent (RA3) and so is the tag.
    expect((await rawReferrers(repoName, admin, image, subject)).status, 'RA3').toBe(404);
    expect((await rawReferrersIndex(repoName, image, subject)).status, 'no index tag yet').toBe(
      404,
    );

    const referrer = await attach(session, ref, sbomType, 'sbom.cdx.json', sbomType);

    // The referrer manifest: an OCI artifact manifest pointing at the image by `subject`.
    const stored = await rawGetManifest(repoName, admin, image, referrer);
    expect(stored.status, 'the referrer manifest is stored under its digest').toBe(200);
    expect(`sha256:${sha256Hex(stored.body)}`).toBe(referrer);
    const manifest = asManifest(stored.body);
    expect(manifest.subject?.digest, 'subject is the image').toBe(subject);
    expect(manifest.subject?.mediaType, "subject carries the image's own media type").toBe(
      (await rawGetManifest(repoName, admin, image, 'v1')).contentType,
    );
    expect(manifest.artifactType).toBe(sbomType);
    expect(manifest.config.digest, 'empty-JSON config').toBe(EMPTY_DIGEST);
    expect(manifest.layers).toHaveLength(1);
    expect((manifest.layers[0] as Descriptor).digest).toBe(
      `sha256:${sha256Hex(Buffer.from(sbom))}`,
    );

    // The API is still absent: oras did not need it for an attach.
    expect((await rawReferrers(repoName, admin, image, subject)).status, 'RA3 still').toBe(404);

    // The fallback: the tag `sha256-<hex>` now holds an image index listing the referrer.
    const index = await rawReferrersIndex(repoName, image, subject);
    expect(index.status, 'the referrers tag exists').toBe(200);
    expect(index.contentType).toBe(OCI_INDEX);
    const parsed = asIndex(index.body);
    expect(parsed.mediaType).toBe(OCI_INDEX);
    expect(parsed.manifests, 'one referrer listed').toHaveLength(1);
    const entry = parsed.manifests[0] as Descriptor;
    expect(entry.digest).toBe(referrer);
    expect(entry.mediaType).toBe(OCI_MANIFEST);
    expect(entry.artifactType).toBe(sbomType);
    expect(entry.size).toBe(stored.body.length);

    // crane reads the very same index bytes.
    const { home, work } = await isolatedWorkDir(`docker-oras-or2-crane-${seeder.runId}`);
    await renderDockerConfig(home, admin);
    const craneManifest = await run(
      'crane',
      [
        'manifest',
        `${registryHost()}/${repoPath(repoName)}/${image}:${referrersTag(subject)}`,
        ...CRANE_INSECURE,
      ],
      {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 60_000,
        redact: secretsOf(admin),
        label: 'or2-crane-manifest',
      },
    );
    expect(craneManifest.exitCode, `crane manifest: ${craneManifest.stderr}`).toBe(0);
    expect(Buffer.from(craneManifest.stdout, 'utf8').equals(index.body), 'crane: same bytes').toBe(
      true,
    );

    // The image itself is untouched.
    expect((await rawGetManifest(repoName, admin, image, 'v1')).digestHeader).toBe(subject);
  },
);

test(
  'docker > oras discover needs the tag schema forced, the API 404 is read as a missing repository (OR3)',
  { tag: ['@oras', '@auth'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasdiscover`;
    const admin = adminCredential();
    const ref = imageRef(repoName, image, 'v1');
    const subject = await pushImage(repoName, image, 'v1', 'or3');
    const session = await openOrasSession(admin, `docker-oras-or3-${seeder.runId}`);
    await writeWork(session, 'sbom.json', `{"sbom":"${seeder.runId}"}`);
    const sbomRef = await attach(
      session,
      ref,
      'application/vnd.e2e.sbom',
      'sbom.json',
      'application/json',
    );

    // A read-write deploy token attaches a second referrer through the same fallback.
    const rw = await tokenCredential(seeder, repoName, false);
    const rwSession = await openOrasSession(rw, `docker-oras-or3-rw-${seeder.runId}`);
    await writeWork(rwSession, 'sig.json', `{"sig":"${seeder.runId}"}`);
    const sigRef = await attach(
      rwSession,
      ref,
      'application/vnd.e2e.signature',
      'sig.json',
      'application/json',
    );

    // The read-only one is refused at the manifest PUT and the index does not change.
    const ro = await tokenCredential(seeder, repoName, true);
    const roSession = await openOrasSession(ro, `docker-oras-or3-ro-${seeder.runId}`);
    await writeWork(roSession, 'x.json', '{}');
    const refused = await roSession.run(
      [
        'attach',
        ...roSession.common,
        '--artifact-type',
        'application/vnd.e2e.refused',
        ref,
        'x.json:application/json',
      ],
      'or3-ro-attach',
    );
    expect(refused.exitCode, 'a read-only token cannot attach').not.toBe(0);
    expect(refused.stderr.toLowerCase()).toContain('unauthorized');
    const index = asIndex((await rawReferrersIndex(repoName, image, subject)).body);
    expect(
      index.manifests.map((m) => m.digest).sort(),
      'the index lists exactly the two attached referrers',
    ).toEqual([sbomRef, sigRef].sort());

    // discover as it is: the referrers API 404 (NAME_UNKNOWN) is a failure, not a fallback.
    const plain = await session.run(['discover', ...session.common, ref], 'or3-discover');
    expect(plain.exitCode, 'oras discover without the tag schema').not.toBe(0);
    expect(plain.stderr.toLowerCase(), "the registry's own answer").toContain('unknownpath');

    // Forced to the tag schema it lists both, as JSON and filtered.
    const json = await session.run(
      [
        'discover',
        ...session.common,
        '--distribution-spec',
        REFERRERS_TAG,
        '--format',
        'json',
        ref,
      ],
      'or3-discover-json',
    );
    expect(json.exitCode, `oras discover (tag schema): ${json.stderr}`).toBe(0);
    const listed = JSON.parse(json.stdout) as {
      digest: string;
      referrers: { digest: string; artifactType: string }[];
    };
    expect(listed.digest, 'the discovered subject').toBe(subject);
    expect(
      listed.referrers.map((r) => [r.artifactType, r.digest]).sort(),
      'both referrers with their artifact types',
    ).toEqual(
      [
        ['application/vnd.e2e.sbom', sbomRef],
        ['application/vnd.e2e.signature', sigRef],
      ].sort(),
    );

    const filtered = await session.run(
      [
        'discover',
        ...session.common,
        '--distribution-spec',
        REFERRERS_TAG,
        '--artifact-type',
        'application/vnd.e2e.signature',
        '--format',
        'json',
        ref,
      ],
      'or3-discover-filtered',
    );
    expect(filtered.exitCode, `oras discover --artifact-type: ${filtered.stderr}`).toBe(0);
    expect(
      (JSON.parse(filtered.stdout) as { referrers: { digest: string }[] }).referrers.map(
        (r) => r.digest,
      ),
      'only the signature',
    ).toEqual([sigRef]);
  },
);

test(
  'docker > oras copy between two repos uploads the bytes after the mount fallback, -r needs the tag schema on both sides (OR4)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    const from = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-orascp` };
    const to = { repoName: await newDockerRepo(seeder), image: `e2e-${seeder.runId}-orascp2` };
    const admin = adminCredential();
    const session = await openOrasSession(admin, `docker-oras-or4-${seeder.runId}`);
    await writeWork(session, 'a.txt', `artifact ${seeder.runId}`);
    await writeWork(session, 'sbom.json', `{"sbom":"${seeder.runId}"}`);
    const fromRef = imageRef(from.repoName, from.image, 'v1');
    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.copy',
        fromRef,
        'a.txt:text/plain',
      ],
      'or4-push',
    );
    expect(pushed.exitCode, `oras push: ${pushed.stderr}`).toBe(0);
    const digest = digestOf(pushed.stdout);
    const referrer = await attach(
      session,
      fromRef,
      'application/vnd.e2e.sbom',
      'sbom.json',
      'application/json',
    );

    // Plain copy (debug on): a mount is asked for, Repsy answers 202, oras uploads.
    const copied = await session.run(
      ['copy', '--debug', ...session.copyCommon, fromRef, imageRef(to.repoName, to.image, 'v1')],
      'or4-copy',
    );
    expect(copied.exitCode, `oras copy: ${copied.command}\n${copied.stderr}`).toBe(0);
    expect(copied.stderr, 'oras asked for a blob mount first (RA5)').toContain(
      'blobs/uploads/?from=',
    );
    expect(await expectSameImage(from, to, 'v1', 'oras copy across repos')).toBe(digest);
    expect(
      (await rawReferrersIndex(to.repoName, to.image, digest)).status,
      'a plain copy leaves the referrers behind',
    ).toBe(404);

    // -r asks the referrers API of the source: it fails, and nothing is written to the destination.
    const other = imageRef(to.repoName, to.image, 'v2');
    const recursive = await session.run(
      ['copy', '--recursive', ...session.copyCommon, fromRef, other],
      'or4-copy-recursive',
    );
    expect(recursive.exitCode, 'oras copy -r without the tag schema').not.toBe(0);
    expect(recursive.stderr.toLowerCase()).toContain('unknownpath');
    expect((await rawHeadManifest(to.repoName, adminCredential(), to.image, 'v2')).status).toBe(
      404,
    );

    // -r with both sides on the tag schema copies the referrer and rebuilds its index tag.
    const forced = await session.run(
      [
        'copy',
        '--recursive',
        '--from-distribution-spec',
        REFERRERS_TAG,
        '--to-distribution-spec',
        REFERRERS_TAG,
        ...session.copyCommon,
        fromRef,
        other,
      ],
      'or4-copy-recursive-tag',
    );
    expect(forced.exitCode, `oras copy -r (tag schema): ${forced.command}\n${forced.stderr}`).toBe(
      0,
    );
    expect(
      (await rawGetManifest(to.repoName, admin, to.image, referrer)).status,
      'the referrer manifest is in the destination',
    ).toBe(200);
    const index = asIndex((await rawReferrersIndex(to.repoName, to.image, digest)).body);
    expect(
      index.manifests.map((m) => m.digest),
      'the destination index lists it',
    ).toEqual([referrer]);
  },
);

test(
  'docker > oras manifest delete deletes the manifest with every tag, needs MANAGE, and deleting a referrer needs the tag schema (OR5)',
  { tag: ['@oras', '@auth'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasdelete`;
    const admin = adminCredential();
    const session = await openOrasSession(admin, `docker-oras-or5-${seeder.runId}`);
    await writeWork(session, 'a.txt', `artifact ${seeder.runId}`);
    await writeWork(session, 'sbom.json', `{"sbom":"${seeder.runId}"}`);
    await writeWork(session, 'sig.json', `{"sig":"${seeder.runId}"}`);
    const ref = imageRef(repoName, image, 'v1');
    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.delete',
        ref,
        'a.txt:text/plain',
      ],
      'or5-push',
    );
    expect(pushed.exitCode, `oras push: ${pushed.stderr}`).toBe(0);
    const digest = digestOf(pushed.stdout);
    const tagged = await session.run(['tag', ...session.common, ref, 'v2'], 'or5-tag');
    expect(tagged.exitCode, `oras tag: ${tagged.stderr}`).toBe(0);
    expect((await rawHeadManifest(repoName, admin, image, 'v2')).digestHeader).toBe(digest);

    // A read-write deploy token is refused, nothing goes.
    const rw = await tokenCredential(seeder, repoName, false);
    const rwSession = await openOrasSession(rw, `docker-oras-or5-rw-${seeder.runId}`);
    const refused = await rwSession.run(
      ['manifest', 'delete', ...rwSession.common, '--force', ref],
      'or5-rw-delete',
    );
    expect(refused.exitCode, 'oras manifest delete with a deploy token').not.toBe(0);
    expect(refused.stderr.toLowerCase()).toContain('unauthorized');
    expect((await rawHeadManifest(repoName, admin, image, 'v1')).status, 'v1 stays').toBe(200);
    expect((await rawHeadManifest(repoName, admin, image, 'v2')).status, 'v2 stays').toBe(200);

    // Referrers: deleting one fails on the referrers-API probe, works with the tag schema and updates the index.
    const sbom = await attach(
      session,
      ref,
      'application/vnd.e2e.sbom',
      'sbom.json',
      'application/json',
    );
    const sig = await attach(
      session,
      ref,
      'application/vnd.e2e.signature',
      'sig.json',
      'application/json',
    );
    const sbomRef = `${registryHost()}/${repoPath(repoName)}/${image}@${sbom}`;
    const plain = await session.run(
      ['manifest', 'delete', ...session.common, '--force', sbomRef],
      'or5-delete-referrer',
    );
    expect(plain.exitCode, 'deleting a referrer without the tag schema').not.toBe(0);
    expect(plain.stderr.toLowerCase()).toContain('unknownpath');
    expect((await rawHeadManifest(repoName, admin, image, sbom)).status, 'the referrer stays').toBe(
      200,
    );

    const forced = await session.run(
      [
        'manifest',
        'delete',
        ...session.common,
        '--distribution-spec',
        REFERRERS_TAG,
        '--force',
        sbomRef,
      ],
      'or5-delete-referrer-tag',
    );
    expect(forced.exitCode, `oras manifest delete (tag schema): ${forced.stderr}`).toBe(0);
    expect(
      (await rawHeadManifest(repoName, admin, image, sbom)).status,
      'the referrer is gone',
    ).toBe(404);
    expect(
      asIndex((await rawReferrersIndex(repoName, image, digest)).body).manifests.map(
        (m) => m.digest,
      ),
      'and so is its entry in the referrers index',
    ).toEqual([sig]);

    // The admin, by tag reference: the manifest goes by digest, so both tags go with it.
    const deleted = await session.run(
      ['manifest', 'delete', ...session.common, '--force', ref],
      'or5-delete',
    );
    expect(deleted.exitCode, `oras manifest delete: ${deleted.stderr}`).toBe(0);
    expect((await rawHeadManifest(repoName, admin, image, digest)).status, 'by digest').toBe(404);
    expect((await rawHeadManifest(repoName, admin, image, 'v1')).status, 'by tag v1').toBe(404);
    expect((await rawHeadManifest(repoName, admin, image, 'v2')).status, 'by tag v2').toBe(404);
  },
);

// RPS-1490: a manifest that names the same blob digest twice (config == a layer, or two equal layers)
// is answered 404 MANIFEST_BLOB_UNKNOWN / layerNotFound on PUT although every blob is stored.
const RPS_1490 =
  'RPS-1490: PUT manifests/<ref> of a manifest whose config digest equals a layer digest (or with a repeated layer) answers 404 MANIFEST_BLOB_UNKNOWN / layerNotFound';

test(
  'docker > oras push without files stores the empty artifact (OR6a, RPS-1490)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    test.fail(true, RPS_1490);
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasempty`;
    const session = await openOrasSession(adminCredential(), `docker-oras-or6a-${seeder.runId}`);
    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.empty',
        imageRef(repoName, image, 'v1'),
      ],
      'or6a-push',
    );
    expect(pushed.exitCode, `oras push (no files): ${pushed.command}\n${pushed.stderr}`).toBe(0);
    expect((await rawHeadManifest(repoName, adminCredential(), image, 'v1')).status).toBe(200);
  },
);

test(
  'docker > oras attach with only an annotation stores the referrer (OR6b, RPS-1490)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    test.fail(true, RPS_1490);
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasannot`;
    const ref = imageRef(repoName, image, 'v1');
    const subject = await pushImage(repoName, image, 'v1', 'or6b');
    const session = await openOrasSession(adminCredential(), `docker-oras-or6b-${seeder.runId}`);
    const attached = await session.run(
      [
        'attach',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.note',
        '--annotation',
        'e2e.note=hello',
        ref,
      ],
      'or6b-attach',
    );
    expect(
      attached.exitCode,
      `oras attach (annotation only): ${attached.command}\n${attached.stderr}`,
    ).toBe(0);
    expect(
      asIndex((await rawReferrersIndex(repoName, image, subject)).body).manifests,
      'the referrer is listed',
    ).toHaveLength(1);
  },
);

test(
  'docker > oras push of two files with identical bytes stores the artifact (OR6c, RPS-1490)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    test.fail(true, RPS_1490);
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasdup`;
    const session = await openOrasSession(adminCredential(), `docker-oras-or6c-${seeder.runId}`);
    await writeWork(session, 'one.txt', 'same bytes');
    await writeWork(session, 'two.txt', 'same bytes');
    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.dup',
        imageRef(repoName, image, 'v1'),
        'one.txt:text/plain',
        'two.txt:text/plain',
      ],
      'or6c-push',
    );
    expect(
      pushed.exitCode,
      `oras push (two equal layers): ${pushed.command}\n${pushed.stderr}`,
    ).toBe(0);
    expect((await rawHeadManifest(repoName, adminCredential(), image, 'v1')).status).toBe(200);
  },
);

test(
  'docker > a raw manifest whose config digest equals its only layer digest is stored (OR6d, RPS-1490)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    test.fail(true, RPS_1490);
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orasraw`;
    const admin = adminCredential();
    const blob = Buffer.from('{}');
    const upload = await rawUploadBlob(repoName, admin, image, blob, EMPTY_DIGEST);
    expect(upload.status, 'the {} blob upload').toBe(201);
    const manifest = Buffer.from(
      JSON.stringify({
        schemaVersion: 2,
        mediaType: OCI_MANIFEST,
        artifactType: 'application/vnd.e2e.raw',
        config: { mediaType: EMPTY_MEDIA_TYPE, digest: EMPTY_DIGEST, size: blob.length },
        layers: [{ mediaType: EMPTY_MEDIA_TYPE, digest: EMPTY_DIGEST, size: blob.length }],
      }),
    );
    const put = await rawPutManifest(repoName, admin, image, 'v1', manifest, OCI_MANIFEST);
    expect(
      put.status,
      `PUT manifests/v1 (config == layer == ${EMPTY_DIGEST}): ${put.body.toString('utf8')}`,
    ).toBe(201);
  },
);

test(
  'docker > oras repo tags and repo ls fail on the missing tags/list and _catalog (OR7, RPS-1489)',
  { tag: ['@oras'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-orastags`;
    const session = await openOrasSession(adminCredential(), `docker-oras-or7-${seeder.runId}`);
    await writeWork(session, 'a.txt', 'a');
    const pushed = await session.run(
      [
        'push',
        ...session.common,
        '--artifact-type',
        'application/vnd.e2e.tags',
        imageRef(repoName, image, 'v1'),
        'a.txt:text/plain',
      ],
      'or7-push',
    );
    expect(pushed.exitCode, `oras push: ${pushed.stderr}`).toBe(0);
    for (const [what, args] of [
      [
        'repo tags',
        ['repo', 'tags', ...session.common, `${registryHost()}/${repoPath(repoName)}/${image}`],
      ],
      ['repo ls', ['repo', 'ls', ...session.common, `${registryHost()}/${repoPath(repoName)}`]],
    ] as const) {
      const result = await session.run([...args], `or7-${what}`);
      expect(
        result.exitCode,
        `oras ${what} cannot list: no tags/list or _catalog route (RPS-1489)`,
      ).not.toBe(0);
      expect(result.stderr.toLowerCase(), `oras ${what}: the registry's own answer`).toContain(
        'unknownpath',
      );
    }
  },
);
