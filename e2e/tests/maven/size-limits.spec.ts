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

/**
 * RPS-1482: the Maven upload limits, on the DEFAULT stack (no overlay). Unlike every other format,
 * Maven's limits are not configurable and are not multipart: they are fixed in the protocol handlers
 * (`MavenUploadSizeLimitIT` pins them in MockMvc) and a breach is a plain 400 with a msgId that names
 * the limit, NOT the 413 `payloadTooLarge` of the size-limit leg (README.md "Size-limit leg"):
 *
 *   maven-metadata.xml  10 MiB   400 `mavenMetadataTooLarge`   "The maven-metadata.xml file is larger than 10 MiB."
 *   a POM               10 MiB   400 `pomFileTooLarge`         "The POM file is larger than 10 MiB."
 *   a POM's `.asc`      64 KiB   400 `mavenSignatureTooLarge`  "The signature file is larger than 64 KiB."
 *
 * Each is refused before anything is stored. What the tests do that the IT cannot: the POM case is
 * a real `mvn deploy` of a project whose POM is over the limit (the exit code, what Maven prints and
 * the raw replay of the same POM), and every case runs through Tomcat with a real body. A signature
 * over 64 KiB cannot come from a real signer (a signature is a few hundred bytes), so the `.asc` and
 * the metadata cases are raw PUTs; the boundary is pinned on both sides for the POM (exactly 10 MiB is
 * accepted) and the `.asc` (exactly 64 KiB passes the size rule and meets the next one, the 422 of the
 * signature check).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { deploy } from '../../src/clients/maven.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import {
  adminCredential,
  artifactDir,
  minimalPom,
  rawGet,
  rawPut,
  type RawResponse,
  repoTree,
  splitPackageName,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { oversizeWorld, SIZE_LIMIT_SCENARIO } from '../../src/clients/oversize.js';
import { expect, materializeCredentialKind, test } from '../../src/scenarios/fixtures.js';

const MIB = 1024 * 1024;
const KIB = 1024;
const MAX_POM_BYTES = 10 * MIB;
const MAX_METADATA_BYTES = 10 * MIB;
const MAX_SIGNATURE_BYTES = 64 * KIB;

const OCTET = 'application/octet-stream';
const XML = 'application/xml';

/** A valid POM of exactly `bytes` bytes: `minimalPom` with an XML comment that takes up the rest. */
function pomOfSize(groupId: string, artifactId: string, version: string, bytes: number): string {
  const bare = minimalPom(groupId, artifactId, version, '<!---->\n');
  const filler = bytes - Buffer.byteLength(bare, 'utf8');
  if (filler < 0) {
    throw new Error(`pomOfSize: ${bytes} bytes is below the smallest POM (${bare.length})`);
  }
  return minimalPom(groupId, artifactId, version, `<!--${'a'.repeat(filler)}-->\n`);
}

function expectRefused(res: RawResponse, msgId: string, text: string, what: string): void {
  expect(res.status, `${what}: ${res.body.toString('utf8').slice(0, 300)}`).toBe(400);
  expect(res.msgId, `${what}: msgId`).toBe(msgId);
  const body = JSON.parse(res.body.toString('utf8')) as { type?: string; text?: string };
  expect(body.type, `${what}: type`).toBe('ERROR');
  expect(body.text, `${what}: text`).toBe(text);
}

