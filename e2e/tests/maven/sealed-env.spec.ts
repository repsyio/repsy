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
 * The Maven, Gradle, sbt, Ivy/Ant and gpg clients' environments are allow-lists.
 * (RPS-1446, modelled on `tests/npm-clients/sealed-env.spec.ts`.) Every client of this suite runs in
 * an allow-list environment (`clientEnv`, `src/clients/client-env.ts`), never the runner's own:
 * `REPSY_ADMIN_PASSWORD` and the rest of the harness's variables must not reach the client, its
 * plugins or the scripts it runs. Each cell runs `env` through `run()` with the environment the
 * client's own builder produced and reads back the NAMES that arrived (`src/clients/env-probe.ts`).
 * No stack is needed: nothing here talks to Repsy.
 */
import path from 'node:path';

import { test } from '@playwright/test';

import { isolatedWorkDir } from '../../src/clients/exec.js';
import { expectSealed, probeEnv } from '../../src/clients/env-probe.js';
import { gradleEnv } from '../../src/clients/gradle.js';
import { gpgEnv } from '../../src/clients/gpg.js';
import { prepareIvyRun } from '../../src/clients/ivy.js';
import { mavenEnv } from '../../src/clients/maven.js';
import { prepareSbtRun } from '../../src/clients/sbt.js';

test('maven > no runner variable reaches mvn', { tag: ['@sealed-env'] }, async () => {
  const { home, work } = await isolatedWorkDir('maven-sealed-env');
  expectSealed(await probeEnv(mavenEnv(home), work, 'maven-sealed-env'), home);
});

test(
  'gradle > no runner variable reaches gradle, its user home does',
  { tag: ['@sealed-env'] },
  async () => {
    const { home, work } = await isolatedWorkDir('gradle-sealed-env');
    const seen = await probeEnv(
      gradleEnv(home, path.join(home, 'gradle-home')),
      work,
      'gradle-sealed-env',
    );
    expectSealed(seen, home, ['GRADLE_USER_HOME']);
  },
);

test(
  'sbt > no runner variable reaches sbt, its credential variables do',
  { tag: ['@sealed-env'] },
  async () => {
    const run = await prepareSbtRun('sbt-sealed-env', {
      transport: 'basic',
      username: 'token',
      password: 'sealed-env-secret',
      kind: 'token',
    });
    const seen = await probeEnv(run.env, run.work, 'sbt-sealed-env');
    expectSealed(seen, run.home, ['COURSIER_CACHE', 'E2E_SBT_USER', 'E2E_SBT_PASS']);
  },
);

test('ivy > no runner variable reaches ant', { tag: ['@sealed-env'] }, async () => {
  const run = await prepareIvyRun('ivy-sealed-env');
  const seen = await probeEnv(run.env, run.work, 'ivy-sealed-env');
  expectSealed(seen, run.home, ['ANT_OPTS']);
});

test('gpg > no runner variable reaches gpg, its home does', { tag: ['@sealed-env'] }, async () => {
  const { home, work } = await isolatedWorkDir('gpg-sealed-env');
  const seen = await probeEnv(
    gpgEnv({ gnupgHome: path.join(home, 'gnupg') }, home),
    work,
    'gpg-sealed-env',
  );
  expectSealed(seen, home, ['GNUPGHOME']);
});
