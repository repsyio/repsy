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
 * The Apache Ivy tests the shared catalog (`scenarios/catalog.ts`) cannot express (RPS-135), next to
 * the catalog loop in `ivy.spec.ts`, all of them with the real `ant` and the real Ivy jar:
 *
 *  - IV1 (`@smoke`): a deploy token publishes and a resolve gets the same jar, and the files, the
 *    checksums and the panel are what an Ivy publish leaves (a jar and a POM, no metadata, no ivy file);
 *  - IV2/IV3: Ivy resolves what `mvn deploy` published, and `mvn dependency:get` what Ivy published;
 *  - IV4: transitive dependencies through the POM `ivy:makepom` writes, and the optional dependency a
 *    POM without the makepom mapping lists;
 *  - IV5: dynamic revisions (`1.+`, `latest.release`, `latest.integration`, a range) resolved through the
 *    `maven-metadata.xml` Repsy generates from the registered versions, since Ivy sends none (RPS-1369);
 *  - IV9 (RPS-1369, RPS-1437): Maven's `LATEST`, `RELEASE` and version range, and Gradle's `1.+`, resolve
 *    an Ivy-published artifact through that generated file, a `mvn deploy` of another version of it
 *    merges the generated versions into the file it stores, and an Ivy publish after a `mvn deploy`
 *    adds its version to the file Maven stored;
 *  - IV6/IV7: an unknown module, and Ivy's own default `overwrite="false"` (a first release publish
 *    goes through, a second is refused by Ivy, RPS-1368);
 *  - IV8: the ways a first configuration goes wrong: no realm on the credential, `publishivy="true"`,
 *    and a dependency line without a `conf` (which resolves since RPS-1368, before it failed on the
 *    missing sources and javadoc), next to the panel's own line (with its `conf`, RPS-1395) used as
 *    it is;
 *  - the panel's version detail of an Ivy SNAPSHOT (RPS-1370, fixed: the POM is found without a
 *    version-level maven-metadata.xml) and its version delete (RPS-1331, fixed: it removes the
 *    version and keeps the other, with no artifact-level maven-metadata.xml).
 *
 * Like the sbt extras (`scenarios/sbt-extras.ts`) and the dotted-artifactId test in
 * `publish-consume.spec.ts`, each test builds its `World` by hand, since a coordinate other than the
 * catalog's own is not a scenario.
 */
import { createHash } from 'node:crypto';

import { RepoType } from '../../src/api/panel-api.js';
import {
  ANT_VERSION,
  IVY_JAR,
  IVY_VERSION,
  publish,
  publishWithIvy,
  resolveWithIvy,
  type IvyOptions,
} from '../../src/clients/ivy.js';
import { GradleConsumer } from '../../src/clients/gradle-consumer.js';
import { expectPublishStored } from '../../src/clients/ivy-checks.js';
import { run } from '../../src/clients/exec.js';
import * as maven from '../../src/clients/maven.js';
import { uniqueVersion } from '../../src/clients/maven-adapter.js';
import {
  adminCredential,
  artifactDir,
  parseArtifactVersions,
  rawGet,
  rawPut,
  repoTree,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { expect, test, type Fixtures } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, MaterializedCredential, World } from '../../src/scenarios/world.js';

// A cold Ant JVM (and Ivy's settings and resolve) per publish and resolve, several per test.
test.describe.configure({ timeout: 240_000 });

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

interface IvyWorld {
  world: World;
  repoName: string;
  groupId: string;
  artifactId: string;
  version: string;
}

/** A private Maven repo, a credential (the admin's by default) and a release (or SNAPSHOT) coordinate. */
async function newWorld(
  seeder: Fixtures['seeder'],
  id: string,
  options: {
    versionType?: 'release' | 'snapshot';
    version?: string;
    credential?: MaterializedCredential;
  } = {},
): Promise<IvyWorld> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const artifactId = `ivy-${id}`;
  const version = options.version ?? uniqueVersion(options.versionType ?? 'release');
  const target: Coordinates = { packageName: `${groupId}:${artifactId}`, version };
  const world: World = {
    scenario: extraScenario(id),
    protocol: 'ivy',
    repoName: repo.name,
    credential: options.credential ?? adminCredential(),
    publishTarget: target,
    consumeTarget: target,
  };
  return { world, repoName: repo.name, groupId, artifactId, version };
}

