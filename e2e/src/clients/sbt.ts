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
 * The sbt client (RPS-134): `publish`/`resolve` render a tiny Scala project from
 * `src/packages/sbt/*.template.*` into an isolated work directory and run the real `sbt` launcher
 * against it. The repository is an ordinary Repsy Maven repository: sbt publishes with its Ivy
 * publisher in Maven style (`publishMavenStyle`, `publishTo`) and resolves with Coursier, so this is
 * the same server code `clients/maven.ts` reaches with `mvn`, by a client with its own wire
 * behaviour (README.md, "sbt runner": a non-unique SNAPSHOT, no `maven-metadata.xml`, the
 * cross-version `_2.13` artifactId suffix).
 *
 * Like `mvn` and Gradle, sbt hides the HTTP status behind its own exit code, so the `Outcome` is
 * derived from the raw probes `clients/maven.ts` makes (`rawPublishCheck`/`rawConsumeCheck`), with
 * sbt's exit code as corroborating evidence. The probe PUTs the LITERAL `-SNAPSHOT` name for a
 * SNAPSHOT (`literalSnapshot`), since that is the file sbt itself sends.
 *
 * Everything sbt writes lives under this run's own `HOME`, Ivy home, global base and temp directory.
 * Only the sbt launcher's boot directory is shared, and a publish also reads Coursier's cache: both are
 * primed in the runner image (`runners/maven.Dockerfile`, `runners/sbt-warmup`) with the sbt jars, the
 * Scala compilers and their bridges, never a test artifact, because every test publishes to a
 * repository of its own. A resolve gets an empty Coursier cache of its own and resolves neither the
 * Scala library nor the compiler, so it can only ever find the library in the Repsy repository.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../env.js';
import { repoUrl } from '../repo-url.js';
import type { AdapterResult } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';
import { digestOf, rawConsumeCheck, rawPublishCheck } from './maven.js';
import { minimalPom, splitPackageName } from './maven-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/sbt');

/** The sbt and Scala versions of the runner image. Keep equal to `runners/maven.Dockerfile` and `runners/sbt-warmup`. */
export const SBT_VERSION = '1.13.0';
export const SCALA_213 = '2.13.18';
export const SCALA_3 = '3.3.8';

/** The realm of Repsy's Basic challenge (`Basic realm="Repsy"`, `BasicAuthChallenge.REPSY`), which sbt's Credentials must name. */
export const REPSY_REALM = 'Repsy';

/** A cold sbt (JVM start, build load, compile) is slower than a cold `mvn`. */
const PUBLISH_TIMEOUT_MS = 240_000;
const CONSUME_TIMEOUT_MS = 240_000;

/** The primed caches of the runner image; absent outside it, where sbt then downloads what it needs. */
const SBT_CACHE_DIR = process.env.SBT_CACHE_DIR ?? '/opt/sbt-cache';

/** How the Repsy credential reaches the sbt build (see the file comment). */
export type CredentialVia = 'env' | 'file';

export interface SbtOptions {
  /** Defaults to `env`. */
  credentialVia?: CredentialVia;
  /** The `scalaVersion` of the project; defaults to `SCALA_213`. */
  scalaVersion?: string;
  /** `+publish` for these instead of `publish` for `scalaVersion` alone. */
  crossScalaVersions?: readonly string[];
  /** Publish the sources and Scaladoc jars as well (a real project does; slower, Scaladoc is a compile). */
  withDocs?: boolean;
  /** sbt's `publishConfiguration.overwrite`: `true` sends the file whatever exists, `false` (sbt's
   *  default for a release) makes the client refuse to replace one. Defaults to `true`. */
  overwrite?: boolean;
  /** The realm the credential names; defaults to Repsy's own. */
  realm?: string;
}

/** An sbt project directory, its isolated home, and the environment and arguments an `sbt` run needs. */
export interface SbtRun {
  home: string;
  work: string;
  env: NodeJS.ProcessEnv;
  /** Values to replace with `***` in the command line and captured output. */
  secrets: string[];
  /** The `sbt` arguments before the task names. */
  args: string[];
}

