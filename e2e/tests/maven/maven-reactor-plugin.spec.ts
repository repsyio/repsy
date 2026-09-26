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

/**
 * Maven packagings deployed and consumed by the real `mvn` (RPS-1488): a multi-module reactor
 * (parent POM + jar + war in ONE `mvn deploy`), a standalone war, and a `maven-plugin` deployed by
 * `mvn deploy` and run by prefix. Recovered from the step-5 branch `rps-294-e2e-maven-specific-a`
 * (which ported repsy-cloud's `protocols/maven/{multi_module,war}`) and ported to the current
 * client environment and helpers; the plugin case is new.
 *
 *  - RA1 (`@smoke`): a reactor root (`packaging=pom`) with a jar module and a war module that depends on
 *    the jar is deployed by one `mvn deploy`: the three reactor entries are deployed, the repo holds the
 *    parent POM, the jar, the war and their POMs, each with its checksums, and each artifact's
 *    `maven-metadata.xml` lists the version. A second project that depends on the jar and the war
 *    resolves them (and the parent POM they name) into a clean local repository, byte for byte.
 *  - RA2 (`@smoke`): a hand-built `packaging=war` project is deployed and resolved back by a real
 *    consumer, with the bytes it built, a raw `GET` of the war is 200 and its `.sha1` is the digest of
 *    the war.
 *  - RA3 (`@smoke`): the hello `maven-plugin` is deployed by `mvn deploy` (which, unlike Gradle, sbt or
 *    Ivy, uploads the group-level `maven-metadata.xml` itself): that file is STORED (listed in the
 *    directory), lists the plugin by its prefix, and `mvn hello:hi` from a clean local repository finds the
 *    plugin by that prefix and runs it. README "Maven plugin prefix" (RPS-1438, RPS-1457, RPS-1458) covers
 *    the plugins published without the file.
 *  - RA4: a second plugin deployed by `mvn deploy` (Maven downloads the stored group-level file, adds its
 *    entry and uploads it) leaves both listed, and both prefixes run.
 *  - RA5 (RPS-1458, RPS-1589): a plugin with its own `goalPrefix` (`tl`) deployed by `mvn deploy` onto that
 *    group is listed by it, ONCE and not under the prefix derived from its artifactId (the POM arrives before
 *    the jar, and the jar corrects the entry the POM added), and run by `mvn tl:hi`.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';

import { RepoType } from '../../src/api/panel-api.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import {
  consume,
  deployReactor,
  deployWar,
  localRepoFile,
  type ReactorLayout,
} from '../../src/clients/maven-reactor.js';
import {
  BYE_PLUGIN,
  deployPlugin,
  groupMetadataPath,
  HELLO_PLUGIN,
  PLUGIN_ARTIFACT_ID,
  PLUGIN_GROUP_ID,
  PLUGIN_MARKER,
  PLUGIN_PREFIX,
  PLUGIN_VERSION,
  pluginFilePath,
  runPrefixGoal,
  TOOL_PLUGIN,
} from '../../src/clients/maven-plugin.js';
import {
  adminCredential,
  artifactDir,
  parseArtifactVersions,
  rawGet,
  repoTree,
  sha256Hex,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

// A real Maven build of a war or a plugin needs plugins from Central on a cold cache.
test.describe.configure({ timeout: 420_000 });

/** A fresh private maven repo and a fresh read-write deploy token for it, like every deploy of the
 *  dedicated Maven specs (`ivy-client.spec.ts`): the credential a real `mvn deploy` is given. */
async function newRepoWithToken(
  seeder: Seeder,
): Promise<{ repoName: string; credential: MaterializedCredential }> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  return {
    repoName: repo.name,
    credential: {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    },
  };
}

const digest = (algorithm: string, body: Buffer): string =>
  createHash(algorithm).update(body).digest('hex');

/** Maven uploads an MD5 and an SHA-1 next to every file it deploys, and no other digest. */
function expectChecksums(tree: Record<string, string>, file: string): void {
  for (const extension of ['md5', 'sha1']) {
    expect(Object.keys(tree), `${file}.${extension} is stored`).toContain(`${file}.${extension}`);
  }
}

