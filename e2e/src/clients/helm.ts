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
 * The Helm OCI client adapter (step 4b, RPS-294): `publish`/`resolve`/`seedPublish` build a tiny,
 * hand-assembled chart `.tgz` (`helm-chart.ts`'s `buildChart` -- never `helm package`, see that
 * file's header) and run the real `helm` binary (v4.3.0, no daemon of any kind: `push`/`pull`/
 * `registry login` are pure HTTP client commands) against it: `helm push <tgz> oci://<host>/<repo>
 * --plain-http` / `helm pull oci://<host>/<repo>/<chart> --version <v> --plain-http`.
 *
 * `--plain-http` is required unconditionally on this harness's own `http://localhost:9090` stack
 * (confirmed live, H3: unlike `crane`, Helm has NO localhost/loopback auto-detection --
 * `pkg/registry/client.go`'s `plainHTTP` is only ever set by the flag), derived from
 * `env.repoBaseUrl`'s own scheme, never from `REPSY_E2E_INSECURE_REGISTRY` (that instead maps to
 * `--insecure-skip-tls-verify`, for a remote HTTPS-with-a-bad-cert target).
 *
 * `crane`'s byte-identical re-PUT companion-probe pattern (`docker.ts`'s file header) applies
 * unchanged here: `helm push` reads the `.tgz` file's bytes verbatim for the chart layer
 * (`pkg/pusher/ocipusher.go`, confirmed live H2/H4), so `publish`'s raw-HTTP probe
 * (`helm-raw.ts`'s `rawPutManifest`) re-PUTs the exact manifest a real `helm push` would send
 * (built by hand here, from the same chart bytes/digests, rather than parsed back out of the
 * client's own output -- Helm has no `--format=oci`-style local manifest dump the way `crane push`
 * does) under the SAME tag, with the SAME credential: every `ok`-expected scenario runs with
 * `allowOverride: true` (the fixture default), so the re-PUT is an accepted, identical
 * replacement; `no-override` gets the same `409` a real push gets (confirmed live); every auth
 * failure gets the same `401` (single-hop Basic, no token hop to fail at -- unlike Docker).
 *
 * `resolve`'s `Outcome` is derived from a raw manifest GET instead (mirrors every other adapter's
 * "a metadata read never touches the possibly-troublesome artifact bytes" reasoning).
 *
 * Every publish/seed-publish packs a fresh random marker into the chart, so two publishes of one
 * coordinate never share content; `AdapterResult.contentSha256` is the sha256 of the WHOLE `.tgz`
 * file (what `helm pull` writes to disk verbatim in both modes, confirmed live H4/H17) -- not the
 * OCI layer/manifest digest.
 *
 * Credential mapping (`renderHelmRegistryConfig`, `packages/helm/registry-config.template.json`,
 * the Docker `config.json` `auths` shape `pkg/registry/client.go`'s `Login` itself writes,
 * confirmed live H5): a Basic-transport credential renders one `auths["<registryHost>"]` entry
 * with a base64 `user:secret` `auth` value; `anonymous` renders `{"auths":{}}`.
 *
 * **B-H4 (candidate)**, confirmed live and documented in `helm-raw.ts`'s file header: `helm
 * registry login` itself SUCCEEDS with a wrong password (a Docker-provider token-endpoint quirk
 * that surfaces through Helm's shared `/v2/` ping) -- this adapter therefore never treats a
 * successful `helm registry login` as proof of a correct credential; only the push/pull
 * request's own raw-HTTP-probed status decides `Outcome`, exactly like every other protocol here.
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
import { buildChart, writeChartFile, type BuiltChart } from './helm-chart.js';
import {
  adminCredential,
  chartName as rawChartName,
  chartFileName,
  HELM_MEDIA_TYPES,
  indexEntry,
  ociChartRef,
  ociRepoRef,
  parseIndex,
  rawGetBlob,
  rawGetIndex,
  rawGetManifest,
  rawHeadBlob,
  rawHeadManifest,
  rawPutManifest,
  rawUploadBlob,
  registryHost,
  sha256Hex,
} from './helm-raw.js';
import { env } from '../env.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/helm');

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
 * `HELM_REGISTRY_CONFIG` in `home`, rendered fresh per invocation -- never a machine-wide config
 * (see `helmEnv`). `anonymous` writes `{"auths":{}}` directly (the template's own JSON has to stay
 * valid, unrendered, for `prettier`, exactly like `docker.ts`'s `renderDockerConfig`). Exported for
 * `tests/helm/publish-consume.spec.ts`'s "HL1" real-`helm registry login` test.
 */
export async function renderHelmRegistryConfig(
  home: string,
  credential: MaterializedCredential,
): Promise<string> {
  const dir = path.join(home, 'config', 'registry');
  await fs.mkdir(dir, { recursive: true });
  const cfgPath = path.join(dir, 'config.json');

  if (credential.transport !== 'basic') {
    await fs.writeFile(cfgPath, JSON.stringify({ auths: {} }), 'utf8');
    return cfgPath;
  }

  const authBase64 = Buffer.from(
    `${credential.username ?? ''}:${credential.password ?? ''}`,
  ).toString('base64');
  await renderTemplate('registry-config.template.json', cfgPath, {
    registryHost: registryHost(),
    authBase64,
  });
  return cfgPath;
}

/** Every env var isolating one `helm` invocation from the host and from every other invocation
 *  (parallel Playwright workers, or publish vs. consume in the same test): a private `HOME` plus
 *  every `HELM_*` directory Helm 4 reads (see this story's plan section 4), and `HELM_PLUGINS`
 *  pointed at the shared, read-only, build-time-installed `cm-push` plugin dir (never installed
 *  per-invocation -- `runners/helm.Dockerfile`'s header). */
export function helmEnv(home: string): NodeJS.ProcessEnv {
  return clientEnv(home, {
    HELM_CACHE_HOME: path.join(home, 'cache'),
    HELM_CONFIG_HOME: path.join(home, 'config'),
    HELM_DATA_HOME: path.join(home, 'data'),
    HELM_REGISTRY_CONFIG: path.join(home, 'config', 'registry', 'config.json'),
    HELM_REPOSITORY_CONFIG: path.join(home, 'config', 'repositories.yaml'),
    HELM_REPOSITORY_CACHE: path.join(home, 'cache', 'repository'),
    HELM_PLUGINS: process.env.HELM_PLUGINS ?? '/opt/helm/plugins',
    HELM_COLOR: 'never',
    NO_COLOR: '1',
  });
}

/** `[]` on `http:` (always, this harness's own stack) or `['--plain-http']` -- derived from
 *  `env.repoBaseUrl`'s scheme (confirmed live H3: Helm has no localhost auto-detection, unlike
 *  `crane`). `REPSY_E2E_INSECURE_REGISTRY` maps to `--insecure-skip-tls-verify` instead, for a
 *  remote HTTPS target with a certificate Helm would otherwise refuse. */
export function plainHttpFlag(): string[] {
  return new URL(env.repoBaseUrl).protocol === 'http:' ? ['--plain-http'] : [];
}

function insecureTlsFlag(): string[] {
  return env.insecureRegistry ? ['--insecure-skip-tls-verify'] : [];
}

interface PublishRun {
  exitCode: number;
  command: string;
  built: BuiltChart;
  configBytes: Buffer;
  configDigest: string;
  manifestBytes: Buffer;
  manifestMediaType: string;
  marker: string;
}

/** The Helm OCI config blob: chart metadata JSON (`pkg/registry/client.go`'s `Push`), media type
 *  `application/vnd.cncf.helm.config.v1+json`. */
export function buildConfigBytes(built: BuiltChart): Buffer {
  return Buffer.from(
    JSON.stringify({
      name: built.name,
      version: built.version,
      apiVersion: 'v2',
      type: 'application',
    }),
    'utf8',
  );
}

/** The OCI manifest a real `helm push` sends for one chart layer, no provenance file (this harness
 *  never publishes a `.prov`) -- `schemaVersion: 2`, config + one chart-content layer, no
 *  annotations (Helm's own `created` annotation is intentionally left out: it would make this
 *  hand-built manifest byte-different from the real client's on every run for no benefit, since
 *  this adapter never compares the manifest bytes THEMSELVES -- only the tgz `contentSha256` and
 *  the outcome of the raw re-PUT this manifest fronts). */
export function buildManifestBytes(
  built: BuiltChart,
  configBytes: Buffer,
): { bytes: Buffer; digest: string } {
  const configDigest = `sha256:${sha256Hex(configBytes)}`;
  const manifestObj = {
    schemaVersion: 2,
    mediaType: HELM_MEDIA_TYPES.manifest,
    config: { mediaType: HELM_MEDIA_TYPES.config, digest: configDigest, size: configBytes.length },
    layers: [
      {
        mediaType: HELM_MEDIA_TYPES.layer,
        digest: built.tgzDigest,
        size: built.tgzBytes.length,
      },
    ],
  };
  const bytes = Buffer.from(JSON.stringify(manifestObj), 'utf8');
  return { bytes, digest: configDigest };
}

/** Builds a fresh chart and runs the real `helm push` with `world.credential`. */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: chart, version } = world.publishTarget;

  const marker = randomUUID();
  const built = await buildChart({ name: chart, version, marker });
  const tgzFile = await writeChartFile(work, built);
  await renderHelmRegistryConfig(home, world.credential);

  // `helm push` takes the REPO-level ref alone: it appends the chart NAME from the pushed
  // Chart.yaml's own metadata itself (`pkg/pusher/ocipusher.go`: `ref = path.Join(<host>/<repo>,
  // meta.Name) + ":" + meta.Version`) -- confirmed live: passing `ociChartRef` (repo + chart) here
  // duplicates the chart-name path segment and 404s every push. `helm pull` (`resolve`, below) is
  // the opposite: it has no local chart metadata to append a name from, so IT needs the full
  // `ociChartRef`.
  const ref = ociRepoRef(world.repoName);
  const secrets = [world.credential.password].filter((s): s is string => Boolean(s));

  const execResult = await run(
    'helm',
    ['push', tgzFile, ref, ...plainHttpFlag(), ...insecureTlsFlag()],
    {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: secrets,
      label,
    },
  );

  const configBytes = buildConfigBytes(built);
  const { bytes: manifestBytes, digest: configDigest } = buildManifestBytes(built, configBytes);

  return {
    exitCode: execResult.exitCode,
    command: execResult.command,
    built,
    configBytes,
    configDigest,
    manifestBytes,
    manifestMediaType: HELM_MEDIA_TYPES.manifest,
    marker,
  };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `helm-publish-${world.scenario.id}`);
  const { packageName: chart, version } = world.publishTarget;

  // The chart-layer blob is already in storage (the real `helm push` just uploaded it, keyed by
  // its digest, which this hand-built manifest also names -- see this file's header). The CONFIG
  // blob this hand-built manifest references is NOT what the real client uploaded (its own Helm
  // chart-metadata JSON differs from `buildConfigBytes`'s), and Repsy's own manifest-push handler
  // never reads it either way (confirmed live) -- but a real `helm pull`'s OWN client-side fetch
  // DOES resolve every blob a served manifest names, config included (oras's `Copy`), and 404s
  // otherwise (confirmed live: a manifest referencing a config digest nothing ever uploaded pulls
  // fine through Repsy's own push handler but then fails a real client's pull with "not found").
  // So this config blob is uploaded here, with the SAME credential, before the re-PUT that will
  // reference it -- otherwise every "ok"-expected scenario's own `resolve()` would break on a real
  // `helm pull`, for a reason that has nothing to do with what that scenario is testing.
  await rawUploadBlob(
    world.repoName,
    world.credential,
    chart,
    published.configBytes,
    published.configDigest,
  );

  // Byte-identical re-PUT of the manifest a real `helm push` would send, under the same tag, with
  // the same credential (see this file's header). This manifest PUT alone decides the outcome,
  // exactly as it did for the real client's own last request.
  const rawRes = await rawPutManifest(
    world.repoName,
    world.credential,
    chart,
    version,
    published.manifestBytes,
    published.manifestMediaType,
  );

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.built.tgzDigestHex,
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
 * coordinate (`reuseCoordinates`). Only the real client runs, mirroring `clients/docker.ts`'s
 * `seedPublish`; `world.credential` is already the admin credential by the time this runs.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `helm-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `helm adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(helm exit ${published.exitCode}); its "consume: ok" expectation depends on this chart ` +
        'actually existing.',
    );
  }
  return { contentSha256: published.built.tgzDigestHex };
}

/** The `.tgz` file `helm pull` leaves in its `--destination` directory. */
async function readPulledFile(
  destDir: string,
  chart: string,
  version: string,
): Promise<{ hex: string; file: string } | undefined> {
  const rel = chartFileName(chart, version);
  try {
    const bytes = await fs.readFile(path.join(destDir, rel));
    return { hex: sha256Hex(bytes), file: path.join('pulled', rel) };
  } catch {
    return undefined;
  }
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`helm-con-${world.scenario.id}`);
  const { packageName: chart, version } = world.consumeTarget;

  await renderHelmRegistryConfig(home, world.credential);
  const ref = ociChartRef(world.repoName, chart);
  const pulledDir = path.join(work, 'pulled');
  // Confirmed live: unlike `crane pull --format=oci` (docker.ts), `helm pull --destination` does
  // NOT create the destination directory itself -- it downloads successfully (prints "Pulled:"/
  // "Digest:") but then fails to persist the file into a destination that does not exist yet
  // ("open .../<file>.tgz<random>: no such file or directory"), exit 1 despite the success message.
  await fs.mkdir(pulledDir, { recursive: true });

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'helm',
    [
      'pull',
      ref,
      '--version',
      version,
      '--destination',
      pulledDir,
      ...plainHttpFlag(),
      ...insecureTlsFlag(),
    ],
    {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `helm-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe: a manifest GET never touches the (possibly troublesome) chart
  // bytes themselves.
  const rawRes = await rawGetManifest(world.repoName, world.credential, chart, version);
  const resolved = await readPulledFile(pulledDir, chart, version);

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved?.hex,
    resolvedFile: resolved?.file,
  };
}

