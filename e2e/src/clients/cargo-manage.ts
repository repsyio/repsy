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
 * The Cargo operations of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`), each
 * run by the REAL `cargo` client and, for a refused cell, replayed as the raw request the client
 * sends, so the HTTP status the client hides behind its exit code is pinned:
 *
 *  - `yank` (`cargo yank --version <v>`, `DELETE .../<crate>/<v>/yank`) and `unyank` (`cargo yank
 *    --undo`, `PUT .../<crate>/<v>/unyank`): WRITE (`AbstractCargoYankProtocolMethodHandler`). They
 *    only flip the `yanked` flag of the sparse index entry; the `.crate` stays downloadable.
 *  - `owner-add` (`cargo owner --add <login>`, `PUT .../<crate>/owners`) and `owner-remove` (`cargo
 *    owner --remove <login>`, `DELETE` of the same URL): WRITE (`AbstractCargoOwnersModifyProtocol
 *    MethodHandler`, `writeOperation: true`, so a public repo does not let them through unauthenticated
 *    either). Repsy has no ownership model finer than the repository: the handler answers 200 with a
 *    fixed body and changes NOTHING (probed live, RPS-1475: the sparse index, the `.crate` bytes and
 *    the owners listing are byte for byte the same after an accepted `--add`/`--remove`). Their
 *    allowed cell therefore checks that the client succeeded (the matrix requires exit 0) and that
 *    nothing changed, so a future ownership model that starts storing something fails it on purpose;
 *    a refused cell is the useful half: the crate stays as it was and the status is 401.
 *
 * Every cell yanks/owns a crate it seeded itself with the admin credential (through the client's own
 * `seedPublish`: the server reads the crate's manifest, so a made-up `.crate` is a 500). The crate
 * name is underscore-only (`cargo.ts`'s header).
 * Existing pins of single cells stay where they are (`tests/cargo/protocol-specific.spec.ts`: HC-1
 * the yank/unyank round trip and `owner --list`, HC-6 the read-only token).
 */
import { expect } from '@playwright/test';

import { repoUrl } from '../repo-url.js';
import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { cargoAdapter, cargoEnv, renderCargoConfig, seedPublish } from './cargo.js';
import {
  adminCredential,
  cargoAuthHeader,
  cargoToken,
  ownersUrl,
  parseIndex,
  rawDownload,
  rawGetIndex,
  rawOwners,
  rawUnyank,
  rawYank,
  sha256Hex,
} from './cargo-raw.js';
import { isolatedWorkDir, run } from './exec.js';
import { withBackoff429Response } from './raw-http.js';

const SCENARIO: Scenario = {
  id: 'manage',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

const CLIENT_TIMEOUT_MS = 60_000;
/** The login the owner operations name; Repsy never looks it up. */
const OWNER_LOGIN = 'e2e-owner';

/** What a cell acts on: one crate of two versions, and the version the operation is about. */
interface CargoSubject {
  repoName: string;
  crateName: string;
  /** Never touched by the operation. */
  kept: string;
  /** The version yanked/unyanked. */
  target: string;
}

/** What the matrix reads back of the crate, as admin. */
export interface CargoManageFingerprint {
  indexStatus: number;
  indexSha256?: string;
  /** Per index entry: the `yanked` flag. */
  yanked: Record<string, boolean>;
  /** Per seeded version: the sha256 of the served `.crate`, or `status:<n>`. */
  crates: Record<string, string>;
  ownersStatus: number;
  ownersBody: string;
}

async function seedSubject(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<CargoSubject> {
  const crateName = `e2e_${seeder.runId}_${operationId.replaceAll('-', '_')}`;
  const kept = cargoAdapter.version('release');
  const target = cargoAdapter.version('release');
  for (const version of [kept, target]) {
    // The real client seeds: the server reads the crate's own manifest, so bytes that are not a
    // real `.crate` are refused with a 500.
    const coordinates = { packageName: crateName, version };
    await seedPublish({
      scenario: SCENARIO,
      protocol: 'cargo',
      repoName: repo.name,
      credential: adminCredential(),
      publishTarget: coordinates,
      consumeTarget: coordinates,
    });
  }
  return { repoName: repo.name, crateName, kept, target };
}

async function fingerprint(subject: CargoSubject): Promise<CargoManageFingerprint> {
  const admin = adminCredential();
  const indexRes = await rawGetIndex(subject.repoName, admin, subject.crateName);
  const owners = await rawOwners(subject.repoName, admin, subject.crateName);

  const crates: Record<string, string> = {};
  for (const version of [subject.kept, subject.target]) {
    const dl = await rawDownload(subject.repoName, admin, subject.crateName, version);
    crates[version] = dl.status === 200 ? sha256Hex(dl.body) : `status:${dl.status}`;
  }

  const yanked: Record<string, boolean> = {};
  if (indexRes.status === 200) {
    for (const entry of parseIndex(indexRes.body)) {
      yanked[entry.vers] = entry.yanked;
    }
  }
  return {
    indexStatus: indexRes.status,
    indexSha256: indexRes.status === 200 ? sha256Hex(indexRes.body) : undefined,
    yanked,
    crates,
    ownersStatus: owners.status,
    ownersBody: owners.body.toString('utf8'),
  };
}

/** Runs the real `cargo <args>` in a work directory that has the registry config, with `credential`. */
async function runCargo(
  subject: CargoSubject,
  credential: MaterializedCredential,
  args: readonly string[],
  label: string,
): Promise<{ clientExitCode: number; command: string }> {
  const { home, work } = await isolatedWorkDir(label);
  await renderCargoConfig(work, subject.repoName);
  const secrets = [credential.password, cargoToken(credential)].filter(
    (value): value is string => value !== undefined && value !== '',
  );
  const result = await run('cargo', [...args], {
    cwd: work,
    env: cargoEnv(home, credential),
    timeoutMs: CLIENT_TIMEOUT_MS,
    redact: secrets,
    label,
  });
  return { clientExitCode: result.exitCode, command: result.command };
}

/** Raw `PUT`/`DELETE .../<crate>/owners` with the body `cargo owner --add|--remove` sends. */
async function rawOwnersModify(
  subject: CargoSubject,
  method: 'PUT' | 'DELETE',
  credential: MaterializedCredential,
): Promise<number> {
  const res = await withBackoff429Response(async () => {
    const response = await fetch(repoUrl(subject.repoName, ownersUrl(subject.crateName)), {
      method,
      headers: { ...cargoAuthHeader(credential), 'Content-Type': 'application/json' },
      body: JSON.stringify({ users: [OWNER_LOGIN] }),
    });
    const bytes = Buffer.from(await response.arrayBuffer());
    return { status: response.status, msgId: undefined, body: bytes };
  });
  return res.status;
}

function yank(): ManageOperation {
  return {
    protocol: 'cargo',
    id: 'yank',
    permission: 'WRITE',
    client: 'cargo yank --version <v> <crate>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'yank');
      return bindPrepared<CargoManageFingerprint>({
        run: (credential) =>
          runCargo(
            subject,
            credential,
            ['yank', '--registry', 'repsy', '--version', subject.target, subject.crateName],
            `cargo-manage-yank-${subject.crateName}`,
          ),
        probe: async (credential) =>
          (await rawYank(subject.repoName, credential, subject.crateName, subject.target)).status,
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.yanked, 'nothing was yanked').toEqual({
            [subject.kept]: false,
            [subject.target]: false,
          });
          expect(after.yanked, 'only the target is yanked').toEqual({
            [subject.kept]: false,
            [subject.target]: true,
          });
          expect(after.crates, 'yank is index-only: every .crate is still served').toEqual(
            before.crates,
          );
        },
      });
    },
  };
}

function unyank(): ManageOperation {
  return {
    protocol: 'cargo',
    id: 'unyank',
    permission: 'WRITE',
    client: 'cargo yank --undo --version <v> <crate>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'unyank');
      const yanked = await rawYank(
        subject.repoName,
        adminCredential(),
        subject.crateName,
        subject.target,
      );
      expect(yanked.status, `seed the yank: ${yanked.body.toString('utf8')}`).toBe(200);
      return bindPrepared<CargoManageFingerprint>({
        run: (credential) =>
          runCargo(
            subject,
            credential,
            [
              'yank',
              '--undo',
              '--registry',
              'repsy',
              '--version',
              subject.target,
              subject.crateName,
            ],
            `cargo-manage-unyank-${subject.crateName}`,
          ),
        probe: async (credential) =>
          (await rawUnyank(subject.repoName, credential, subject.crateName, subject.target)).status,
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.yanked[subject.target], 'the target was yanked').toBe(true);
          expect(after.yanked, 'nothing is yanked any more').toEqual({
            [subject.kept]: false,
            [subject.target]: false,
          });
          expect(after.crates, 'every .crate is still served').toEqual(before.crates);
        },
      });
    },
  };
}

function ownerOperation(kind: 'add' | 'remove'): ManageOperation {
  const flag = kind === 'add' ? '--add' : '--remove';
  return {
    protocol: 'cargo',
    id: `owner-${kind}`,
    permission: 'WRITE',
    client: `cargo owner ${flag} <login> <crate>`,
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, `owner-${kind}`);
      return bindPrepared<CargoManageFingerprint>({
        run: (credential) =>
          runCargo(
            subject,
            credential,
            ['owner', flag, OWNER_LOGIN, '--registry', 'repsy', subject.crateName],
            `cargo-manage-owner-${kind}-${subject.crateName}`,
          ),
        probe: (credential) =>
          rawOwnersModify(subject, kind === 'add' ? 'PUT' : 'DELETE', credential),
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          // Repsy has no owners of its own to change (this file's header): an accepted call is a
          // no-op, and the listing keeps answering the repo-level owner.
          expect(after, 'an accepted owner change stores nothing').toEqual(before);
          expect(before.ownersStatus, 'the owners listing answers').toBe(200);
        },
      });
    },
  };
}

/** The Cargo operations of the manage matrix (`tests/cargo/manage-matrix.spec.ts`). */
export const CARGO_MANAGE_OPERATIONS: readonly ManageOperation[] = [
  yank(),
  unyank(),
  ownerOperation('add'),
  ownerOperation('remove'),
];
