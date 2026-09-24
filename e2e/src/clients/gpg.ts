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
 * A REAL `gpg` key, for the signed-deploy specs (RPS-1316) whose signer is `maven-gpg-plugin` or
 * Gradle's `signing` plugin, both of which shell out to the `gpg` binary of the maven runner image
 * (`runners/maven.Dockerfile`). Unlike `pgp.ts` (OpenPGP.js, in-process, no binary) this is the
 * path a real publisher takes.
 *
 * Every key lives in its own `GNUPGHOME`, a fresh directory under the OS temp dir, so parallel
 * Playwright workers never share a keyring, an agent socket or a lock: the home is short on purpose
 * (a gpg-agent socket path is limited to about 100 characters) and `dispose()` stops that home's
 * agent before removing it. The key is protected by a passphrase, which the signing tool has to
 * supply the way a CI job does (`MAVEN_GPG_PASSPHRASE`, Gradle's `signing.gnupg.passphrase`).
 */
import { randomBytes } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { run } from './exec.js';

export interface GpgKey {
  /** The isolated `GNUPGHOME` this key (and only this key) lives in. */
  gnupgHome: string;
  /** The 40 hex digit fingerprint of the primary key, upper-case. */
  fingerprint: string;
  /** The 64 bit key id, upper-case hex, as the panel API reports a registered key. */
  keyIdHex: string;
  passphrase: string;
  /** `gpg --armor --export`: what a publisher uploads to a key store. */
  publicKeyArmored: string;
  /** Stops the home's gpg-agent and deletes the home. Safe to call twice. */
  dispose: () => Promise<void>;
}

/** The environment of a `gpg`-driving process: only this key's home is visible to it. */
export function gpgEnv(key: Pick<GpgKey, 'gnupgHome'>, home: string): NodeJS.ProcessEnv {
  return { ...process.env, HOME: home, GNUPGHOME: key.gnupgHome };
}

/** Generates a fresh 2048 bit RSA signing key with `gpg --batch --gen-key` in its own GNUPGHOME. */
export async function generateGpgKey(): Promise<GpgKey> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'gpg-'));
  const gnupgHome = path.join(root, 'h');
  await fs.mkdir(gnupgHome, { mode: 0o700 });
  const passphrase = randomBytes(12).toString('hex');
  const env = { ...process.env, HOME: root, GNUPGHOME: gnupgHome };

  const dispose = async (): Promise<void> => {
    await run('gpgconf', ['--kill', 'gpg-agent'], { cwd: root, env }).catch(() => undefined);
    await fs.rm(root, { recursive: true, force: true });
  };

  try {
    const params = path.join(root, 'key.params');
    await fs.writeFile(
      params,
      [
        'Key-Type: RSA',
        'Key-Length: 2048',
        'Key-Usage: sign',
        'Name-Real: repsy e2e',
        'Name-Email: e2e@repsy.test',
        'Expire-Date: 0',
        `Passphrase: ${passphrase}`,
        '%commit',
        '',
      ].join('\n'),
    );
    const generated = await run(
      'gpg',
      ['--batch', '--pinentry-mode', 'loopback', '--gen-key', params],
      { cwd: root, env, redact: [passphrase], label: 'gpg-gen-key' },
    );
    if (generated.exitCode !== 0) {
      throw new Error(`gpg --gen-key exited ${generated.exitCode}: ${generated.stderr}`);
    }

    const listed = await run('gpg', ['--batch', '--with-colons', '--fingerprint', '--list-keys'], {
      cwd: root,
      env,
      label: 'gpg-list-keys',
    });
    const fingerprint = listed.stdout
      .split('\n')
      .find((line) => line.startsWith('fpr:'))
      ?.split(':')[9];
    if (!fingerprint) {
      throw new Error(`no fingerprint in gpg --list-keys output: ${listed.stdout}`);
    }

    const exported = await run('gpg', ['--batch', '--armor', '--export', fingerprint], {
      cwd: root,
      env,
      label: 'gpg-export',
    });
    if (exported.exitCode !== 0 || !exported.stdout.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
      throw new Error(`gpg --export failed (${exported.exitCode}): ${exported.stderr}`);
    }

    return {
      gnupgHome,
      fingerprint: fingerprint.toUpperCase(),
      keyIdHex: fingerprint.slice(-16).toUpperCase(),
      passphrase,
      publicKeyArmored: `${exported.stdout}\n`,
      dispose,
    };
  } catch (err) {
    await dispose();
    throw err;
  }
}
