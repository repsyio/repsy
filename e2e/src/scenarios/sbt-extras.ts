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
 * The sbt-specific checks the shared catalog (`scenarios/catalog.ts`) cannot express (RPS-134),
 * registered next to the catalog loop in `tests/maven/sbt.spec.ts`:
 *
 *  - the exact file set a real `sbt publish` stores (POM, jar, sources and Scaladoc jars, each with
 *    its `.sha1` and `.md5`) and what it never sends (`maven-metadata.xml`, at any level);
 *  - the cross-build (`+publish`): one artifact per Scala binary version, `<name>_2.13` and
 *    `<name>_3`, and a Scala 3 project resolving the `_3` one with `%%`;
 *  - the credential coming from the user's `~/.sbt/.credentials` instead of the build;
 *  - sbt's own default of not replacing a release (`publishConfiguration.overwrite` false), which the
 *    client enforces itself before the server's `allowOverride` rule is reached;
 *  - what the panel shows of an sbt-published artifact, and deleting one of its versions.
 *
 * Like the dotted-artifactId test in `tests/maven/publish-consume.spec.ts`, each test builds its
 * `World` by hand, since a coordinate other than the catalog's own is not a scenario.
 */
import { createHash } from 'node:crypto';

import { RepoType } from '../api/panel-api.js';
import { uniqueVersion } from '../clients/maven-adapter.js';
import {
  adminCredential,
  rawGet,
  rawHead,
  repoTree,
  sha256Hex,
  versionDir,
  artifactDir,
} from '../clients/maven-raw.js';
import {
  crossSuffix,
  publish,
  publishWithSbt,
  resolve,
  SCALA_213,
  SCALA_3,
  type SbtOptions,
} from '../clients/sbt.js';
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

interface SbtWorld {
  world: World;
  repoName: string;
  groupId: string;
  /** The `name` in the build; the artifactId in the repository carries a cross-version suffix. */
  base: string;
  version: string;
}

/** A private Maven repo, the admin credential, and a release (or SNAPSHOT) coordinate of Scala 2.13. */
async function adminWorld(
  seeder: Fixtures['seeder'],
  id: string,
  versionType: 'release' | 'snapshot' = 'release',
  version: string = uniqueVersion(versionType),
): Promise<SbtWorld> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const base = `sbt-${id}`;
  const target: Coordinates = {
    packageName: `${groupId}:${base}${crossSuffix(SCALA_213)}`,
    version,
  };
  const world: World = {
    scenario: extraScenario(id),
    protocol: 'sbt',
    repoName: repo.name,
    credential: adminCredential(),
    publishTarget: target,
    consumeTarget: target,
  };
  return { world, repoName: repo.name, groupId, base, version };
}

/** `world` with its coordinate replaced (a second version, or the `_3` artifact). */
function withTarget(world: World, target: Coordinates): World {
  return { ...world, publishTarget: target, consumeTarget: target };
}

const sha1Hex = (body: Buffer): string => createHash('sha1').update(body).digest('hex');

