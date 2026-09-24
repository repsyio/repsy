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
 * A repo's Maven key store can hold armored OpenPGP public keys directly, consulted before any key
 * server when a `.pom.asc` is verified (RPS-1189). Real key pairs are generated in-process with
 * OpenPGP.js (`src/clients/pgp.ts`), no `gpg` binary and no network. Companion to
 * `upload-rules.spec.ts`, which pins the signature rules that do not depend on a registered key
 * (an invalid `.asc`, a signature that arrives before its POM, ...).
 *
 *  - A signature made by a REGISTERED key verifies (200), the stored `.asc` reads back byte-equal,
 *    and the key shows up in the repo's public-key list with the right `keyId`.
 *  - The same registered key, but a signature over different bytes, is refused (422
 *    `artifactSignatureNotVerified`) and stores nothing.
 *  - A signature made by a key that was never registered (and that no key server on this offline
 *    instance can answer for) is refused with 404. The message is free text (RPS-1127), so only the
 *    status is asserted.
 *  - A key registered on one repo does not verify a signature on a different repo: still 404.
 *  - Deleting a registered key makes a signature that used to verify get 404 too.
 *
 * Two per-repo Maven settings change what is verified:
 *  - `pgpVerifyAllSignaturesEnabled` (RPS-1188): every artifact `.asc` (`.jar.asc`, ...) is verified
 *    against the file it signs like the `.pom.asc`, and the version is `signed` only once every file
 *    of it has a verified signature. Off by default, when only the `.pom.asc` is verified and the
 *    other signatures are stored as sent (pinned in `upload-rules.spec.ts`).
 *  - `pgpKeyServerLookupEnabled` (RPS-1204): with it off, a signature by a key that is not
 *    registered on the repo is refused at once with 404 `artifactSigningKeyNotRegistered`, without
 *    asking any key server.
 *
 * Turning `pgpVerifyAllSignaturesEnabled` on or off recomputes `signed` of every existing version
 * of the repo in the background (RPS-1316), and turning it on verifies the `.asc` files that were
 * stored while it was off, so an honest publisher's versions stay signed (RPS-1323).
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  minimalPom,
  rawGet,
  rawPut,
  type RawResponse,
  repoTree,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { detachedSign, generateKeyPair } from '../../src/clients/pgp.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const OCTET = 'application/octet-stream';
const ARTIFACT_ID = 'signed';
const VERSION = '1.0';

interface Layout {
  repoName: string;
  groupId: string;
  pomPath: string;
  ascPath: string;
  pomBody: string;
  put: (relPath: string, body: string, contentType: string) => Promise<RawResponse>;
}

/** A fresh maven repo with its release POM already stored, ready for a `.pom.asc`. */
async function newRepoWithPom(seeder: Seeder): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const admin = adminCredential();
  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const pomPath = `${versionDir(groupId, ARTIFACT_ID, VERSION)}/${ARTIFACT_ID}-${VERSION}.pom`;
  const pomBody = minimalPom(groupId, ARTIFACT_ID, VERSION);
  const put = (relPath: string, body: string, contentType: string) =>
    rawPut(repo.name, admin, relPath, body, contentType);

  const uploaded = await put(pomPath, pomBody, OCTET);
  expect(uploaded.status, 'seed PUT of the POM').toBe(200);

  return { repoName: repo.name, groupId, pomPath, ascPath: `${pomPath}.asc`, pomBody, put };
}