/** The host a Credentials entry is keyed by: the repository base URL's host name. */
export function repoHost(): string {
  return new URL(env.repoBaseUrl).hostname;
}

/**
 * The isolated directories, environment and launcher arguments of one sbt run, with the credential
 * passed the way `via` says. An anonymous credential passes nothing.
 */
export async function prepareSbtRun(
  prefix: string,
  credential: MaterializedCredential,
  via: CredentialVia = 'env',
  realm: string = REPSY_REALM,
  coursierCache: 'primed' | 'private' = 'primed',
): Promise<SbtRun> {
  const { home, work } = await isolatedWorkDir(prefix);
  // Short on purpose: sbt's boot server socket is a unix domain socket in `<tmp>/.sbt/sbt-socket<n>/`,
  // and a path over ~100 characters (the work directory carries the scenario id) cannot be bound.
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'sbt-'));
  const primed = await fs.access(SBT_CACHE_DIR).then(
    () => true,
    () => false,
  );

  const childEnv: NodeJS.ProcessEnv = clientEnv(home);
  const args = [
    '-batch',
    '-no-colors',
    '-Dsbt.server.autostart=false',
    '-Dsbt.supershell=false',
    '-Dsbt.ci=true',
    `-Dsbt.global.base=${path.join(home, 'sbt-global')}`,
    `-Dsbt.ivy.home=${path.join(home, 'ivy')}`,
    // sbt's boot server socket lives under java.io.tmpdir, and a directory left in /tmp by another uid
    // (the image's build) cannot be written to.
    `-J-Djava.io.tmpdir=${tmpDir}`,
    // The JVM takes user.home from the passwd entry, not from $HOME, and `Path.userHome` (where a
    // build finds ~/.sbt/.credentials) is user.home.
    `-J-Duser.home=${home}`,
    '-J-Xmx768m',
    // A run lives for seconds: one GC thread and the C1 compiler alone cost a third of the CPU of the
    // defaults (measured: about 15s against 35-45s of user time for a publish), which is what a dozen
    // parallel workers, or a busy machine, run out of first.
    '-J-XX:+UseSerialGC',
    '-J-XX:TieredStopAtLevel=1',
  ];
  childEnv.COURSIER_CACHE = path.join(home, 'coursier');
  if (primed) {
    args.push(`-Dsbt.boot.directory=${path.join(SBT_CACHE_DIR, 'boot')}`);
    if (coursierCache === 'primed') {
      childEnv.COURSIER_CACHE = path.join(SBT_CACHE_DIR, 'coursier');
    }
  }

  const secrets: string[] = [];
  if (credential.transport === 'basic') {
    const username = credential.username ?? '';
    const password = credential.password ?? '';
    if (via === 'env') {
      childEnv.E2E_SBT_USER = username;
      childEnv.E2E_SBT_PASS = password;
    } else {
      await fs.mkdir(path.join(home, '.sbt'), { recursive: true });
      await fs.writeFile(
        path.join(home, '.sbt', '.credentials'),
        `realm=${realm}\nhost=${repoHost()}\nuser=${username}\npassword=${password}\n`,
      );
    }
    if (password) {
      secrets.push(password);
    }
  }

  return { home, work, env: childEnv, secrets, args };
}

/** Renders `<name>.template.<ext>` into `dest`. */
async function renderTemplate(
  name: string,
  extension: string,
  dest: string,
  view: Record<string, unknown>,
): Promise<void> {
  const template = await fs.readFile(
    path.join(TEMPLATES_DIR, `${name}.template.${extension}`),
    'utf8',
  );
  await fs.mkdir(path.dirname(dest), { recursive: true });
  await fs.writeFile(dest, mustache.render(template, view), 'utf8');
}

