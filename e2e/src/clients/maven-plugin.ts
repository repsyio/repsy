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
 * A real Maven plugin, and the real `mvn prefix:goal` that resolves it (RPS-1438).
 *
 * `buildHelloPlugin` builds the one-goal plugin `hello-maven-plugin` (prefix `hello`; `buildPlugin`
 * builds others, one with its own `goalPrefix` too, RPS-1458) with the real
 * `mvn package` (maven-plugin-plugin from Central, like every other spec's Maven build) once per
 * worker, and `uploadPluginFiles` sends its jar and POM the way Gradle `maven-publish`, sbt or Ivy do:
 * two PUTs, and no group-level `maven-metadata.xml`. `runPrefixGoal` then runs `mvn hello:hi` in a
 * clean local repository with the plugin's group in `pluginGroups`, which is the one situation where
 * Maven reads the group-level file to find the plugin by its prefix.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { repoUrl } from '../repo-url.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';
import { credentialView, ensureSharedCacheWarm, mavenEnv, SHARED_M2_DIR } from './maven.js';
import { groupPath, rawPut } from './maven-raw.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../packages/maven');

const BUILD_TIMEOUT_MS = 240_000;
const RUN_TIMEOUT_MS = 180_000;

/** The group of the plugin: the repo, not the group, is unique per test. */
export const PLUGIN_GROUP_ID = 'io.repsy.e2e.plugins';
export const PLUGIN_ARTIFACT_ID = 'hello-maven-plugin';
export const PLUGIN_VERSION = '1.0';
/** What `mvn hello:hi` logs: the goal ran, so Maven found the plugin. */
export const PLUGIN_MARKER = `E2E-HELLO-PLUGIN-RAN-${randomUUID()}`;
/** The prefix maven-plugin-plugin derives from the artifactId (`hello-maven-plugin`). */
export const PLUGIN_PREFIX = 'hello';

/**
 * A plugin `buildPlugin` builds: its artifactId, its POM `<name>`, the prefix it is run by (what
 * maven-plugin-plugin derives from the artifactId, or the `goalPrefix` when one is configured) and
 * what its goal logs.
 */
export interface PluginSpec {
  artifactId: string;
  name: string;
  prefix: string;
  marker: string;
  /** Sets `<goalPrefix>` in the plugin's `maven-plugin-plugin` configuration (RPS-1458); its jar's plugin.xml then names it. */
  goalPrefix?: string;
}

export const HELLO_PLUGIN: PluginSpec = {
  artifactId: PLUGIN_ARTIFACT_ID,
  name: 'Hello Maven Plugin',
  prefix: PLUGIN_PREFIX,
  marker: PLUGIN_MARKER,
};

/** A second plugin of the same group, for the specs that publish two (RPS-1457). */
export const BYE_PLUGIN: PluginSpec = {
  artifactId: 'bye-maven-plugin',
  name: 'Bye Maven Plugin',
  prefix: 'bye',
  marker: `E2E-BYE-PLUGIN-RAN-${randomUUID()}`,
};

/** A plugin with its own `goalPrefix`, that differs from the one derived from its artifactId (RPS-1458). */
export const TOOL_PLUGIN: PluginSpec = {
  artifactId: 'tool-maven-plugin',
  name: 'Tool Maven Plugin',
  prefix: 'tl',
  goalPrefix: 'tl',
  marker: `E2E-TOOL-PLUGIN-RAN-${randomUUID()}`,
};

export interface BuiltPlugin {
  artifactId: string;
  jar: Buffer;
  pom: Buffer;
}

const built = new Map<string, Promise<BuiltPlugin>>();

async function render(template: string, dest: string, view: Record<string, unknown>) {
  const source = await fs.readFile(path.join(TEMPLATES_DIR, template), 'utf8');
  await fs.mkdir(path.dirname(dest), { recursive: true });
  await fs.writeFile(dest, mustache.render(source, view), 'utf8');
}

/** The hello plugin's jar and POM, built by the real `mvn package` once per worker. */
export function buildHelloPlugin(): Promise<BuiltPlugin> {
  return buildPlugin(HELLO_PLUGIN);
}

/** A plugin's jar and POM, built by the real `mvn package` once per worker and plugin. */
export function buildPlugin(spec: PluginSpec): Promise<BuiltPlugin> {
  const cached = built.get(spec.artifactId);
  if (cached) {
    return cached;
  }

  const build = (async () => {
    const { home, work } = await isolatedWorkDir('mvn-plugin-build');
    await render('plugin-pom.template.xml', path.join(work, 'pom.xml'), {
      groupId: PLUGIN_GROUP_ID,
      artifactId: spec.artifactId,
      version: PLUGIN_VERSION,
      name: spec.name,
      goalPrefix: spec.goalPrefix,
    });
    await render(
      'HelloMojo.template.java',
      path.join(work, 'src/main/java/io/repsy/e2e/plugin/HelloMojo.java'),
      { marker: spec.marker },
    );

    const result = await run(
      'mvn',
      [
        '-B',
        '-ntp',
        'package',
        `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
        `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
      ],
      {
        cwd: work,
        env: mavenEnv(home),
        timeoutMs: BUILD_TIMEOUT_MS,
        label: 'maven-plugin-build',
      },
    );
    if (result.exitCode !== 0) {
      throw new Error(`mvn package of ${spec.artifactId} failed (exit ${result.exitCode})`);
    }

    return {
      artifactId: spec.artifactId,
      jar: await fs.readFile(path.join(work, 'target', `${spec.artifactId}-${PLUGIN_VERSION}.jar`)),
      pom: await fs.readFile(path.join(work, 'pom.xml')),
    };
  })();

  built.set(spec.artifactId, build);

  return build;
}

