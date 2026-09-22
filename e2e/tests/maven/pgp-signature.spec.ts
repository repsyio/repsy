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
