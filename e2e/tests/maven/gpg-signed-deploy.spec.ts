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
 * Signed deploys by the two clients a publisher really signs with (RPS-1316), against a repo that
 * verifies every signature (`pgpVerifyAllSignaturesEnabled`, RPS-1188): `mvn deploy` with
 * `maven-gpg-plugin`, and Gradle's `maven-publish` + `signing` (`useGpgCmd()`). Both sign with the
 * `gpg` binary and a throwaway, passphrase-protected key generated inside the runner
 * (`src/clients/gpg.ts`, one isolated GNUPGHOME per test), whose public key is registered on the
 * repo through the panel API exactly as a user does it. The in-process OpenPGP.js signatures of
 * `pgp-signature.spec.ts` / `parallel-signed-deploy.spec.ts` are what this is the real-client
 * counterpart of.
 *
 * The same three deploys, for each client (a repo per test, the key server lookup off so the
 * outcome does not depend on a network):
 *  - signed by the REGISTERED key: the client exits 0, every file and its `.asc` is stored, and the
 *    version ends `signed`;
 *  - NOT signed at all: accepted, every file stored, no `.asc`, and the version is not `signed`;
 *  - signed by a key that is NOT registered on the repo: the client fails with a 404 (on a
 *    signature, or on the file a parked signature belongs to: `artifactSigningKeyNotRegistered`,
 *    pinned raw in `pgp-signature.spec.ts`), no `.asc` is stored and the version is not `signed`.
 *    The files uploaded before the failure stay: a deploy is not one transaction.
 *
 * Tagged `@gpg` for a focused run: `./run.sh test --protocol maven --grep @gpg`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import type { PanelApi } from '../../src/api/panel-api.js';
import { generateGpgKey, type GpgKey } from '../../src/clients/gpg.js';
import { adminCredential, rawGet, repoTree, versionDir } from '../../src/clients/maven-raw.js';
import {
  gradleSigningPublish,
  mavenGpgDeploy,
  type SigningDeployOptions,
  type SigningDeployResult,
} from '../../src/clients/maven-signing.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const ARTIFACT_ID = 'sig-lib';
const VERSION = '1.0';
const PGP_HEADER = '-----BEGIN PGP SIGNATURE-----';

interface SigningClient {
  name: string;
  deploy: (opts: SigningDeployOptions) => Promise<SigningDeployResult>;
  /** The files (not their checksums) the client uploads, relative to the version directory. */
  files: string[];
  /** What the client prints once it has signed with gpg: the failure below is not a signing one. */
  signedWithGpg: RegExp;
  /**
   * What the client prints when the server answers 404. It is the `.asc` itself or the file it
   * signs, depending on which of the two the client sent second: a signature that arrives before
   * its file is parked, and only verified (and refused, for an unregistered key) with the file.
   */
  refusedWith404: RegExp;
}

const BASE = `${ARTIFACT_ID}-${VERSION}`;
const CLIENTS: SigningClient[] = [
  {
    name: 'maven-gpg-plugin (mvn deploy)',
    deploy: mavenGpgDeploy,
    files: [`${BASE}.pom`, `${BASE}.jar`, `${BASE}-sources.jar`],
    signedWithGpg: /Signer 'gpg' is signing 3 files/,
    // Maven Resolver reports a 404 on a PUT as a missing artifact.
    refusedWith404: /Could not find artifact /,
  },
  {
    name: 'Gradle maven-publish + signing (gradle publish)',
    deploy: gradleSigningPublish,
    // Gradle also publishes (and signs) its module metadata.
    files: [`${BASE}.pom`, `${BASE}.jar`, `${BASE}-sources.jar`, `${BASE}.module`],
    signedWithGpg: /> Task :signMavenJavaPublication\b(?! FAILED)/,
    refusedWith404: /Could not PUT '[^']*'\. Received status code 404/,
  },
];

interface Setup {
  repoName: string;
  groupId: string;
  signer: GpgKey;
}

/**
 * A repo that verifies every signature, and the key the client signs with. `registered` is the key
 * whose public half is registered on the repo: the signer itself, or, for the "wrong key" case, a
 * different key.
 */
async function setUp(
  seeder: Seeder,
  keys: GpgKey[],
  registered: 'signer' | 'another key',
): Promise<Setup> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  await seeder.setSettings(repo.name, {
    pgpVerifyAllSignaturesEnabled: true,
    pgpKeyServerLookupEnabled: false,
  });

  const signer = await generateGpgKey();
  keys.push(signer);
  let publicKey = signer;
  if (registered === 'another key') {
    publicKey = await generateGpgKey();
    keys.push(publicKey);
  }
  const item = await seeder.registerPgpPublicKey(repo.name, publicKey.publicKeyArmored);
  expect(item.keyId, 'the registered key id is the one gpg reports').toBe(publicKey.keyIdHex);

  return { repoName: repo.name, groupId: `io.repsy.e2e.${seeder.runId}`, signer };
}

