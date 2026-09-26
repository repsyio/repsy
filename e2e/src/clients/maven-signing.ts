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
 * Real signed deploys (RPS-1316): a `mvn deploy` with `maven-gpg-plugin` bound to `verify`, and a
 * Gradle `maven-publish` + `signing` publish, both signing with a real `gpg` key (`gpg.ts`) and
 * uploading to a Repsy Maven repo. Each call renders a tiny project into an isolated work directory
 * and runs the real client with an isolated `HOME`, so parallel Playwright workers share nothing but
 * Maven's read-only third-party cache tail (see `maven.ts`): a run has its own local repository, its
 * own `GNUPGHOME` (the key's, see `gpg.ts`) and, for Gradle, its own `GRADLE_USER_HOME` (with a
 * `--no-daemon` build, so no daemon outlives the test).
 *
 * The client's exit code and output are returned, never asserted here: what the server did with the
 * files is judged by the spec, from the repo itself.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { env } from '../env.js';
import { gradleEnv } from './gradle.js';
import { gpgEnv, type GpgKey } from './gpg.js';
import { isolatedWorkDir, run } from './exec.js';
import { mavenEnv, SHARED_M2_DIR } from './maven.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MAVEN_TEMPLATES = path.resolve(__dirname, '../packages/maven');
const GRADLE_TEMPLATES = path.resolve(__dirname, '../packages/gradle');

/** Pinned like the runner's Maven: a plugin version a real project would pin. */
const GPG_PLUGIN_VERSION = '3.2.8';
const SOURCE_PLUGIN_VERSION = '3.3.1';

const DEPLOY_TIMEOUT_MS = 180_000;
const WARM_TIMEOUT_MS = 180_000;
const WARM_MARKER = path.join(SHARED_M2_DIR, '.e2e-warm-signing');

export interface SignedProject {
  repoName: string;
  groupId: string;
  artifactId: string;
  version: string;
}

export interface SigningDeployOptions extends SignedProject {
  /** The key to sign with, or `undefined` for an unsigned deploy of the same project. */
  key?: GpgKey;
}

export interface SigningDeployResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

async function render(
  dir: string,
  template: string,
  dest: string,
  view: Record<string, unknown>,
): Promise<void> {
  const source = await fs.readFile(path.join(dir, template), 'utf8');
  await fs.writeFile(dest, mustache.render(source, view), 'utf8');
}

