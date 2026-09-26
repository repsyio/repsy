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
 * `crane delete` against the registry (RPS-1440), the real client's view of what R15
 * (`registry-rules.spec.ts`) pins over raw HTTP after RPS-1434: the token from `/v2/token` has to
 * carry `delete` for `<repo>/<image>`.
 *
 *  - The runner's `crane` asks for `push,pull,delete` up front, so with the admin's password the
 *    DELETE is accepted (202): by tag it removes the tag only, by digest the manifest and every tag of
 *    it. Afterwards the real client and a raw GET agree that it is gone.
 *  - A deploy token (read-write) pushes but never deletes, whatever scope its token is asked for (a
 *    delete needs MANAGE, RPS-1216/1424): `crane delete` fails and nothing was removed.
 *  - An older `crane` (v0.12 to v0.20) asks for `push,pull` only, gets one 401 `insufficient_scope`
 *    naming the `delete` scope, asks again for that plus its old scope and is accepted. That client is
 *    not in the runner image, so this test replays its two requests over raw HTTP.
 */
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import { buildImage } from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  parseBearerChallenge,
  pushScope,
  rawDeleteManifest,
  rawGetManifest,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run, type RunResult } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

interface CraneSession {
  crane(args: string[], label: string): Promise<RunResult>;
  /** `crane push` of a fresh image under `tag`; returns its manifest digest. */
  push(tag: string, marker: string): Promise<string>;
}

/** A private `HOME`/`DOCKER_CONFIG` logged in with `credential`, and `crane` runs against it. */
async function craneSession(
  repoName: string,
  image: string,
  credential: MaterializedCredential,
  label: string,
): Promise<CraneSession> {
  const { home, work } = await isolatedWorkDir(label);
  await renderDockerConfig(home, credential);
  const secrets = [credential.password].filter((s): s is string => Boolean(s));
  const insecure = env.insecureRegistry ? ['--insecure'] : [];
  const crane = (args: string[], stepLabel: string) =>
    run('crane', [...args, ...insecure], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 60_000,
      redact: secrets,
      label: stepLabel,
    });
  return {
    crane,
    async push(tag, marker) {
      const built = await buildImage({ dir: path.join(work, tag), marker });
      const ref = imageRef(repoName, image, tag);
      const result = await crane(['push', built.dir, ref], `${label}-push-${tag}`);
      expect(result.exitCode, `crane push ${tag}: ${result.command}`).toBe(0);
      return built.manifestDigest;
    },
  };
}

/** `<host>/<repo>/<image>@<digest>`: how `crane` is told to delete (or read) a manifest by digest. */
function digestRef(repoName: string, image: string, digest: string): string {
  return `${imageRef(repoName, image, 'x').slice(0, -2)}@${digest}`;
}

