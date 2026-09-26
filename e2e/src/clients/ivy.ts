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
 * The Apache Ivy client (RPS-135): `publish`/`resolve` render a small Ant project from
 * `src/packages/ivy/*.template.xml` into an isolated work directory and run the real `ant` with the
 * Ivy jar on its classpath (`ant -lib ivy.jar`, the way Ivy is used from a build). The repository is an
 * ordinary Repsy Maven repository: an `ibiblio` resolver in Maven-compatible mode publishes a jar and
 * the POM `ivy:makepom` writes, and resolves through the same layout, so this is the server code
 * `clients/maven.ts` reaches with `mvn`, by a client with its own wire behaviour (README.md, "The Ivy
 * client": the jar before the POM, a SNAPSHOT published under its literal name, no
 * `maven-metadata.xml`, dynamic revisions through the directory listing).
 *
 * Like `mvn`, Gradle and sbt, Ivy hides the HTTP status behind its own exit code, so the `Outcome` is
 * derived from the raw probes `clients/maven.ts` makes (`rawPublishCheck`/`rawConsumeCheck`), with
 * Ant's exit code as corroborating evidence. The probe PUTs the LITERAL `-SNAPSHOT` name for a
 * SNAPSHOT (`literalSnapshot`), since that is the file Ivy itself sends.
 *
 * Everything Ivy and Ant write lives under this run's own `HOME`, Ivy user directory and cache, all in
 * the run's isolated directory, so a resolve never finds what a publish fetched. Ivy needs nothing from
 * the network but the Repsy repository (the one resolver is Repsy's, and the modules declare no
 * third-party dependency). The Repsy credential is only ever written into the run's `ivysettings.xml`
 * (never argv).
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { repoUrl } from '../repo-url.js';
import type { AdapterResult } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import { digestOf, rawConsumeCheck, rawPublishCheck } from './maven.js';
import { minimalPom, splitPackageName } from './maven-raw.js';
import { findResolvedJar, repoHost, REPSY_REALM } from './sbt.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/ivy');

/** The Ant and Ivy versions of the runner image. Keep equal to `runners/maven.Dockerfile`. */
export const ANT_VERSION = '1.10.15';
export const IVY_VERSION = '2.5.3';

/** The Ivy jar of the runner image (`ENV IVY_JAR` in `runners/maven.Dockerfile`). */
export const IVY_JAR = process.env.IVY_JAR ?? '/opt/ivy/ivy.jar';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

export { REPSY_REALM };

/** A module another module's POM lists as a dependency. */
export interface IvyDependency {
  groupId: string;
  artifactId: string;
  version: string;
}

export interface IvyOptions {
  /** `ivy:publish`'s `overwrite`: `true` sends the file whatever exists, `false` (Ivy's own default,
   *  and what the README documents) makes the client ask with a HEAD request first. The harness
   *  defaults to `true`, so that the server's own `allowOverride` rule decides a redeploy. */
  overwrite?: boolean;
  /** `ivy:publish`'s `publishivy`: also send Ivy's own ivy file, which Repsy refuses. Defaults to `false`. */
  publishIvy?: boolean;
  /** The realm the credential names; defaults to Repsy's own. `null` leaves the realm out. */
  realm?: string | null;
  /** The modules this one depends on (its ivy file and, through `ivy:makepom`, its POM). */
  dependencies?: readonly IvyDependency[];
  /** `ivy:makepom`'s `<mapping conf="default" scope="compile"/>`; without it every dependency is
   *  written to the POM as optional. Defaults to `true`. */
  mapDependencies?: boolean;
  /** `resolve` only: the consumer dependency's `conf`. Defaults to `default->default`; `null` sends
   *  a bare `<dependency org name rev/>` as it is. */
  conf?: string | null;
  /** `resolve` only: the whole `<dependency .../>` element, verbatim (the line the panel shows);
   *  `conf` and `transitive` are then not used. */
  dependencyLine?: string;
  /** `resolve` only: `false` resolves the module alone, not its dependencies. Defaults to `true`. */
  transitive?: boolean;
}

/** An Ant project directory, its isolated home, and the environment an `ant` run needs. */
export interface IvyRun {
  home: string;
  work: string;
  env: NodeJS.ProcessEnv;
  /** The `ant` arguments before the target name. */
  args: string[];
}