test.describe('maven PGP registered public keys (raw HTTP)', () => {
  test(
    'a registered key verifies a signature, the .asc reads back byte-equal, and it is listed',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      const layout = await newRepoWithPom(seeder);
      const key = await generateKeyPair();
      const registered = await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      expect(registered.keyId, 'registered key id').toBe(key.keyIdHex);

      const signature = await detachedSign(key.privateKeyArmored, Buffer.from(layout.pomBody));
      const put = await layout.put(layout.ascPath, signature, OCTET);
      expect(put.status, `PUT ${layout.ascPath} answered ${put.status} ${put.msgId ?? ''}`).toBe(
        200,
      );

      const admin = adminCredential();
      const stored = await rawGet(layout.repoName, admin, layout.ascPath);
      expect(stored.status, `GET ${layout.ascPath}`).toBe(200);
      expect(stored.body.toString('utf8'), 'stored .asc bytes').toBe(signature);

      const keys = await panelApi.listPgpPublicKeys(layout.repoName);
      expect(
        keys.map((k) => k.keyId),
        'registered keys of the repo',
      ).toContain(key.keyIdHex);
    },
  );

  test(
    'the same registered key, signing different bytes, is refused and stores nothing',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepoWithPom(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const before = await repoTree(layout.repoName);

      const signature = await detachedSign(key.privateKeyArmored, Buffer.from('not the pom'));
      const put = await layout.put(layout.ascPath, signature, OCTET);

      expect(put.status, `PUT ${layout.ascPath} answered ${put.status}`).toBe(422);
      expect(put.msgId, 'error message id').toBe('artifactSignatureNotVerified');
      expect(await repoTree(layout.repoName), 'repo tree unchanged').toEqual(before);
    },
  );

  test(
    'a signature by an UNREGISTERED key is refused with 404 (message is free text, RPS-1127)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepoWithPom(seeder);
      // Never registered on this or any repo, and this instance has no network access, so no key
      // server can answer for it either.
      const unregistered = await generateKeyPair();

      const signature = await detachedSign(
        unregistered.privateKeyArmored,
        Buffer.from(layout.pomBody),
      );
      const put = await layout.put(layout.ascPath, signature, OCTET);

      expect(put.status, `PUT ${layout.ascPath} answered ${put.status}`).toBe(404);
      const stored = await rawGet(layout.repoName, adminCredential(), layout.ascPath);
      expect(stored.status, `GET ${layout.ascPath}`).toBe(404);
    },
  );

  test(
    'a key registered on one repo does not verify a signature on a different repo',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layoutA = await newRepoWithPom(seeder);
      const layoutB = await newRepoWithPom(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layoutA.repoName, key.publicKeyArmored);

      const signature = await detachedSign(key.privateKeyArmored, Buffer.from(layoutB.pomBody));
      const put = await layoutB.put(layoutB.ascPath, signature, OCTET);

      expect(put.status, `PUT on repo B answered ${put.status}`).toBe(404);
      const stored = await rawGet(layoutB.repoName, adminCredential(), layoutB.ascPath);
      expect(stored.status, `GET on repo B`).toBe(404);
    },
  );

  test(
    'after deleting the registered key, a signature that used to verify also gets 404',
    { tag: ['@settings', '@negative'] },
    async ({ seeder, panelApi }) => {
      const layout = await newRepoWithPom(seeder);
      const key = await generateKeyPair();
      const registered = await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const signature = await detachedSign(key.privateKeyArmored, Buffer.from(layout.pomBody));

      // Verifies while the key is registered.
      const before = await layout.put(layout.ascPath, signature, OCTET);
      expect(before.status, 'PUT while the key is registered').toBe(200);

      await panelApi.deletePgpPublicKey(layout.repoName, registered.id);

      // The .asc from before is still stored; a fresh PUT of the same signature is asked again with
      // the key gone, and this instance has no network access to fall back to a key server.
      const afterDelete = await layout.put(layout.ascPath, signature, OCTET);
      expect(afterDelete.status, 'PUT after the key was deleted').toBe(404);
    },
  );
});

