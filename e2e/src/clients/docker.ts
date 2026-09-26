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
 * The Docker (Registry HTTP API V2 / OCI distribution) client adapter (step 4a, RPS-294):
 * `publish`/`resolve`/`seedPublish` build a tiny, hand-assembled OCI image layout
 * (`docker-image.ts`'s `buildImage` -- deliberately never `docker build`/`crane append`, see that
 * file's header) and run the real `crane` binary (go-containerregistry, daemonless) against it --
 * `crane push <oci-layout-dir> <ref>` / `crane pull --format=oci <ref> <dir>`, never `docker`/
 * `dockerd`: no daemon anywhere in this harness (see `runners/docker.Dockerfile`'s header for why
 * DinD/a host socket are rejected for this step).
 *
 * Docker's two-step publish (blobs, then the manifest) is entirely inside `publish()`/`seedPublish()`
 * -- the loop's single publish/consume pair still maps to one `crane push`/one `crane pull`, so
 * `scenarios/loop.ts` needs no change at all.
 *
 * `crane` hides the HTTP status behind its own exit code, exactly like every other real client in
 * this harness, so `publish`'s `Outcome` is derived from a raw-HTTP companion probe
 * (`docker-raw.ts`'s `rawPutManifest`) -- a BYTE-IDENTICAL re-PUT of the exact manifest `crane push`
 * just sent, under the SAME tag, with the SAME credential (the maven/npm/nuget re-PUT pattern, not
 * cargo's prerelease-sibling workaround): every catalog scenario but the settings pair runs with
 * `allowOverride: true` (the fixture default), so the re-PUT is an accepted, identical replacement;
 * `no-override` gets the same `403` the client got; every auth failure gets the same `401` -- at
 * whichever hop (`dockerRequest`'s `hop: 'token' | 'request'`) a real client would have failed at
 * too (confirmed live: a read-only/other-repo token's own token-EXCHANGE still succeeds, 200; only
 * the WRITE request itself is refused, `401` -- see `docker-raw.ts`'s file header).
 *
 * `resolve`'s `Outcome` is derived from a raw manifest GET instead (mirrors npm's packument-GET/
 * cargo's sparse-index-GET/nuget's flat-version-list-GET reasoning): every consume expectation in
 * the catalog is an authn/authz outcome, and a manifest GET never touches the blob bytes themselves.
 *
 * Every publish/seed-publish packs a fresh random marker into the one layer, so two publishes of one
 * coordinate never share a manifest digest; `AdapterResult.contentSha256` is the bare-hex sha256 of
 * the MANIFEST bytes (the content address of the whole image: it covers the config/layer digests
 * transitively) -- `crane pull --format=oci` writes an `index.json` whose one descriptor's `digest`
 * is the sha256 of the raw manifest bytes it fetched, so "the consumer got the very image" is exactly
 * `published contentSha256 === resolved contentSha256` plus (`resolve`'s own check) the resolved
 * manifest blob's OWN sha256 matching its filename.
 *
 * Credential mapping (`renderDockerConfig`, one rendered `DOCKER_CONFIG/config.json` per invocation,
 * `src/packages/docker/config.template.json`): a Basic-transport credential (both `password`- and
 * `token`-kind: the token endpoint tries the password value as a deploy token first, then as a user
 * password -- one code path, `docker-raw.ts`'s file header) renders one `auths["<registryHost>"]`
 * entry with a base64 `user:secret` `auth` value, exactly what `crane auth login --password-stdin`
 * itself writes (pinned literally by the "D2" real-client test, `tests/docker/publish-consume.spec
 * .ts`). `anonymous` renders `{"auths":{}}` -- no credentials at all, matching a real `docker
 * login`-less invocation.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import { buildImage, DOCKER_MEDIA_TYPES } from './docker-image.js';
import {
  adminCredential,
  imageName as rawImageName,
  imageRef,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  rawPutManifest,
  registryHost,
  sha256Hex,
} from './docker-raw.js';
import { env } from '../env.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/docker');

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/**
 * `DOCKER_CONFIG/config.json` in `home`, rendered fresh per invocation -- never a machine-wide
 * config, and never a real `crane auth login` except in the one dedicated test that pins that flow
 * literally (see this file's header). `anonymous` (no Basic transport at all) writes `{"auths":{}}`
 * directly rather than through the template: the template's own JSON has to stay valid, unrendered,
 * for `prettier` (every mustache placeholder sits inside an already-quoted string value, the same
 * shape every other protocol's `*.template.json` uses), so a conditionally-PRESENT entry is a code
 * decision, not a mustache section. Exported for `tests/docker/publish-consume.spec.ts`.
 */
export async function renderDockerConfig(
  home: string,
  credential: MaterializedCredential,
): Promise<string> {
  const dockerDir = path.join(home, '.docker');
  await fs.mkdir(dockerDir, { recursive: true });
  const cfgPath = path.join(dockerDir, 'config.json');

  if (credential.transport !== 'basic') {
    await fs.writeFile(cfgPath, JSON.stringify({ auths: {} }), 'utf8');
    return cfgPath;
  }

  const authBase64 = Buffer.from(
    `${credential.username ?? ''}:${credential.password ?? ''}`,
  ).toString('base64');
  await renderTemplate('config.template.json', cfgPath, {
    registryHost: registryHost(),
    authBase64,
  });
  return cfgPath;
}

/** Every env var isolating one `crane` invocation from the host and from every other invocation
 *  (parallel Playwright workers, or publish vs. consume in the same test): a private `HOME`/
 *  `DOCKER_CONFIG`, nothing else -- `crane` is a static, daemonless binary with no other state. */
export function craneEnv(home: string): NodeJS.ProcessEnv {
  return clientEnv(home, { DOCKER_CONFIG: path.join(home, '.docker') });
}

/** `--insecure` iff `REPSY_E2E_INSECURE_REGISTRY` is set (a remote plain-HTTP host): ggcr already
 *  resolves `localhost`/loopback/RFC1918 hosts as plain HTTP on its own (`pkg/name/registry.go`'s
 *  `Scheme()`), confirmed live against this harness's own `localhost:9090` stack -- see README.md's
 *  "H1". */
function insecureFlag(): string[] {
  return env.insecureRegistry ? ['--insecure'] : [];
}

function stripSha256Prefix(digest: string): string {
  return digest.startsWith('sha256:') ? digest.slice('sha256:'.length) : digest;
}

interface PublishRun {
  exitCode: number;
  command: string;
  manifestBytes: Buffer;
  manifestDigest: string;
  manifestMediaType: string;
  marker: string;
}

/** Builds a fresh OCI layout (`buildImage`) and runs the real `crane push` with `world.credential`. */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: image, version: tag } = world.publishTarget;

  const marker = randomUUID();
  const built = await buildImage({ dir: path.join(work, 'image'), marker });
  await renderDockerConfig(home, world.credential);

  const ref = imageRef(world.repoName, image, tag);
  const secrets = [world.credential.password].filter((s): s is string => Boolean(s));

  const execResult = await run('crane', ['push', built.dir, ref, ...insecureFlag()], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  return {
    exitCode: execResult.exitCode,
    command: execResult.command,
    manifestBytes: built.manifestBytes,
    manifestDigest: built.manifestDigest,
    manifestMediaType: built.manifestMediaType,
    marker,
  };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `docker-publish-${world.scenario.id}`);
  const { packageName: image, version: tag } = world.publishTarget;

  // Byte-identical re-PUT of the exact manifest the client just pushed, under the same tag, with the
  // same credential (see this file's header): every `ok`-expected scenario runs with
  // `allowOverride: true` (the fixture default), so the re-PUT is an accepted, identical replacement;
  // `no-override` gets the same 403 the client got; every auth failure gets the same 401, at
  // whichever hop (token exchange or the operation itself) a real client would have failed at too.
  const rawRes = await rawPutManifest(
    world.repoName,
    world.credential,
    image,
    tag,
    published.manifestBytes,
    published.manifestMediaType,
  );

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: stripSha256Prefix(published.manifestDigest),
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
 * coordinate (`reuseCoordinates`). Only the real client runs, mirroring `clients/nuget.ts`'s
 * `seedPublish`; `world.credential` is already the admin credential by the time this runs
 * (`fixtures.ts`'s `world` factory substitutes it before calling this).
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `docker-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `docker adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(crane exit ${published.exitCode}); its "consume: ok" expectation depends on this image ` +
        'actually existing.',
    );
  }
  return { contentSha256: stripSha256Prefix(published.manifestDigest) };
}

/** The manifest blob `crane pull --format=oci` leaves in its OCI layout output directory, keyed off
 *  `index.json`'s one descriptor -- also verifies the blob's OWN sha256 matches the filename it is
 *  stored under, the same "prove the bytes are what they claim to be" step every other adapter's
 *  `find*`/`resolved` helper does. */
async function readResolvedImage(
  pulledDir: string,
): Promise<{ hex: string; file: string } | undefined> {
  let indexRaw: string;
  try {
    indexRaw = await fs.readFile(path.join(pulledDir, 'index.json'), 'utf8');
  } catch {
    return undefined;
  }
  const index = JSON.parse(indexRaw) as { manifests?: { digest?: unknown }[] };
  const digest = index.manifests?.[0]?.digest;
  if (typeof digest !== 'string' || !digest.startsWith('sha256:')) {
    return undefined;
  }
  const hex = stripSha256Prefix(digest);
  const relFile = path.join('pulled', 'blobs', 'sha256', hex);
  let bytes: Buffer;
  try {
    bytes = await fs.readFile(path.join(pulledDir, 'blobs', 'sha256', hex));
  } catch {
    return undefined;
  }
  if (sha256Hex(bytes) !== hex) {
    throw new Error(
      `docker adapter: resolved manifest blob's own sha256 does not match its own filename (${hex})`,
    );
  }
  return { hex, file: relFile };
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`docker-con-${world.scenario.id}`);
  const { packageName: image, version: tag } = world.consumeTarget;

  await renderDockerConfig(home, world.credential);
  const ref = imageRef(world.repoName, image, tag);
  const pulledDir = path.join(work, 'pulled');

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'crane',
    ['pull', '--format=oci', ref, pulledDir, ...insecureFlag()],
    {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `docker-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe (see this file's header): a manifest GET never touches the blob
  // bytes themselves.
  const rawRes = await rawGetManifest(world.repoName, world.credential, image, tag);
  const resolved = await readResolvedImage(pulledDir);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved?.hex,
    resolvedFile: resolved?.file,
  };
}

/** Every digest a manifest's `config`/`layers` entries name (`sha256:<hex>` each). */
function manifestBlobDigests(body: Buffer): string[] {
  const parsed = JSON.parse(body.toString('utf8')) as {
    config?: { digest?: unknown };
    layers?: { digest?: unknown }[];
  };
  const digests: string[] = [];
  if (typeof parsed.config?.digest === 'string') {
    digests.push(parsed.config.digest);
  }
  for (const layer of parsed.layers ?? []) {
    if (typeof layer.digest === 'string') {
      digests.push(layer.digest);
    }
  }
  return digests;
}

/** A snapshot of one TAG's own content, for `ProtocolAdapter.fingerprint`/`expectNothingStored`:
 *  Docker has no `tags/list`/`_catalog` route on Repsy (no handler exists for either), and the blobs
 *  of a REFUSED manifest push are stored by protocol design (blobs go up before the manifest, so a
 *  refused manifest never rolls them back -- confirmed live, README.md's "R6"), so "nothing changed
 *  for consumers of this tag" is the honest, tag-scoped invariant, not a whole-repo one. */
export interface DockerFingerprint {
  tagDigest?: string;
  manifestSha256?: string;
  blobs: Record<string, string>;
}

async function fingerprint(world: World): Promise<DockerFingerprint> {
  const admin = adminCredential();
  const { packageName: image, version: tag } = world.publishTarget;

  const res = await rawGetManifest(world.repoName, admin, image, tag);
  if (res.status === 404) {
    return { blobs: {} };
  }
  if (res.status !== 200) {
    throw new Error(
      `docker adapter: fingerprint(): GET manifest of "${image}:${tag}" answered ${res.status}`,
    );
  }

  const manifestSha256 = sha256Hex(res.body);
  const blobs: Record<string, string> = {};
  for (const digest of manifestBlobDigests(res.body)) {
    const headRes = await rawHeadBlob(world.repoName, admin, image, digest);
    blobs[digest] = headRes.status === 200 ? 'present' : `status:${headRes.status}`;
  }

  return { tagDigest: res.digestHeader, manifestSha256, blobs };
}

async function expectNothingStored(world: World, before: DockerFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the tag exactly as it was').toEqual(before);

  if (before.tagDigest === undefined) {
    const admin = adminCredential();
    const { packageName: image, version: tag } = world.publishTarget;
    const headRes = await rawHeadManifest(world.repoName, admin, image, tag);
    expect(headRes.status, 'the refused tag was never created (HEAD)').toBe(404);
    const getRes = await rawGetManifest(world.repoName, admin, image, tag);
    expect(getRes.status, 'the refused tag was never created (GET)').toBe(404);
  }
}

/**
 * Runs only after both the publish and the consume of one scenario succeeded (`scenarios/loop.ts`):
 * the published tag is servable by both tag and digest, HEAD agrees with GET, every blob the served
 * manifest names exists, and the pulled image matches the served manifest bytes exactly.
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const { packageName: image, version: tag } = world.consumeTarget;
  const expectedHex =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(
    expectedHex,
    'no digest of the published manifest to compare the resolved one with',
  ).toBeDefined();
  const expectedDigest = `sha256:${expectedHex}`;

  const getByTag = await rawGetManifest(world.repoName, admin, image, tag);
  expect(getByTag.status, 'the published tag is servable by tag').toBe(200);
  expect(getByTag.digestHeader, 'Docker-Content-Digest matches the published manifest').toBe(
    expectedDigest,
  );
  expect(sha256Hex(getByTag.body), 'the served manifest bytes match the published ones').toBe(
    expectedHex,
  );
  expect(
    getByTag.contentType?.split(';')[0],
    'Content-Type is the pushed manifest media type',
  ).toBe(DOCKER_MEDIA_TYPES.manifest);

  const getByDigest = await rawGetManifest(world.repoName, admin, image, expectedDigest);
  expect(getByDigest.status, 'pull by digest works').toBe(200);

  const headByTag = await rawHeadManifest(world.repoName, admin, image, tag);
  expect(headByTag.status, 'HEAD by tag works').toBe(200);
  expect(headByTag.digestHeader).toBe(expectedDigest);

  for (const digest of manifestBlobDigests(getByTag.body)) {
    const headBlob = await rawHeadBlob(world.repoName, admin, image, digest);
    expect(headBlob.status, `blob ${digest} named by the served manifest exists`).toBe(200);
  }

  expect(resolved.contentSha256, 'the pulled image matches the served manifest').toBe(expectedHex);
}

export const dockerAdapter: ProtocolAdapter<DockerFingerprint> = {
  protocol: 'docker',
  client: { name: 'crane', publishVerb: 'push', consumeVerb: 'pull' },

  packageName: (runId, scenario) => rawImageName(runId, scenario),
  // The TAG. `boundedSemverVersion()` (`0.<secs>.<seq>`) is well inside distribution/reference's tag
  // grammar (`[\w][\w.-]{0,127}`); `versionType` is ignored -- Docker has no release/snapshot concept.
  version: () => boundedSemverVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
