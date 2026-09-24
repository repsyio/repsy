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
 * The Gradle client (RPS-133): `publish`/`resolve` render a tiny project from
 * `src/packages/gradle/*.template.gradle[.kts]` into an isolated work directory and run the real
 * `gradle` binary against it, once with a Groovy DSL build file (`build.gradle`) and once with a
 * Kotlin DSL one (`build.gradle.kts`). The repository is an ordinary Repsy Maven repository: Gradle
 * publishes and resolves through the Maven protocol (`maven-publish`, `maven { url }`), so this is
 * the same server code `clients/maven.ts` exercises with `mvn`, reached by a different client.
 *
 * Like `mvn`, Gradle hides the HTTP status behind its own exit code, so the `Outcome` is derived from
 * the same raw probes `clients/maven.ts` makes (`rawPublishCheck`/`rawConsumeCheck`), with the
 * client's exit code as corroborating evidence. Everything Gradle writes lives under this run's own
 * `HOME` and `GRADLE_USER_HOME`, and every build is `--no-daemon`: a consume never finds a
 * dependency in a cache a publish (or another test) filled, which is the false pass this harness
 * exists to rule out, and no daemon outlives a test.
 *
 * The Repsy credential reaches the build the way a CI job passes it: as the `repsyUsername` and
 * `repsyPassword` project properties, from `ORG_GRADLE_PROJECT_*` environment variables (or, with
 * `credentialVia: 'properties'`, from the run's own `gradle.properties`). The anonymous credential
 * sends nothing, and the templates then render no `credentials` block.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../env.js';
import type { AdapterResult } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { isolatedWorkDir, run } from './exec.js';
import { digestOf, rawConsumeCheck, rawPublishCheck } from './maven.js';
import { minimalPom, splitPackageName } from './maven-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/gradle');

/** A cold Gradle (JVM start, Kotlin script compilation) is slower than a cold `mvn`. */
const PUBLISH_TIMEOUT_MS = 180_000;
const CONSUME_TIMEOUT_MS = 180_000;
const WARM_TIMEOUT_MS = 240_000;
const WARM_WAIT_MS = 300_000;
/** A lock older than this was left behind by a warm-up that never finished. */
const WARM_LOCK_STALE_MS = 600_000;

/**
 * A Gradle user home holding only what every Gradle needs to start, primed once (see
 * `ensureGradleHomeWarm`) and copied into each run's own home. Without it a run pays for the
 * first-use work of a fresh home (native libraries, the generated Gradle API and Kotlin DSL jars, the
 * type-safe accessors of the plugins the templates apply): tens of seconds of CPU per build, which
 * is most of a Kotlin DSL build and enough for a dozen parallel workers to time each other out. It
 * never holds a dependency: the warm-up resolves nothing, so no run can find a Repsy artifact there.
 */
export const SHARED_GRADLE_HOME_DIR =
  process.env.GRADLE_SHARED_HOME_DIR ?? path.join(os.tmpdir(), 'repsy-e2e-gradle-home');
const WARM_READY = path.join(SHARED_GRADLE_HOME_DIR, '.e2e-warm-ready');
const WARM_LOCK = path.join(SHARED_GRADLE_HOME_DIR, '.e2e-warm-lock');

export type GradleDsl = 'groovy' | 'kotlin';

interface DslFiles {
  /** The template extension and the build file's own: `build.gradle` / `build.gradle.kts`. */
  suffix: string;
}

const DSL: Record<GradleDsl, DslFiles> = {
  groovy: { suffix: 'gradle' },
  kotlin: { suffix: 'gradle.kts' },
};

/** How the Repsy credential reaches the Gradle build (see the file comment). */
export type CredentialVia = 'env' | 'properties';

export interface GradleOptions {
  dsl: GradleDsl;
  /** Defaults to `env`. */
  credentialVia?: CredentialVia;
}