test.describe('maven verifies every signature when the repo asks for it (RPS-1188)', () => {
  /** The layout of `newRepoWithPom`, with the setting on, and the jar stored beside the POM. */
  async function newRepoWithPomAndJar(seeder: Seeder) {
    const layout = await newRepoWithPom(seeder);
    await seeder.setSettings(layout.repoName, { pgpVerifyAllSignaturesEnabled: true });
    const jarPath = `${versionDir(layout.groupId, ARTIFACT_ID, VERSION)}/${ARTIFACT_ID}-${VERSION}.jar`;
    const jarBody = `jar of ${layout.repoName}`;
    const uploaded = await layout.put(jarPath, jarBody, OCTET);
    expect(uploaded.status, 'seed PUT of the jar').toBe(200);
    return { layout, jarPath, jarBody };
  }

  test(
    'a jar signature made over other bytes is refused and stores nothing',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const { layout, jarPath } = await newRepoWithPomAndJar(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const before = await repoTree(layout.repoName);

      const armorOnly = '-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n';
      const garbage = await layout.put(`${jarPath}.asc`, armorOnly, OCTET);
      expect(garbage.status, 'an armor-only .jar.asc').toBe(422);
      expect(garbage.msgId).toBe('artifactSignatureNotVerified');

      const wrong = await detachedSign(key.privateKeyArmored, Buffer.from('not the jar'));
      const refused = await layout.put(`${jarPath}.asc`, wrong, OCTET);
      expect(refused.status, 'a signature over other bytes').toBe(422);
      expect(refused.msgId).toBe('artifactSignatureNotVerified');

      expect(await repoTree(layout.repoName), 'repo tree unchanged').toEqual(before);
    },
  );

  test(
    'the version is signed only once the POM and the jar are both signed',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      const { layout, jarPath, jarBody } = await newRepoWithPomAndJar(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const signed = async () =>
        (
          await panelApi.getMavenArtifactVersion(
            layout.repoName,
            layout.groupId,
            ARTIFACT_ID,
            VERSION,
          )
        ).signed;
      expect(await signed(), 'before any signature').toBe(false);

      const jarSignature = await detachedSign(key.privateKeyArmored, Buffer.from(jarBody));
      const jarPut = await layout.put(`${jarPath}.asc`, jarSignature, OCTET);
      expect(
        jarPut.status,
        `PUT ${jarPath}.asc answered ${jarPut.status} ${jarPut.msgId ?? ''}`,
      ).toBe(200);
      expect(await signed(), 'after the jar signature only').toBe(false);

      const pomSignature = await detachedSign(key.privateKeyArmored, Buffer.from(layout.pomBody));
      const pomPut = await layout.put(layout.ascPath, pomSignature, OCTET);
      expect(pomPut.status, `PUT ${layout.ascPath} answered ${pomPut.status}`).toBe(200);
      expect(await signed(), 'after both signatures').toBe(true);

      const stored = await rawGet(layout.repoName, adminCredential(), `${jarPath}.asc`);
      expect(stored.body.toString('utf8'), 'stored .jar.asc bytes').toBe(jarSignature);
    },
  );

  test(
    'a signature before the file it signs is parked, unserved, and verified when the file arrives (RPS-1188)',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      const { layout } = await newRepoWithPomAndJar(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const admin = adminCredential();
      const sourcesPath = `${versionDir(layout.groupId, ARTIFACT_ID, VERSION)}/${ARTIFACT_ID}-${VERSION}-sources.jar`;
      const sourcesBody = `sources of ${layout.repoName}`;
      const signature = await detachedSign(key.privateKeyArmored, Buffer.from(sourcesBody));

      const parked = await layout.put(`${sourcesPath}.asc`, signature, OCTET);
      expect(parked.status, `PUT ${sourcesPath}.asc answered ${parked.status}`).toBe(200);
      const whilePending = await rawGet(layout.repoName, admin, `${sourcesPath}.asc`);
      expect(whilePending.status, 'GET of a parked signature').toBe(404);

      const file = await layout.put(sourcesPath, sourcesBody, OCTET);
      expect(file.status, `PUT ${sourcesPath} answered ${file.status} ${file.msgId ?? ''}`).toBe(
        200,
      );
      const afterwards = await rawGet(layout.repoName, admin, `${sourcesPath}.asc`);
      expect(afterwards.status, 'GET after the file arrived').toBe(200);
      expect(afterwards.body.toString('utf8'), 'stored .asc bytes').toBe(signature);
      // The version is not signed yet: its POM and jar have no verified signature.
      const version = await panelApi.getMavenArtifactVersion(
        layout.repoName,
        layout.groupId,
        ARTIFACT_ID,
        VERSION,
      );
      expect(version.signed, 'signed with the POM and the jar unsigned').toBe(false);
    },
  );

  test(
    'a parked signature that does not verify fails the file with 422 pendingSignatureNotVerified (RPS-1188)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const { layout } = await newRepoWithPomAndJar(seeder);
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);
      const sourcesPath = `${versionDir(layout.groupId, ARTIFACT_ID, VERSION)}/${ARTIFACT_ID}-${VERSION}-sources.jar`;
      const wrong = await detachedSign(key.privateKeyArmored, Buffer.from('not the sources'));
      expect((await layout.put(`${sourcesPath}.asc`, wrong, OCTET)).status, 'parking').toBe(200);
      const before = await repoTree(layout.repoName);

      const file = await layout.put(sourcesPath, 'the sources', OCTET);

      expect(file.status, `PUT ${sourcesPath} answered ${file.status}`).toBe(422);
      expect(file.msgId, 'error message id').toBe('pendingSignatureNotVerified');
      expect(
        await repoTree(layout.repoName),
        'repo tree unchanged: the file was taken back',
      ).toEqual(before);
    },
  );

  test(
    'garbage that is not a signature is refused at once, even before its file',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const { layout, jarPath } = await newRepoWithPomAndJar(seeder);
      const before = await repoTree(layout.repoName);

      const put = await layout.put(
        `${jarPath.replace(/\.jar$/, '-javadoc.jar')}.asc`,
        '-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n',
        OCTET,
      );

      expect(put.status, 'an armor-only .asc before its file').toBe(422);
      expect(put.msgId).toBe('artifactSignatureNotVerified');
      expect(await repoTree(layout.repoName), 'repo tree unchanged').toEqual(before);
    },
  );

  test(
    'with the setting off (the default) a jar signature is stored as sent, unverified',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepoWithPom(seeder);
      const jarPath = `${versionDir(layout.groupId, ARTIFACT_ID, VERSION)}/${ARTIFACT_ID}-${VERSION}.jar`;
      expect((await layout.put(jarPath, 'jar', OCTET)).status, 'seed PUT of the jar').toBe(200);

      const put = await layout.put(`${jarPath}.asc`, 'not a signature', OCTET);

      expect(put.status, `PUT ${jarPath}.asc answered ${put.status}`).toBe(200);
    },
  );
});