/** `world` with its coordinate replaced (a second version, or another module of the same group). */
function withTarget(world: World, target: Coordinates): World {
  return { ...world, publishTarget: target, consumeTarget: target };
}

/** What Ant printed, on both streams: its own failure message is on stderr. */
const output = (result: { stdout: string; stderr: string }): string =>
  `${result.stdout}\n${result.stderr}`;

/** Publishes with Ivy, and fails the test (with Ant's own output attached) unless Ivy exits 0. */
async function publishOk(world: World, options: IvyOptions = {}): Promise<string | undefined> {
  const published = await publishWithIvy(world, options);
  expect(published.exitCode, `ant publish: ${published.command}`).toBe(0);
  return published.contentSha256;
}

test('ivy > the runner has the pinned Ant and Ivy', async () => {
  const ant = await run('ant', ['-version'], { cwd: '/tmp' });
  expect(ant.stdout).toContain(`Apache Ant(TM) version ${ANT_VERSION} `);
  const ivy = await run('java', ['-cp', IVY_JAR, 'org.apache.ivy.Main', '-version'], {
    cwd: '/tmp',
  });
  expect(`${ivy.stdout}${ivy.stderr}`).toContain(`Apache Ivy ${IVY_VERSION} `);
});

test(
  'ivy > a deploy token publishes and resolves, and the repository holds the jar and the POM, each with its checksums',
  { tag: ['@smoke', '@auth'] },
  async ({ seeder, panelApi }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const groupId = `io.repsy.e2e.${seeder.runId}`;
    const artifactId = 'ivy-smoke';
    const version = uniqueVersion('release');
    const target: Coordinates = { packageName: `${groupId}:${artifactId}`, version };
    const world: World = {
      scenario: extraScenario('smoke'),
      protocol: 'ivy',
      repoName: repo.name,
      credential: {
        transport: 'basic',
        username: token.username,
        password: token.token,
        kind: 'token',
      },
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await publishWithIvy(world);
    expect(published.exitCode, `ant publish: ${published.command}`).toBe(0);
    // Ivy sends the jar first, then the POM that registers the version.
    const sent = [...published.stdout.matchAll(/published \S+ to \S+\/(\S+)$/gm)].map((m) => m[1]);
    expect(sent, 'the files Ivy says it published, in order').toEqual([
      `${artifactId}-${version}.jar`,
      `${artifactId}-${version}.pom`,
    ]);

    await expectPublishStored(world, published);
    const stem = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}`;
    expect(
      Object.keys(await repoTree(repo.name)),
      'Ivy stores the jar and the POM with their checksums, no maven-metadata.xml and no ivy file',
    ).toEqual(
      [`${stem}.jar`, `${stem}.pom`].flatMap((file) => [file, `${file}.md5`, `${file}.sha1`]),
    );

    const resolved = await resolveWithIvy(world);
    expect(resolved.clientExitCode, `ant retrieve: ${resolved.command}`).toBe(0);
    expect(resolved.retrieved).toEqual([`${artifactId}-${version}.jar`]);
    expect(resolved.contentSha256, 'the resolved jar is the published one').toBe(
      published.contentSha256,
    );

    expect(await panelApi.listMavenArtifactNames(repo.name, groupId)).toEqual([artifactId]);
    expect(await panelApi.listMavenArtifactVersionNames(repo.name, groupId, artifactId)).toEqual([
      version,
    ]);
    const info = await panelApi.getMavenArtifactVersion(repo.name, groupId, artifactId, version);
    expect(info.pomFile, 'the panel serves the POM ivy:makepom wrote').toContain(
      `<artifactId>${artifactId}</artifactId>`,
    );
  },
);

test('ivy > resolves what mvn deploy published, a release and a SNAPSHOT', async ({ seeder }) => {
  for (const versionType of ['release', 'snapshot'] as const) {
    const { world } = await newWorld(seeder, `from-mvn-${versionType}`, { versionType });

    const seeded = await maven.seedPublish(world);

    const resolved = await resolveWithIvy(world);
    expect(resolved.clientExitCode, `${versionType}: ant retrieve: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `${versionType}: the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the deployed one`,
    ).toBe(seeded.contentSha256);
  }
});

