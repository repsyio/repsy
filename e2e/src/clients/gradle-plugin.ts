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
 * The Gradle client as a plugin consumer (RPS-133): Repsy used the way a team uses a private Gradle
 * plugin repository. `publish` builds and publishes a tiny plugin (`java-gradle-plugin` +
 * `maven-publish`: the plugin jar and the plugin marker artifact `<plugin id>:<plugin id>.gradle.plugin`
 * a `plugins { id ... }` block resolves through), and `resolve` applies it from a build whose
 * `settings.gradle[.kts]` names Repsy as the only plugin repository (`pluginManagement`), which also
 * switches Gradle's default, the Gradle Plugin Portal, off: a plugin can only come from Repsy.
 *
 * What ties the two together is a marker packed into the plugin jar. The plugin's `e2eMarker` task
 * prints it, so the marker the consumer's build printed proves which published jar it applied, the way
 * the jar digest does for a library. `contentSha256` is the marker's digest on both sides.
 *
 * Everything else is `clients/gradle.ts`: the isolated `HOME` and `GRADLE_USER_HOME`, the credential
 * as project properties, the raw HTTP probes that give the `Outcome`.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { repoUrl } from '../repo-url.js';
import type { AdapterResult } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { SeedResult, World } from '../scenarios/world.js';
import {
  buildFileName,
  gradleArgs,
  prepareGradleRun,
  renderGradleTemplate,
  settingsFileName,
  writePluginSource,
  type GradleOptions,
} from './gradle.js';
import { rawConsumeCheck, rawPublishCheck } from './maven.js';
import { minimalPom, splitPackageName } from './maven-raw.js';
import { run, type RunResult } from './exec.js';
import { sha256Hex } from './raw-http.js';

const PUBLISH_TIMEOUT_MS = 240_000;
const CONSUME_TIMEOUT_MS = 240_000;

/** What the plugin's `e2eMarker` task prints before the marker (`MarkerPlugin.java`). */
const MARKER_PREFIX = 'E2E-PLUGIN-MARKER:';

/** The plugin id of the plugin published as `groupId:artifactId`. */
export function pluginIdOf(groupId: string, artifactId: string): string {
  return `${groupId}.${artifactId}`;
}

/** How a consumer maps a plugin id to a jar: the marker artifact, or by hand (`eachPlugin`). */
export type PluginRoute = 'marker' | 'legacy';

export interface PluginPublishOptions extends GradleOptions {
  /** Publish only the plugin jar, not the plugin marker artifact. */
  jarOnly?: boolean;
}

export interface PluginResolveOptions extends GradleOptions {
  /** Defaults to `marker`. */
  route?: PluginRoute;
  /** Apply this plugin id instead of the published one (which does not exist anywhere). */
  pluginId?: string;
}

export interface PluginPublishRun {
  exitCode: number;
  command: string;
  /** The marker packed into the plugin jar of this publish. */
  marker: string;
  /** The POM of the plugin jar, which the raw probe re-sends. */
  pomBytes: Buffer;
  result: RunResult;
}

/** Builds and publishes the plugin of `world.publishTarget` with the real Gradle client. */
export async function publishPluginWithGradle(
  world: World,
  options: PluginPublishOptions,
): Promise<PluginPublishRun> {
  const { dsl, credentialVia, jarOnly } = options;
  const gradle = await prepareGradleRun(
    `gradle-${dsl}-plugin-pub-${world.scenario.id}`,
    world.credential,
    credentialVia,
  );
  const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
  const version = world.publishTarget.version;

  await renderGradleTemplate(dsl, 'settings', path.join(gradle.work, settingsFileName(dsl)), {
    artifactId,
  });
  await renderGradleTemplate(dsl, 'plugin/publish', path.join(gradle.work, buildFileName(dsl)), {
    groupId,
    version,
    pluginId: pluginIdOf(groupId, artifactId),
    repoUrl: repoUrl(world.repoName),
    hasCredential: world.credential.transport === 'basic',
  });
  await writePluginSource(gradle.work);
  const marker = randomUUID();
  const resourcesDir = path.join(gradle.work, 'src', 'main', 'resources');
  await fs.mkdir(resourcesDir, { recursive: true });
  await fs.writeFile(path.join(resourcesDir, 'e2e-plugin-marker.txt'), marker, 'utf8');

  const result = await run(
    'gradle',
    gradleArgs(jarOnly ? 'publishPluginMavenPublicationToMavenRepository' : 'publish'),
    {
      cwd: gradle.work,
      env: gradle.env,
      timeoutMs: PUBLISH_TIMEOUT_MS,
      redact: gradle.secrets,
      label: `gradle-${dsl}-plugin-publish-${world.scenario.id}`,
    },
  );

  const pomBytes = await fs
    .readFile(path.join(gradle.work, 'build', 'publications', 'pluginMaven', 'pom-default.xml'))
    .catch(() => Buffer.from(minimalPom(groupId, artifactId, version)));

  return { exitCode: result.exitCode, command: result.command, marker, pomBytes, result };
}

