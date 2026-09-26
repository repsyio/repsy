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
 * The maven client adapter (plan section "Client adapters"): `publish`/`resolve` render a tiny
 * project from `src/packages/maven/*.template.xml` into an isolated work directory and run the real
 * `mvn` binary against it, exactly as `README.md`'s "Verify" section does by hand.
 *
 * `mvn` hides the HTTP status behind its own exit code (0/1), so the `Outcome` this module returns
 * is derived from a raw HTTP request this adapter also makes with the same credential (see
 * `rawPublishCheck`/`rawConsumeCheck`), not from that exit code; `clientExitCode` is corroborating
 * evidence (the spec asserts the two agree: a refused deploy must fail the real client, an accepted
 * one must not) and is attached to the test on failure by `clients/exec.ts`.
 *
 * What the server judges on a deploy (`ArtifactServiceImpl.checkDeploymentRules`, RPS-1174/RPS-1176),
 * which the raw probe below and the catalog's pinned statuses depend on: the release/snapshot
 * switches (`releases`/`snapshots`) refuse an upload of that version kind with 403 whether the
 * version is new or already exists; `allowOverride: false` refuses re-uploading an existing file
 * (403), never metadata; a checksum is judged by the file it belongs to (RPS-1183), and a
 * metadata checksum by its directory (`g/a/<X-SNAPSHOT>/` is a snapshot); the artifact-level and
 * group-level `maven-metadata.xml` carry no version kind and are not judged, while the
 * version-level snapshot metadata is judged by its own `<version>`. A real `mvn deploy` PUTs all artifact files first (pom, jar, each followed by
 * its checksums), then all metadata, and stops at the first refusal, so a refused deploy is refused
 * on its first file and leaves nothing behind.
 *
 * Two local Maven repositories are kept apart everywhere in this file:
 *  - `maven.repo.local` (primary, writable): a fresh, empty directory per `publish`/`resolve` call.
 *    `mvn deploy` also runs `install`, which copies the artifact into whatever local repo that
 *    invocation used; reusing one primary repo between a test's own `publish` and `resolve` would
 *    let resolution succeed from that local copy without ever asking the Repsy server, which is
 *    exactly the false pass this harness exists to catch.
 *  - `maven.repo.local.tail` (read-only fallback, shared): a named Docker volume
 *    (`MAVEN_SHARED_REPO_DIR`, see `docker-compose.runners.yml`) holding only third-party downloads
 *    (Maven's own core plugins and their dependencies) -- never this harness's own test artifacts,
 *    since those always live under the unique `io.repsy.e2e.<runid>` groupId a tail lookup never
 *    produces a hit for. Maven only *reads* a tail repo; it never writes a fresh download into one
 *    (see MNG-6976), so `ensureSharedCacheWarm` primes it once, the first time any test in a
 *    container's lifetime needs it (guarded by a marker file so concurrent workers and future runs
 *    against the same volume don't redo it).
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../env.js';
import type { AdapterResult } from '../scenarios/adapter.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import {
  authHeader,
  baseVersion,
  groupPath,
  isSnapshotVersion,
  rawPut,
  sha256Hex,
  snapshotTimestamp,
  splitPackageName,
} from './maven-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/maven');

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;
const WARM_TIMEOUT_MS = 180_000;

/** Maven's shared, third-party-only local-repository tail (see the file comment). */
export const SHARED_M2_DIR =
  process.env.MAVEN_SHARED_REPO_DIR ?? path.join(os.tmpdir(), 'repsy-e2e-maven-m2');
const WARM_MARKER = path.join(SHARED_M2_DIR, '.e2e-warm');

/** Re-exported so nothing importing `AdapterResult` from this module (its original home) breaks;
 *  the type itself now lives in `scenarios/adapter.ts`, generalised for every protocol. */
export type { AdapterResult };

