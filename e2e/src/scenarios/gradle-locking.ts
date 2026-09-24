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
 * Dependency locking with the real Gradle client (RPS-133), registered once per build file language.
 * Locking pins what a dynamic version (`1.+`) resolved to in `gradle.lockfile`, so these tests are also
 * a check that Repsy keeps the artifact-level `maven-metadata.xml` right after every Gradle publish and
 * every version delete: a dynamic version is resolved through it.
 *
 *  - `--write-locks` pins the newest version; a newer publish does not move the lock (a control build
 *    without locking does see it), and `--update-locks` does;
 *  - an artifact that is not in the lockfile fails a locked build, and STRICT mode fails a build with
 *    no lock state at all;
 *  - a locked version that was deleted from Repsy fails a fresh build, and `--update-locks` repairs it.
 *
 * Every test builds its own repository and `World`, and versions are fixed (`1.0.0`, `1.1.0`, ...):
 * the repository is the test's alone.
 */
import { RepoType } from '../api/panel-api.js';
import { GradleConsumer } from '../clients/gradle-consumer.js';
import { publish, type GradleDsl } from '../clients/gradle.js';
import { gradleProtocol } from '../clients/gradle-adapter.js';
import { adminCredential } from '../clients/maven-raw.js';
import { expect, test, type Fixtures } from './fixtures.js';
import type { Scenario } from './types.js';
import type { World } from './world.js';

function lockingScenario(id: string): Scenario {
  return {
    id,
    tags: ['@smoke'],
    repo: { privateRepo: true },
    credential: 'admin-password',
    expect: { publish: 'ok', consume: 'ok' },
  };
}

interface LockingFixture {
  world: World;
  groupId: string;
  artifactId: string;
  /** Publishes `artifactId` (or `other`) at `version` with the real Gradle client. */
  publishVersion: (version: string, other?: string) => Promise<void>;
}

async function lockingFixture(
  seeder: Fixtures['seeder'],
  dsl: GradleDsl,
  id: string,
): Promise<LockingFixture> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const artifactId = `gradle-${dsl}-${id}`;
  const world: World = {
    scenario: lockingScenario(id),
    protocol: gradleProtocol(dsl),
    repoName: repo.name,
    credential: adminCredential(),
    publishTarget: { packageName: `${groupId}:${artifactId}`, version: '1.0.0' },
    consumeTarget: { packageName: `${groupId}:${artifactId}`, version: '1.0.0' },
  };

  const publishVersion = async (version: string, other?: string): Promise<void> => {
    const target = { packageName: `${groupId}:${other ?? artifactId}`, version };
    const published = await publish(
      { ...world, scenario: lockingScenario(`${id}-${version}`), publishTarget: target },
      { dsl },
    );
    expect(
      published.outcome,
      `publish ${target.packageName}:${version}: ${published.command}`,
    ).toBe('ok');
    expect(published.clientExitCode, `gradle publish: ${published.command}`).toBe(0);
  };

  return { world, groupId, artifactId, publishVersion };
}