/** `groupId:artifactId_2.13` -> the artifactId sbt is given as `name` (its cross-version suffix is added by sbt). */
export function artifactBaseOf(artifactId: string): string {
  return artifactId.replace(/_(2\.13|2\.12|3)$/, '');
}

/** The cross-version suffix sbt appends to an artifactId for a Scala version (`_2.13`, `_3`). */
export function crossSuffix(scalaVersion: string): string {
  return scalaVersion.startsWith('3.') ? '_3' : `_${scalaVersion.split('.').slice(0, 2).join('.')}`;
}

function credentialView(
  credential: MaterializedCredential,
  via: CredentialVia,
  realm: string,
): Record<string, unknown> {
  const basic = credential.transport === 'basic';
  return {
    credentialsEnv: basic && via === 'env',
    credentialsFile: basic && via === 'file',
    realm,
    host: repoHost(),
  };
}

/** Writes the publisher project of `world.publishTarget` into `sbt.work`. */
async function renderPublisher(
  sbt: SbtRun,
  world: World,
  options: SbtOptions,
): Promise<{ groupId: string; artifactId: string; artifactBase: string; version: string }> {
  const { credentialVia = 'env', realm = REPSY_REALM } = options;
  const scalaVersion = options.scalaVersion ?? SCALA_213;
  const cross = options.crossScalaVersions ?? [scalaVersion];
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const artifactBase = artifactBaseOf(artifactId);
  const version = world.publishTarget.version;

  await renderTemplate('build', 'sbt', path.join(sbt.work, 'build.sbt'), {
    groupId,
    artifactBase,
    version,
    scalaVersion,
    crossScalaVersions: cross.map((v) => JSON.stringify(v)).join(', '),
    repoUrl: repoUrl(world.repoName),
    overwrite: String(options.overwrite ?? true),
    withDocs: String(options.withDocs ?? false),
    ...credentialView(world.credential, credentialVia, realm),
  });
  await renderTemplate('build', 'properties', path.join(sbt.work, 'project', 'build.properties'), {
    sbtVersion: SBT_VERSION,
  });

  const sourceDir = path.join(sbt.work, 'src', 'main', 'scala', 'io', 'repsy', 'e2e');
  await fs.mkdir(sourceDir, { recursive: true });
  await fs.copyFile(path.join(TEMPLATES_DIR, 'Marker.scala'), path.join(sourceDir, 'Marker.scala'));
  // A per-publish random marker, so the jar's digest identifies exactly this publish (see
  // `AdapterResult.contentSha256`): two publishes of one coordinate would otherwise build identical bytes.
  const resourcesDir = path.join(sbt.work, 'src', 'main', 'resources');
  await fs.mkdir(resourcesDir, { recursive: true });
  await fs.writeFile(path.join(resourcesDir, 'e2e-deploy-marker.txt'), randomUUID(), 'utf8');

  return { groupId, artifactId, artifactBase, version };
}

export interface PublishRun {
  exitCode: number;
  command: string;
  stdout: string;
  stderr: string;
  /** sha256 of the jar sbt built for `scalaVersion` (see `AdapterResult.contentSha256`). */
  contentSha256?: string;
  /** The POM sbt generated for `scalaVersion`, which the raw probe re-sends. */
  pomBytes: Buffer;
  /** The work directory, for a test that reads more of what sbt built. */
  work: string;
}

/**
 * Runs the real `sbt publish` (`+publish` when `crossScalaVersions` is given) of
 * `world.publishTarget` with `world.credential` and nothing else.
 */
