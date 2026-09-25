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
 * The Gradle-specific checks the shared catalog (`scenarios/catalog.ts`) cannot express (RPS-133),
 * registered once per build file language next to the catalog loop:
 *
 *  - what a real `gradle publish` stores beyond what `mvn deploy` does: the sources jar, the POM and
 *    the Gradle module metadata (`.module`), whose file list names the jar with the digest of the very
 *    bytes stored next to it;
 *  - the credential reaching the build from the user's `gradle.properties` instead of an environment
 *    variable, the other place a real project keeps it, for a publish and for a resolve.
 *
 * Like the dotted-artifactId test in `tests/maven/publish-consume.spec.ts`, each test builds its
 * `World` by hand, since a coordinate other than the catalog's own is not a scenario.
 */
import { RepoType } from '../api/panel-api.js';
import { publish, resolve, type GradleDsl } from '../clients/gradle.js';
import { gradleProtocol } from '../clients/gradle-adapter.js';
import { uniqueVersion } from '../clients/maven-adapter.js';
import { adminCredential, rawGet, sha256Hex, versionDir } from '../clients/maven-raw.js';
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
  const artifactId = `gradle-${dsl}-${id}`;
  const version = uniqueVersion('release');
  const target: Coordinates = { packageName: `${groupId}:${artifactId}`, version };
  const world: World = {
    scenario: extraScenario(id),
    protocol: gradleProtocol(dsl),
    repoName: repo.name,
    credential: adminCredential(),
    publishTarget: target,
    consumeTarget: target,
  };
  return { world, groupId, artifactId, version };
}

export function registerGradleExtras(dsl: GradleDsl): void {
  const protocol = gradleProtocol(dsl);

  test.describe(`${protocol} extras`, () => {
    test(
      `${protocol} > stores the sources jar, the POM and the module metadata`,
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const { world, groupId, artifactId, version } = await adminWorld(seeder, dsl, 'module');

        const published = await publish(world, { dsl });
        expect(published.outcome, `publish: ${published.command}`).toBe('ok');
        expect(published.clientExitCode, `gradle publish: ${published.command}`).toBe(0);

        const dir = versionDir(groupId, artifactId, version);
        const base = `${dir}/${artifactId}-${version}`;
        const admin = adminCredential();

        const pom = await rawGet(world.repoName, admin, `${base}.pom`);
        expect(pom.status, `GET ${base}.pom`).toBe(200);
        const pomXml = pom.body.toString('utf8');
        expect(pomXml).toContain(`<groupId>${groupId}</groupId>`);
        expect(pomXml).toContain(`<artifactId>${artifactId}</artifactId>`);
        expect(pomXml).toContain(`<version>${version}</version>`);

        const sources = await rawGet(world.repoName, admin, `${base}-sources.jar`);
        expect(sources.status, `GET ${base}-sources.jar`).toBe(200);

        const jar = await rawGet(world.repoName, admin, `${base}.jar`);
        expect(jar.status, `GET ${base}.jar`).toBe(200);
        expect(sha256Hex(jar.body), 'the stored jar is the one Gradle built').toBe(
          published.contentSha256,
        );

        const module = await rawGet(world.repoName, admin, `${base}.module`);
        expect(module.status, `GET ${base}.module`).toBe(200);
        const metadata = JSON.parse(module.body.toString('utf8')) as {
          component: { group: string; module: string; version: string };
          variants: { files?: { url: string; sha256: string }[] }[];
        };
        expect(metadata.component).toMatchObject({
          group: groupId,
          module: artifactId,
          version,
        });
        const listedJar = metadata.variants
          .flatMap((variant) => variant.files ?? [])
          .find((file) => file.url === `${artifactId}-${version}.jar`);
        expect(listedJar, 'the module metadata lists the jar').toBeDefined();
        expect(listedJar?.sha256, "the listed digest is the stored jar's").toBe(
          sha256Hex(jar.body),
        );
      },
    );

    test(
      `${protocol} > the credential can come from gradle.properties`,
      { tag: ['@auth'] },
      async ({ seeder }) => {
        const { world } = await adminWorld(seeder, dsl, 'properties');
        const options = { dsl, credentialVia: 'properties' } as const;

        const published = await publish(world, options);
        expect(published.outcome, `publish: ${published.command}`).toBe('ok');
        expect(published.clientExitCode, `gradle publish: ${published.command}`).toBe(0);

        const resolved = await resolve(world, options);
        expect(resolved.outcome, `resolve: ${resolved.command}`).toBe('ok');
        expect(resolved.clientExitCode, `gradle resolve: ${resolved.command}`).toBe(0);
        expect(
          resolved.contentSha256,
          `the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
        ).toBe(published.contentSha256);
      },
    );
  });
}
