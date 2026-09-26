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
 * The Ruby operation of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`):
 * `yank` (`gem yank <gem> -v <version> --host <repo>`, a form-encoded `DELETE /api/v1/gems/yank`),
 * run by the REAL `gem` client with the credential in `GEM_HOST_API_KEY` (`ruby.ts`'s `gemEnv`) and,
 * for a refused cell, replayed as the raw request the client sends, so the HTTP status the client
 * hides behind its exit code is pinned. The real client's own exit code is no signal
 * (`yankExitCode`).
 *
 * Yank is WRITE (RPS-1317: it used to be MANAGE, which a USER account and a deploy token could never
 * hold): ADMIN, USER and a read-write token may, a read-only token and an anonymous caller get a 401.
 * A yank only unpublishes the version from the compact index: `/info/<gem>` omits it, `/versions`
 * marks it `-<version>`, and the `.gem` stays downloadable by exact URL (RPS-1235, RPS-1238).
 *
 * Every cell yanks a version of a gem it seeded itself, next to a second version that has to stay,
 * with the admin credential (a raw `POST` of a built gem: seeding is not what a cell is about).
 * Existing pins of single cells stay where they are (`tests/ruby/registry-rules.spec.ts` R8, and
 * `tests/ruby/publish-consume.spec.ts`'s yank with a read-write token).
 */
import { expect } from '@playwright/test';

import { repoUrl } from '../repo-url.js';
import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { isolatedWorkDir, run } from './exec.js';
import { gemEnv, rubyAdapter } from './ruby.js';
import {
  adminCredential,
  apiKeyFor,
  buildGem,
  gemFilename,
  infoRelPath,
  parseInfo,
  parseVersionsIndex,
  rawDownload,
  rawGet,
  rawPublish,
  rawYank,
  sha256Hex,
  versionsRelPath,
} from './ruby-raw.js';

const CLIENT_TIMEOUT_MS = 30_000;
/** What a successful yank prints: the server's own 200 body (`AbstractRubyGemYankHandler`). */
const YANKED_MESSAGE = 'Successfully yanked gem';

/** What a cell acts on: one gem of two versions, and the version the operation is about. */
interface RubySubject {
  repoName: string;
  gemName: string;
  /** Never touched by the operation. */
  kept: string;
  /** The version yanked. */
  target: string;
}

/** What the matrix reads back of the gem, as admin. */
export interface RubyManageFingerprint {
  infoStatus: number;
  infoSha256?: string;
  /** The versions `/info/<gem>` lists (a yanked one is left out). */
  infoVersions: string[];
  /** `/versions`' line of the gem, where a yanked version carries a `-` prefix. */
  versionsCsv?: string;
  /** Per seeded version: the sha256 of the served `.gem`, or `status:<n>`. */
  gems: Record<string, string>;
}

async function seedSubject(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<RubySubject> {
  const gemName = `e2e_${seeder.runId}_${operationId}`;
  const kept = rubyAdapter.version('release');
  const target = rubyAdapter.version('release');
  for (const version of [kept, target]) {
    const built = await buildGem({ name: gemName, version });
    const res = await rawPublish(repo.name, adminCredential(), built.bytes);
    expect(res.status, `seed ${gemName}@${version}: ${res.body.toString('utf8')}`).toBe(200);
  }
  return { repoName: repo.name, gemName, kept, target };
}

async function fingerprint(subject: RubySubject): Promise<RubyManageFingerprint> {
  const admin = adminCredential();
  const info = await rawGet(subject.repoName, admin, infoRelPath(subject.gemName));
  const versions = await rawGet(subject.repoName, admin, versionsRelPath());

  const gems: Record<string, string> = {};
  for (const version of [subject.kept, subject.target]) {
    const dl = await rawDownload(subject.repoName, admin, gemFilename(subject.gemName, version));
    gems[version] = dl.status === 200 ? sha256Hex(dl.body) : `status:${dl.status}`;
  }
  return {
    infoStatus: info.status,
    infoSha256: info.status === 200 ? sha256Hex(info.body) : undefined,
    infoVersions: info.status === 200 ? parseInfo(info.body).map((line) => line.version) : [],
    // `/versions` opens with a `created_at` line that changes on every request, so only the gem's
    // own line is compared.
    versionsCsv:
      versions.status === 200
        ? parseVersionsIndex(versions.body)[subject.gemName]?.versionsCsv
        : undefined,
    gems,
  };
}

/**
 * `gem yank` exits 0 whatever the server answered: RubyGems 4.0's `yank_command.rb` just `say`s the
 * response body and never looks at the status (probed live, RPS-1475: a read-only token prints the
 * 401 JSON, `"msgId":"unAuthorized"`, and the exit code is 0; `gem push` does fail, its
 * `with_response` terminates on a non-2xx). That is the client, not Repsy, so the outcome of the
 * yank is read from what the client printed: the server's success message, or not. A failure keeps
 * the client's own exit code when it has one and is reported as 1 otherwise.
 */
function yankExitCode(result: { exitCode: number; stdout: string }): number {
  if (result.exitCode !== 0) {
    return result.exitCode;
  }
  return result.stdout.includes(YANKED_MESSAGE) ? 0 : 1;
}

function yank(): ManageOperation {
  return {
    protocol: 'ruby',
    id: 'yank',
    permission: 'WRITE',
    client: 'gem yank <gem> -v <version>',
    async prepare(seeder, repo) {
      const subject = await seedSubject(seeder, repo, 'yank');
      return bindPrepared<RubyManageFingerprint>({
        run: async (credential: MaterializedCredential) => {
          const { home, work } = await isolatedWorkDir(`ruby-manage-yank-${subject.gemName}`);
          const secrets = [credential.password, apiKeyFor(credential)].filter(
            (value): value is string => value !== undefined && value !== '',
          );
          const result = await run(
            'gem',
            ['yank', subject.gemName, '-v', subject.target, '--host', repoUrl(subject.repoName)],
            {
              cwd: work,
              env: gemEnv(home, credential),
              timeoutMs: CLIENT_TIMEOUT_MS,
              redact: secrets,
              label: `ruby-manage-yank-${subject.gemName}`,
            },
          );
          return { clientExitCode: yankExitCode(result), command: result.command };
        },
        probe: async (credential) =>
          (
            await rawYank(subject.repoName, credential, {
              gemName: subject.gemName,
              version: subject.target,
            })
          ).status,
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.infoVersions.sort(), 'both versions were listed').toEqual(
            [subject.kept, subject.target].sort(),
          );
          expect(after.infoVersions, '/info omits the yanked version').toEqual([subject.kept]);
          expect(after.versionsCsv, '/versions marks the yanked version with a "-"').toContain(
            `-${subject.target}`,
          );
          expect(after.gems, 'yank is index-only: every .gem is still served').toEqual(before.gems);
        },
      });
    },
  };
}

/** The Ruby operations of the manage matrix (`tests/ruby/manage-matrix.spec.ts`). */
export const RUBY_MANAGE_OPERATIONS: readonly ManageOperation[] = [yank()];