test(
  'docker > crane delete with a password removes a tag, then the image by digest (RPS-1440)',
  { tag: ['@settings', '@auth'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const image = `e2e-${seeder.runId}-cranedelete`;
    const admin = adminCredential();
    const label = `docker-cranedelete-${seeder.runId}`;
    const session = await craneSession(repo.name, image, admin, label);

    const digest = await session.push('v1', 'crane-delete-one');
    const tagged = await session.crane(
      ['tag', imageRef(repo.name, image, 'v1'), 'v2'],
      `${label}-tag`,
    );
    expect(tagged.exitCode, `crane tag: ${tagged.command}`).toBe(0);

    // By tag: the tag goes, its sibling and the manifest stay. `-v` logs the token request, so the
    // scope the current crane asks for up front is pinned too.
    const byTag = await session.crane(
      ['delete', '-v', imageRef(repo.name, image, 'v2')],
      `${label}-by-tag`,
    );
    expect(byTag.exitCode, `crane delete by tag: ${byTag.command}\n${byTag.stderr}`).toBe(0);
    expect(decodeURIComponent(byTag.stderr), 'crane asks for delete on its own').toContain(
      `scope=repository:${repoPath(repo.name)}/${image}:push,pull,delete`,
    );
    expect((await rawGetManifest(repo.name, admin, image, 'v2')).status, 'v2 is gone').toBe(404);
    expect((await rawGetManifest(repo.name, admin, image, 'v1')).status, 'v1 stays').toBe(200);
    expect((await rawGetManifest(repo.name, admin, image, digest)).status, 'and the manifest').toBe(
      200,
    );

    // By digest: the manifest and the tag still pointing at it.
    const byDigest = await session.crane(
      ['delete', digestRef(repo.name, image, digest)],
      `${label}-by-digest`,
    );
    expect(
      byDigest.exitCode,
      `crane delete by digest: ${byDigest.command}\n${byDigest.stderr}`,
    ).toBe(0);
    expect((await rawGetManifest(repo.name, admin, image, digest)).status, 'by digest').toBe(404);
    expect((await rawGetManifest(repo.name, admin, image, 'v1')).status, 'by tag').toBe(404);
    const pulled = await session.crane(
      ['manifest', imageRef(repo.name, image, 'v1')],
      `${label}-manifest-after`,
    );
    expect(pulled.exitCode, 'the real client no longer finds it either').not.toBe(0);
  },
);

test(
  'docker > crane delete with a deploy token is refused and the image stays (RPS-1440)',
  { tag: ['@negative', '@auth'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential: MaterializedCredential = {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    };
    const image = `e2e-${seeder.runId}-cranetoken`;
    const label = `docker-cranetoken-${seeder.runId}`;
    const session = await craneSession(repo.name, image, credential, label);

    // The token pushes and reads with the real client...
    const digest = await session.push('v1', 'crane-delete-token');
    const read = await session.crane(
      ['manifest', imageRef(repo.name, image, 'v1')],
      `${label}-read`,
    );
    expect(read.exitCode, `crane manifest: ${read.command}`).toBe(0);

    // ...but a delete needs MANAGE, which a deploy token never has, by tag or by digest.
    for (const ref of [imageRef(repo.name, image, 'v1'), digestRef(repo.name, image, digest)]) {
      const refused = await session.crane(['delete', ref], `${label}-delete`);
      expect(refused.exitCode, `crane delete ${ref} with a deploy token`).not.toBe(0);
      expect(refused.stderr.toLowerCase(), 'the registry said why').toContain('unauthorized');
    }
    const admin = adminCredential();
    expect((await rawGetManifest(repo.name, admin, image, 'v1')).status, 'tag stays').toBe(200);
    expect((await rawGetManifest(repo.name, admin, image, digest)).status, 'digest stays').toBe(
      200,
    );
  },
);

test(
  'docker > an older crane deletes after one insufficient_scope challenge (RPS-1440)',
  { tag: ['@auth'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const image = `e2e-${seeder.runId}-craneolder`;
    const admin = adminCredential();
    const session = await craneSession(
      repo.name,
      image,
      admin,
      `docker-craneolder-${seeder.runId}`,
    );
    const digest = await session.push('v1', 'crane-older');

    // The older client's first token is for `push,pull`: the DELETE is refused with the scope to ask
    // for, and nothing is removed.
    const first = await rawDeleteManifest(
      repo.name,
      admin,
      image,
      digest,
      pushScope(repo.name, image),
    );
    expect(first.status, 'first attempt').toBe(401);
    expect(first.wwwAuthenticate, 'insufficient_scope').toContain('error="insufficient_scope"');
    const challenge = parseBearerChallenge(first.wwwAuthenticate ?? '');
    expect(challenge.scope).toBe(`repository:${repoPath(repo.name)}/${image}:delete`);
    expect((await rawGetManifest(repo.name, admin, image, digest)).status, 'still there').toBe(200);

    // It asks again for the challenge's scope plus its old one, and is accepted.
    const retry = await rawDeleteManifest(
      repo.name,
      admin,
      image,
      digest,
      `${challenge.scope} ${pushScope(repo.name, image)}`,
    );
    expect(retry.status, `retry with the challenged scope: ${retry.body}`).toBe(202);
    expect((await rawGetManifest(repo.name, admin, image, digest)).status, 'gone').toBe(404);
  },
);
