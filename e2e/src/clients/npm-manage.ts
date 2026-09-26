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
 * The npm operations of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`), each
 * run by the REAL `npm` client (`npm.ts`'s `runNpmCommand`) and, for a refused cell, replayed as the
 * raw request the client sends, so the HTTP status the client hides behind its exit code is pinned:
 *
 *  - `unpublish-version` (`npm unpublish <pkg>@<v>`) and `unpublish-package` (`npm unpublish <pkg>
 *    --force`) remove stored files: MANAGE (`AbstractNpmPackageUnpublishProtocolMethodHandler`, `…Delete…`).
 *    ADMIN only; a USER account and a deploy token, read-write or not, are refused (RPS-1424).
 *  - `deprecate` (`npm deprecate`, a `PUT` of the packument) and `dist-tag-add`/`dist-tag-rm`
 *    (`npm dist-tag`) only change what is advertised: WRITE (RPS-1424, RPS-1317). ADMIN, USER and a
 *    read-write token may; a read-only token and an anonymous caller may not.
 *
 * The raw replays are the wire requests of npm 11 (see `unpublish.spec.ts` for the whole
 * `unpublish` exchange): `PUT /<pkg>/-rev/<rev>` with the packument minus the version, `DELETE
 * /<pkg>/-rev/<rev>` for the whole package, `PUT /<pkg>` with `versions[v].deprecated`, and
 * `PUT|DELETE /-/package/<pkg>/dist-tags/<tag>`.
 *
 * Existing pins of single cells stay where they are (`tests/npm/unpublish.spec.ts` for the happy
 * path and the read-write token, `tests/npm-clients/pnpm/commands.spec.ts`,
 * `tests/npm/search-token-dist-tags.spec.ts` for the dist-tag routes): the matrix adds the other
 * credentials and the "nothing changed" half of every refused cell.
 */
import { expect } from '@playwright/test';

import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential, World } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { npmAdapter, runNpmCommand } from './npm.js';
import {
  adminCredential,
  buildPublishDocument,
  buildTarball,
  encodePackageNameForUrl,
  npmAuthHeader,
  parsePackument,
  rawGetPackument,
  rawGetTarballCanonical,
  rawPublish,
  rawRequestPath,
  sha256Hex,
} from './npm-raw.js';

const DEPRECATION_MESSAGE = 'e2e deprecated by the manage matrix';
const TAG = 'beta';

const SCENARIO: Scenario = {
  id: 'manage',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

/** What a cell acts on: one package of two versions, and the version the operation targets. */
interface NpmSubject {
  repoName: string;
  packageName: string;
  /** Stays after an unpublish of `target`, keeps `latest`. */
  kept: string;
  /** The version the operation is about (the higher one). */
  target: string;
}

/** What the matrix reads back of the package, as admin. */
export interface NpmManageFingerprint {
  packumentStatus: number;
  packumentSha256?: string;
  versions: string[];
  distTags: Record<string, string>;
  deprecated: Record<string, string>;
  /** Per seeded version: the sha256 of the stored tarball, or `status:<n>` when it is not served. */
  tarballs: Record<string, string>;
}

function worldFor(subject: NpmSubject, credential: MaterializedCredential): World {
  const target = { packageName: subject.packageName, version: subject.target };
  return {
    scenario: SCENARIO,
    protocol: 'npm',
    repoName: subject.repoName,
    credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

/** Publishes two versions of a fresh package with the admin credential (a raw PUT: seeding is not
 *  what a cell is about, the real client runs the operation under test). */
async function seedSubject(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<NpmSubject> {
  const packageName = `e2e-${seeder.runId}-${operationId}`;
  const kept = npmAdapter.version('release');
  const target = npmAdapter.version('release');
  for (const version of [kept, target]) {
    const document = buildPublishDocument({
      repoName: repo.name,
      packageName,
      version,
      tarballBytes: buildTarball({ packageName, version }),
    });
    const res = await rawPublish(repo.name, adminCredential(), packageName, document);
    expect(res.status, `seed ${packageName}@${version}: ${res.msgId ?? ''}`).toBe(200);
  }
  return { repoName: repo.name, packageName, kept, target };
}

async function fingerprint(subject: NpmSubject): Promise<NpmManageFingerprint> {
  const admin = adminCredential();
  const res = await rawGetPackument(subject.repoName, admin, subject.packageName);

  const tarballs: Record<string, string> = {};
  for (const version of [subject.kept, subject.target]) {
    const tarball = await rawGetTarballCanonical(
      subject.repoName,
      admin,
      subject.packageName,
      version,
    );
    tarballs[version] =
      tarball.status === 200 ? sha256Hex(tarball.body) : `status:${tarball.status}`;
  }

  if (res.status !== 200) {
    return { packumentStatus: res.status, versions: [], distTags: {}, deprecated: {}, tarballs };
  }
  const packument = parsePackument(res.body);
  const full = JSON.parse(res.body.toString('utf8')) as {
    versions?: Record<string, { deprecated?: string }>;
  };
  const deprecated: Record<string, string> = {};
  for (const [version, manifest] of Object.entries(full.versions ?? {})) {
    if (manifest.deprecated !== undefined) {
      deprecated[version] = manifest.deprecated;
    }
  }
  return {
    packumentStatus: 200,
    packumentSha256: sha256Hex(res.body),
    versions: Object.keys(packument.versions).sort(),
    distTags: packument.distTags,
    deprecated,
    tarballs,
  };
}

/** The `_rev` a client puts in an unpublish URL: the packument's own, or the one Repsy accepts. */
async function currentRev(subject: NpmSubject): Promise<string> {
  const res = await rawGetPackument(subject.repoName, adminCredential(), subject.packageName);
  const rev = (JSON.parse(res.body.toString('utf8')) as { _rev?: string })._rev;
  return rev ?? '1-0';
}

function jsonHeaders(credential: MaterializedCredential): Record<string, string> {
  return { ...npmAuthHeader(credential), 'Content-Type': 'application/json' };
}

async function packumentAsAdmin(subject: NpmSubject): Promise<Record<string, unknown>> {
  const res = await rawGetPackument(subject.repoName, adminCredential(), subject.packageName);
  return JSON.parse(res.body.toString('utf8')) as Record<string, unknown>;
}

function unpublishVersion(): ManageOperation {
  return {
    protocol: 'npm',
    id: 'unpublish-version',
    permission: 'MANAGE',
    client: 'npm unpublish <pkg>@<version>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'unpublish-version');
      return bindPrepared<NpmManageFingerprint>({
        run: async (credential) => {
          const run = await runNpmCommand(
            worldFor(subject, credential),
            ['unpublish', `${subject.packageName}@${subject.target}`],
            'npm-manage-unpublish-version',
          );
          return { clientExitCode: run.exitCode, command: run.command };
        },
        // The first request of the exchange that changes anything: the packument minus the version.
        probe: async (credential) => {
          const packument = await packumentAsAdmin(subject);
          const versions = { ...(packument.versions as Record<string, unknown>) };
          delete versions[subject.target];
          const res = await rawRequestPath(
            subject.repoName,
            'PUT',
            `${encodePackageNameForUrl(subject.packageName)}/-rev/${await currentRev(subject)}`,
            jsonHeaders(credential),
            JSON.stringify({ ...packument, versions }),
          );
          return res.status;
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.versions, 'both versions were there').toEqual(
            [subject.kept, subject.target].sort(),
          );
          expect(after.versions, 'the packument lost the version').toEqual([subject.kept]);
          expect(after.distTags.latest, 'latest moved to the remaining version').toBe(subject.kept);
          expect(after.tarballs[subject.target], 'the tarball is gone').toBe('status:404');
          expect(after.tarballs[subject.kept], 'the other tarball is untouched').toBe(
            before.tarballs[subject.kept],
          );
        },
      });
    },
  };
}

function unpublishPackage(): ManageOperation {
  return {
    protocol: 'npm',
    id: 'unpublish-package',
    permission: 'MANAGE',
    client: 'npm unpublish <pkg> --force',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'unpublish-package');
      return bindPrepared<NpmManageFingerprint>({
        run: async (credential) => {
          const run = await runNpmCommand(
            worldFor(subject, credential),
            ['unpublish', subject.packageName, '--force'],
            'npm-manage-unpublish-package',
          );
          return { clientExitCode: run.exitCode, command: run.command };
        },
        // `DELETE /<pkg>/-rev/<rev>`: what removes the whole package.
        probe: async (credential) => {
          const res = await rawRequestPath(
            subject.repoName,
            'DELETE',
            `${encodePackageNameForUrl(subject.packageName)}/-rev/${await currentRev(subject)}`,
            npmAuthHeader(credential),
          );
          return res.status;
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (_before, after) => {
          expect(after.packumentStatus, 'the package is gone').toBe(404);
          expect(after.tarballs, 'every tarball is gone').toEqual({
            [subject.kept]: 'status:404',
            [subject.target]: 'status:404',
          });
        },
      });
    },
  };
}

function deprecate(): ManageOperation {
  return {
    protocol: 'npm',
    id: 'deprecate',
    permission: 'WRITE',
    client: 'npm deprecate <pkg>@<version> <message>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'deprecate');
      return bindPrepared<NpmManageFingerprint>({
        run: async (credential) => {
          const run = await runNpmCommand(
            worldFor(subject, credential),
            ['deprecate', `${subject.packageName}@${subject.target}`, DEPRECATION_MESSAGE],
            'npm-manage-deprecate',
          );
          return { clientExitCode: run.exitCode, command: run.command };
        },
        // `PUT /<pkg>` of the whole packument with `deprecated` on the version.
        probe: async (credential) => {
          const packument = await packumentAsAdmin(subject);
          const versions = packument.versions as Record<string, Record<string, unknown>>;
          versions[subject.target] = {
            ...versions[subject.target],
            deprecated: DEPRECATION_MESSAGE,
          };
          const res = await rawRequestPath(
            subject.repoName,
            'PUT',
            encodePackageNameForUrl(subject.packageName),
            jsonHeaders(credential),
            JSON.stringify(packument),
          );
          return res.status;
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.deprecated, 'nothing was deprecated').toEqual({});
          expect(after.deprecated, 'the version carries the message').toEqual({
            [subject.target]: DEPRECATION_MESSAGE,
          });
          expect(after.versions, 'no version was removed').toEqual(before.versions);
          expect(after.tarballs, 'no tarball changed').toEqual(before.tarballs);
        },
      });
    },
  };
}

function distTagAdd(): ManageOperation {
  return {
    protocol: 'npm',
    id: 'dist-tag-add',
    permission: 'WRITE',
    client: 'npm dist-tag add <pkg>@<version> <tag>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'dist-tag-add');
      return bindPrepared<NpmManageFingerprint>({
        run: async (credential) => {
          const run = await runNpmCommand(
            worldFor(subject, credential),
            ['dist-tag', 'add', `${subject.packageName}@${subject.target}`, TAG],
            'npm-manage-dist-tag-add',
          );
          return { clientExitCode: run.exitCode, command: run.command };
        },
        probe: async (credential) => {
          const res = await rawRequestPath(
            subject.repoName,
            'PUT',
            `-/package/${encodePackageNameForUrl(subject.packageName)}/dist-tags/${TAG}`,
            jsonHeaders(credential),
            JSON.stringify(subject.target),
          );
          return res.status;
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.distTags[TAG], 'the tag did not exist').toBeUndefined();
          expect(after.distTags[TAG], 'the tag points at the version').toBe(subject.target);
          expect(after.distTags.latest, 'latest did not move').toBe(before.distTags.latest);
          expect(after.tarballs, 'no tarball changed').toEqual(before.tarballs);
        },
      });
    },
  };
}

function distTagRemove(): ManageOperation {
  return {
    protocol: 'npm',
    id: 'dist-tag-rm',
    permission: 'WRITE',
    client: 'npm dist-tag rm <pkg> <tag>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'dist-tag-rm');
      const tagged = await rawRequestPath(
        subject.repoName,
        'PUT',
        `-/package/${encodePackageNameForUrl(subject.packageName)}/dist-tags/${TAG}`,
        jsonHeaders(adminCredential()),
        JSON.stringify(subject.target),
      );
      expect(tagged.status, `seed the ${TAG} dist-tag: ${tagged.msgId ?? ''}`).toBe(200);
      return bindPrepared<NpmManageFingerprint>({
        run: async (credential) => {
          const run = await runNpmCommand(
            worldFor(subject, credential),
            ['dist-tag', 'rm', subject.packageName, TAG],
            'npm-manage-dist-tag-rm',
          );
          return { clientExitCode: run.exitCode, command: run.command };
        },
        probe: async (credential) => {
          const res = await rawRequestPath(
            subject.repoName,
            'DELETE',
            `-/package/${encodePackageNameForUrl(subject.packageName)}/dist-tags/${TAG}`,
            npmAuthHeader(credential),
          );
          return res.status;
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.distTags[TAG], 'the tag existed').toBe(subject.target);
          expect(after.distTags[TAG], 'the tag is gone').toBeUndefined();
          expect(after.distTags.latest, 'latest did not move').toBe(before.distTags.latest);
          expect(after.tarballs, 'no tarball changed').toEqual(before.tarballs);
        },
      });
    },
  };
}

/** The npm operations of the manage matrix (`tests/npm/manage-matrix.spec.ts`). */
export const NPM_MANAGE_OPERATIONS: readonly ManageOperation[] = [
  unpublishVersion(),
  unpublishPackage(),
  deprecate(),
  distTagAdd(),
  distTagRemove(),
];