/** The environment of every `mvn` invocation (this file's, `maven-plugin.ts`'s, `maven-signing.ts`'s
 *  and the specs'): `clientEnv` alone. The runner image sets `JAVA_HOME`, `MAVEN_HOME`, `GRADLE_HOME`,
 *  `SBT_HOME` and `ANT_HOME`, but the launchers find their JVM and install through `PATH` (probed
 *  live, RPS-1446: the whole maven project, Gradle, sbt, Ivy and gpg included, passes without any of
 *  them), so none is inherited. */
export function mavenEnv(home: string, extra: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return clientEnv(home, extra);
}

function credentialView(credential: MaterializedCredential): Record<string, unknown> {
  return {
    hasCredential: credential.transport === 'basic',
    username: credential.username ?? '',
    password: credential.password ?? '',
  };
}

async function renderTemplate(
  templateName: string,
  destPath: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  await fs.writeFile(destPath, mustache.render(template, view), 'utf8');
}

/** Standard Maven layout path of the artifact's POM, used only for the raw-HTTP publish check. */
function pomPath(groupId: string, artifactId: string, version: string): string {
  return `${groupPath(groupId)}/${artifactId}/${version}/${artifactId}-${version}.pom`;
}

let probeSeq = 0;

/**
 * The path of a POM a SNAPSHOT deploy could have uploaded, `g/a/<base>-SNAPSHOT/a-<base>-<ts>-<n>.pom`
 * with a timestamp of "now" and a build number no real deploy reaches (a deploy counts 1, 2, 3, ...
 * per version; this counts from 900001), so it is a *new* file every time: `allowOverride: false`
 * never sees it as an override, and only the release/snapshot rules can refuse it. mvn and Gradle
 * never PUT the literal `a-<base>-SNAPSHOT.pom` name, so it is no stand-in for what they send. sbt
 * and Ivy do send it: they publish a SNAPSHOT non-uniquely (RPS-134, RPS-135), and
 * `rawPublishCheck`'s `literalSnapshot` sends exactly that. It is not an override either
 * (RPS-1328: `allowOverride` leaves a non-unique snapshot alone), see `README.md`, "SNAPSHOT and
 * redeploy behaviour, as probed".
 */
function snapshotProbePomPath(groupId: string, artifactId: string, version: string): string {
  probeSeq += 1;
  const name = `${artifactId}-${baseVersion(version)}-${snapshotTimestamp()}-${900_000 + probeSeq}`;
  return `${groupPath(groupId)}/${artifactId}/${version}/${name}.pom`;
}

/**
 * A raw PUT of `pomBytes` to the path a deploy of these coordinates would start with: the release
 * POM for a RELEASE, a fresh timestamped POM (see `snapshotProbePomPath`) for a SNAPSHOT. Both are
 * the first file of a real deploy, and `checkDeploymentRules` classifies release vs. snapshot from
 * the filename it is given, so this pins the exact status the refusing rule answers with, without
 * replicating Maven's own snapshot timestamp negotiation. When the deploy was accepted this PUT is
 * accepted too and stores one harmless extra POM (it never touches metadata, so it changes nothing
 * a consumer resolves); when the deploy was refused, so is this, and nothing is written.
 * `Content-Type: application/octet-stream` is required: Repsy's POM parser reads the body as a
 * stream, and a PUT with no content type (curl's default for a raw body is
 * `application/x-www-form-urlencoded`) gets it consumed as form data first, which this harness found
 * out the hard way surfaces as an unrelated `malformedPomFile` 400 instead of the real status.
 * With `literalSnapshot` a SNAPSHOT is probed under its literal name, `a-<base>-SNAPSHOT.pom`, the
 * file a non-unique deploy (sbt, Ivy) sends. Like the timestamped one it is never refused by
 * `allowOverride: false` (RPS-1328), so only the release/snapshot rules can refuse it.
 */