test.describe('maven key-server lookup switched off (RPS-1204)', () => {
  test(
    'a signature by an unregistered key is refused at once with 404 artifactSigningKeyNotRegistered',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepoWithPom(seeder);
      await seeder.setSettings(layout.repoName, { pgpKeyServerLookupEnabled: false });
      const unregistered = await generateKeyPair();
      const before = await repoTree(layout.repoName);

      const signature = await detachedSign(
        unregistered.privateKeyArmored,
        Buffer.from(layout.pomBody),
      );
      const started = Date.now();
      const put = await layout.put(layout.ascPath, signature, OCTET);
      const elapsedMs = Date.now() - started;

      expect(put.status, `PUT ${layout.ascPath} answered ${put.status}`).toBe(404);
      expect(put.msgId, 'error message id').toBe('artifactSigningKeyNotRegistered');
      // With the lookup on, the default key servers are asked and may take their whole timeout
      // when this sandbox has no network; switched off, nothing is asked.
      expect(elapsedMs, 'answered without waiting for a key server').toBeLessThan(2_000);
      expect(await repoTree(layout.repoName), 'repo tree unchanged').toEqual(before);
    },
  );

  test('a registered key still verifies', { tag: ['@settings'] }, async ({ seeder }) => {
    const layout = await newRepoWithPom(seeder);
    await seeder.setSettings(layout.repoName, { pgpKeyServerLookupEnabled: false });
    const key = await generateKeyPair();
    await seeder.registerPgpPublicKey(layout.repoName, key.publicKeyArmored);

    const signature = await detachedSign(key.privateKeyArmored, Buffer.from(layout.pomBody));
    const put = await layout.put(layout.ascPath, signature, OCTET);

    expect(put.status, `PUT ${layout.ascPath} answered ${put.status} ${put.msgId ?? ''}`).toBe(200);
  });
});