async function versionSigned(panelApi: PanelApi, setup: Setup): Promise<boolean> {
  const version = await panelApi.getMavenArtifactVersion(
    setup.repoName,
    setup.groupId,
    ARTIFACT_ID,
    VERSION,
  );
  return version.signed === true;
}

function diagnostics(result: SigningDeployResult): string {
  return `exit ${result.exitCode}${result.timedOut ? ' (timed out)' : ''}\n${(result.stdout + result.stderr).slice(-2500)}`;
}

for (const client of CLIENTS) {
  test.describe(`real signed deploy: ${client.name} (RPS-1316)`, () => {
    // A real client with a cold JVM: generous, and the runner's own default is 120 s.
    test.describe.configure({ timeout: 240_000 });

    // Every key made by a test is disposed (its gpg-agent stopped, its GNUPGHOME removed) whatever
    // the outcome; the repos are the seeder's, cleaned up by its own fixture.
    let keys: GpgKey[] = [];

    test.afterEach(async () => {
      const made = keys;
      keys = [];
      await Promise.all(made.map((key) => key.dispose()));
    });

    test(
      'a deploy signed with the registered key is accepted and the version ends signed',
      { tag: ['@gpg', '@settings'] },
      async ({ seeder, panelApi }) => {
        const setup = await setUp(seeder, keys, 'signer');

        const result = await client.deploy({
          repoName: setup.repoName,
          groupId: setup.groupId,
          artifactId: ARTIFACT_ID,
          version: VERSION,
          key: setup.signer,
        });

        expect(result.exitCode, diagnostics(result)).toBe(0);
        const dir = versionDir(setup.groupId, ARTIFACT_ID, VERSION);
        for (const file of client.files) {
          const stored = await rawGet(setup.repoName, adminCredential(), `${dir}/${file}`);
          expect(stored.status, `GET ${file}`).toBe(200);
          const signature = await rawGet(setup.repoName, adminCredential(), `${dir}/${file}.asc`);
          expect(signature.status, `GET ${file}.asc`).toBe(200);
          expect(signature.body.toString('utf8'), `${file}.asc is a PGP signature`).toContain(
            PGP_HEADER,
          );
        }
        expect(await versionSigned(panelApi, setup), 'the version is signed').toBe(true);
      },
    );

    test(
      'an unsigned deploy is accepted with every file stored, but the version is not signed',
      { tag: ['@gpg', '@settings', '@negative'] },
      async ({ seeder, panelApi }) => {
        const setup = await setUp(seeder, keys, 'signer');

        const result = await client.deploy({
          repoName: setup.repoName,
          groupId: setup.groupId,
          artifactId: ARTIFACT_ID,
          version: VERSION,
        });

        expect(result.exitCode, diagnostics(result)).toBe(0);
        const dir = versionDir(setup.groupId, ARTIFACT_ID, VERSION);
        for (const file of client.files) {
          const stored = await rawGet(setup.repoName, adminCredential(), `${dir}/${file}`);
          expect(stored.status, `GET ${file}`).toBe(200);
        }
        const paths = Object.keys(await repoTree(setup.repoName));
        expect(
          paths.filter((p) => p.endsWith('.asc')),
          'no signature was sent, none is stored',
        ).toEqual([]);
        expect(await versionSigned(panelApi, setup), 'the version is signed').toBe(false);
      },
    );

    test(
      'a deploy signed with a key that is not registered on the repo fails and stores no signature',
      { tag: ['@gpg', '@settings', '@negative'] },
      async ({ seeder, panelApi }) => {
        const setup = await setUp(seeder, keys, 'another key');

        const result = await client.deploy({
          repoName: setup.repoName,
          groupId: setup.groupId,
          artifactId: ARTIFACT_ID,
          version: VERSION,
          key: setup.signer,
        });

        expect(result.exitCode, `the client must fail\n${diagnostics(result)}`).not.toBe(0);
        const output = result.stdout + result.stderr;
        expect(output, 'gpg signed, so the failure is the server refusing the signature').toMatch(
          client.signedWithGpg,
        );
        expect(output, 'the refusal is a 404').toMatch(client.refusedWith404);
        const paths = Object.keys(await repoTree(setup.repoName));
        expect(
          paths.filter((p) => p.endsWith('.asc')),
          'a signature by an unregistered key is refused, none is stored',
        ).toEqual([]);
        expect(await versionSigned(panelApi, setup), 'the version is signed').toBe(false);
      },
    );
  });
}