/** `group:artifact:version=` at the start of a line of a lockfile. */
function lockLine(groupId: string, artifactId: string, version: string): RegExp {
  const escaped = `${groupId}:${artifactId}:${version}`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^${escaped}=`, 'm');
}

export function registerGradleLocking(dsl: GradleDsl): void {
  const protocol = gradleProtocol(dsl);

  test.describe(`${protocol} dependency locking`, () => {
    test(
      `${protocol} > a lock pins the resolved version until it is updated`,
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const { world, groupId, artifactId, publishVersion } = await lockingFixture(
          seeder,
          dsl,
          'pin',
        );
        const dynamic = `${groupId}:${artifactId}:1.+`;
        await publishVersion('1.0.0');
        await publishVersion('1.1.0');

        const locked = await GradleConsumer.create({ dsl, world, dependencies: [dynamic] });
        const written = await locked.run('dependencies', '--write-locks');
        expect(written.exitCode, `--write-locks: ${written.command}`).toBe(0);
        const lockfile = await locked.lockfile();
        expect(lockfile, 'gradle.lockfile was written').toBeDefined();
        expect(lockfile).toMatch(lockLine(groupId, artifactId, '1.1.0'));
        expect((await locked.run('fetchDependencies')).exitCode).toBe(0);
        expect(await locked.resolvedVersions(artifactId)).toEqual(['1.1.0']);

        await publishVersion('1.2.0');

        // The control: the same dependency without locking sees the new publish, so the lock below is
        // what keeps the build on 1.1.0, not a repository that has not caught up.
        const control = await GradleConsumer.create({
          dsl,
          world,
          dependencies: [dynamic],
          locking: false,
        });
        expect((await control.run('fetchDependencies')).exitCode).toBe(0);
        expect(await control.resolvedVersions(artifactId)).toEqual(['1.2.0']);

        const again = await locked.run('fetchDependencies');
        expect(again.exitCode, `locked build: ${again.command}`).toBe(0);
        expect(await locked.resolvedVersions(artifactId)).toEqual(['1.1.0']);
        expect(await locked.lockfile(), 'the lock did not move').toBe(lockfile);

        // `--refresh-dependencies`: this consumer's Gradle home caches the metadata of a dynamic version
        // for 24 hours, so without it the update would still see 1.0.0 and 1.1.0 only.
        const updated = await locked.run(
          'dependencies',
          '--update-locks',
          `${groupId}:${artifactId}`,
          '--refresh-dependencies',
        );
        expect(updated.exitCode, `--update-locks: ${updated.command}`).toBe(0);
        expect(await locked.lockfile()).toMatch(lockLine(groupId, artifactId, '1.2.0'));
        expect((await locked.run('fetchDependencies')).exitCode).toBe(0);
        expect(await locked.resolvedVersions(artifactId)).toEqual(['1.2.0']);
      },
    );

    test(
      `${protocol} > a module outside the lock and a missing lock state fail the build`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const { world, groupId, artifactId, publishVersion } = await lockingFixture(
          seeder,
          dsl,
          'outside',
        );
        const other = `${artifactId}-other`;
        await publishVersion('1.0.0');
        await publishVersion('1.0.0', other);

        const locked = await GradleConsumer.create({
          dsl,
          world,
          dependencies: [`${groupId}:${artifactId}:1.0.0`],
        });
        expect((await locked.run('dependencies', '--write-locks')).exitCode).toBe(0);

        await locked.setDependencies([
          `${groupId}:${artifactId}:1.0.0`,
          `${groupId}:${other}:1.0.0`,
        ]);
        const outside = await locked.run('fetchDependencies');
        expect(outside.exitCode, `an unlocked module: ${outside.command}`).not.toBe(0);
        expect(`${outside.stdout}\n${outside.stderr}`).toContain(
          'which is not part of the dependency lock state',
        );

        const strict = await GradleConsumer.create({
          dsl,
          world: { ...world, scenario: lockingScenario('outside-strict') },
          dependencies: [`${groupId}:${artifactId}:1.0.0`],
          strict: true,
        });
        const noState = await strict.run('fetchDependencies');
        expect(noState.exitCode, `STRICT without a lockfile: ${noState.command}`).not.toBe(0);
        expect(`${noState.stdout}\n${noState.stderr}`).toContain('does not have lock state');
      },
    );

    test(
      `${protocol} > a locked version deleted from Repsy fails a fresh build until the lock is updated`,
      { tag: ['@negative'] },
      async ({ seeder, panelApi }) => {
        const { world, groupId, artifactId, publishVersion } = await lockingFixture(
          seeder,
          dsl,
          'deleted',
        );
        const dynamic = `${groupId}:${artifactId}:1.+`;
        await publishVersion('1.0.0');
        await publishVersion('1.1.0');

        const writer = await GradleConsumer.create({ dsl, world, dependencies: [dynamic] });
        expect((await writer.run('dependencies', '--write-locks')).exitCode).toBe(0);
        const lockfile = await writer.lockfile();
        expect(lockfile).toMatch(lockLine(groupId, artifactId, '1.1.0'));

        await panelApi.deleteMavenArtifactVersion(world.repoName, groupId, artifactId, '1.1.0');

        // A fresh Gradle home: the writer's would still serve 1.1.0 from its own cache.
        const fresh = await GradleConsumer.create({
          dsl,
          world: { ...world, scenario: lockingScenario('deleted-fresh') },
          dependencies: [dynamic],
        });
        await fresh.writeLockfile(lockfile as string);
        const stale = await fresh.run('fetchDependencies');
        expect(stale.exitCode, `the locked version is gone: ${stale.command}`).not.toBe(0);
        expect(`${stale.stdout}\n${stale.stderr}`).toContain('1.1.0');
        expect(await fresh.resolvedVersions(artifactId)).toEqual([]);

        const updated = await fresh.run(
          'dependencies',
          '--update-locks',
          `${groupId}:${artifactId}`,
        );
        expect(updated.exitCode, `--update-locks: ${updated.command}`).toBe(0);
        expect(await fresh.lockfile()).toMatch(lockLine(groupId, artifactId, '1.0.0'));
        expect((await fresh.run('fetchDependencies')).exitCode).toBe(0);
        expect(await fresh.resolvedVersions(artifactId)).toEqual(['1.0.0']);
      },
    );
  });
}