/** A Gradle project directory, its isolated home, and the environment a `gradle` run needs. */
export interface GradleRun {
  home: string;
  work: string;
  gradleUserHome: string;
  env: NodeJS.ProcessEnv;
  /** Values to replace with `***` in the command line and captured output. */
  secrets: string[];
}

async function exists(file: string): Promise<boolean> {
  return fs.access(file).then(
    () => true,
    () => false,
  );
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Waits for another worker's warm-up to finish; `false` when it does not in time. */
async function waitForWarm(): Promise<boolean> {
  const deadline = Date.now() + WARM_WAIT_MS;
  while (Date.now() < deadline) {
    if (await exists(WARM_READY)) {
      return true;
    }
    await sleep(2_000);
  }
  return false;
}

/** Runs `gradle help` in a project rendered from both templates of `dsl`, into `SHARED_GRADLE_HOME_DIR`. */
async function warmDsl(dsl: GradleDsl): Promise<void> {
  const { home, work } = await isolatedWorkDir(`gradle-warm-${dsl}`);
  for (const template of ['publish', 'consumer']) {
    const project = path.join(work, template);
    await fs.mkdir(project, { recursive: true });
    await renderGradleTemplate(dsl, 'settings', path.join(project, settingsFileName(dsl)), {
      artifactId: 'warm',
    });
    await renderGradleTemplate(dsl, template, path.join(project, buildFileName(dsl)), {
      groupId: 'io.repsy.e2e.warm',
      version: '0.0.0',
      repoUrl: 'http://localhost/none',
      hasCredential: false,
      coordinates: 'io.repsy.e2e.warm:warm:0.0.0',
    });
    // `help` configures the project (compiles the build script, generates the accessors of the
    // plugins it applies) and resolves nothing.
    await run('gradle', gradleArgs('help'), {
      cwd: project,
      env: { ...process.env, HOME: home, GRADLE_USER_HOME: SHARED_GRADLE_HOME_DIR },
      timeoutMs: WARM_TIMEOUT_MS,
      label: `gradle-warm-${dsl}-${template}`,
    });
  }
}

let warmPromise: Promise<boolean> | undefined;

/**
 * Primes `SHARED_GRADLE_HOME_DIR` once per volume (a named Docker volume, `docker-compose.runners.yml`;
 * a temp directory outside a container): the worker that wins an exclusive-create of a lock file runs
 * `gradle help` for both DSLs and then writes a ready marker, and every other worker (in this run or a
 * later one) waits for that marker instead of warming too, or, when it never comes, runs against an
 * empty home. Best-effort: `false` (an empty home) is only slower, never wrong, so a failure never
 * fails a test.
 */
async function ensureGradleHomeWarm(): Promise<boolean> {
  warmPromise ??= (async () => {
    try {
      await fs.mkdir(SHARED_GRADLE_HOME_DIR, { recursive: true });
      if (await exists(WARM_READY)) {
        return true;
      }
      const lockAge = await fs
        .stat(WARM_LOCK)
        .then((stat) => Date.now() - stat.mtimeMs)
        .catch(() => 0);
      if (lockAge > WARM_LOCK_STALE_MS) {
        await fs.rm(WARM_LOCK, { force: true });
      }

      let handle;
      try {
        handle = await fs.open(WARM_LOCK, 'wx');
      } catch {
        return await waitForWarm();
      }
      await handle.close();

      await warmDsl('groovy');
      await warmDsl('kotlin');
      await fs.rm(path.join(SHARED_GRADLE_HOME_DIR, 'daemon'), { recursive: true, force: true });
      await fs.writeFile(WARM_READY, new Date().toISOString());
      return true;
    } catch (err) {
      console.warn('gradle: shared-home warm-up failed (non-fatal):', err);
      return false;
    }
  })();

  return warmPromise;
}

/** Escapes a value for a `.properties` file (`key=value`). */
function propertiesValue(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\n/g, '\\n');
}

/**
 * The isolated directories and environment of one Gradle run, with the credential passed the way
 * `via` says. An anonymous credential passes nothing.
 */