export async function rawPublishCheck(
  world: World,
  pomBytes: Buffer,
  options: { literalSnapshot?: boolean } = {},
): Promise<number> {
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const version = world.publishTarget.version;
  const relPath =
    isSnapshotVersion(version) && !options.literalSnapshot
      ? snapshotProbePomPath(groupId, artifactId, version)
      : pomPath(groupId, artifactId, version);

  const res = await rawPut(
    world.repoName,
    world.credential,
    relPath,
    pomBytes,
    'application/octet-stream',
  );
  return res.status;
}

/**
 * A raw GET of the repo root, not of the resolved artifact's own path: every consume expectation in
 * the catalog depends only on authentication/authorization (never on override/release/snapshot
 * rules, which are write-only), so the repo root is an equally valid proxy for the same authz
 * decision `dependency:get` gets -- and, unlike the artifact's own path, it does not require
 * replicating Maven's server-negotiated timestamped SNAPSHOT filename to find the right path to GET.
 */
export async function rawConsumeCheck(world: World): Promise<number> {
  const url = `${env.repoBaseUrl}/${world.repoName}/`;
  return withBackoff429(async () => {
    const res = await fetch(url, { headers: authHeader(world.credential) });
    await res.arrayBuffer().catch(() => undefined);
    return res.status;
  });
}

let warmPromise: Promise<void> | undefined;

/**
 * Primes `SHARED_M2_DIR` with Maven's own core plugins (compiler/jar/install/deploy/dependency),
 * once per container lifetime (or ever, for the volume itself, once its marker file exists). Guarded
 * by an exclusive-create of `WARM_MARKER`: the worker that wins the race warms the cache, every
 * other worker (in this run or a later one, since `SHARED_M2_DIR` is a named volume that outlives a
 * single `run.sh test`) sees the marker and moves on, at worst falling back to an uncached download
 * for whichever of its own tests races ahead of the warm-up finishing. Best-effort: a warm-up
 * failure only means slower (not broken) runs, so it never fails a test.
 */
async function ensureSharedCacheWarm(): Promise<void> {
  warmPromise ??= (async () => {
    await fs.mkdir(SHARED_M2_DIR, { recursive: true });

    let handle;
    try {
      handle = await fs.open(WARM_MARKER, 'wx');
    } catch {
      return; // Already warmed (or another worker is warming it right now).
    }
    await handle.close();

    try {
      const { home, work } = await isolatedWorkDir('mvn-warm');
      await renderTemplate('pom.template.xml', path.join(work, 'pom.xml'), {
        groupId: 'io.repsy.e2e.warm',
        artifactId: 'warm',
        version: '0.0.0',
        withDistribution: false,
      });

      await run('mvn', ['-B', '-ntp', `-Dmaven.repo.local=${SHARED_M2_DIR}`, 'package'], {
        cwd: work,
        env: mavenEnv(home),
        timeoutMs: WARM_TIMEOUT_MS,
        label: 'maven-warm-package',
      });
      // Best-effort: also prime maven-dependency-plugin itself, which `resolve` needs and `package`
      // above does not touch. A real, stable, small Central artifact; its own resolution result is
      // not asserted on, only the plugin download it forces as a side effect.
      await run(
        'mvn',
        [
          '-B',
          '-ntp',
          `-Dmaven.repo.local=${SHARED_M2_DIR}`,
          'dependency:get',
          '-Dartifact=org.apache.commons:commons-lang3:3.14.0',
        ],
        {
          cwd: work,
          env: mavenEnv(home),
          timeoutMs: WARM_TIMEOUT_MS,
          label: 'maven-warm-dependency-plugin',
        },
      );
    } catch (err) {
      console.warn('maven adapter: shared-cache warm-up failed (non-fatal):', err);
    }
  })();

  return warmPromise;
}

interface DeployRun {
  exitCode: number;
  command: string;
  /** sha256 of the jar `mvn` built (see `AdapterResult.contentSha256`). */
  contentSha256?: string;
  /** The POM the deploy was built from, which the raw probe re-sends. */
  pomBytes: Buffer;
  /** stdout and stderr of the `mvn deploy`, for the message it prints. */
  output: string;
}

