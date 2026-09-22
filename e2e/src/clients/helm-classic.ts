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
 * The Helm CLASSIC (ChartMuseum-protocol) client adapter (step 4b, RPS-294): `publish` runs the
 * real `helm cm-push` (the chartmuseum/helm-push plugin, v0.11.1, installed at build time into the
 * shared `HELM_PLUGINS` dir -- `runners/helm.Dockerfile`) against a hand-assembled chart `.tgz`
 * (`helm-chart.ts`'s `buildChart`); `resolve` runs the real `helm pull --repo <url> ...`.
 *
 * **Confirmed live, a genuine deviation from this story's own plan**: unlike every other real
 * client in this harness (`crane push`, `dotnet nuget push`, `cargo publish`'s raw probe, and
 * `helm push`'s own OCI layer, all of which send a file's bytes VERBATIM), `helm cm-push` does
 * **NOT** send the `.tgz` argument's bytes as-is. `cmd/helm-cm-push/main.go`'s `push()` always calls
 * `helm.GetChartByName` (`loader.Load`, works the same for a directory or an existing `.tgz`) and
 * then `helm.CreateChartPackage` (`chartutil.Save`) to build a FRESH package in a temp dir, which is
 * what actually gets POSTed -- so the server-stored bytes for a `cm-push` are a REPACKAGED chart,
 * never byte-identical to the file this adapter built (different tar entry order/gzip header, same
 * logical content). This means `contentSha256` can NOT be computed ahead of time from the adapter's
 * own built bytes, unlike every sibling adapter:
 *
 *  - `publish()` still runs the real `helm cm-push` (so its own exit code is genuinely pinned), but
 *    follows it with a byte-identical raw multipart re-POST of the SAME `.tgz` this adapter built
 *    (`helm-raw.ts`'s `rawUploadChart`, hitting `POST /api/<repo>/charts` -- the exact route
 *    `cm-push` itself builds, confirmed live) with the SAME credential: every `ok`-expected scenario
 *    runs with `allowOverride: true` (the fixture default), so this re-POST is an accepted,
 *    identical replacement that OVERWRITES whatever `cm-push` just repackaged and stored -- the
 *    classic push handler's `update` path re-stores the file verbatim (confirmed live: a raw
 *    multipart POST's bytes round-trip exactly), so after an `ok` outcome the truly-stored bytes are
 *    once again exactly `sha256(tgzBytes)`, the same pattern `docker.ts`/`nuget.ts` use for their own
 *    re-PUT/re-POST. `no-override` gets the same `409` `cm-push` itself gets (confirmed live, and
 *    confirmed that `cm-push --force` does NOT bypass it server-side -- see "H-C2" and
 *    `helm-raw.ts`'s file header); every auth failure gets the same `401`.
 *  - `seedPublish()` (the fixture's own pre-publish, real client only, no raw companion -- mirrors
 *    every sibling adapter's `seedPublish`) can NOT trust `sha256(tgzBytes)` for the SAME reason:
 *    nothing overwrites `cm-push`'s own repackaged bytes afterwards. It therefore reads back what
 *    is ACTUALLY stored (an admin `rawDownloadChart` right after a successful `cm-push`) and hashes
 *    THAT, so `SeedResult.contentSha256` -- what `expectResolvedContent`/`afterSuccessfulRoundTrip`
 *    compare a consumer's download against for every "the pre-published chart, not this scenario's
 *    own doomed publish" case -- is always correct regardless of the repackaging.
 *
 * `resolve`'s `Outcome` is derived from a raw `GET index.yaml` instead (mirrors every other
 * adapter's "a metadata read never touches the possibly-troublesome artifact bytes" reasoning).
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { expect } from '@playwright/test';

import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';
import { helmEnv } from './helm.js';
import { buildChart, writeChartFile, type BuiltChart } from './helm-chart.js';
import {
  adminCredential,
  chartName as rawChartName,
  chartFileName,
  classicRepoUrl,
  indexEntry,
  parseIndex,
  rawDownloadChart,
  rawGetIndex,
  rawUploadChart,
  sha256Hex,
} from './helm-raw.js';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

/** `--username <u> --password <secret>` for a Basic-transport credential, nothing for
 *  `anonymous` -- `cm-push` has no separate api-key concept (unlike nuget), only Basic or
 *  `--access-token` (a raw deploy token via the Bearer path, pinned literally by "C1"). */
function credentialArgs(credential: MaterializedCredential): string[] {
  if (credential.transport !== 'basic') {
    return [];
  }
  return ['--username', credential.username ?? '', '--password', credential.password ?? ''];
}

interface PublishRun {
  exitCode: number;
  command: string;
  built: BuiltChart;
  tgzFile: string;
}

/** Builds a fresh chart and runs the real `helm cm-push <tgz> <repoUrl>` with `world.credential`,
 *  by URL (no `helm repo add`/`HELM_REPOSITORY_CONFIG` needed for this route -- see this file's
 *  header and "C1" for the by-NAME flow through the repositories file). */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: chart, version } = world.publishTarget;

  const marker = randomUUID();
  const built = await buildChart({ name: chart, version, marker });
  const tgzFile = await writeChartFile(work, built);

  const secrets = [world.credential.password].filter((s): s is string => Boolean(s));
  const execResult = await run(
    'helm',
    ['cm-push', tgzFile, classicRepoUrl(world.repoName), ...credentialArgs(world.credential)],
    {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: secrets,
      label,
    },
  );

  return { exitCode: execResult.exitCode, command: execResult.command, built, tgzFile };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `helm-classic-publish-${world.scenario.id}`);
  const { packageName: chart, version } = world.publishTarget;
  const fileName = chartFileName(chart, version);

  // Byte-identical raw re-POST of the exact tgz this adapter built (see this file's header: NOT
  // what `cm-push` itself sent, which is a repackaged chart with different bytes) -- overwrites
  // whatever `cm-push` just stored when accepted, so the truly-stored bytes for an "ok" outcome are
  // once again exactly known.
  const rawRes = await rawUploadChart(
    world.repoName,
    world.credential,
    published.built.tgzBytes,
    fileName,
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
 * coordinate (`reuseCoordinates`). Only the real client runs (`world.credential` is already the
 * admin credential by the time this runs, `fixtures.ts`'s `world` factory). Reads back the
 * ACTUALLY stored bytes afterwards (see this file's header: `cm-push` repackages, so this
 * adapter's own built bytes are not what ends up stored).
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `helm-classic-seed-${world.scenario.id}`);
  if (published.exitCode !== 0) {
    throw new Error(
      `helm-classic adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(helm cm-push exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'chart actually existing.',
    );
  }
  const { packageName: chart, version } = world.publishTarget;
  const admin = adminCredential();
  const dl = await rawDownloadChart(world.repoName, admin, chart, version);
  if (dl.status !== 200) {
    throw new Error(
      `helm-classic adapter: seedPublish(): admin re-download of "${chart}"/"${version}" right ` +
        `after a successful cm-push answered ${dl.status} -- expected 200`,
    );
  }
  return { contentSha256: sha256Hex(dl.body) };
}

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
  const { home, work } = await isolatedWorkDir(`helm-classic-con-${world.scenario.id}`);
  const { packageName: chart, version } = world.consumeTarget;
  const pulledDir = path.join(work, 'pulled');
  // See helm.ts's resolve(): helm pull --destination does not create the directory itself.
  await fs.mkdir(pulledDir, { recursive: true });

  const secrets = world.credential.password ? [world.credential.password] : [];
  const execResult = await run(
    'helm',
    [
      'pull',
      '--repo',
      classicRepoUrl(world.repoName),
      chart,
      '--version',
      version,
      '--destination',
      pulledDir,
      ...credentialArgs(world.credential),
    ],
    {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `helm-classic-consume-${world.scenario.id}`,
    },
  );

  // The auth-only companion probe: a GET of index.yaml never touches the (possibly troublesome)
  // chart bytes themselves.
  const rawRes = await rawGetIndex(world.repoName, world.credential);
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

/** A snapshot of one chart VERSION's own content: the index.yaml entry (existence, digest, urls)
 *  plus the sha256 of what the classic download route actually serves. Scoped to the one
 *  coordinate a scenario's publish targets, exactly like every sibling adapter's fingerprint. */
export interface HelmClassicFingerprint {
  indexEntry?: { digest?: string; urls: string[] };
  contentSha256?: string;
}

async function fingerprint(world: World): Promise<HelmClassicFingerprint> {
  const admin = adminCredential();
  const { packageName: chart, version } = world.publishTarget;

  const indexRes = await rawGetIndex(world.repoName, admin);
  const entry =
    indexRes.status === 200
      ? indexEntry(parseIndex(indexRes.body.toString('utf8')), chart, version)
      : undefined;

  const dl = await rawDownloadChart(world.repoName, admin, chart, version);
  const contentSha256 = dl.status === 200 ? sha256Hex(dl.body) : `status:${dl.status}`;

  return {
    indexEntry: entry ? { digest: entry.digest, urls: entry.urls } : undefined,
    contentSha256,
  };
}

async function expectNothingStored(world: World, before: HelmClassicFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the chart exactly as it was').toEqual(before);

  if (before.indexEntry === undefined) {
    const admin = adminCredential();
    const { packageName: chart, version } = world.publishTarget;
    const dl = await rawDownloadChart(world.repoName, admin, chart, version);
    expect(dl.status, 'the refused version was never stored').toBe(404);
  }
}

/**
 * Runs only after both the publish and the consume of one scenario succeeded: the index carries an
 * entry for the published version whose `digest` names the expected content (a classic `update`
 * DOES refresh the digest -- unlike the OCI adapter's B-H2, this holds for an override too) and
 * whose `urls[0]` is the canonical relative download path; that path resolves to the expected
 * bytes; the pulled chart matches.
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

  const indexRes = await rawGetIndex(world.repoName, admin);
  expect(indexRes.status, 'index.yaml is served').toBe(200);
  const entry = indexEntry(parseIndex(indexRes.body.toString('utf8')), chart, version);
  expect(entry, `an index.yaml entry for "${chart}"/"${version}"`).toBeDefined();
  expect(entry?.digest, "the entry's digest names the expected content").toBe(
    `sha256:${expectedHex}`,
  );
  expect(entry?.urls[0], "the entry's relative download URL").toBe(
    `charts/${chartFileName(chart, version)}`,
  );

  const dlRes = await rawDownloadChart(world.repoName, admin, chart, version);
  expect(dlRes.status, 'the advertised chart URL resolves').toBe(200);
  expect(sha256Hex(dlRes.body), 'the served chart matches the expected content').toBe(expectedHex);

  expect(resolved.contentSha256, 'the pulled chart matches the served one').toBe(
    sha256Hex(dlRes.body),
  );
}

export const helmClassicAdapter: ProtocolAdapter<HelmClassicFingerprint> = {
  protocol: 'helm-classic',
  client: { name: 'helm', publishVerb: 'cm-push', consumeVerb: 'pull --repo' },

  packageName: (runId, scenario) => rawChartName(runId, scenario),
  version: () => boundedSemverVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