test('ivy > mvn dependency:get resolves what Ivy published', async ({ seeder }) => {
  const { world } = await newWorld(seeder, 'to-mvn');

  const published = await publish(world);
  expect(published.clientExitCode, `ant publish: ${published.command}`).toBe(0);

  const resolved = await maven.resolve(world);
  expect(resolved.clientExitCode, `mvn dependency:get: ${resolved.command}`).toBe(0);
  expect(
    resolved.contentSha256,
    `the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
  ).toBe(published.contentSha256);
});

test.describe('ivy dependencies', () => {
  test('ivy > a dependency ivy:makepom maps to a scope is resolved transitively, an unmapped one is optional', async ({
    seeder,
  }) => {
    const { world, groupId, artifactId } = await newWorld(seeder, 'deps', { version: '1.0' });
    await publishOk(world);
    const base = { groupId, artifactId, version: '1.0' };

    const mapped = withTarget(world, {
      packageName: `${groupId}:${artifactId}-mapped`,
      version: '1.0',
    });
    await publishOk(mapped, { dependencies: [base] });
    const unmapped = withTarget(world, {
      packageName: `${groupId}:${artifactId}-unmapped`,
      version: '1.0',
    });
    await publishOk(unmapped, { dependencies: [base], mapDependencies: false });

    const pom = async (w: World): Promise<string> => {
      const [, artifact] = w.publishTarget.packageName.split(':');
      const res = await rawGet(
        w.repoName,
        adminCredential(),
        `${versionDir(groupId, artifact, '1.0')}/${artifact}-1.0.pom`,
      );
      return res.body.toString('utf8');
    };
    expect(await pom(mapped)).toContain('<scope>compile</scope>');
    expect(await pom(unmapped), 'without the mapping every dependency is optional').toContain(
      '<optional>true</optional>',
    );

    const all = await resolveWithIvy(mapped);
    expect(all.clientExitCode, `ant retrieve: ${all.command}`).toBe(0);
    expect(all.retrieved, 'the module and its dependency').toEqual([
      `${artifactId}-1.0.jar`,
      `${artifactId}-mapped-1.0.jar`,
    ]);

    const alone = await resolveWithIvy(mapped, { transitive: false });
    expect(alone.clientExitCode, `ant retrieve: ${alone.command}`).toBe(0);
    expect(alone.retrieved, 'transitive="false" resolves the module alone').toEqual([
      `${artifactId}-mapped-1.0.jar`,
    ]);

    const optional = await resolveWithIvy(unmapped);
    expect(optional.clientExitCode, `ant retrieve: ${optional.command}`).toBe(0);
    expect(optional.retrieved, 'an optional dependency is not resolved').toEqual([
      `${artifactId}-unmapped-1.0.jar`,
    ]);
  });
});

test('ivy > dynamic revisions resolve through the generated maven-metadata.xml, Ivy having stored none', async ({
  seeder,
}) => {
  const { world, repoName, groupId, artifactId } = await newWorld(seeder, 'dynamic');
  for (const version of ['1.0', '1.1', '1.2', '1.10', '2.0-SNAPSHOT']) {
    await publishOk(withTarget(world, { packageName: world.publishTarget.packageName, version }));
  }
  const metadataPath = `${artifactDir(groupId, artifactId)}/maven-metadata.xml`;
  expect(
    Object.keys(await repoTree(repoName)).filter((path) => path.includes('maven-metadata.xml')),
    'Ivy sends no maven-metadata.xml, so none is stored',
  ).toEqual([]);
  // RPS-1369: the server answers it from the registered versions, the SNAPSHOT included.
  const metadata = await rawGet(repoName, adminCredential(), metadataPath);
  expect(metadata.status, 'the artifact-level maven-metadata.xml is generated').toBe(200);
  expect(parseArtifactVersions(metadata.body.toString('utf8'))).toEqual([
    '1.0',
    '1.1',
    '1.2',
    '1.10',
    '2.0-SNAPSHOT',
  ]);
  const versionLevel = await rawGet(
    repoName,
    adminCredential(),
    `${versionDir(groupId, artifactId, '2.0-SNAPSHOT')}/maven-metadata.xml`,
  );
  expect(versionLevel.status, 'the version-level file of the SNAPSHOT is not generated').toBe(404);

  const expected: Record<string, string> = {
    '1.+': '1.10',
    'latest.release': '1.10',
    'latest.integration': '2.0-SNAPSHOT',
    '[1.0,1.2)': '1.1',
  };
  for (const [revision, version] of Object.entries(expected)) {
    const resolved = await resolveWithIvy(
      withTarget(world, { packageName: world.publishTarget.packageName, version: revision }),
    );
    expect(resolved.clientExitCode, `${revision}: ant retrieve: ${resolved.command}`).toBe(0);
    expect(resolved.retrieved, `${revision} resolves to ${version}`).toEqual([
      `${artifactId}-${version}.jar`,
    ]);
  }
});

test.describe('the generated maven-metadata.xml (RPS-1369)', () => {
  test('ivy > Maven LATEST, RELEASE and a range, and Gradle 1.+, resolve an Ivy-published artifact', async ({
    seeder,
  }) => {
    const { world, groupId, artifactId } = await newWorld(seeder, 'dyn-mvn');
    // Releases only, so LATEST and RELEASE are the same version whatever the snapshot policy.
    for (const version of ['1.0', '1.1', '1.10']) {
      await publishOk(withTarget(world, { packageName: world.publishTarget.packageName, version }));
    }

    const expected: Record<string, string> = {
      LATEST: '1.10',
      RELEASE: '1.10',
      '[1.0,1.10)': '1.1',
    };
    for (const [requested, version] of Object.entries(expected)) {
      const resolved = await maven.resolveDynamic(world, requested);
      expect(
        resolved.clientExitCode,
        `mvn dependency:get ${requested}: ${resolved.command}\n${resolved.output}`,
      ).toBe(0);
      expect(resolved.versions, `${requested} resolves to ${version}`).toEqual([version]);
    }

    const gradle = await GradleConsumer.create({
      dsl: 'groovy',
      world,
      dependencies: [`${groupId}:${artifactId}:1.+`],
      locking: false,
    });
    const fetched = await gradle.run('fetchDependencies');
    expect(fetched.exitCode, `gradle 1.+: ${fetched.command}`).toBe(0);
    expect(await gradle.resolvedVersions(artifactId)).toEqual(['1.10']);
  });

  test('ivy > a mvn deploy of another version merges the Ivy versions into the metadata it stores', async ({
    seeder,
  }) => {
    const { world, repoName, groupId, artifactId } = await newWorld(seeder, 'mixed');
    for (const version of ['1.0', '2.0']) {
      await publishOk(withTarget(world, { packageName: world.publishTarget.packageName, version }));
    }
    const metadataPath = `${artifactDir(groupId, artifactId)}/maven-metadata.xml`;
    expect(Object.keys(await repoTree(repoName))).not.toContain(metadataPath);

    // mvn deploy asks for the artifact-level file first and merges its own version into it: it used to
    // find none and to upload a file that listed only 3.0, hiding the versions Ivy published.
    await maven.seedPublish(
      withTarget(world, { packageName: world.publishTarget.packageName, version: '3.0' }),
    );

    expect(Object.keys(await repoTree(repoName)), 'Maven stored its own file').toContain(
      metadataPath,
    );
    const stored = await rawGet(repoName, adminCredential(), metadataPath);
    expect(stored.status).toBe(200);
    expect(parseArtifactVersions(stored.body.toString('utf8'))).toEqual(['1.0', '2.0', '3.0']);

    const latest = await maven.resolveDynamic(world, 'LATEST');
    expect(latest.clientExitCode, `mvn dependency:get LATEST: ${latest.command}`).toBe(0);
    expect(latest.versions).toEqual(['3.0']);
  });
});

test.describe('the metadata Maven stored, then an Ivy publish (RPS-1437)', () => {
  test('ivy > an Ivy publish after mvn deploy adds its version to the metadata Maven stored (RPS-1437)', async ({
    seeder,
  }) => {
    const { world, repoName, groupId, artifactId } = await newWorld(seeder, 'mvn-first');
    const metadataPath = `${artifactDir(groupId, artifactId)}/maven-metadata.xml`;

    // mvn deploy stores the file (and its checksums) with 1.0 in it; Ivy then publishes 2.0 and
    // uploads no metadata, so the stored file used to keep answering 1.0 for LATEST and RELEASE.
    await maven.seedPublish(
      withTarget(world, { packageName: world.publishTarget.packageName, version: '1.0' }),
    );
    expect(Object.keys(await repoTree(repoName)), 'Maven stored its own file').toContain(
      metadataPath,
    );
    const before = await rawGet(repoName, adminCredential(), metadataPath);
    expect(parseArtifactVersions(before.body.toString('utf8'))).toEqual(['1.0']);

    await publishOk(
      withTarget(world, { packageName: world.publishTarget.packageName, version: '2.0' }),
    );

    const stored = await rawGet(repoName, adminCredential(), metadataPath);
    expect(stored.status).toBe(200);
    expect(parseArtifactVersions(stored.body.toString('utf8'))).toEqual(['1.0', '2.0']);
    const sha1 = await rawGet(repoName, adminCredential(), `${metadataPath}.sha1`);
    expect(sha1.status, 'the stored checksum is rewritten, not dropped').toBe(200);
    expect(sha1.body.toString('utf8').trim(), 'and matches the rewritten file').toBe(
      createHash('sha1').update(stored.body).digest('hex'),
    );

    for (const requested of ['LATEST', 'RELEASE']) {
      const resolved = await maven.resolveDynamic(world, requested);
      expect(
        resolved.clientExitCode,
        `mvn dependency:get ${requested}: ${resolved.command}\n${resolved.output}`,
      ).toBe(0);
      expect(resolved.versions, `${requested} resolves to the version Ivy published`).toEqual([
        '2.0',
      ]);
    }
  });
});

test('ivy > a module Repsy does not have is an unresolved dependency', async ({ seeder }) => {
  const { world, repoName, groupId, artifactId, version } = await newWorld(seeder, 'unknown');

  const resolved = await resolveWithIvy(world);
  expect(resolved.clientExitCode, `ant retrieve: ${resolved.command}`).not.toBe(0);
  expect(output(resolved)).toContain(`:: ${groupId}#${artifactId};${version}: not found`);
  const jar = await rawGet(
    repoName,
    adminCredential(),
    `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}.jar`,
  );
  expect(jar.status).toBe(404);
});

test("ivy > Ivy's own overwrite=false lets a release publish once and then refuses to replace it", async ({
  seeder,
}) => {
  // RPS-1368/RPS-1394: overwrite="false" is Ivy's own default, and what the README and the panel's
  // snippet leave it at. Ivy asks with a HEAD whether the file exists: Repsy answers 404 for a
  // missing one, so the first publish goes through, and 200 for the stored one, so the second is
  // refused by Ivy itself ("destination file exists and overwrite == false").
  const { world, repoName } = await newWorld(seeder, 'no-overwrite');
  const options: IvyOptions = { overwrite: false };

  const first = await publishWithIvy(world, options);
  expect(first.exitCode, `first ant publish: ${first.command}`).toBe(0);
  const before = await repoTree(repoName);

  const second = await publishWithIvy(world, options);
  expect(second.exitCode, `second ant publish: ${second.command}`).not.toBe(0);
  expect(await repoTree(repoName), 'the refused republish changed the repository').toEqual(before);
});

test.describe('ivy first-configuration pitfalls', () => {
  for (const [name, realm] of [
    ['no realm', null],
    ['another realm', 'Some Other Realm'],
  ] as const) {
    test(`ivy > a credential with ${name} is never sent, so the publish is refused on its first file`, async ({
      seeder,
    }) => {
      const { world, repoName } = await newWorld(
        seeder,
        `realm-${realm === null ? 'none' : 'other'}`,
      );

      const published = await publishWithIvy(world, { realm });

      expect(published.exitCode, `ant publish: ${published.command}`).not.toBe(0);
      expect(output(published)).toContain(
        `${world.publishTarget.packageName.split(':')[1]}-${world.publishTarget.version}.jar was refused by the server`,
      );
      expect(await repoTree(repoName), 'nothing was stored').toEqual({});
    });
  }

  test('ivy > publishivy="true" sends an ivy file Repsy refuses, after the jar and the POM were stored', async ({
    seeder,
    panelApi,
  }) => {
    const { world, repoName, groupId, artifactId, version } = await newWorld(seeder, 'publishivy');

    const published = await publishWithIvy(world, { publishIvy: true });

    expect(published.exitCode, `ant publish: ${published.command}`).not.toBe(0);
    expect(output(published)).toContain(
      `${artifactId}/${version}/ivy-${version}.xml failed with status code 400`,
    );
    const stem = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}`;
    expect(
      Object.keys(await repoTree(repoName)),
      'the jar and the POM are stored, the ivy file is not',
    ).toEqual(
      [`${stem}.jar`, `${stem}.pom`].flatMap((file) => [file, `${file}.md5`, `${file}.sha1`]),
    );
    expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
      version,
    ]);

    const ivyFile = await rawPut(
      repoName,
      adminCredential(),
      `${versionDir(groupId, artifactId, version)}/ivy-${version}.xml`,
      '<ivy-module version="2.0"/>',
      'application/octet-stream',
    );
    expect(ivyFile.status).toBe(400);
    expect(ivyFile.msgId).toBe('invalidArtifactPath');
  });

  test("ivy > the panel's dependency line, used as it is, retrieves the artifact, and a bare line does too", async ({
    seeder,
  }) => {
    const { world, groupId, artifactId, version } = await newWorld(seeder, 'panel-dependency');
    await publishOk(world);

    // RPS-1395: exactly what the version detail's "Apache Ivy" block shows.
    const dependencyLine = `<dependency org="${groupId}" name="${artifactId}" rev="${version}" conf="default->default" />`;
    const shown = await resolveWithIvy(world, { dependencyLine });
    expect(shown.clientExitCode, `ant retrieve: ${shown.command}`).toBe(0);
    expect(shown.retrieved).toEqual([`${artifactId}-${version}.jar`]);

    // Without a conf, Ivy's default mapping also looks for the sources and javadoc artifacts, which
    // are not there. Ivy locates an artifact with a HEAD: while Repsy answered 200 for any path it
    // then failed downloading them ("FAILED DOWNLOADS"), now the 404 (RPS-1368) makes it skip them.
    const bare = await resolveWithIvy(world, { conf: null });
    expect(bare.clientExitCode, `ant retrieve: ${bare.command}`).toBe(0);
    expect(bare.retrieved).toEqual([`${artifactId}-${version}.jar`]);
    expect(output(bare)).not.toMatch(/FAILED/);
  });
});

test('ivy > the panel lists and shows a SNAPSHOT that Ivy published', async ({
  seeder,
  panelApi,
}) => {
  // RPS-1370: Ivy never sends the version-level maven-metadata.xml, so the detail finds the POM by
  // the literal file name in the version directory.
  const { world, repoName, groupId, artifactId, version } = await newWorld(
    seeder,
    'panel-snapshot',
    { versionType: 'snapshot' },
  );
  await publishOk(world);

  expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
    version,
  ]);
  const info = await panelApi.getMavenArtifactVersion(repoName, groupId, artifactId, version);
  expect(info.versionName).toBe(version);
  expect(info.pomFile, 'the panel serves the POM').toContain(
    `<artifactId>${artifactId}</artifactId>`,
  );
});

test('ivy > deleting one of two Ivy-published versions in the panel removes it and keeps the other', async ({
  seeder,
  panelApi,
}) => {
  // RPS-1331: Ivy never sends an artifact-level maven-metadata.xml, and the delete used to move the
  // files to the trash, answer 404 and leave the database row behind.
  const { world, repoName, groupId, artifactId, version } = await newWorld(seeder, 'delete');
  const second = uniqueVersion('release');
  for (const v of [version, second]) {
    await publishOk(
      withTarget(world, { packageName: world.publishTarget.packageName, version: v }),
    );
  }

  await panelApi.deleteMavenArtifactVersion(repoName, groupId, artifactId, version);

  expect(await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId)).toEqual([
    second,
  ]);
  const admin = adminCredential();
  const metadataPath = `${artifactDir(groupId, artifactId)}/maven-metadata.xml`;
  // RPS-1369: Ivy stores no maven-metadata.xml, and the generated one lists the versions that are left.
  expect(Object.keys(await repoTree(repoName)), 'Ivy sends no metadata').not.toContain(
    metadataPath,
  );
  const generated = await rawGet(repoName, admin, metadataPath);
  expect(generated.status, 'the generated maven-metadata.xml').toBe(200);
  expect(parseArtifactVersions(generated.body.toString('utf8'))).toEqual([second]);
  const dir = versionDir(groupId, artifactId, version);
  expect((await rawGet(repoName, admin, `${dir}/${artifactId}-${version}.jar`)).status).toBe(404);
  expect((await rawGet(repoName, admin, `${dir}/${artifactId}-${version}.pom`)).status).toBe(404);
  const keptDir = versionDir(groupId, artifactId, second);
  expect((await rawGet(repoName, admin, `${keptDir}/${artifactId}-${second}.jar`)).status).toBe(
    200,
  );
  expect((await rawGet(repoName, admin, `${keptDir}/${artifactId}-${second}.pom`)).status).toBe(
    200,
  );
  expect(Object.keys(await repoTree(repoName)), 'the delete stores no metadata file').not.toContain(
    metadataPath,
  );

  const info = await panelApi.getMavenArtifactVersion(repoName, groupId, artifactId, second);
  expect(info.versionName).toBe(second);
});