export async function prepareGradleRun(
  prefix: string,
  credential: MaterializedCredential,
  via: CredentialVia = 'env',
): Promise<GradleRun> {
  const { home, work } = await isolatedWorkDir(prefix);
  const gradleUserHome = path.join(home, 'gradle-home');
  await fs.mkdir(gradleUserHome, { recursive: true });
  if (await ensureGradleHomeWarm()) {
    // A private copy: nothing a run writes (a resolved dependency, a lock) ever reaches the shared
    // one, or another run.
    await fs.cp(SHARED_GRADLE_HOME_DIR, gradleUserHome, {
      recursive: true,
      filter: (source) => path.basename(source) !== '.e2e-warm-lock',
    });
  }

  const childEnv: NodeJS.ProcessEnv = {
    ...process.env,
    HOME: home,
    GRADLE_USER_HOME: gradleUserHome,
  };
  const secrets: string[] = [];

  if (credential.transport === 'basic') {
    const username = credential.username ?? '';
    const password = credential.password ?? '';
    if (via === 'env') {
      childEnv.ORG_GRADLE_PROJECT_repsyUsername = username;
      childEnv.ORG_GRADLE_PROJECT_repsyPassword = password;
    } else {
      await fs.writeFile(
        path.join(gradleUserHome, 'gradle.properties'),
        `repsyUsername=${propertiesValue(username)}\nrepsyPassword=${propertiesValue(password)}\n`,
      );
    }
    if (password) {
      secrets.push(password);
    }
  }

  return { home, work, gradleUserHome, env: childEnv, secrets };
}

/** Renders `<name>.template.<dsl suffix>` into `dest`. */
export async function renderGradleTemplate(
  dsl: GradleDsl,
  name: string,
  dest: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(
    path.join(TEMPLATES_DIR, `${name}.template.${DSL[dsl].suffix}`),
    'utf8',
  );
  await fs.writeFile(dest, mustache.render(template, view), 'utf8');
}

/** The build file a DSL's project has: `build.gradle` or `build.gradle.kts`. */
export function buildFileName(dsl: GradleDsl): string {
  return `build.${DSL[dsl].suffix}`;
}

/** The settings file a DSL's project has: `settings.gradle` or `settings.gradle.kts`. */
export function settingsFileName(dsl: GradleDsl): string {
  return `settings.${DSL[dsl].suffix}`;
}

/** The arguments every Gradle run of this harness shares. */
export function gradleArgs(...tasks: string[]): string[] {
  return ['--no-daemon', '--console=plain', '--stacktrace', ...tasks];
}

interface PublishRun {
  exitCode: number;
  command: string;
  /** sha256 of the jar Gradle built (see `AdapterResult.contentSha256`). */
  contentSha256?: string;
  /** The POM Gradle generated for the publication, which the raw probe re-sends. */
  pomBytes: Buffer;
}

/**
 * Runs the real `gradle publish` of `world.publishTarget` with `world.credential` and nothing else.
 * The built jar carries a per-publish random marker (a resource file), so its digest identifies
 * exactly this publish: two publishes of the same coordinate would otherwise usually build
 * identical bytes, and "the consumer got the latest publish" could not be told from "the consumer
 * got the first".
 */
