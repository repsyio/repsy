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
 * Helpers shared by the client-specific specs of the second Docker client family
 * (`tests/docker/skopeo.spec.ts`, `regctl.spec.ts`, `client-tag-list.spec.ts`; RPS-1478 part B):
 * seeding a repository and a credential, and the raw-HTTP comparison of two stored images.
 */
import { RepoType } from '../api/panel-api.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { Seeder } from '../seed/seeder.js';
import { expect } from '@playwright/test';
import { adminCredential, rawGetManifest, rawHeadBlob, sha256Hex } from './docker-raw.js';

export async function newDockerRepo(
  seeder: Seeder,
  opts: { privateRepo?: boolean } = {},
): Promise<string> {
  const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: opts.privateRepo ?? true });
  return repo.name;
}

/** A deploy-token credential (Basic transport, the token as the password) for `repoName`. */
export async function tokenCredential(
  seeder: Seeder,
  repoName: string,
  readOnly: boolean,
): Promise<MaterializedCredential> {
  const seeded = await seeder.createToken(repoName, { readOnly });
  return { transport: 'basic', username: seeded.username, password: seeded.token, kind: 'token' };
}

/** `<host>/<repo>/<image>@<digest>`, the reference a client is told to read or delete by digest. */
export function byDigest(ref: string, digest: string): string {
  return `${ref.slice(0, ref.lastIndexOf(':'))}@${digest}`;
}

/** The manifest `ref` serves to the admin: status, bytes and the reported digest. */
async function adminManifest(repoName: string, image: string, reference: string) {
  return rawGetManifest(repoName, adminCredential(), image, reference);
}

/**
 * The very same image is stored under both names: same manifest bytes, so the same digest, and every
 * blob the manifest names exists in the destination repository. `what` names the client run that
 * copied it, for the failure message.
 */
export async function expectSameImage(
  from: { repoName: string; image: string },
  to: { repoName: string; image: string },
  tag: string,
  what: string,
): Promise<string> {
  const source = await adminManifest(from.repoName, from.image, tag);
  const copy = await adminManifest(to.repoName, to.image, tag);
  expect(source.status, `${what}: the source manifest`).toBe(200);
  expect(copy.status, `${what}: the copied manifest`).toBe(200);
  expect(copy.body.equals(source.body), `${what}: byte-identical manifest`).toBe(true);
  expect(copy.digestHeader, `${what}: same digest`).toBe(source.digestHeader);
  expect(copy.digestHeader).toBe(`sha256:${sha256Hex(source.body)}`);

  const parsed = JSON.parse(source.body.toString('utf8')) as {
    config: { digest: string };
    layers: { digest: string }[];
  };
  for (const digest of [parsed.config.digest, ...parsed.layers.map((l) => l.digest)]) {
    const head = await rawHeadBlob(to.repoName, adminCredential(), to.image, digest);
    expect(head.status, `${what}: blob ${digest} is in the destination repo`).toBe(200);
  }
  return copy.digestHeader ?? '';
}