export function registerSbtExtras(): void {
  test.describe('sbt extras', () => {
    test(
      'sbt > stores exactly the files sbt sends, each with its checksums, and no maven-metadata.xml',
      { tag: ['@smoke'] },
      async ({ seeder }) => {
        const { world, repoName, groupId, base, version } = await adminWorld(seeder, 'files');
        const artifactId = `${base}${crossSuffix(SCALA_213)}`;

        const published = await publishWithSbt(world, { withDocs: true });
        expect(published.exitCode, `sbt publish: ${published.command}`).toBe(0);

        const dir = versionDir(groupId, artifactId, version);
        const stem = `${dir}/${artifactId}-${version}`;
        const stored = Object.keys(await repoTree(repoName)).sort();
        const files = ['.pom', '.jar', '-sources.jar', '-javadoc.jar'].map((s) => `${stem}${s}`);
        const withChecksums = (list: string[]): string[] =>
          list.flatMap((file) => [file, `${file}.md5`, `${file}.sha1`]);
        expect(stored, 'sbt sends the POM, the jar, the sources and the docs').toEqual(
          expect.arrayContaining(withChecksums(files)),
        );
        // sbt 1.13 also sends the sources and Scaladoc jars of its test configuration
        // (`-tests-sources.jar`, `-tests-javadoc.jar`) whatever the project has in it.
        const artifacts = stored.filter((path) => !/\.(md5|sha1)$/.test(path));
        expect(
          stored,
          'every file sbt sends is followed by a .sha1 and a .md5, and nothing else is stored',
        ).toEqual(withChecksums(artifacts).sort());

        const admin = adminCredential();
        for (const file of files) {
          const body = await rawGet(repoName, admin, file);
          const sha1 = await rawGet(repoName, admin, `${file}.sha1`);
          expect(sha1.body.toString('utf8').trim(), `${file}.sha1`).toBe(sha1Hex(body.body));
        }

        const jar = await rawGet(repoName, admin, `${stem}.jar`);
        expect(sha256Hex(jar.body), 'the stored jar is the one sbt built').toBe(
          published.contentSha256,
        );

        const pom = (await rawGet(repoName, admin, `${stem}.pom`)).body.toString('utf8');
        expect(pom).toContain(`<groupId>${groupId}</groupId>`);
        expect(pom).toContain(`<artifactId>${artifactId}</artifactId>`);
        expect(pom).toContain(`<version>${version}</version>`);

        // RPS-1368: the HEAD sbt asks with is answered like the GET (a real Tomcat, so the length
        // header is what a client sees, unlike MockMvc), and 404 for what sbt never sent.
        const head = await rawHead(repoName, admin, `${stem}.jar`);
        expect(head.status, `HEAD ${stem}.jar`).toBe(200);
        expect(head.contentLength, 'HEAD carries the length of the jar').toBe(jar.body.length);
        expect(head.bodyLength, 'HEAD carries no body').toBe(0);
        expect((await rawHead(repoName, admin, `${stem}-nope.jar`)).status).toBe(404);

        for (const path of [
          `${dir}/maven-metadata.xml`,
          `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
        ]) {
          const res = await rawGet(repoName, admin, path);
          expect(res.status, `sbt sends no ${path}`).toBe(404);
        }
      },
    );

    test('sbt > +publish stores one artifact per Scala binary version, and a Scala 3 build resolves the _3 one', async ({
      seeder,
      panelApi,
    }) => {
      const { world, repoName, groupId, base, version } = await adminWorld(seeder, 'cross');

      const published = await publishWithSbt(world, {
        crossScalaVersions: [SCALA_213, SCALA_3],
      });
      expect(published.exitCode, `sbt +publish: ${published.command}`).toBe(0);

      const admin = adminCredential();
      for (const suffix of [crossSuffix(SCALA_213), crossSuffix(SCALA_3)]) {
        const artifactId = `${base}${suffix}`;
        const dir = versionDir(groupId, artifactId, version);
        for (const extension of ['pom', 'jar']) {
          const file = `${dir}/${artifactId}-${version}.${extension}`;
          expect((await rawGet(repoName, admin, file)).status, `GET ${file}`).toBe(200);
        }
      }

      const panel = await panelApi.listMavenArtifactNames(repoName, groupId);
      expect(panel.sort(), 'the panel lists both artifacts').toEqual(
        [`${base}${crossSuffix(SCALA_213)}`, `${base}${crossSuffix(SCALA_3)}`].sort(),
      );

      const scala3 = withTarget(world, {
        packageName: `${groupId}:${base}${crossSuffix(SCALA_3)}`,
        version,
      });
      const resolved = await resolve(scala3, { scalaVersion: SCALA_3 });
      expect(resolved.clientExitCode, `sbt resolve: ${resolved.command}`).toBe(0);
      expect(resolved.resolvedFile, 'a Scala 3 build resolves the _3 jar').toBe(
        `${base}${crossSuffix(SCALA_3)}-${version}.jar`,
      );
    });

    test(
      'sbt > the credential can come from ~/.sbt/.credentials',
      { tag: ['@auth'] },
      async ({ seeder }) => {
        const { world } = await adminWorld(seeder, 'credfile');
        const options: SbtOptions = { credentialVia: 'file' };

        const published = await publish(world, options);
        expect(published.outcome, `publish: ${published.command}`).toBe('ok');
        expect(published.clientExitCode, `sbt publish: ${published.command}`).toBe(0);

        const resolved = await resolve(world, options);
        expect(resolved.outcome, `resolve: ${resolved.command}`).toBe('ok');
        expect(resolved.clientExitCode, `sbt resolve: ${resolved.command}`).toBe(0);
        expect(
          resolved.contentSha256,
          `the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
        ).toBe(published.contentSha256);
      },
    );

    test("sbt > a release publishes with sbt's own defaults, and sbt then refuses to replace it", async ({
      seeder,
    }) => {
      // RPS-1368: sbt (whose publishConfiguration.overwrite is false for a release) asks with a HEAD
      // whether the file exists. Repsy answers 404 for a missing one, so the first publish goes
      // through, and 200 for the stored one, so the second is refused by sbt itself.
      const { world, repoName } = await adminWorld(seeder, 'defaults');
      const options: SbtOptions = { overwrite: false };

      const first = await publishWithSbt(world, options);
      expect(first.exitCode, `first sbt publish: ${first.command}`).toBe(0);
      const before = await repoTree(repoName);

      const second = await publishWithSbt(world, options);
      expect(second.exitCode, `second sbt publish: ${second.command}`).not.toBe(0);
      expect(await repoTree(repoName), 'the refused republish changed the repository').toEqual(
        before,
      );
    });

    test('sbt > the panel lists and shows a release that sbt published', async ({
      seeder,
      panelApi,
    }) => {
      const { world, repoName, groupId, base, version } = await adminWorld(seeder, 'panel');
      const artifactId = `${base}${crossSuffix(SCALA_213)}`;

      const published = await publishWithSbt(world);
      expect(published.exitCode, `sbt publish: ${published.command}`).toBe(0);

      expect(await panelApi.listMavenArtifactNames(repoName, groupId)).toEqual([artifactId]);
      expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
        version,
      ]);
      const info = await panelApi.getMavenArtifactVersion(repoName, groupId, artifactId, version);
      expect(info.artifactName).toBe(artifactId);
      expect(info.versionName).toBe(version);
      expect(info.pomFile, 'the panel serves the POM').toContain(
        `<artifactId>${artifactId}</artifactId>`,
      );
    });

    test('sbt > the panel lists and shows a SNAPSHOT that sbt published', async ({
      seeder,
      panelApi,
    }) => {
      // RPS-1370: sbt never sends the version-level maven-metadata.xml, so the detail finds the POM
      // by the literal file name in the version directory.
      const { world, repoName, groupId, base, version } = await adminWorld(
        seeder,
        'panel-snapshot',
        'snapshot',
      );
      const artifactId = `${base}${crossSuffix(SCALA_213)}`;

      const published = await publishWithSbt(world);
      expect(published.exitCode, `sbt publish: ${published.command}`).toBe(0);

      expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
        version,
      ]);
      const info = await panelApi.getMavenArtifactVersion(repoName, groupId, artifactId, version);
      expect(info.versionName).toBe(version);
      expect(info.pomFile, 'the panel serves the POM').toContain(
        `<artifactId>${artifactId}</artifactId>`,
      );
    });

    test('sbt > deleting one of two sbt-published versions in the panel removes it and keeps the other', async ({
      seeder,
      panelApi,
    }) => {
      // RPS-1331: sbt never sends an artifact-level maven-metadata.xml, and the delete used to move
      // the files to the trash, answer 404 and leave the database row behind.
      const { world, repoName, groupId, base, version } = await adminWorld(seeder, 'delete');
      const artifactId = `${base}${crossSuffix(SCALA_213)}`;
      const second = uniqueVersion('release');

      for (const v of [version, second]) {
        const published = await publishWithSbt(
          withTarget(world, { packageName: world.publishTarget.packageName, version: v }),
        );
        expect(published.exitCode, `sbt publish ${v}: ${published.command}`).toBe(0);
      }

      await panelApi.deleteMavenArtifactVersion(repoName, groupId, artifactId, version);

      expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
        second,
      ]);
      const admin = adminCredential();
      const dir = versionDir(groupId, artifactId, version);
      expect((await rawGet(repoName, admin, `${dir}/${artifactId}-${version}.jar`)).status).toBe(
        404,
      );
      const keptDir = versionDir(groupId, artifactId, second);
      expect((await rawGet(repoName, admin, `${keptDir}/${artifactId}-${second}.jar`)).status).toBe(
        200,
      );
    });

    test('sbt > a dynamic revision (latest.release) resolves an sbt-published library', async ({
      seeder,
    }) => {
      // RPS-1369: the server never generates maven-metadata.xml, sbt sends none, and a dynamic
      // revision resolves nothing without it.
      test.fail(
        true,
        'RPS-1369: no maven-metadata.xml is generated, so latest.release finds no version',
      );
      const { world, groupId, base, version } = await adminWorld(seeder, 'dynamic');

      const published = await publishWithSbt(world);
      expect(published.exitCode, `sbt publish: ${published.command}`).toBe(0);

      const dynamic = withTarget(world, {
        packageName: `${groupId}:${base}${crossSuffix(SCALA_213)}`,
        version: 'latest.release',
      });
      const resolved = await resolve(dynamic);
      expect(resolved.clientExitCode, `sbt resolve: ${resolved.command}`).toBe(0);
      expect(resolved.resolvedFile).toBe(`${base}${crossSuffix(SCALA_213)}-${version}.jar`);
    });
  });
}