/** A snapshot of one TAG's own content, for `ProtocolAdapter.fingerprint`/`expectNothingStored`:
 *  Helm has no `tags/list` route on Repsy (**B-H3**, confirmed live), so "nothing changed for
 *  consumers of this tag" is the honest, tag-scoped invariant, not a whole-repo one -- the same
 *  reasoning as `docker.ts`'s `DockerFingerprint`. */
export interface HelmFingerprint {
  manifestSha256?: string;
  digestHeader?: string;
  blobs: Record<string, string>;
  indexDigest?: string;
}

async function fingerprint(world: World): Promise<HelmFingerprint> {
  const admin = adminCredential();
  const { packageName: chart, version } = world.publishTarget;

  const res = await rawGetManifest(world.repoName, admin, chart, version);
  if (res.status === 404) {
    return { blobs: {} };
  }
  if (res.status !== 200) {
    throw new Error(
      `helm adapter: fingerprint(): GET manifest of "${chart}:${version}" answered ${res.status}`,
    );
  }

  const manifestSha256 = sha256Hex(res.body);
  const parsed = JSON.parse(res.body.toString('utf8')) as {
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
  const blobs: Record<string, string> = {};
  for (const digest of digests) {
    const headRes = await rawHeadBlob(world.repoName, admin, chart, digest);
    blobs[digest] = headRes.status === 200 ? 'present' : `status:${headRes.status}`;
  }

  const indexRes = await rawGetIndex(world.repoName, admin);
  const index = indexRes.status === 200 ? parseIndex(indexRes.body.toString('utf8')) : undefined;
  const entry = index ? indexEntry(index, chart, version) : undefined;

  return { manifestSha256, digestHeader: res.digestHeader, blobs, indexDigest: entry?.digest };
}

async function expectNothingStored(world: World, before: HelmFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the tag exactly as it was').toEqual(before);

  if (before.manifestSha256 === undefined) {
    const admin = adminCredential();
    const { packageName: chart, version } = world.publishTarget;
    const headRes = await rawHeadManifest(world.repoName, admin, chart, version);
    expect(headRes.status, 'the refused tag was never created (HEAD)').toBe(404);
    const getRes = await rawGetManifest(world.repoName, admin, chart, version);
    expect(getRes.status, 'the refused tag was never created (GET)').toBe(404);
  }
}

/**
 * Runs only after both the publish and the consume of one scenario succeeded: the published chart
 * is servable by tag, HEAD agrees with GET, every blob the served manifest names exists, the
 * classic index carries an entry naming the same relative `urls`, and the pulled chart matches the
 * served manifest's chart-layer digest exactly.
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const { packageName: chart, version } = world.consumeTarget;
  const expectedHex =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(
    expectedHex,
    'no digest of the published chart to compare the resolved one with',
  ).toBeDefined();

  const getByTag = await rawGetManifest(world.repoName, admin, chart, version);
  expect(getByTag.status, 'the published tag is servable by tag').toBe(200);
  expect(getByTag.digestHeader, 'Docker-Content-Digest matches the served manifest bytes').toBe(
    `sha256:${sha256Hex(getByTag.body)}`,
  );
  expect(getByTag.contentType?.split(';')[0], 'Content-Type is the OCI manifest media type').toBe(
    HELM_MEDIA_TYPES.manifest,
  );

  const headByTag = await rawHeadManifest(world.repoName, admin, chart, version);
  expect(headByTag.status, 'HEAD by tag works').toBe(200);

  const parsed = JSON.parse(getByTag.body.toString('utf8')) as {
    config?: { mediaType?: unknown; digest?: unknown };
    layers?: { mediaType?: unknown; digest?: unknown }[];
  };
  expect(parsed.config?.mediaType, 'config media type').toBe(HELM_MEDIA_TYPES.config);
  const layer = parsed.layers?.[0];
  expect(layer?.mediaType, 'chart layer media type').toBe(HELM_MEDIA_TYPES.layer);
  expect(layer?.digest, 'the served manifest names the expected chart layer digest').toBe(
    `sha256:${expectedHex}`,
  );

  const layerDigest = layer?.digest as string;
  const headBlob = await rawHeadBlob(world.repoName, admin, chart, layerDigest);
  expect(headBlob.status, 'the chart layer blob exists').toBe(200);
  const getBlob = await rawGetBlob(world.repoName, admin, chart, layerDigest);
  expect(getBlob.status).toBe(200);
  expect(sha256Hex(getBlob.body), 'the layer blob bytes match the expected chart digest').toBe(
    expectedHex,
  );

  const indexRes = await rawGetIndex(world.repoName, admin);
  expect(indexRes.status, 'the classic index is served for an OCI-pushed chart too (B-H1)').toBe(
    200,
  );
  const index = parseIndex(indexRes.body.toString('utf8'));
  const entry = indexEntry(index, chart, version);
  expect(entry, `index.yaml carries an entry for "${chart}"/"${version}"`).toBeDefined();
  expect(entry?.urls[0], "the entry's relative download URL").toBe(
    `charts/${chartFileName(chart, version)}`,
  );

  expect(resolved.contentSha256, 'the pulled chart matches the expected content').toBe(expectedHex);
}

export const helmAdapter: ProtocolAdapter<HelmFingerprint> = {
  protocol: 'helm',
  client: { name: 'helm', publishVerb: 'push', consumeVerb: 'pull' },

  packageName: (runId, scenario) => rawChartName(runId, scenario),
  // The chart version/OCI tag. `boundedSemverVersion()` (`0.<secs>.<seq>`) satisfies Repsy's
  // strict SemVer chart-version regex, Masterminds semver (Helm's own parser) and the OCI tag
  // grammar; `versionType` is ignored -- Helm has no release/snapshot concept.
  version: () => boundedSemverVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