test.describe('maven upload size limits', { tag: '@negative' }, () => {
  // A 10 MiB body, several times over, and a real mvn deploy: more than a plain HTTP test.
  test.setTimeout(240_000);

  test('a real mvn deploy of a POM over 10 MiB is a 400 pomFileTooLarge; the POM is never stored', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const credential = await materializeCredentialKind(
      seeder,
      'token-rw',
      repo.name,
      RepoType.MAVEN,
    );
    const world = oversizeWorld('maven', repo.name, credential, {
      packageName: mavenAdapter.packageName(seeder.runId, SIZE_LIMIT_SCENARIO),
      version: mavenAdapter.version('release'),
    });
    const before = await mavenAdapter.fingerprint(world);

    const deployed = await deploy(world, { pomPadding: 'a'.repeat(MAX_POM_BYTES + 1) });

    expect(deployed.pomBytes.length, 'the POM really is over the limit').toBeGreaterThan(
      MAX_POM_BYTES,
    );
    expect(deployed.exitCode, `mvn deploy must fail: ${deployed.command}`).not.toBe(0);
    expect(deployed.output, 'what mvn prints').toMatch(
      /Could not transfer artifact \S+:pom:\S+ from\/to repsy \(\S+\): status code: 400, reason phrase:/,
    );

    // The same POM, raw, on the route mvn used: the server's own words for it.
    const [groupId, artifactId] = splitPackageName(world.publishTarget.packageName);
    const pomPath = `${versionDir(groupId, artifactId, world.publishTarget.version)}/${artifactId}-${world.publishTarget.version}.pom`;
    const replay = await rawPut(repo.name, credential, pomPath, deployed.pomBytes, OCTET);
    expectRefused(replay, 'pomFileTooLarge', 'The POM file is larger than 10 MiB.', 'raw replay');

    // A deploy is not one transaction: mvn uploads the jar (and its checksums) BEFORE the POM, and they
    // stay when the POM is refused (the same as an unregistered signing key, gpg-signed-deploy.spec.ts).
    // What must not exist is the POM, its checksums or any metadata: nothing was registered.
    const version = world.publishTarget.version;
    const jar = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}.jar`;
    expect(before, 'the repository started empty').toEqual({});
    expect(
      Object.keys(await repoTree(repo.name)).sort(),
      'only the jar and its checksums, uploaded before the POM, are there',
    ).toEqual([jar, `${jar}.md5`, `${jar}.sha1`]);
    const pomGet = await rawGet(repo.name, adminCredential(), pomPath);
    expect(pomGet.status, 'the refused POM is not served').toBe(404);
  });

  test('a POM of exactly 10 MiB is stored, one byte more is a 400', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const admin = adminCredential();
    const groupId = `io.repsy.e2e.${seeder.runId}`;
    const path = (artifactId: string): string =>
      `${versionDir(groupId, artifactId, '1.0')}/${artifactId}-1.0.pom`;

    const over = await rawPut(
      repo.name,
      admin,
      path('over'),
      pomOfSize(groupId, 'over', '1.0', MAX_POM_BYTES + 1),
      OCTET,
    );
    expectRefused(over, 'pomFileTooLarge', 'The POM file is larger than 10 MiB.', '10 MiB + 1');
    expect(await repoTree(repo.name), 'the refused POM stored nothing').toEqual({});

    const exact = await rawPut(
      repo.name,
      admin,
      path('exact'),
      pomOfSize(groupId, 'exact', '1.0', MAX_POM_BYTES),
      OCTET,
    );
    expect(exact.status, `10 MiB: ${exact.body.toString('utf8').slice(0, 300)}`).toBeLessThan(300);
    expect(Object.keys(await repoTree(repo.name)), 'the POM of exactly 10 MiB is stored').toContain(
      path('exact'),
    );
  });

  test('a maven-metadata.xml over 10 MiB is a 400 mavenMetadataTooLarge and stores nothing', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const admin = adminCredential();
    const groupId = `io.repsy.e2e.${seeder.runId}`;
    const before = await repoTree(repo.name);

    const res = await rawPut(
      repo.name,
      admin,
      `${artifactDir(groupId, 'lib')}/maven-metadata.xml`,
      `<metadata><!--${'a'.repeat(MAX_METADATA_BYTES + 1)}--></metadata>`,
      XML,
    );

    expectRefused(
      res,
      'mavenMetadataTooLarge',
      'The maven-metadata.xml file is larger than 10 MiB.',
      'metadata',
    );
    expect(await repoTree(repo.name), 'nothing was stored').toEqual(before);
  });

  test('a POM signature over 64 KiB is a 400 mavenSignatureTooLarge; exactly 64 KiB meets the signature check instead', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const admin = adminCredential();
    const groupId = `io.repsy.e2e.${seeder.runId}`;
    const pom = `${versionDir(groupId, 'lib', '1.0')}/lib-1.0.pom`;
    const seeded = await rawPut(repo.name, admin, pom, minimalPom(groupId, 'lib', '1.0'), OCTET);
    expect(seeded.status, 'the POM the signature belongs to').toBeLessThan(300);
    const before = await repoTree(repo.name);

    const over = await rawPut(
      repo.name,
      admin,
      `${pom}.asc`,
      'a'.repeat(MAX_SIGNATURE_BYTES + 1),
      OCTET,
    );
    expectRefused(
      over,
      'mavenSignatureTooLarge',
      'The signature file is larger than 64 KiB.',
      '64 KiB + 1',
    );
    expect(await repoTree(repo.name), 'the refused signature stored nothing').toEqual(before);

    // At the limit the size rule lets it through, and the next rule refuses it: it is not a signature.
    const exact = await rawPut(
      repo.name,
      admin,
      `${pom}.asc`,
      'a'.repeat(MAX_SIGNATURE_BYTES),
      OCTET,
    );
    expect(exact.status, `64 KiB: ${exact.body.toString('utf8').slice(0, 300)}`).toBe(422);
    expect(exact.msgId).toBe('artifactSignatureNotVerified');
    expect(await repoTree(repo.name), 'nor did that one').toEqual(before);
  });
});