/**
 * The isolated directories, environment and arguments of one Ant run. Ivy's user directory and cache
 * are inside `work`/`home`, and the JVM's `user.home` is the run's home, so nothing of the invoking
 * user (or of another run) is read or written.
 */
export async function prepareIvyRun(prefix: string): Promise<IvyRun> {
  const { home, work } = await isolatedWorkDir(prefix);
  const childEnv: NodeJS.ProcessEnv = clientEnv(home, {
    // A run lives for seconds, and Ant's JVM needs neither a parallel collector nor the C2 compiler.
    ANT_OPTS: `-Duser.home=${home} -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`,
  });
  const args = [
    '-noinput',
    '-lib',
    IVY_JAR,
    `-Divy.default.ivy.user.dir=${path.join(home, '.ivy2')}`,
  ];
  return { home, work, env: childEnv, args };
}

/** The realm the credential names: Repsy's own unless the options say otherwise (`null`: none). */
function realmOf(options: IvyOptions): string | null {
  return options.realm === undefined ? REPSY_REALM : options.realm;
}

/** Renders `<name>.template.xml` into `dest`. */
async function renderTemplate(
  name: string,
  dest: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, `${name}.template.xml`), 'utf8');
  await fs.writeFile(dest, mustache.render(template, view), 'utf8');
}

/** The `ivysettings.xml` view: the credential (when there is one), the repository and the cache. */
function settingsView(
  credential: MaterializedCredential,
  repoName: string,
  work: string,
  realm: string | null,
): Record<string, unknown> {
  return {
    cacheDir: path.join(work, 'ivy-cache'),
    repoUrl: repoUrl(repoName),
    hasCredential: credential.transport === 'basic',
    host: repoHost(),
    realm: realm ?? undefined,
    username: credential.username ?? '',
    password: credential.password ?? '',
  };
}

/** Writes the publisher project of `world.publishTarget` into `ivy.work`. */
async function renderPublisher(
  ivy: IvyRun,
  world: World,
  options: IvyOptions,
): Promise<{ groupId: string; artifactId: string; version: string }> {
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const version = world.publishTarget.version;
  const dependencies = options.dependencies ?? [];

  await renderTemplate(
    'ivysettings',
    path.join(ivy.work, 'ivysettings.xml'),
    settingsView(world.credential, world.repoName, ivy.work, realmOf(options)),
  );
  await renderTemplate('ivy', path.join(ivy.work, 'ivy.xml'), {
    groupId,
    module: artifactId,
    version,
    hasDependencies: dependencies.length > 0,
    dependencies,
  });
  await renderTemplate('build', path.join(ivy.work, 'build.xml'), {
    module: artifactId,
    version,
    publishIvy: String(options.publishIvy ?? false),
    overwrite: String(options.overwrite ?? true),
    mapDependencies: options.mapDependencies ?? true,
  });

  // A per-publish random marker, so the jar's digest identifies exactly this publish (see
  // `AdapterResult.contentSha256`): two publishes of one coordinate would otherwise build identical bytes.
  const resourcesDir = path.join(ivy.work, 'resources');
  await fs.mkdir(resourcesDir, { recursive: true });
  await fs.writeFile(path.join(resourcesDir, 'e2e-deploy-marker.txt'), randomUUID(), 'utf8');

  return { groupId, artifactId, version };
}

export interface PublishRun {
  exitCode: number;
  command: string;
  stdout: string;
  stderr: string;
  /** sha256 of the jar Ant built (see `AdapterResult.contentSha256`). */
  contentSha256?: string;
  /** The POM `ivy:makepom` wrote, which the raw probe re-sends. */
  pomBytes: Buffer;
  /** The work directory, for a test that reads more of what Ivy built. */
  work: string;
}