/**
 * Runs the real `mvn deploy` of `world.publishTarget` with `world.credential` and nothing else. The
 * built jar carries a per-deploy random marker (a resource file), so the jar's digest identifies
 * exactly this deploy: without it two deploys of the same coordinate would usually build identical
 * bytes and "the consumer got the latest deploy" could not be told from "the consumer got the first".
 */
export async function deploy(
  world: World,
  /** `pomPadding`: text for a `<description>` that makes the POM as big as a test needs (the size
   *  limits of `tests/maven/size-limits.spec.ts`, RPS-1482). */
  opts: { pomPadding?: string } = {},
): Promise<DeployRun> {
  await ensureSharedCacheWarm();

  const { home, work } = await isolatedWorkDir(`mvn-pub-${world.scenario.id}`);
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const version = world.publishTarget.version;
  const repoUrl = `${env.repoBaseUrl}/${world.repoName}`;

  await renderTemplate('pom.template.xml', path.join(work, 'pom.xml'), {
    groupId,
    artifactId,
    version,
    withDistribution: true,
    repoUrl,
    pomPadding: opts.pomPadding,
  });
  await renderTemplate(
    'settings.template.xml',
    path.join(work, 'settings.xml'),
    credentialView(world.credential),
  );

  const resourcesDir = path.join(work, 'src', 'main', 'resources');
  await fs.mkdir(resourcesDir, { recursive: true });
  await fs.writeFile(path.join(resourcesDir, 'e2e-deploy-marker.txt'), randomUUID(), 'utf8');

  const localRepo = path.join(home, 'repo-local');
  await fs.mkdir(localRepo, { recursive: true });

  const secrets = world.credential.password ? [world.credential.password] : [];

  const execResult = await run(
    'mvn',
    [
      '-B',
      '-ntp',
      'deploy',
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: secrets,
      label: `maven-publish-${world.scenario.id}`,
    },
  );

  return {
    exitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: await digestOf(path.join(work, 'target', `${artifactId}-${version}.jar`)),
    pomBytes: await fs.readFile(path.join(work, 'pom.xml')),
    output: `${execResult.stdout}\n${execResult.stderr}`,
  };
}

/** sha256 of a file's content, or `undefined` when it does not exist. */
export async function digestOf(file: string): Promise<string | undefined> {
  try {
    return sha256Hex(await fs.readFile(file));
  } catch {
    return undefined;
  }
}

export async function publish(world: World): Promise<AdapterResult> {
  const deployed = await deploy(world);
  const httpStatus = await rawPublishCheck(world, deployed.pomBytes);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: deployed.exitCode,
    command: deployed.command,
    contentSha256: deployed.contentSha256,
  };
}

/** One `mvn dependency:get` of `requestedVersion` of the world's consume target, in a clean local repo. */
async function dependencyGet(
  world: World,
  requestedVersion: string,
): Promise<{
  execResult: Awaited<ReturnType<typeof run>>;
  localRepo: string;
  groupId: string;
  artifactId: string;
}> {
  await ensureSharedCacheWarm();

  const { home, work } = await isolatedWorkDir(`mvn-con-${world.scenario.id}`);
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const repoUrl = `${env.repoBaseUrl}/${world.repoName}`;

  // A minimal, source-free consumer project: dependency:get resolves the explicit -Dartifact below,
  // so this pom declares no dependency of its own and names no repository -- it exists only so
  // `mvn` has a project to run in.
  await renderTemplate('pom.template.xml', path.join(work, 'pom.xml'), {
    groupId: `${groupId}.consumer`,
    artifactId: `${artifactId}-consumer`,
    version: '0.0.0',
    withDistribution: false,
  });
  await renderTemplate(
    'settings.template.xml',
    path.join(work, 'settings.xml'),
    credentialView(world.credential),
  );

  const localRepo = path.join(home, 'repo-local');
  await fs.mkdir(localRepo, { recursive: true });

  const secrets = world.credential.password ? [world.credential.password] : [];

  const execResult = await run(
    'mvn',
    [
      '-B',
      '-ntp',
      'dependency:get',
      `-Dartifact=${groupId}:${artifactId}:${requestedVersion}:jar`,
      `-DremoteRepositories=repsy::default::${repoUrl}`,
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `maven-consume-${world.scenario.id}`,
    },
  );

  return { execResult, localRepo, groupId, artifactId };
}