/**
 * The real `mvn deploy` of a plugin (RPS-1488), the client that uploads everything a plugin publishes:
 * the jar, the POM, the artifact-level `maven-metadata.xml` and, for `maven-plugin` packaging, the
 * group-level one that lists the plugin by its prefix (which the by-hand uploads of `uploadPluginFiles`
 * never send). Built fresh in its own work directory (unlike `buildPlugin`, whose POM names no
 * repository), with the same fresh local repository plus shared read-only tail as every other `mvn`.
 */
export async function deployPlugin(
  repoName: string,
  credential: MaterializedCredential,
  spec: PluginSpec = HELLO_PLUGIN,
): Promise<RunResult> {
  await ensureSharedCacheWarm();
  const { home, work } = await isolatedWorkDir('mvn-plugin-deploy');
  await render('plugin-pom.template.xml', path.join(work, 'pom.xml'), {
    groupId: PLUGIN_GROUP_ID,
    artifactId: spec.artifactId,
    version: PLUGIN_VERSION,
    name: spec.name,
    goalPrefix: spec.goalPrefix,
    repoUrl: repoUrl(repoName),
  });
  await render(
    'HelloMojo.template.java',
    path.join(work, 'src/main/java/io/repsy/e2e/plugin/HelloMojo.java'),
    { marker: spec.marker },
  );
  await render(
    'settings.template.xml',
    path.join(work, 'settings.xml'),
    credentialView(credential),
  );

  return run(
    'mvn',
    [
      '-B',
      '-ntp',
      'deploy',
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: BUILD_TIMEOUT_MS,
      redact: credential.password ? [credential.password] : [],
      label: `maven-plugin-deploy-${spec.artifactId}`,
    },
  );
}

/** The path of a file of the plugin's version directory. */
export function pluginFilePath(fileName: string, artifactId = PLUGIN_ARTIFACT_ID): string {
  return `${groupPath(PLUGIN_GROUP_ID)}/${artifactId}/${PLUGIN_VERSION}/${fileName}`;
}

/** The path of the group-level `maven-metadata.xml` of the plugin's group (or of one of its checksums). */
export function groupMetadataPath(suffix = ''): string {
  return `${groupPath(PLUGIN_GROUP_ID)}/maven-metadata.xml${suffix}`;
}

/** Uploads the jar, then the POM that registers the version: what Gradle or sbt send, and no metadata. */
export async function uploadPluginFiles(
  repoName: string,
  credential: MaterializedCredential,
  plugin: BuiltPlugin,
): Promise<void> {
  for (const [name, body, type] of [
    [`${plugin.artifactId}-${PLUGIN_VERSION}.jar`, plugin.jar, 'application/java-archive'],
    [`${plugin.artifactId}-${PLUGIN_VERSION}.pom`, plugin.pom, 'application/octet-stream'],
  ] as const) {
    const res = await rawPut(
      repoName,
      credential,
      pluginFilePath(name, plugin.artifactId),
      body,
      type,
    );
    if (res.status !== 200 && res.status !== 201) {
      throw new Error(`PUT ${name} to "${repoName}" answered ${res.status}`);
    }
  }
}

const xmlEscape = (text: string): string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * `mvn hello:hi` in a directory with no project, a clean local repository and the repo as both a
 * repository and a plugin repository, with the plugin's group as a plugin group: Maven has to find
 * the plugin by its prefix, which it does from the group-level `maven-metadata.xml`, then its version
 * from the artifact-level one, and then run it.
 */
export async function runPrefixGoal(
  repoName: string,
  credential: MaterializedCredential,
  prefix = PLUGIN_PREFIX,
): Promise<RunResult> {
  const { home, work } = await isolatedWorkDir('mvn-prefix');
  const url = repoUrl(repoName);
  const settings =
    '<settings>' +
    '<servers><server><id>repsy</id>' +
    `<username>${xmlEscape(credential.username ?? '')}</username>` +
    `<password>${xmlEscape(credential.password ?? '')}</password></server></servers>` +
    `<pluginGroups><pluginGroup>${PLUGIN_GROUP_ID}</pluginGroup></pluginGroups>` +
    '<profiles><profile><id>repsy</id>' +
    `<repositories><repository><id>repsy</id><url>${url}</url></repository></repositories>` +
    `<pluginRepositories><pluginRepository><id>repsy</id><url>${url}</url></pluginRepository></pluginRepositories>` +
    '</profile></profiles><activeProfiles><activeProfile>repsy</activeProfile></activeProfiles>' +
    '</settings>';
  await fs.writeFile(path.join(work, 'settings.xml'), settings, 'utf8');

  return run(
    'mvn',
    [
      '-B',
      '-ntp',
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
      `${prefix}:hi`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: RUN_TIMEOUT_MS,
      redact: credential.password ? [credential.password] : [],
      label: 'maven-prefix-goal',
    },
  );
}