/** Publishes the plugin and derives the outcome from a raw probe of the same rule. */
export async function publish(world: World, options: PluginPublishOptions): Promise<AdapterResult> {
  const published = await publishPluginWithGradle(world, options);
  const httpStatus = await rawPublishCheck(world, published.pomBytes);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: sha256Hex(published.marker),
  };
}

export interface PluginApplyRun {
  result: RunResult;
  /** The marker the plugin's `e2eMarker` task printed, when the plugin was applied. */
  marker?: string;
}

/**
 * Applies the plugin of `world.consumeTarget` in a build of its own and runs its `e2eMarker` task.
 * Kept apart from `resolve` so a spec can read the client's output (a plugin that is not found says
 * where it looked).
 */
export async function applyPlugin(
  world: World,
  options: PluginResolveOptions,
): Promise<PluginApplyRun> {
  const { dsl, credentialVia, route = 'marker' } = options;
  const gradle = await prepareGradleRun(
    `gradle-${dsl}-plugin-con-${world.scenario.id}`,
    world.credential,
    credentialVia,
  );
  const [groupId, artifactId] = splitPackageName(world.consumeTarget.packageName);
  const version = world.consumeTarget.version;

  const view = {
    groupId,
    artifactId,
    version,
    pluginId: options.pluginId ?? pluginIdOf(groupId, artifactId),
    repoUrl: repoUrl(world.repoName),
    hasCredential: world.credential.transport === 'basic',
    legacy: route === 'legacy',
  };
  await renderGradleTemplate(
    dsl,
    'plugin/consumer-settings',
    path.join(gradle.work, settingsFileName(dsl)),
    view,
  );
  await renderGradleTemplate(
    dsl,
    'plugin/consumer-build',
    path.join(gradle.work, buildFileName(dsl)),
    view,
  );

  const result = await run('gradle', gradleArgs('--quiet', 'e2eMarker'), {
    cwd: gradle.work,
    env: gradle.env,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: gradle.secrets,
    label: `gradle-${dsl}-plugin-consume-${world.scenario.id}`,
  });

  const line = result.stdout.split('\n').find((text) => text.startsWith(MARKER_PREFIX));
  return { result, marker: line?.slice(MARKER_PREFIX.length).trim() };
}

/** Applies the plugin and derives the outcome from a raw probe of the same authorization. */
export async function resolve(world: World, options: PluginResolveOptions): Promise<AdapterResult> {
  const applied = await applyPlugin(world, options);
  const httpStatus = await rawConsumeCheck(world);

  return {
    outcome: outcomeForStatus(httpStatus),
    httpStatus,
    clientExitCode: applied.result.exitCode,
    command: applied.result.command,
    contentSha256: applied.marker === undefined ? undefined : sha256Hex(applied.marker),
    resolvedFile: applied.marker === undefined ? undefined : `${MARKER_PREFIX}${applied.marker}`,
  };
}

/** The pre-publish of a scenario that needs the plugin to exist already (see `clients/gradle.ts`). */
export async function seedPublish(
  world: World,
  options: PluginPublishOptions,
): Promise<SeedResult> {
  const published = await publishPluginWithGradle(world, options);
  if (published.exitCode !== 0) {
    throw new Error(
      `gradle plugin adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(gradle exit ${published.exitCode}); its "consume: ok" expectation depends on this ` +
        'plugin actually existing.',
    );
  }
  return { contentSha256: sha256Hex(published.marker) };
}
