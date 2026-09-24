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
 * A Gradle consumer that lives for a whole test (RPS-133, dependency locking): one project directory and
 * one `GRADLE_USER_HOME` for as many `gradle` runs as the test needs, because locking is about what
 * survives between builds (`gradle.lockfile`) while `clients/gradle.ts`'s `resolve` deliberately starts
 * every build from nothing. Everything else is the same: the real `gradle` binary, an isolated `HOME`,
 * the credential as project properties, the Repsy repository as the only repository.
 *
 * A consumer's own Gradle home caches what it resolved, so a test that has to see the repository as it
 * is NOW (after a publish or a delete) creates a second consumer instead of asking the first again.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { env } from '../env.js';
import type { World } from '../scenarios/world.js';
import {
  buildFileName,
  gradleArgs,
  prepareGradleRun,
  renderGradleTemplate,
  settingsFileName,
  type CredentialVia,
  type GradleDsl,
  type GradleRun,
} from './gradle.js';
import { run, type RunResult } from './exec.js';

const RUN_TIMEOUT_MS = 240_000;

export interface GradleConsumerOptions {
  dsl: GradleDsl;
  /** Names the repository and the credential; nothing else of it is read. */
  world: World;
  /** Dependency notations, `group:artifact:version`, a version may be dynamic (`1.+`). */
  dependencies: readonly string[];
  /** `lockAllConfigurations()`. Defaults to `true`. */
  locking?: boolean;
  /** `lockMode = STRICT`: a configuration without lock state fails. Defaults to `false`. */
  strict?: boolean;
  credentialVia?: CredentialVia;
}

export class GradleConsumer {
  private runs = 0;

  private constructor(
    private readonly gradle: GradleRun,
    private readonly options: GradleConsumerOptions,
  ) {}

  static async create(options: GradleConsumerOptions): Promise<GradleConsumer> {
    const gradle = await prepareGradleRun(
      `gradle-${options.dsl}-locking-${options.world.scenario.id}`,
      options.world.credential,
      options.credentialVia,
    );
    const consumer = new GradleConsumer(gradle, options);
    await renderGradleTemplate(
      options.dsl,
      'settings',
      path.join(gradle.work, settingsFileName(options.dsl)),
      { artifactId: `${options.world.scenario.id}-consumer` },
    );
    await consumer.setDependencies(options.dependencies);
    return consumer;
  }

  /** Renders the build file again with other dependencies; the lockfile and the caches stay. */
  async setDependencies(dependencies: readonly string[]): Promise<void> {
    const { dsl, world, locking = true, strict = false } = this.options;
    await renderGradleTemplate(
      dsl,
      'locking-consumer',
      path.join(this.gradle.work, buildFileName(dsl)),
      {
        repoUrl: `${env.repoBaseUrl}/${world.repoName}`,
        hasCredential: world.credential.transport === 'basic',
        locking,
        strict,
        dependencies,
      },
    );
  }

  /** One real `gradle` run in this consumer's project and home. */
  async run(...args: string[]): Promise<RunResult> {
    this.runs += 1;
    return run('gradle', gradleArgs(...args), {
      cwd: this.gradle.work,
      env: this.gradle.env,
      timeoutMs: RUN_TIMEOUT_MS,
      redact: this.gradle.secrets,
      label: `gradle-${this.options.dsl}-locking-${this.options.world.scenario.id}-${this.runs}`,
    });
  }

  /** The versions of `artifactId` the last `fetchDependencies` resolved (`a-1.1.0.jar`, ...). */
  async resolvedVersions(artifactId: string): Promise<string[]> {
    const names = await fs
      .readdir(path.join(this.gradle.work, 'build', 'resolved'))
      .catch(() => [] as string[]);
    const prefix = `${artifactId}-`;
    return names
      .filter((name) => name.startsWith(prefix) && name.endsWith('.jar'))
      .map((name) => name.slice(prefix.length, -'.jar'.length))
      .sort();
  }

  /** The text of `gradle.lockfile`, or `undefined` when there is none. */
  async lockfile(): Promise<string | undefined> {
    return fs
      .readFile(path.join(this.gradle.work, 'gradle.lockfile'), 'utf8')
      .catch(() => undefined);
  }

  /** Puts a lockfile another consumer wrote into this one. */
  async writeLockfile(text: string): Promise<void> {
    await fs.writeFile(path.join(this.gradle.work, 'gradle.lockfile'), text, 'utf8');
  }
}