async function publishWithGradle(world: World, options: GradleOptions): Promise<PublishRun> {
  const { dsl, credentialVia } = options;
  const gradle = await prepareGradleRun(
    `gradle-${dsl}-pub-${world.scenario.id}`,
    world.credential,
    credentialVia,
  );
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const version = world.publishTarget.version;

  await renderGradleTemplate(dsl, 'settings', path.join(gradle.work, settingsFileName(dsl)), {
    artifactId,
  });
  await renderGradleTemplate(dsl, 'publish', path.join(gradle.work, buildFileName(dsl)), {
    groupId,
    version,
    repoUrl: `${env.repoBaseUrl}/${world.repoName}`,
    hasCredential: world.credential.transport === 'basic',
  });

  const resourcesDir = path.join(gradle.work, 'src', 'main', 'resources');
  await fs.mkdir(resourcesDir, { recursive: true });
  await fs.writeFile(path.join(resourcesDir, 'e2e-deploy-marker.txt'), randomUUID(), 'utf8');

  const result = await run('gradle', gradleArgs('publish'), {
    cwd: gradle.work,
    env: gradle.env,
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: gradle.secrets,
    label: `gradle-${dsl}-publish-${world.scenario.id}`,
  });

  const pomBytes = await fs
    .readFile(path.join(gradle.work, 'build', 'publications', 'mavenJava', 'pom-default.xml'))
    .catch(() => Buffer.from(minimalPom(groupId, artifactId, version)));

  return {
    exitCode: result.exitCode,
    command: result.command,
    contentSha256: await digestOf(
      path.join(gradle.work, 'build', 'libs', `${artifactId}-${version}.jar`),
    ),
    pomBytes,
  };
}

/** Publishes with the real Gradle client and derives the outcome from a raw probe of the same rule. */
export async function publish(world: World, options: GradleOptions): Promise<AdapterResult> {
  const published = await publishWithGradle(world, options);
  const httpStatus = await rawPublishCheck(world, published.pomBytes);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.contentSha256,
  };
}

/**
 * Resolves `world.consumeTarget` with the real Gradle client, in a project of its own and a Gradle
 * home nothing else ever wrote to: a `java-library` build declaring only the Repsy repository and
 * the dependency line the panel shows, whose `fetchDependencies` task copies the resolved files into
 * `build/resolved`.
 */
export async function resolve(world: World, options: GradleOptions): Promise<AdapterResult> {
  const { dsl, credentialVia } = options;
  const gradle = await prepareGradleRun(
    `gradle-${dsl}-con-${world.scenario.id}`,
    world.credential,
    credentialVia,
  );
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const version = world.consumeTarget.version;

  await renderGradleTemplate(dsl, 'settings', path.join(gradle.work, settingsFileName(dsl)), {
    artifactId: `${artifactId}-consumer`,
  });
  await renderGradleTemplate(dsl, 'consumer', path.join(gradle.work, buildFileName(dsl)), {
    repoUrl: `${env.repoBaseUrl}/${world.repoName}`,
    hasCredential: world.credential.transport === 'basic',
    coordinates: `${groupId}:${artifactId}:${version}`,
  });

  const result = await run('gradle', gradleArgs('fetchDependencies'), {
    cwd: gradle.work,
    env: gradle.env,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: gradle.secrets,
    label: `gradle-${dsl}-consume-${world.scenario.id}`,
  });

  const httpStatus = await rawConsumeCheck(world);
  const resolved = await findResolvedJar(
    path.join(gradle.work, 'build', 'resolved'),
    artifactId,
    version,
  );

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: result.exitCode,
    command: result.command,
    contentSha256: resolved?.sha256,
    resolvedFile: resolved?.file,
  };
}

/** The jar `fetchDependencies` copied into `dir` (`a-<version>.jar`), or `undefined` when none. */
export async function findResolvedJar(
  dir: string,
  artifactId: string,
  version: string,
): Promise<{ file: string; sha256: string } | undefined> {
  let names: string[];
  try {
    names = await fs.readdir(dir);
  } catch {
    return undefined;
  }
  // Gradle names the file after the requested version, so a SNAPSHOT keeps its `-SNAPSHOT` name.
  const file = names.find((name) => name === `${artifactId}-${version}.jar`);
  if (!file) {
    return undefined;
  }
  const sha256 = await digestOf(path.join(dir, file));
  return sha256 ? { file, sha256 } : undefined;
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`): only the real client runs, and it exiting 0 means every upload was accepted.
 */
export async function seedPublish(world: World, options: GradleOptions): Promise<SeedResult> {
  const published = await publishWithGradle(world, options);
  if (published.exitCode !== 0) {
    throw new Error(
      `gradle adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(gradle exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'artifact actually existing.',
    );
  }
  return { contentSha256: published.contentSha256 };
}
