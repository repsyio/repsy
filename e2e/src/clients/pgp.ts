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
 * A real OpenPGP key pair and detached signatures, generated in-process with OpenPGP.js (RPS-1189)
 * so `pgp-signature.spec.ts` needs neither a `gpg` binary nor network access. Deliberately kept to
 * OpenPGP.js's default v4 key format (never set `config.v6Keys`): BouncyCastle 1.83, which the
 * backend verifies with, reads v4 keys without special handling.
 */
import * as openpgp from 'openpgp';

export interface PgpKeyPair {
  publicKeyArmored: string;
  privateKeyArmored: string;
  /** The 64 bit key id of the primary key, upper-case hex, as the panel API reports it. */
  keyIdHex: string;
}

/** Generates a fresh 2048 bit RSA key pair. Two calls give two unrelated keys. */
export async function generateKeyPair(): Promise<PgpKeyPair> {
  const { publicKey, privateKey } = await openpgp.generateKey({
    type: 'rsa',
    rsaBits: 2048,
    userIDs: [{ name: 'e2e', email: 'e2e@repsy.test' }],
    format: 'armored',
  });

  const key = await openpgp.readKey({ armoredKey: publicKey });
  const keyIdHex = key.getKeyID().toHex().toUpperCase();

  return { publicKeyArmored: publicKey, privateKeyArmored: privateKey, keyIdHex };
}

/** A detached ASCII-armored signature of `bytes`, the way `gpg --armor --detach-sign` produces one. */
export async function detachedSign(
  privateKeyArmored: string,
  bytes: Buffer | Uint8Array,
): Promise<string> {
  const privateKey = await openpgp.readPrivateKey({ armoredKey: privateKeyArmored });
  const message = await openpgp.createMessage({ binary: bytes });

  return openpgp.sign({
    message,
    signingKeys: privateKey,
    detached: true,
    format: 'armored',
  });
}
