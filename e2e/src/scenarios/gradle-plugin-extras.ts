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
 * The Gradle plugin checks the shared catalog (`scenarios/catalog.ts`) cannot express (RPS-133),
 * registered once per build file language next to the catalog loop:
 *
 *  - the legacy route: a plugin id mapped to its jar by hand (`resolutionStrategy.eachPlugin`), which
 *    needs no plugin marker artifact;
 *  - a plugin published without its marker artifact: `plugins { id ... }` cannot find it (Gradle names
 *    the repository it looked in), while the legacy route still applies the very same jar;
 *  - no fallback to the Gradle Plugin Portal: a plugin id Repsy does not have fails, and Gradle looked
 *    nowhere but Repsy, so no test can pass by resolving a plugin from the internet.
 *
 * Each test builds its `World` by hand, like `scenarios/gradle-extras.ts`.
 */
import { RepoType } from '../api/panel-api.js';
import { applyPlugin, pluginIdOf, publishPluginWithGradle } from '../clients/gradle-plugin.js';
import { gradlePluginProtocol } from '../clients/gradle-plugin-adapter.js';
import type { GradleDsl } from '../clients/gradle.js';
import { uniqueVersion } from '../clients/maven-adapter.js';
import { adminCredential } from '../clients/maven-raw.js';
import { expect, test, type Fixtures } from './fixtures.js';
import type { Scenario } from './types.js';
import type { Coordinates, World } from './world.js';

/** A scenario for a hand-built `World`: only `id` (work directory names) and `expect` are read. */
function extraScenario(id: string): Scenario {
  return {
    id,
    tags: ['@smoke'],
    repo: { privateRepo: true },
    credential: 'admin-password',
    expect: { publish: 'ok', consume: 'ok' },
  };
}

async function adminWorld(
  seeder: Fixtures['seeder'],
  dsl: GradleDsl,
  id: string,
): Promise<{ world: World; groupId: string; artifactId: string; version: string }> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const artifactId = `gradle-plugin-${dsl}-${id}`;
  const version = uniqueVersion('release');
  const target: Coordinates = { packageName: `${groupId}:${artifactId}`, version };
  const world: World = {
    scenario: extraScenario(id),
    protocol: gradlePluginProtocol(dsl),
    repoName: repo.name,
    credential: adminCredential(),
    publishTarget: target,
    consumeTarget: target,
  };
  return { world, groupId, artifactId, version };
}

export function registerGradlePluginExtras(dsl: GradleDsl): void {
  const protocol = gradlePluginProtocol(dsl);

  test.describe(`${protocol} extras`, () => {
    test(
      `${protocol} > the legacy eachPlugin route applies the published plugin`,
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const { world } = await adminWorld(seeder, dsl, 'legacy');

        const published = await publishPluginWithGradle(world, { dsl });
        expect(published.exitCode, `gradle publish: ${published.command}`).toBe(0);

        const applied = await applyPlugin(world, { dsl, route: 'legacy' });
        expect(applied.result.exitCode, `gradle e2eMarker: ${applied.result.command}`).toBe(0);
        expect(applied.marker, 'the applied plugin is the published jar').toBe(published.marker);
      },
    );

    test(
      `${protocol} > a plugin without its marker artifact is not found by id`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const { world, groupId, artifactId, version } = await adminWorld(seeder, dsl, 'nomarker');

        const published = await publishPluginWithGradle(world, { dsl, jarOnly: true });
        expect(published.exitCode, `gradle publish (jar only): ${published.command}`).toBe(0);

        const byId = await applyPlugin(world, { dsl });
        expect(
          byId.result.exitCode,
          `plugins { id } needs the marker: ${byId.result.command}`,
        ).not.toBe(0);
        const output = `${byId.result.stdout}\n${byId.result.stderr}`;
        expect(output).toContain(
          `Plugin [id: '${pluginIdOf(groupId, artifactId)}', version: '${version}'] was not found`,
        );
        expect(byId.marker, 'no plugin was applied').toBeUndefined();

        const byJar = await applyPlugin(world, { dsl, route: 'legacy' });
        expect(byJar.result.exitCode, `the legacy route: ${byJar.result.command}`).toBe(0);
        expect(byJar.marker, 'the very same jar, mapped by hand').toBe(published.marker);
      },
    );

    test(
      `${protocol} > a plugin Repsy does not have is looked for in Repsy only`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const { world, groupId } = await adminWorld(seeder, dsl, 'portal');

        const applied = await applyPlugin(world, {
          dsl,
          pluginId: `${groupId}.no-such-plugin-${dsl}`,
        });
        expect(applied.result.exitCode, `gradle e2eMarker: ${applied.result.command}`).not.toBe(0);
        const output = `${applied.result.stdout}\n${applied.result.stderr}`;
        expect(output).toContain('was not found in any of the following sources');
        expect(output, 'the search names the Repsy repository').toContain(`/${world.repoName}`);
        expect(output, 'the Gradle Plugin Portal is not consulted').not.toContain(
          'plugins.gradle.org',
        );
      },
    );
  });
}