export async function publishWithSbt(world: World, options: SbtOptions = {}): Promise<PublishRun> {
  const sbt = await prepareSbtRun(
    `sbt-pub-${world.scenario.id}`,
    world.credential,
    options.credentialVia,
    options.realm,
  );
  const { groupId, artifactId, artifactBase, version } = await renderPublisher(sbt, world, options);
  const scalaVersion = options.scalaVersion ?? SCALA_213;

  const result = await run(
    'sbt',
    [...sbt.args, options.crossScalaVersions ? '+publish' : 'publish'],
    {
      cwd: sbt.work,
      env: sbt.env,
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: sbt.secrets,
      label: `sbt-publish-${world.scenario.id}`,
    },
  );

  const built = path.join(
    sbt.work,
    'target',
    `scala-${scalaVersion.startsWith('3.') ? scalaVersion : scalaVersion.split('.').slice(0, 2).join('.')}`,
  );
  const stem = `${artifactBase}${crossSuffix(scalaVersion)}-${version}`;
  const pomBytes = await fs
    .readFile(path.join(built, `${stem}.pom`))
    .catch(() => Buffer.from(minimalPom(groupId, artifactId, version)));

  return {
    exitCode: result.exitCode,
    command: result.command,
    stdout: result.stdout,
    stderr: result.stderr,
    contentSha256: await digestOf(path.join(built, `${stem}.jar`)),
    pomBytes,
    work: sbt.work,
  };
}

/** Publishes with the real sbt client and derives the outcome from a raw probe of the same rule. */
export async function publish(world: World, options: SbtOptions = {}): Promise<AdapterResult> {
  const published = await publishWithSbt(world, options);
  const httpStatus = await rawPublishCheck(world, published.pomBytes, { literalSnapshot: true });

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.contentSha256,
  };
}

/**
 * Resolves `world.consumeTarget` with the real sbt client, in a project of its own: only the Repsy
 * repository and the dependency line the panel shows, whose `fetchDependencies` task copies the
 * resolved files into `target/resolved`.
 */
export async function resolve(world: World, options: SbtOptions = {}): Promise<AdapterResult> {
  const { credentialVia = 'env', realm = REPSY_REALM } = options;
  const scalaVersion = options.scalaVersion ?? SCALA_213;
  const sbt = await prepareSbtRun(
    `sbt-con-${world.scenario.id}`,
    world.credential,
    credentialVia,
    realm,
    'private',
  );
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const version = world.consumeTarget.version;

  await renderTemplate('consumer', 'sbt', path.join(sbt.work, 'build.sbt'), {
    groupId,
    artifactBase: artifactBaseOf(artifactId),
    version,
    scalaVersion,
    repoUrl: repoUrl(world.repoName),
    ...credentialView(world.credential, credentialVia, realm),
  });
  await renderTemplate('build', 'properties', path.join(sbt.work, 'project', 'build.properties'), {
    sbtVersion: SBT_VERSION,
  });

  const result = await run('sbt', [...sbt.args, 'fetchDependencies'], {
    cwd: sbt.work,
    env: sbt.env,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: sbt.secrets,
    label: `sbt-consume-${world.scenario.id}`,
  });

  const httpStatus = await rawConsumeCheck(world);
  const resolved = await findResolvedJar(
    path.join(sbt.work, 'target', 'resolved'),
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

/** The jar `fetchDependencies` copied into `dir` (`a_2.13-<version>.jar`), or `undefined` when none. */
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
  // Coursier keeps the requested version in the file name, so a SNAPSHOT keeps its `-SNAPSHOT` name.
  // A dynamic revision (`latest.release`, a range) resolves to a version of its own: any jar of the artifact.
  const dynamic = /^(latest\.|[[(]|.*\+$)/.test(version);
  const file = names.find((name) =>
    dynamic
      ? name.startsWith(`${artifactId}-`) && name.endsWith('.jar')
      : name === `${artifactId}-${version}.jar`,
  );
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
export async function seedPublish(world: World, options: SbtOptions = {}): Promise<SeedResult> {
  const published = await publishWithSbt(world, options);
  if (published.exitCode !== 0) {
    throw new Error(
      `sbt adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(sbt exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        `artifact actually existing. The end of sbt's output:\n${`${published.stdout}\n${published.stderr}`.split('\n').slice(-30).join('\n')}`,
    );
  }
  return { contentSha256: published.contentSha256 };
}
