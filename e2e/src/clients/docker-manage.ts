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
 * The Docker operations of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`), run by
 * the REAL `crane delete` (the adapter's own client, `docker.ts`) and, for a refused cell, replayed as
 * the raw token dance + `DELETE` the client sends (`docker-raw.ts`'s `rawDeleteManifest`), so the HTTP
 * status `crane` hides behind its exit code is pinned:
 *
 *  - `delete-manifest` (`crane delete <host>/<repo>/<image>@<digest>`) removes the manifest and every
 *    tag pointing at it, `delete-tag` (`crane delete <host>/<repo>/<image>:<tag>`) only the tag.
 *    Removing stored content needs MANAGE (`AbstractDockerManifestDelete...`, RPS-1216, RPS-1424):
 *    ADMIN only; a USER account and a deploy token, read-write or not, get a token (the token endpoint
 *    does not check what the scope is for: authorisation is per operation) and are then refused with a
 *    401 on the `DELETE` itself, with a Bearer challenge. An anonymous caller never gets that far: the
 *    token endpoint refuses it a `delete` scope even on a public repo (a 401 with a Basic challenge,
 *    R15). Probed live: the answer is the same for a digest or a tag that does not exist, so a refused
 *    cell does not say whether the image is there.
 *  - The token has to carry the `delete` scope for the image too (RPS-1434): the current `crane` asks
 *    for `push,pull,delete` up front, and the replay asks for `deleteScope` the same way. An older
 *    client that asks for less is `tests/docker/crane-delete.spec.ts` and `registry-rules.spec.ts`
 *    (R15), which stay the pins of the scope handling; the matrix adds every credential and the
 *    "nothing changed" half of every refused cell.
 */
import path from 'node:path';

import { expect } from '@playwright/test';

import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { env } from '../env.js';
import { craneEnv, renderDockerConfig } from './docker.js';
import { buildImage } from './docker-image.js';
import {
  adminCredential,
  imageRef,
  rawDeleteManifest,
  rawGetManifest,
  rawHeadBlob,
} from './docker-raw.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';

const CRANE_TIMEOUT_MS = 60_000;

/** What a cell acts on: one image with a manifest under two tags. */
interface DockerSubject {
  repoName: string;
  image: string;
  /** The tag `delete-tag` leaves alone. */
  kept: string;
  /** The tag `delete-tag` removes. */
  target: string;
  manifestDigest: string;
  blobDigests: string[];
}

/** What the matrix reads back of the image, as admin. */
export interface DockerManageFingerprint {
  /** Per reference (each tag, and the manifest digest): the status and the digest the registry names. */
  manifests: Record<string, { status: number; digest?: string }>;
  /** Per blob of the manifest: `present`, or `status:<n>`. */
  blobs: Record<string, string>;
}

function craneOptions(credential: MaterializedCredential): {
  insecure: string[];
  secrets: string[];
} {
  return {
    insecure: env.insecureRegistry ? ['--insecure'] : [],
    secrets: [credential.password].filter((s): s is string => Boolean(s)),
  };
}

/** Runs the real `crane` in a private `HOME`/`DOCKER_CONFIG` logged in with `credential`. */
async function craneAs(
  credential: MaterializedCredential,
  args: string[],
  label: string,
): Promise<RunResult> {
  const { home, work } = await isolatedWorkDir(label);
  await renderDockerConfig(home, credential);
  const { insecure, secrets } = craneOptions(credential);
  return run('crane', [...args, ...insecure], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: CRANE_TIMEOUT_MS,
    redact: secrets,
    label,
  });
}

/** Pushes a fresh image as admin with the real `crane push`, then tags it a second time. */
async function seedImage(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<DockerSubject> {
  const image = `e2e-${seeder.runId}-${operationId}`;
  const admin = adminCredential();
  const label = `docker-manage-seed-${seeder.runId}-${operationId}`;
  const { home, work } = await isolatedWorkDir(label);
  await renderDockerConfig(home, admin);
  const { insecure, secrets } = craneOptions(admin);
  const crane = (args: string[], step: string) =>
    run('crane', [...args, ...insecure], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: CRANE_TIMEOUT_MS,
      redact: secrets,
      label: `${label}-${step}`,
    });

  const built = await buildImage({
    dir: path.join(work, 'image'),
    marker: `manage-${operationId}`,
  });
  const pushed = await crane(['push', built.dir, imageRef(repo.name, image, 'v1')], 'push');
  expect(pushed.exitCode, `seed crane push: ${pushed.command}\n${pushed.stderr}`).toBe(0);
  const tagged = await crane(['tag', imageRef(repo.name, image, 'v1'), 'v2'], 'tag');
  expect(tagged.exitCode, `seed crane tag: ${tagged.command}\n${tagged.stderr}`).toBe(0);
  return {
    repoName: repo.name,
    image,
    kept: 'v1',
    target: 'v2',
    manifestDigest: built.manifestDigest,
    blobDigests: [built.configDigest, built.layerDigest],
  };
}

async function fingerprint(subject: DockerSubject): Promise<DockerManageFingerprint> {
  const admin = adminCredential();
  const manifests: DockerManageFingerprint['manifests'] = {};
  for (const ref of [subject.kept, subject.target, subject.manifestDigest]) {
    const res = await rawGetManifest(subject.repoName, admin, subject.image, ref);
    manifests[ref] = { status: res.status, digest: res.digestHeader };
  }
  const blobs: Record<string, string> = {};
  for (const digest of subject.blobDigests) {
    const res = await rawHeadBlob(subject.repoName, admin, subject.image, digest);
    blobs[digest] = res.status === 200 ? 'present' : `status:${res.status}`;
  }
  return { manifests, blobs };
}

/** `<host>/<repo>/<image>@<digest>`: how `crane` is told to delete a manifest by digest. */
function digestRef(subject: DockerSubject): string {
  return `${imageRef(subject.repoName, subject.image, 'x').slice(0, -2)}@${subject.manifestDigest}`;
}

function operation(
  id: 'delete-manifest' | 'delete-tag',
  client: string,
  reference: (subject: DockerSubject) => string,
  rawReference: (subject: DockerSubject) => string,
  expectEffect: (
    subject: DockerSubject,
    before: DockerManageFingerprint,
    after: DockerManageFingerprint,
  ) => void,
): ManageOperation {
  return {
    protocol: 'docker',
    id,
    permission: 'MANAGE',
    client,
    async prepare(seeder, repo) {
      const subject = await seedImage(seeder, repo, id);
      return bindPrepared<DockerManageFingerprint>({
        run: async (credential) => {
          const result = await craneAs(
            credential,
            ['delete', reference(subject)],
            `docker-manage-${id}-${seeder.runId}`,
          );
          return { clientExitCode: result.exitCode, command: result.command };
        },
        // The token dance with the `delete` scope and the DELETE that `crane delete` sends.
        probe: async (credential) =>
          (
            await rawDeleteManifest(
              subject.repoName,
              credential,
              subject.image,
              rawReference(subject),
            )
          ).status,
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          for (const [ref, seen] of Object.entries(before.manifests)) {
            expect(seen.status, `seed: ${ref} was served`).toBe(200);
          }
          expectEffect(subject, before, after);
        },
      });
    },
  };
}

/** The Docker operations of the manage matrix (`tests/docker/manage-matrix.spec.ts`). */
export const DOCKER_MANAGE_OPERATIONS: readonly ManageOperation[] = [
  operation(
    'delete-manifest',
    'crane delete <image>@<digest>',
    digestRef,
    (subject) => subject.manifestDigest,
    (subject, before, after) => {
      for (const ref of [subject.kept, subject.target, subject.manifestDigest]) {
        expect(after.manifests[ref]?.status, `${ref} is gone with the manifest`).toBe(404);
      }
    },
  ),
  operation(
    'delete-tag',
    'crane delete <image>:<tag>',
    (subject) => imageRef(subject.repoName, subject.image, subject.target),
    (subject) => subject.target,
    (subject, before, after) => {
      expect(after.manifests[subject.target]?.status, 'the tag is gone').toBe(404);
      expect(after.manifests[subject.kept], 'the other tag is untouched').toEqual(
        before.manifests[subject.kept],
      );
      expect(after.manifests[subject.manifestDigest], 'and so is the manifest').toEqual(
        before.manifests[subject.manifestDigest],
      );
      expect(after.blobs, 'no blob was removed').toEqual(before.blobs);
    },
  ),
];
