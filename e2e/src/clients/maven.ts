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
 * `rawPublishCheck`/`rawConsumeCheck`), not from that exit code; `clientExitCode` is only
 * corroborating evidence, attached to the test on failure by `clients/exec.ts`.
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
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../env.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, World } from '../scenarios/world.js';
import { registerSeedPublisher } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/maven');

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;
const WARM_TIMEOUT_MS = 180_000;

const SHARED_M2_DIR =
  process.env.MAVEN_SHARED_REPO_DIR ?? path.join(os.tmpdir(), 'repsy-e2e-maven-m2');
const WARM_MARKER = path.join(SHARED_M2_DIR, '.e2e-warm');

export interface AdapterResult {
  outcome: import('../scenarios/types.js').Outcome;
  httpStatus: number;
  clientExitCode: number;
  /** The (redacted) command line `mvn` ran, useful in assertion failure messages. */
  command: string;
}

function splitPackageName(packageName: string): [string, string] {
  const parts = packageName.split(':');
  if (parts.length !== 2) {
    throw new Error(`maven adapter: expected "groupId:artifactId", got "${packageName}"`);
  }
  return [parts[0], parts[1]];
}

function authHeader(credential: MaterializedCredential): Record<string, string> {
  if (credential.transport !== 'basic') {
    return {};
  }
  const basic = Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
  return { Authorization: `Basic ${basic}` };
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
  return `${groupId.replace(/\./g, '/')}/${artifactId}/${version}/${artifactId}-${version}.pom`;
}

/**
 * A raw PUT of `pomBytes` to the exact path a RELEASE deploy of these coordinates would use (and,
 * for a SNAPSHOT scenario, the literal `-SNAPSHOT`-suffixed path rather than a resolved timestamped
 * one -- `checkDeploymentRules` classifies release vs. snapshot from the filename it is given, the
 * same either way, so this pins the exact status without replicating Maven's own snapshot timestamp
 * negotiation). `Content-Type: application/octet-stream` is required: Repsy's POM parser reads the
 * body as a stream, and a PUT with no content type (curl's default for a raw body is
 * `application/x-www-form-urlencoded`) gets it consumed as form data first, which this harness found
 * out the hard way surfaces as an unrelated `malformedPomFile` 400 instead of the real status.
 */
async function rawPublishCheck(world: World, pomBytes: Buffer): Promise<number> {
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const url = `${env.repoBaseUrl}/${world.repoName}/${pomPath(groupId, artifactId, world.publishTarget.version)}`;

  return withBackoff429(async () => {
    const res = await fetch(url, {
      method: 'PUT',
      headers: { ...authHeader(world.credential), 'Content-Type': 'application/octet-stream' },
      body: new Uint8Array(pomBytes),
    });
    await res.arrayBuffer().catch(() => undefined);
    return res.status;
  });
}

/**
 * A raw GET of the repo root, not of the resolved artifact's own path: every consume expectation in
 * the catalog depends only on authentication/authorization (never on override/release/snapshot
 * rules, which are write-only), so the repo root is an equally valid proxy for the same authz
 * decision `dependency:get` gets -- and, unlike the artifact's own path, it does not require
 * replicating Maven's server-negotiated timestamped SNAPSHOT filename to find the right path to GET.
 */
async function rawConsumeCheck(world: World): Promise<number> {
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
        env: { ...process.env, HOME: home },
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
          env: { ...process.env, HOME: home },
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

export async function publish(world: World): Promise<AdapterResult> {
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
      'deploy',
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd: work,
      env: { ...process.env, HOME: home },
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: secrets,
      label: `maven-publish-${world.scenario.id}`,
    },
  );

  const pomBytes = await fs.readFile(path.join(work, 'pom.xml'));
  const httpStatus = await rawPublishCheck(world, pomBytes);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
  };
}

export async function resolve(world: World): Promise<AdapterResult> {
  await ensureSharedCacheWarm();

  const { home, work } = await isolatedWorkDir(`mvn-con-${world.scenario.id}`);
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const version = world.consumeTarget.version;
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
      `-Dartifact=${groupId}:${artifactId}:${version}:jar`,
      `-DremoteRepositories=repsy::default::${repoUrl}`,
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd: work,
      env: { ...process.env, HOME: home },
      timeoutMs: CONSUME_TIMEOUT_MS,
      redact: secrets,
      label: `maven-consume-${world.scenario.id}`,
    },
  );

  const httpStatus = await rawConsumeCheck(world);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
  };
}

registerSeedPublisher('maven', async (world: World) => {
  const result = await publish(world);
  if (result.outcome !== 'ok') {
    throw new Error(
      `maven adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(http ${result.httpStatus}, mvn exit ${result.clientExitCode}); its "consume: ok" ` +
        'expectation depends on this artifact actually existing.',
    );
  }
});
