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
 * The NuGet operations of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`):
 *
 *  - `unlist` (`DELETE /v3/package/<id>/<version>`, 204) and `relist` (`POST` of the same URL, 200) hide
 *    a version from the registration index (`listed: false`) and show it again; the flat container
 *    keeps serving it either way. Both are WRITE (`AbstractNuGetUnlistProtocolMethodHandler`,
 *    `AbstractNuGetRelistProtocolMethodHandler`): ADMIN, USER and a read-write token may, a read-only
 *    token and an anonymous caller get a 401.
 *
 *  - `unlist-client` (`dotnet nuget delete <id> <version> --source <index.json> --api-key <key>
 *    --non-interactive`, the documented unlist route, RPS-1486): the same `DELETE`, sent by the real
 *    client, so the credential reaches the server the way a user's does (`--api-key` from
 *    `nugetApiKey`, the `NuGet.Config` credentials of the isolated HOME for the Basic retry). The
 *    client hides the status behind its exit code, so a refused cell replays the request raw (`probe`).
 *    `dotnet` has no relist command, so `relist` has no client cell.
 *
 * `unlist` and `relist` run as RAW requests, `client: 'raw'`. A raw request is faithful here because
 * the operation is a single, header-authenticated request; it carries the credential the way `dotnet
 * nuget push` does (`nugetPublishHeaders`: `X-NuGet-ApiKey` for a token, Basic for a password, nothing
 * for anonymous), and a refused cell reads its status straight from that request.
 *
 * Every cell seeds two versions of a package with the admin credential (a raw `PUT`), acts on one of
 * them (`unlist`; for `relist` the seed unlists it first) and reads back, as admin, the registration
 * leaves (`listed` per version), the flat version list and every stored `.nupkg`. Existing pins of
 * single cells stay where they are (`tests/nuget/protocol-specific.spec.ts`: HN-1 unlist/relist).
 */
import { expect } from '@playwright/test';

import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';
import { nugetAdapter, nugetEnv, renderNugetConfig, userNugetConfigPath } from './nuget.js';
import {
  adminCredential,
  buildNupkg,
  normalizeVersion,
  nugetApiKey,
  parseRegistrationIndex,
  parseVersions,
  rawDownloadNupkg,
  rawGetRegistrationIndex,
  rawGetVersions,
  rawPublish,
  rawRelist,
  rawUnlist,
  serviceIndexUrl,
  sha256Hex,
} from './nuget-raw.js';

/** What a cell acts on: one package of two versions, and the version the operation is about. */
interface NuGetSubject {
  repoName: string;
  /** Lower-case: the spelling every route serves. */
  idLower: string;
  /** Never touched by the operation. */
  kept: string;
  /** The version unlisted/relisted (normalized and lower-case, as served). */
  target: string;
}

/** What the matrix reads back of the package, as admin. */
export interface NuGetManageFingerprint {
  registrationStatus: number;
  registrationSha256?: string;
  /** Per registration leaf: `listed`. */
  listed: Record<string, boolean>;
  versionsStatus: number;
  versions: string[];
  /** Per version of the flat container: the sha256 of the `.nupkg`, or `status:<n>`. */
  nupkgs: Record<string, string>;
}

async function seedSubject(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<NuGetSubject> {
  const packageId = `e2e-${seeder.runId}-${operationId}`;
  const versions = [nugetAdapter.version('release'), nugetAdapter.version('release')];
  for (const version of versions) {
    const res = await rawPublish(repo.name, adminCredential(), buildNupkg({ packageId, version }));
    expect(res.status, `seed ${packageId}@${version}: ${res.body.toString('utf8')}`).toBe(201);
  }
  return {
    repoName: repo.name,
    idLower: packageId.toLowerCase(),
    kept: normalizeVersion(versions[0]),
    target: normalizeVersion(versions[1]),
  };
}

async function fingerprint(subject: NuGetSubject): Promise<NuGetManageFingerprint> {
  const admin = adminCredential();
  const registration = await rawGetRegistrationIndex(subject.repoName, admin, subject.idLower);
  const flat = await rawGetVersions(subject.repoName, admin, subject.idLower);

  const listed: Record<string, boolean> = {};
  if (registration.status === 200) {
    for (const leaf of parseRegistrationIndex(registration.body)) {
      listed[leaf.version] = leaf.listed;
    }
  }
  const versions = flat.status === 200 ? parseVersions(flat.body) : [];
  const nupkgs: Record<string, string> = {};
  for (const version of [subject.kept, subject.target]) {
    const dl = await rawDownloadNupkg(subject.repoName, admin, subject.idLower, version);
    nupkgs[version] = dl.status === 200 ? sha256Hex(dl.body) : `status:${dl.status}`;
  }
  return {
    registrationStatus: registration.status,
    registrationSha256: registration.status === 200 ? sha256Hex(registration.body) : undefined,
    listed,
    versionsStatus: flat.status,
    versions,
    nupkgs,
  };
}

const CLIENT_TIMEOUT_MS = 60_000;

/**
 * A real `dotnet nuget delete <id> <version> --source <index.json> --non-interactive` (NuGet's unlist
 * command: Repsy answers the `DELETE` with 204 and only flips `listed`). The command has no
 * `--configfile`, so the credential goes where a user puts it: the user-level `NuGet.Config` of the
 * isolated HOME (a token or password as `packageSourceCredentials`, the panel's "Option A") plus the
 * `--api-key` a push uses (`nugetApiKey`: the token itself, `any` for a password, none for an
 * anonymous caller). Nothing secret is on the command line but the api key, which `run` redacts.
 */
export async function dotnetNugetDelete(
  repoName: string,
  credential: MaterializedCredential,
  packageId: string,
  version: string,
): Promise<RunResult> {
  const { home, work } = await isolatedWorkDir('nuget-delete');
  await renderNugetConfig(home, repoName, credential, {
    destination: userNugetConfigPath(home),
  });
  const apiKey = nugetApiKey(credential);
  const args = [
    'nuget',
    'delete',
    packageId,
    version,
    '--source',
    serviceIndexUrl(repoName),
    '--non-interactive',
  ];
  if (apiKey !== undefined) {
    args.push('--api-key', apiKey);
  }
  return run('dotnet', args, {
    cwd: work,
    env: nugetEnv(home),
    timeoutMs: CLIENT_TIMEOUT_MS,
    redact: [credential.password, apiKey].filter((s): s is string => Boolean(s)),
    label: 'nuget-delete',
  });
}

function unlistOperation(id: string, client: string, viaClient: boolean): ManageOperation {
  return {
    protocol: 'nuget',
    id,
    permission: 'WRITE',
    client,
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, id);
      const raw = async (credential: MaterializedCredential): Promise<number> =>
        (await rawUnlist(subject.repoName, credential, subject.idLower, subject.target)).status;
      return bindPrepared<NuGetManageFingerprint>({
        run: async (credential: MaterializedCredential) => {
          if (viaClient) {
            const res = await dotnetNugetDelete(
              subject.repoName,
              credential,
              subject.idLower,
              subject.target,
            );
            return { clientExitCode: res.exitCode, command: res.command };
          }
          return {
            status: await raw(credential),
            command: `DELETE v3/package/${subject.idLower}/${subject.target} (${credential.kind})`,
          };
        },
        // A refused client cell replays the wire request: the client only shows an exit code.
        probe: viaClient ? raw : undefined,
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.listed, 'both versions were listed').toEqual({
            [subject.kept]: true,
            [subject.target]: true,
          });
          expect(after.listed, 'only the target is unlisted').toEqual({
            [subject.kept]: true,
            [subject.target]: false,
          });
          expect(after.versions, 'the flat container still lists both').toEqual(before.versions);
          expect(after.nupkgs, 'unlisted is not deleted: every .nupkg is served').toEqual(
            before.nupkgs,
          );
        },
      });
    },
  };
}

function unlist(): ManageOperation {
  return unlistOperation('unlist', 'raw DELETE /v3/package/<id>/<version>', false);
}

function unlistClient(): ManageOperation {
  return unlistOperation('unlist-client', 'dotnet nuget delete', true);
}

function relist(): ManageOperation {
  return {
    protocol: 'nuget',
    id: 'relist',
    permission: 'WRITE',
    client: 'raw POST /v3/package/<id>/<version>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'relist');
      const unlisted = await rawUnlist(
        subject.repoName,
        adminCredential(),
        subject.idLower,
        subject.target,
      );
      expect(unlisted.status, `seed the unlist: ${unlisted.body.toString('utf8')}`).toBe(204);
      return bindPrepared<NuGetManageFingerprint>({
        run: async (credential: MaterializedCredential) => {
          const res = await rawRelist(
            subject.repoName,
            credential,
            subject.idLower,
            subject.target,
          );
          return {
            status: res.status,
            command: `POST v3/package/${subject.idLower}/${subject.target} (${credential.kind})`,
          };
        },
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.listed[subject.target], 'the target was unlisted').toBe(false);
          expect(after.listed, 'both versions are listed again').toEqual({
            [subject.kept]: true,
            [subject.target]: true,
          });
          expect(after.nupkgs, 'every .nupkg is still served').toEqual(before.nupkgs);
        },
      });
    },
  };
}

/** The NuGet operations of the manage matrix (`tests/nuget/manage-matrix.spec.ts`). */
export const NUGET_MANAGE_OPERATIONS: readonly ManageOperation[] = [
  unlist(),
  relist(),
  unlistClient(),
];