/**
 * Uploads a release with a good POM signature and a jar whose `.asc` is a good one, one over other
 * bytes, or none. Nothing verifies the jar's while the repo does not verify every signature.
 */
async function uploadRelease(
  repoName: string,
  key: Awaited<ReturnType<typeof generateKeyPair>>,
  groupId: string,
  version: string,
  jarSignature: 'good' | 'forged' | 'none',
): Promise<void> {
  const admin = adminCredential();
  const dir = versionDir(groupId, ARTIFACT_ID, version);
  const pomPath = `${dir}/${ARTIFACT_ID}-${version}.pom`;
  const jarPath = `${dir}/${ARTIFACT_ID}-${version}.jar`;
  const jarBody = `jar of ${version} of ${repoName}`;
  const pomBody = minimalPom(groupId, ARTIFACT_ID, version);
  const uploads: [string, string][] = [
    [pomPath, pomBody],
    [`${pomPath}.asc`, await detachedSign(key.privateKeyArmored, Buffer.from(pomBody))],
    [jarPath, jarBody],
  ];
  if (jarSignature !== 'none') {
    const over = jarSignature === 'good' ? jarBody : `not the jar of ${version}`;
    uploads.push([`${jarPath}.asc`, await detachedSign(key.privateKeyArmored, Buffer.from(over))]);
  }
  for (const [path, body] of uploads) {
    const res = await rawPut(repoName, admin, path, body, OCTET);
    expect(res.status, `PUT ${path} answered ${res.status} ${res.msgId ?? ''}`).toBe(200);
  }
}

test.describe('maven recomputes signed when verify-all is toggled (RPS-1316, RPS-1323)', () => {
  /** The background recompute is quick on a handful of versions; the bound is generous. */
  const RECOMPUTE_TIMEOUT_MS = 30_000;

  test(
    'turning verify-all on keeps an honest version signed, unsigns a forged one, and off restores it',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      // Nothing may ask a key server on this offline instance: only the registered key is known.
      await seeder.setSettings(repo.name, { pgpKeyServerLookupEnabled: false });
      const key = await generateKeyPair();
      await seeder.registerPgpPublicKey(repo.name, key.publicKeyArmored);

      const signedOf = async (version: string) =>
        (await panelApi.getMavenArtifactVersion(repo.name, groupId, ARTIFACT_ID, version)).signed;

      // Uploaded while only the POM's signature is verified, the jar's is stored as sent.
      await uploadRelease(repo.name, key, groupId, '1.0', 'good');
      await uploadRelease(repo.name, key, groupId, '2.0', 'forged');
      await uploadRelease(repo.name, key, groupId, '3.0', 'none');
      expect(await signedOf('1.0'), '1.0 before the toggle').toBe(true);
      expect(await signedOf('2.0'), '2.0 before the toggle').toBe(true);
      expect(await signedOf('3.0'), '3.0 before the toggle').toBe(true);

      await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: true });

      // 2.0 and 3.0 have a jar without a verified signature; 1.0's stored signature verifies.
      await expect
        .poll(() => signedOf('2.0'), { timeout: RECOMPUTE_TIMEOUT_MS, message: '2.0 after on' })
        .toBe(false);
      await expect
        .poll(() => signedOf('3.0'), { timeout: RECOMPUTE_TIMEOUT_MS, message: '3.0 after on' })
        .toBe(false);
      expect(await signedOf('1.0'), '1.0 after on: its stored jar signature was verified').toBe(
        true,
      );

      await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: false });

      // Only the POM's signature counts again.
      for (const version of ['2.0', '3.0']) {
        await expect
          .poll(() => signedOf(version), {
            timeout: RECOMPUTE_TIMEOUT_MS,
            message: `${version} after off`,
          })
          .toBe(true);
      }
      expect(await signedOf('1.0'), '1.0 after off').toBe(true);
    },
  );
});