/** Runs the real `ant publish` of `world.publishTarget` with `world.credential` and nothing else. */
export async function publishWithIvy(world: World, options: IvyOptions = {}): Promise<PublishRun> {
  const ivy = await prepareIvyRun(`ivy-pub-${world.scenario.id}`);
  const { groupId, artifactId, version } = await renderPublisher(ivy, world, options);
  const secrets = world.credential.password ? [world.credential.password] : [];

  const result = await run('ant', [...ivy.args, 'publish'], {
    cwd: ivy.work,
    env: ivy.env,
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label: `ivy-publish-${world.scenario.id}`,
  });

  const pomBytes = await fs
    .readFile(path.join(ivy.work, 'build', `${artifactId}.pom`))
    .catch(() => Buffer.from(minimalPom(groupId, artifactId, version)));

  return {
    exitCode: result.exitCode,
    command: result.command,
    stdout: result.stdout,
    stderr: result.stderr,
    contentSha256: await digestOf(path.join(ivy.work, 'build', `${artifactId}.jar`)),
    pomBytes,
    work: ivy.work,
  };
}

/** Publishes with the real Ivy client and derives the outcome from a raw probe of the same rule. */
export async function publish(world: World, options: IvyOptions = {}): Promise<AdapterResult> {
  const published = await publishWithIvy(world, options);
  const httpStatus = await rawPublishCheck(world, published.pomBytes, { literalSnapshot: true });

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.contentSha256,
  };
}

export interface ResolveRun extends AdapterResult {
  stdout: string;
  stderr: string;
  /** The file names `ivy:retrieve` copied into `lib/` (the module and, when transitive, its dependencies). */
  retrieved: string[];
}

/**
 * Resolves `world.consumeTarget` with the real Ivy client, in a project of its own: only the Repsy
 * resolver and a dependency line (the panel's, when `dependencyLine` is given), whose `ivy:retrieve` copies the resolved jars into
 * `lib/`. `consumeTarget.version` may be a dynamic revision (`1.+`, `latest.release`, a range).
 */
export async function resolveWithIvy(world: World, options: IvyOptions = {}): Promise<ResolveRun> {
  const ivy = await prepareIvyRun(`ivy-con-${world.scenario.id}`);
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const version = world.consumeTarget.version;
  const secrets = world.credential.password ? [world.credential.password] : [];

  await renderTemplate(
    'ivysettings',
    path.join(ivy.work, 'ivysettings.xml'),
    settingsView(world.credential, world.repoName, ivy.work, realmOf(options)),
  );
  await renderTemplate('consumer-ivy', path.join(ivy.work, 'consumer-ivy.xml'), {
    groupId,
    module: artifactId,
    version,
    conf: options.conf === undefined ? 'default->default' : options.conf,
    transitive: options.transitive ?? true,
    dependencyLine: options.dependencyLine ?? null,
  });
  await renderTemplate('build', path.join(ivy.work, 'build.xml'), {
    module: artifactId,
    version,
    publishIvy: 'false',
    overwrite: 'true',
    mapDependencies: true,
  });

  const result = await run('ant', [...ivy.args, 'retrieve'], {
    cwd: ivy.work,
    env: ivy.env,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: secrets,
    label: `ivy-consume-${world.scenario.id}`,
  });

  const httpStatus = await rawConsumeCheck(world);
  const lib = path.join(ivy.work, 'lib');
  const retrieved = await fs.readdir(lib).catch(() => [] as string[]);
  const resolved = await findResolvedJar(lib, artifactId, version);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: result.exitCode,
    command: result.command,
    contentSha256: resolved?.sha256,
    resolvedFile: resolved?.file,
    stdout: result.stdout,
    stderr: result.stderr,
    retrieved: retrieved.sort(),
  };
}

/** `resolveWithIvy` as the `AdapterResult` the scenario loop needs. */
export async function resolve(world: World, options: IvyOptions = {}): Promise<AdapterResult> {
  const resolved = await resolveWithIvy(world, options);
  return {
    outcome: resolved.outcome,
    httpStatus: resolved.httpStatus,
    clientExitCode: resolved.clientExitCode,
    command: resolved.command,
    contentSha256: resolved.contentSha256,
    resolvedFile: resolved.resolvedFile,
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`): only the real client runs, and it exiting 0 means every upload was accepted.
 */
export async function seedPublish(world: World, options: IvyOptions = {}): Promise<SeedResult> {
  const published = await publishWithIvy(world, options);
  if (published.exitCode !== 0) {
    throw new Error(
      `ivy adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(ant exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        `artifact actually existing. The end of Ant's output:\n${`${published.stdout}\n${published.stderr}`.split('\n').slice(-30).join('\n')}`,
    );
  }
  return { contentSha256: published.contentSha256 };
}