test(
  'maven reactor > one mvn deploy of a parent POM, a jar and a war, consumed by a second project',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const { repoName, credential } = await newRepoWithToken(seeder);
    const layout: ReactorLayout = {
      groupId: `io.repsy.e2e.${seeder.runId}.reactor`,
      version: mavenAdapter.version('release'),
      parentArtifactId: 'reactor-parent',
      libArtifactId: 'reactor-lib',
      webArtifactId: 'reactor-web',
    };
    const { groupId, version } = layout;

    const deployed = await deployReactor(repoName, credential, layout);
    const output = `${deployed.result.stdout}\n${deployed.result.stderr}`;
    expect(
      deployed.result.exitCode,
      `mvn deploy (reactor): ${deployed.result.command}\n${output}`,
    ).toBe(0);
    for (const artifactId of [
      layout.parentArtifactId,
      layout.libArtifactId,
      layout.webArtifactId,
    ]) {
      expect(output, `${artifactId} is a reactor entry that ran deploy:deploy`).toMatch(
        new RegExp(`--- deploy:\\S+:deploy \\(default-deploy\\) @ ${artifactId} ---`),
      );
    }

    // What the repo holds: the parent POM, each module's POM, the jar and the war, each with checksums.
    const tree = await repoTree(repoName);
    const fileOf = (artifactId: string, extension: string) =>
      `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}.${extension}`;
    for (const file of [
      fileOf(layout.parentArtifactId, 'pom'),
      fileOf(layout.libArtifactId, 'pom'),
      fileOf(layout.libArtifactId, 'jar'),
      fileOf(layout.webArtifactId, 'pom'),
      fileOf(layout.webArtifactId, 'war'),
    ]) {
      expect(Object.keys(tree), `${file} is stored`).toContain(file);
      expectChecksums(tree, file);
    }
    expect(tree[fileOf(layout.libArtifactId, 'jar')], 'the stored jar is the built one').toBe(
      deployed.libSha256,
    );
    expect(tree[fileOf(layout.webArtifactId, 'war')], 'the stored war is the built one').toBe(
      deployed.webSha256,
    );

    const admin = adminCredential();
    for (const artifactId of [
      layout.parentArtifactId,
      layout.libArtifactId,
      layout.webArtifactId,
    ]) {
      const metadata = await rawGet(
        repoName,
        admin,
        `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
      );
      expect(metadata.status, `GET the maven-metadata.xml of ${artifactId}`).toBe(200);
      expect(parseArtifactVersions(metadata.body.toString('utf8')), artifactId).toEqual([version]);
    }

    // The second project: depends on the jar and the war, resolves them from the server alone.
    const consumed = await consume(repoName, credential, layout);
    expect(
      consumed.result.exitCode,
      `mvn dependency:copy-dependencies: ${consumed.result.command}\n${consumed.result.stdout}\n${consumed.result.stderr}`,
    ).toBe(0);
    expect(
      sha256Hex(await fs.readFile(`${consumed.copied}/${layout.libArtifactId}-${version}.jar`)),
      'the consumer got the deployed jar',
    ).toBe(deployed.libSha256);
    expect(
      sha256Hex(await fs.readFile(`${consumed.copied}/${layout.webArtifactId}-${version}.war`)),
      'the consumer got the deployed war',
    ).toBe(deployed.webSha256);
    const parentPom = await fs.readFile(
      localRepoFile(consumed.localRepo, groupId, layout.parentArtifactId, version, 'pom'),
    );
    expect(
      sha256Hex(parentPom),
      'the parent POM the jar and the war name was resolved from the server',
    ).toBe(tree[fileOf(layout.parentArtifactId, 'pom')]);
  },
);

test(
  'maven war > a hand-built packaging=war project deploys and resolves back with the bytes it built',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const { repoName, credential } = await newRepoWithToken(seeder);
    const groupId = `io.repsy.e2e.${seeder.runId}.war`;
    const artifactId = 'war-roundtrip';
    const version = mavenAdapter.version('release');

    const deployed = await deployWar(repoName, credential, { groupId, artifactId, version });
    expect(
      deployed.result.exitCode,
      `mvn deploy (war): ${deployed.result.command}\n${deployed.result.stdout}\n${deployed.result.stderr}`,
    ).toBe(0);
    expect(deployed.sha256, 'a war was built').not.toBe(sha256Hex(Buffer.alloc(0)));

    const consumed = await consume(repoName, credential, {
      groupId,
      webArtifactId: artifactId,
      version,
    });
    expect(
      consumed.result.exitCode,
      `mvn dependency:copy-dependencies: ${consumed.result.command}\n${consumed.result.stdout}\n${consumed.result.stderr}`,
    ).toBe(0);
    expect(
      sha256Hex(await fs.readFile(`${consumed.copied}/${artifactId}-${version}.war`)),
      'the consumer got the deployed war',
    ).toBe(deployed.sha256);

    const warPath = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}.war`;
    const war = await rawGet(repoName, adminCredential(), warPath);
    expect(war.status, 'raw GET of the deployed war').toBe(200);
    expect(sha256Hex(war.body)).toBe(deployed.sha256);
    const sha1 = await rawGet(repoName, adminCredential(), `${warPath}.sha1`);
    expect(sha1.status, 'the .sha1 Maven uploaded next to the war').toBe(200);
    expect(sha1.body.toString('utf8').trim()).toBe(digest('sha1', war.body));
  },
);

test(
  'maven plugin > mvn deploy of a maven-plugin stores the group-level file Maven uploads, and mvn prefix:goal runs it from a clean local repository',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const { repoName, credential } = await newRepoWithToken(seeder);

    const deployed = await deployPlugin(repoName, credential);
    expect(
      deployed.exitCode,
      `mvn deploy (plugin): ${deployed.command}\n${deployed.stdout}\n${deployed.stderr}`,
    ).toBe(0);

    // Maven itself uploaded the group-level file (and the artifact-level one): both are stored.
    const tree = await repoTree(repoName);
    expect(Object.keys(tree), 'the group-level maven-metadata.xml is stored').toContain(
      groupMetadataPath(),
    );
    expectChecksums(tree, groupMetadataPath());
    const artifactMetadata = `${artifactDir(PLUGIN_GROUP_ID, PLUGIN_ARTIFACT_ID)}/maven-metadata.xml`;
    expect(Object.keys(tree)).toContain(artifactMetadata);
    for (const name of [
      `${PLUGIN_ARTIFACT_ID}-${PLUGIN_VERSION}.jar`,
      `${PLUGIN_ARTIFACT_ID}-${PLUGIN_VERSION}.pom`,
    ]) {
      expect(Object.keys(tree), name).toContain(pluginFilePath(name));
    }

    const admin = adminCredential();
    const group = await rawGet(repoName, admin, groupMetadataPath());
    expect(group.status).toBe(200);
    const xml = group.body.toString('utf8');
    expect(xml).toContain('<plugins>');
    expect(xml).toContain(`<prefix>${PLUGIN_PREFIX}</prefix>`);
    expect(xml).toContain(`<artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>`);
    expect(xml).toContain(`<name>${HELLO_PLUGIN.name}</name>`);
    expect(sha256Hex(group.body), 'served as it was stored').toBe(tree[groupMetadataPath()]);
    const artifact = await rawGet(repoName, admin, artifactMetadata);
    expect(parseArtifactVersions(artifact.body.toString('utf8'))).toEqual([PLUGIN_VERSION]);

    // A clean local repository, the group in pluginGroups: the prefix resolves through that file.
    const result = await runPrefixGoal(repoName, credential);
    const output = `${result.stdout}\n${result.stderr}`;
    expect(result.exitCode, `mvn ${PLUGIN_PREFIX}:hi: ${result.command}\n${output}`).toBe(0);
    expect(output, 'the goal of the plugin ran').toContain(PLUGIN_MARKER);
    expect(output).not.toContain('No plugin found for prefix');

    expect(await repoTree(repoName), 'running the plugin stored nothing').toEqual(tree);
  },
);