export async function resolve(world: World): Promise<AdapterResult> {
  const version = world.consumeTarget.version;
  const { execResult, localRepo, groupId, artifactId } = await dependencyGet(world, version);

  const httpStatus = await rawConsumeCheck(world);
  const resolved = await findResolvedJar(localRepo, groupId, artifactId, version);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256: resolved?.sha256,
    resolvedFile: resolved?.file,
  };
}

/**
 * `dependency:get` of a version that is not a fixed one (`LATEST`, `RELEASE`, a range): which version
 * it picked is not known up front, so this reports the versions whose jar the clean local repository
 * holds afterwards (one, when the resolve worked), instead of the jar of a version the caller names.
 */
export async function resolveDynamic(
  world: World,
  requestedVersion: string,
): Promise<{ clientExitCode: number; command: string; output: string; versions: string[] }> {
  const { execResult, localRepo, groupId, artifactId } = await dependencyGet(
    world,
    requestedVersion,
  );

  const artifactDirectory = path.join(localRepo, groupPath(groupId), artifactId);
  const names = await fs.readdir(artifactDirectory).catch(() => [] as string[]);
  const versions: string[] = [];
  for (const name of names) {
    if (await digestOf(path.join(artifactDirectory, name, `${artifactId}-${name}.jar`))) {
      versions.push(name);
    }
  }

  return {
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    output: `${execResult.stdout}\n${execResult.stderr}`,
    versions: versions.sort(),
  };
}

/**
 * The jar `dependency:get` left in the clean local repository. A SNAPSHOT is resolved through the
 * version-level `maven-metadata.xml` to a timestamped file (`a-1.0-20260921.101010-2.jar`), which
 * Maven also copies to the literal `a-1.0-SNAPSHOT.jar`; only the timestamped one proves the
 * metadata was followed, so that is the one reported. Nothing is reported when none is there.
 */
async function findResolvedJar(
  localRepo: string,
  groupId: string,
  artifactId: string,
  version: string,
): Promise<{ file: string; sha256: string } | undefined> {
  const dir = path.join(localRepo, groupPath(groupId), artifactId, version);
  let names: string[];
  try {
    names = await fs.readdir(dir);
  } catch {
    return undefined;
  }

  const wanted = isSnapshotVersion(version)
    ? new RegExp(
        `^${escapeRegExp(`${artifactId}-${baseVersion(version)}`)}-\\d{8}\\.\\d{6}-\\d+\\.jar$`,
      )
    : new RegExp(`^${escapeRegExp(`${artifactId}-${version}`)}\\.jar$`);
  const file = names.find((name) => wanted.test(name));
  if (!file) {
    return undefined;
  }
  const sha256 = await digestOf(path.join(dir, file));
  return sha256 ? { file, sha256 } : undefined;
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`). Only the real client runs: the raw probe of `publish` would leave an extra POM
 * behind that a later "nothing changed" comparison has no use for. The client exiting 0 means every
 * PUT of the deploy was accepted.
 *
 * Exported (instead of self-registering into the old `registerSeedPublisher` registry, gone as of
 * step 3a) so `maven-adapter.ts` can wire it up as `ProtocolAdapter.seedPublish` directly.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const deployed = await deploy(world);
  if (deployed.exitCode !== 0) {
    throw new Error(
      `maven adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(mvn exit ${deployed.exitCode}); its "consume: ok" expectation depends on this artifact ` +
        'actually existing.',
    );
  }
  return { contentSha256: deployed.contentSha256 };
}