/** The Repsy credential as a Maven `settings.xml` server entry, id `repsy`. */
function settingsXml(): string {
  return (
    '<settings><servers><server><id>repsy</id>' +
    `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
    '</server></servers></settings>'
  );
}

let warmPromise: Promise<void> | undefined;

/**
 * Primes the shared read-only cache tail with maven-gpg-plugin and maven-source-plugin (and their
 * dependencies), once per volume, the way `maven.ts` primes Maven's core plugins: Maven never writes
 * a download into a tail repo, so a warm-up that uses it as the primary repo is the only way a
 * later deploy finds them there instead of downloading them again. Best-effort: on failure the
 * deploys just download what they need.
 */
async function ensureSigningPluginsWarm(): Promise<void> {
  warmPromise ??= (async () => {
    await fs.mkdir(SHARED_M2_DIR, { recursive: true });
    let handle;
    try {
      handle = await fs.open(WARM_MARKER, 'wx');
    } catch {
      return;
    }
    await handle.close();

    try {
      const { home, work } = await isolatedWorkDir('mvn-warm-signing');
      await render(MAVEN_TEMPLATES, 'signed-pom.template.xml', path.join(work, 'pom.xml'), {
        groupId: 'io.repsy.e2e.warm',
        artifactId: 'warm',
        version: '0.0.0',
        repoUrl: 'http://localhost/none',
        gpgPluginVersion: GPG_PLUGIN_VERSION,
        sourcePluginVersion: SOURCE_PLUGIN_VERSION,
      });
      await run(
        'mvn',
        ['-B', '-ntp', `-Dmaven.repo.local=${SHARED_M2_DIR}`, '-Dgpg.skip=true', 'verify'],
        {
          cwd: work,
          env: mavenEnv(home),
          timeoutMs: WARM_TIMEOUT_MS,
          label: 'maven-warm-signing-plugins',
        },
      );
    } catch (err) {
      console.warn('maven-signing: shared-cache warm-up failed (non-fatal):', err);
    }
  })();

  return warmPromise;
}

/**
 * `mvn deploy` of a signed jar project. With a `key`, `maven-gpg-plugin` signs with that key out of
 * its own GNUPGHOME (the passphrase reaches it the way a CI job passes it, `MAVEN_GPG_PASSPHRASE`);
 * without one, `-Dgpg.skip=true` deploys the same project unsigned.
 */
export async function mavenGpgDeploy(opts: SigningDeployOptions): Promise<SigningDeployResult> {
  await ensureSigningPluginsWarm();

  const { home, work } = await isolatedWorkDir('mvn-gpg');
  await render(MAVEN_TEMPLATES, 'signed-pom.template.xml', path.join(work, 'pom.xml'), {
    groupId: opts.groupId,
    artifactId: opts.artifactId,
    version: opts.version,
    repoUrl: `${env.repoBaseUrl}/${opts.repoName}`,
    gpgPluginVersion: GPG_PLUGIN_VERSION,
    sourcePluginVersion: SOURCE_PLUGIN_VERSION,
  });
  await fs.writeFile(path.join(work, 'settings.xml'), settingsXml());
  const sourceDir = path.join(work, 'src', 'main', 'java', 'io', 'repsy', 'e2e');
  await fs.mkdir(sourceDir, { recursive: true });
  await render(MAVEN_TEMPLATES, 'Signed.template.java', path.join(sourceDir, 'Signed.java'), {
    marker: randomUUID(),
  });

  const args = [
    '-B',
    '-ntp',
    'deploy',
    '-s',
    'settings.xml',
    `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
    `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
  ];
  let childEnv: NodeJS.ProcessEnv = mavenEnv(home);
  const secrets = [env.adminPassword];
  if (opts.key) {
    args.push(`-Dgpg.keyname=${opts.key.fingerprint}`, `-Dgpg.homedir=${opts.key.gnupgHome}`);
    childEnv = { ...gpgEnv(opts.key, home), MAVEN_GPG_PASSPHRASE: opts.key.passphrase };
    secrets.push(opts.key.passphrase);
  } else {
    args.push('-Dgpg.skip=true');
  }

  const result = await run('mvn', args, {
    cwd: work,
    env: childEnv,
    timeoutMs: DEPLOY_TIMEOUT_MS,
    redact: secrets,
    label: `maven-gpg-deploy-${opts.key ? 'signed' : 'unsigned'}`,
  });
  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    stderr: result.stderr,
    timedOut: result.timedOut,
  };
}

/**
 * `gradle publish` of a `maven-publish` + `signing` project (Gradle's own `useGpgCmd()`, so the same
 * `gpg` binary and key as the Maven run). Everything Gradle writes lives under this run's own
 * `GRADLE_USER_HOME`. Without a `key` the `signing` block is left out: an unsigned publish.
 */
export async function gradleSigningPublish(
  opts: SigningDeployOptions,
): Promise<SigningDeployResult> {
  const { home, work } = await isolatedWorkDir('gradle-sign');
  const gradleUserHome = path.join(home, 'gradle-home');
  await fs.mkdir(gradleUserHome, { recursive: true });

  const view = {
    groupId: opts.groupId,
    artifactId: opts.artifactId,
    version: opts.version,
    repoUrl: `${env.repoBaseUrl}/${opts.repoName}`,
    signed: opts.key !== undefined,
  };
  await render(
    GRADLE_TEMPLATES,
    'settings.template.gradle',
    path.join(work, 'settings.gradle'),
    view,
  );
  await render(GRADLE_TEMPLATES, 'build.template.gradle', path.join(work, 'build.gradle'), view);
  const sourceDir = path.join(work, 'src', 'main', 'java', 'io', 'repsy', 'e2e');
  await fs.mkdir(sourceDir, { recursive: true });
  await render(MAVEN_TEMPLATES, 'Signed.template.java', path.join(sourceDir, 'Signed.java'), {
    marker: randomUUID(),
  });

  const secrets = [env.adminPassword];
  let childEnv: NodeJS.ProcessEnv = gradleEnv(home, gradleUserHome, {
    ORG_GRADLE_PROJECT_repsyUsername: env.adminUsername,
    ORG_GRADLE_PROJECT_repsyPassword: env.adminPassword,
  });
  if (opts.key) {
    await fs.writeFile(
      path.join(gradleUserHome, 'gradle.properties'),
      [
        'signing.gnupg.executable=gpg',
        `signing.gnupg.homeDir=${opts.key.gnupgHome}`,
        `signing.gnupg.keyName=${opts.key.fingerprint}`,
        `signing.gnupg.passphrase=${opts.key.passphrase}`,
        '',
      ].join('\n'),
    );
    childEnv = { ...childEnv, ...gpgEnv(opts.key, home), GRADLE_USER_HOME: gradleUserHome };
    secrets.push(opts.key.passphrase);
  }

  const result = await run(
    'gradle',
    ['--no-daemon', '--console=plain', '--stacktrace', 'publish'],
    {
      cwd: work,
      env: childEnv,
      timeoutMs: DEPLOY_TIMEOUT_MS,
      redact: secrets,
      label: `gradle-signing-publish-${opts.key ? 'signed' : 'unsigned'}`,
    },
  );
  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    stderr: result.stderr,
    timedOut: result.timedOut,
  };
}