test('maven plugin > a second plugin deployed by mvn joins the group-level file Maven stored, and both prefixes run', async ({
  seeder,
}) => {
  const { repoName, credential } = await newRepoWithToken(seeder);

  for (const spec of [HELLO_PLUGIN, BYE_PLUGIN]) {
    const deployed = await deployPlugin(repoName, credential, spec);
    expect(
      deployed.exitCode,
      `mvn deploy (${spec.artifactId}): ${deployed.command}\n${deployed.stdout}\n${deployed.stderr}`,
    ).toBe(0);
  }

  const group = await rawGet(repoName, adminCredential(), groupMetadataPath());
  expect(group.status).toBe(200);
  const xml = group.body.toString('utf8');
  for (const spec of [HELLO_PLUGIN, BYE_PLUGIN]) {
    expect(xml, `${spec.artifactId} is listed`).toContain(
      `<artifactId>${spec.artifactId}</artifactId>`,
    );
    expect(xml).toContain(`<prefix>${spec.prefix}</prefix>`);
    expect(
      xml.split(`<artifactId>${spec.artifactId}</artifactId>`).length - 1,
      `${spec.artifactId} once`,
    ).toBe(1);
  }
  const sha1 = await rawGet(repoName, adminCredential(), groupMetadataPath('.sha1'));
  expect(sha1.body.toString('utf8').trim(), 'the stored .sha1 is that of the merged file').toBe(
    digest('sha1', group.body),
  );

  for (const spec of [HELLO_PLUGIN, BYE_PLUGIN]) {
    const result = await runPrefixGoal(repoName, credential, spec.prefix);
    const output = `${result.stdout}\n${result.stderr}`;
    expect(result.exitCode, `mvn ${spec.prefix}:hi: ${result.command}\n${output}`).toBe(0);
    expect(output, `the goal of ${spec.artifactId} ran`).toContain(spec.marker);
  }
});

