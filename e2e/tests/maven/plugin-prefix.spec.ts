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
 * Maven plugin prefix resolution against a plugin that was published without a group-level
 * `maven-metadata.xml` (RPS-1438). `mvn hello:hi` (with the plugin's group in `pluginGroups`) finds a
 * plugin by its prefix in `<group path>/maven-metadata.xml`. `mvn deploy` uploads that file, but
 * Gradle's `maven-publish`, sbt, Ivy and a plain PUT do not, so the plugin they published was not found
 * ("No plugin found for prefix 'hello'") although it is in the repo. Repsy answers the file from the
 * plugins it has registered, when none is stored.
 *
 *  - PP1: the plugin (built by the real `mvn package`, its jar and POM uploaded and nothing else) is
 *    run by prefix by the real `mvn`, the generated file and its four checksums are served and match,
 *    a HEAD has the length of the GET, a signature is a 404, and nothing is stored for any of it.
 *  - PP2 (control): the same command against a repo with no plugin still fails with the same
 *    message, and the file is a 404.
 *  - PP3: a group-level file a client stored is served as it is and used, its checksum is a 404 until
 *    one is stored.
 *  - PP4 (RPS-1457): a second plugin of the group, published without the file after a group-level
 *    file was stored (what `mvn deploy` of the first one leaves), is added to that file, so the real
 *    `mvn bye:hi` finds it, and the stored checksum is rewritten.
 *  - PP5 (RPS-1458): a plugin with its own `goalPrefix` (`tool-maven-plugin`, prefix `tl`), published
 *    jar first and then the POM (Gradle's order) and without the file, is listed and found by that
 *    prefix, not by the one derived from its artifactId.
 */
import { createHash } from 'node:crypto';

import { RepoType } from '../../src/api/panel-api.js';
import {
  buildHelloPlugin,
  buildPlugin,
  BYE_PLUGIN,
  groupMetadataPath,
  PLUGIN_ARTIFACT_ID,
  PLUGIN_GROUP_ID,
  PLUGIN_MARKER,
  PLUGIN_PREFIX,
  runPrefixGoal,
  TOOL_PLUGIN,
  uploadPluginFiles,
} from '../../src/clients/maven-plugin.js';
import { adminCredential, rawGet, rawHead, rawPut, repoTree } from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

// A plugin build from Central (once per worker) and a cold Maven per resolve.
test.describe.configure({ timeout: 300_000 });

const digest = (algorithm: string, body: Buffer): string =>
  createHash(algorithm).update(body).digest('hex');

test(
  'plugin-prefix > mvn prefix:goal runs a plugin that was published without a group-level maven-metadata.xml',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const plugin = await buildHelloPlugin();
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const credential = adminCredential();
    await uploadPluginFiles(repo.name, credential, plugin);

    const before = await repoTree(repo.name);
    expect(
      Object.keys(before).filter((file) => file.endsWith('maven-metadata.xml')),
      'the upload stored no metadata at all',
    ).toEqual([]);

    const result = await runPrefixGoal(repo.name, credential);
    const output = `${result.stdout}\n${result.stderr}`;
    expect(result.exitCode, `mvn ${PLUGIN_PREFIX}:hi: ${result.command}\n${output}`).toBe(0);
    expect(output, 'the goal of the plugin ran').toContain(PLUGIN_MARKER);
    expect(output).not.toContain('No plugin found for prefix');

    const file = await rawGet(repo.name, credential, groupMetadataPath());
    expect(file.status).toBe(200);
    const xml = file.body.toString('utf8');
    expect(xml).toContain('<plugins>');
    expect(xml).toContain(`<prefix>${PLUGIN_PREFIX}</prefix>`);
    expect(xml).toContain(`<artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>`);
    expect(xml).toContain('<name>Hello Maven Plugin</name>');
    expect(xml, 'a group-level file has no versioning').not.toContain('<versioning>');

    for (const [extension, algorithm] of [
      ['md5', 'md5'],
      ['sha1', 'sha1'],
      ['sha256', 'sha256'],
      ['sha512', 'sha512'],
    ] as const) {
      const checksum = await rawGet(repo.name, credential, groupMetadataPath(`.${extension}`));
      expect(checksum.status, `.${extension}`).toBe(200);
      expect(checksum.body.toString('utf8').trim(), `.${extension} is that of the file`).toBe(
        digest(algorithm, file.body),
      );
    }

    const head = await rawHead(repo.name, credential, groupMetadataPath());
    expect(head.status).toBe(200);
    expect(head.contentLength, 'a HEAD has the length of the GET').toBe(file.body.length);
    expect(head.bodyLength).toBe(0);

    for (const suffix of ['.asc', '.asc.sha1']) {
      expect((await rawGet(repo.name, credential, groupMetadataPath(suffix))).status).toBe(404);
    }

    expect(await repoTree(repo.name), 'nothing generated is stored').toEqual(before);
    const listing = await rawGet(repo.name, credential, `${PLUGIN_GROUP_ID.replaceAll('.', '/')}/`);
    expect(listing.body.toString('utf8')).not.toContain('maven-metadata.xml');
  },
);

test('plugin-prefix > without a plugin in the repo the same command still finds none', async ({
  seeder,
}) => {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const credential = adminCredential();

  const result = await runPrefixGoal(repo.name, credential);
  const output = `${result.stdout}\n${result.stderr}`;
  expect(result.exitCode, `mvn ${PLUGIN_PREFIX}:hi: ${result.command}`).not.toBe(0);
  expect(output).toContain(`No plugin found for prefix '${PLUGIN_PREFIX}'`);

  expect((await rawGet(repo.name, credential, groupMetadataPath())).status).toBe(404);
  expect((await rawGet(repo.name, credential, groupMetadataPath('.sha1'))).status).toBe(404);
});

test('plugin-prefix > a group-level file a client stored is served as stored, its checksum is not generated', async ({
  seeder,
}) => {
  const plugin = await buildHelloPlugin();
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const credential = adminCredential();
  await uploadPluginFiles(repo.name, credential, plugin);

  const stored =
    '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <plugins>\n    <plugin>\n' +
    '      <name>Stored By The Client</name>\n' +
    `      <prefix>${PLUGIN_PREFIX}</prefix>\n` +
    `      <artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>\n` +
    '    </plugin>\n  </plugins>\n</metadata>\n';
  const put = await rawPut(
    repo.name,
    credential,
    groupMetadataPath(),
    stored,
    'application/octet-stream',
  );
  expect(put.status, 'a client may store the group-level file').toBe(200);

  const file = await rawGet(repo.name, credential, groupMetadataPath());
  expect(file.body.toString('utf8'), 'the stored file wins').toBe(stored);
  expect((await rawGet(repo.name, credential, groupMetadataPath('.sha1'))).status).toBe(404);

  const result = await runPrefixGoal(repo.name, credential);
  expect(result.exitCode, `mvn ${PLUGIN_PREFIX}:hi: ${result.command}`).toBe(0);
  expect(`${result.stdout}\n${result.stderr}`).toContain(PLUGIN_MARKER);

  const sha1 = digest('sha1', file.body);
  expect(
    (await rawPut(repo.name, credential, groupMetadataPath('.sha1'), sha1, 'text/plain')).status,
  ).toBe(200);
  expect(
    (await rawGet(repo.name, credential, groupMetadataPath('.sha1'))).body.toString('utf8'),
  ).toBe(sha1);
});

test('plugin-prefix > a plugin published without the file after a stored group-level file is added to that file (RPS-1457)', async ({
  seeder,
}) => {
  const hello = await buildHelloPlugin();
  const bye = await buildPlugin(BYE_PLUGIN);
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const credential = adminCredential();
  await uploadPluginFiles(repo.name, credential, hello);

  // What `mvn deploy` of the hello plugin stores: a group-level file that lists it, and its digest.
  const stored =
    '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <plugins>\n    <plugin>\n' +
    '      <name>Stored By Maven</name>\n' +
    `      <prefix>${PLUGIN_PREFIX}</prefix>\n` +
    `      <artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>\n` +
    '    </plugin>\n  </plugins>\n</metadata>\n';
  expect(
    (await rawPut(repo.name, credential, groupMetadataPath(), stored, 'application/octet-stream'))
      .status,
  ).toBe(200);
  expect(
    (
      await rawPut(
        repo.name,
        credential,
        groupMetadataPath('.sha1'),
        digest('sha1', Buffer.from(stored)),
        'text/plain',
      )
    ).status,
  ).toBe(200);

  // Gradle, sbt or Ivy publish the second plugin: its jar and its POM, and no group-level file.
  await uploadPluginFiles(repo.name, credential, bye);

  const file = await rawGet(repo.name, credential, groupMetadataPath());
  expect(file.status).toBe(200);
  const xml = file.body.toString('utf8');
  expect(xml, 'the entry Maven stored is kept as it was').toContain('<name>Stored By Maven</name>');
  expect(xml).toContain(`<artifactId>${PLUGIN_ARTIFACT_ID}</artifactId>`);
  expect(xml, 'the second plugin is listed').toContain(
    `<artifactId>${BYE_PLUGIN.artifactId}</artifactId>`,
  );
  expect(xml).toContain(`<prefix>${BYE_PLUGIN.prefix}</prefix>`);
  expect(xml).toContain(`<name>${BYE_PLUGIN.name}</name>`);
  expect(xml, 'a group-level file has no versioning').not.toContain('<versioning>');
  expect(
    (await rawGet(repo.name, credential, groupMetadataPath('.sha1'))).body.toString('utf8').trim(),
    'the stored digest is that of the rewritten file',
  ).toBe(digest('sha1', file.body));
  expect(
    (await rawGet(repo.name, credential, groupMetadataPath('.sha256'))).status,
    'no checksum is created next to a stored file',
  ).toBe(404);

  const found = await runPrefixGoal(repo.name, credential, BYE_PLUGIN.prefix);
  const output = `${found.stdout}\n${found.stderr}`;
  expect(found.exitCode, `mvn ${BYE_PLUGIN.prefix}:hi: ${found.command}\n${output}`).toBe(0);
  expect(output, 'the goal of the second plugin ran').toContain(BYE_PLUGIN.marker);
  expect(output).not.toContain('No plugin found for prefix');

  const first = await runPrefixGoal(repo.name, credential);
  expect(first.exitCode, `mvn ${PLUGIN_PREFIX}:hi: ${first.command}`).toBe(0);
  expect(`${first.stdout}\n${first.stderr}`).toContain(PLUGIN_MARKER);
});

test('plugin-prefix > a plugin with its own goalPrefix published without the file is found by that prefix (RPS-1458)', async ({
  seeder,
}) => {
  const tool = await buildPlugin(TOOL_PLUGIN);
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const credential = adminCredential();
  // The jar first and then the POM, which registers the plugin: the order of Gradle's maven-publish.
  await uploadPluginFiles(repo.name, credential, tool);

  const file = await rawGet(repo.name, credential, groupMetadataPath());
  expect(file.status).toBe(200);
  const xml = file.body.toString('utf8');
  expect(xml, 'the plugin is listed by the goalPrefix of its plugin.xml').toContain(
    `<prefix>${TOOL_PLUGIN.prefix}</prefix>`,
  );
  expect(xml).toContain(`<artifactId>${TOOL_PLUGIN.artifactId}</artifactId>`);
  expect(xml, 'not by the one derived from the artifactId').not.toContain('<prefix>tool</prefix>');

  const found = await runPrefixGoal(repo.name, credential, TOOL_PLUGIN.prefix);
  const output = `${found.stdout}\n${found.stderr}`;
  expect(found.exitCode, `mvn ${TOOL_PLUGIN.prefix}:hi: ${found.command}\n${output}`).toBe(0);
  expect(output, 'the goal of the plugin ran').toContain(TOOL_PLUGIN.marker);
  expect(output).not.toContain('No plugin found for prefix');

  const derived = await runPrefixGoal(repo.name, credential, 'tool');
  expect(derived.exitCode, `mvn tool:hi: ${derived.command}`).not.toBe(0);
  expect(`${derived.stdout}\n${derived.stderr}`).toContain("No plugin found for prefix 'tool'");
});