test('maven plugin > a plugin with its own goalPrefix deployed by mvn is listed once and run by that prefix (RPS-1458, RPS-1589)', async ({
  seeder,
}) => {
  const { repoName, credential } = await newRepoWithToken(seeder);

  for (const spec of [HELLO_PLUGIN, TOOL_PLUGIN]) {
    const deployed = await deployPlugin(repoName, credential, spec);
    expect(
      deployed.exitCode,
      `mvn deploy (${spec.artifactId}): ${deployed.command}\n${deployed.stdout}\n${deployed.stderr}`,
    ).toBe(0);
  }

  const group = await rawGet(repoName, adminCredential(), groupMetadataPath());
  expect(group.status).toBe(200);
  const xml = group.body.toString('utf8');
  expect(xml, 'the plugin is listed by its own goalPrefix').toContain(
    `<prefix>${TOOL_PLUGIN.prefix}</prefix>`,
  );
  expect(xml).toContain(`<artifactId>${TOOL_PLUGIN.artifactId}</artifactId>`);
  expect(
    xml.split(`<artifactId>${TOOL_PLUGIN.artifactId}</artifactId>`).length - 1,
    'the plugin is listed once',
  ).toBe(1);
  expect(xml, 'and not under the prefix derived from its artifactId').not.toContain(
    '<prefix>tool</prefix>',
  );
  expect(xml, 'the first plugin is still listed, once').toContain(
    `<artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>`,
  );
  expect(xml.split(`<artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>`).length - 1).toBe(1);

  for (const spec of [TOOL_PLUGIN, HELLO_PLUGIN]) {
    const result = await runPrefixGoal(repoName, credential, spec.prefix);
    const output = `${result.stdout}\n${result.stderr}`;
    expect(result.exitCode, `mvn ${spec.prefix}:hi: ${result.command}\n${output}`).toBe(0);
    expect(output, `the goal of ${spec.artifactId} ran`).toContain(spec.marker);
  }
});
