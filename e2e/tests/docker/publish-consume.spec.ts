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
 * The scenario-driven docker suite (step 4a, RPS-294): `registerPublishConsumeLoop(dockerAdapter)`
 * wires the whole shared catalog into docker, exactly like `tests/nuget/publish-consume.spec.ts` does
 * for nuget. Plus four hand-built real-client tests the catalog loop itself cannot exercise:
 *
 *  - "D1" an OCI-media-type-family round trip (`buildImage({ family: 'oci' })`): push and pull both
 *    succeed and the served `Content-Type` is the OCI manifest type, not the docker default.
 *  - "D2" `crane auth login --password-stdin` pins the panel's real credential flow literally: the
 *    written `config.json` has the exact shape `renderDockerConfig` renders by hand, and a push using
 *    it succeeds; a wrong password writes the same shape but the push it fronts fails.
 *  - "D3" pull-by-digest and `crane digest` (HEAD by tag vs. by digest, RPS-1215).
 *  - "D4" a real-client retag (`crane tag`): both tags resolve to the same digest afterwards.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import * as docker from '../../src/clients/docker.js';
import { craneEnv, dockerAdapter, renderDockerConfig } from '../../src/clients/docker.js';
import { buildImage } from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  rawGetManifest,
  rawHeadManifest,
  registryHost,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(dockerAdapter);

test(
  'docker > D1: an OCI-media-type-family image round trips and is served with the OCI content type',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const credential = adminCredential();
    const image = `e2e-${seeder.runId}-oci`;
    const tag = dockerAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`docker-d1-${seeder.runId}`);
    const built = await buildImage({
      dir: path.join(work, 'image'),
      marker: 'd1-oci',
      family: 'oci',
    });
    await renderDockerConfig(home, credential);

    const ref = imageRef(repo.name, image, tag);
    const pushResult = await run('crane', ['push', built.dir, ref], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 60_000,
      label: 'docker-d1-push',
    });
    expect(pushResult.exitCode, `crane push: ${pushResult.command}`).toBe(0);

    const getRes = await rawGetManifest(repo.name, credential, image, tag);
    expect(getRes.status).toBe(200);
    expect(getRes.contentType?.split(';')[0], 'served as the OCI manifest media type').toBe(
      'application/vnd.oci.image.manifest.v1+json',
    );

    const pulledDir = path.join(work, 'pulled');
    const pullResult = await run('crane', ['pull', '--format=oci', ref, pulledDir], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 60_000,
      label: 'docker-d1-pull',
    });
    expect(pullResult.exitCode, `crane pull: ${pullResult.command}`).toBe(0);
    const index = JSON.parse(await fs.readFile(path.join(pulledDir, 'index.json'), 'utf8')) as {
      manifests: { digest: string }[];
    };
    expect(index.manifests[0]?.digest).toBe(built.manifestDigest);
  },
);

test(
  'docker > D2: crane auth login --password-stdin writes the same config.json shape as ' +
    'renderDockerConfig, and fronts a working push; a wrong password fronts a failing one',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const image = `e2e-${seeder.runId}-login`;
    const tag = dockerAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`docker-d2-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(work, 'image'), marker: 'd2-login' });

    const loginResult = await run(
      'crane',
      ['auth', 'login', registryHost(), '-u', token.username, '--password-stdin'],
      {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 30_000,
        redact: [token.token],
        label: 'docker-d2-login',
        input: `${token.token}\n`,
      },
    );
    expect(loginResult.exitCode, `crane auth login: ${loginResult.command}`).toBe(0);

    const written = JSON.parse(
      await fs.readFile(path.join(home, '.docker', 'config.json'), 'utf8'),
    ) as { auths: Record<string, { auth: string }> };
    const expectedAuth = Buffer.from(`${token.username}:${token.token}`).toString('base64');
    expect(written.auths[registryHost()]?.auth, 'the same shape renderDockerConfig writes').toBe(
      expectedAuth,
    );

    const ref = imageRef(repo.name, image, tag);
    const pushResult = await run('crane', ['push', built.dir, ref], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 60_000,
      label: 'docker-d2-push',
    });
    expect(pushResult.exitCode, `crane push fronted by auth login: ${pushResult.command}`).toBe(0);

    // A wrong password writes the same shape, but the push it fronts fails.
    const { home: badHome, work: badWork } = await isolatedWorkDir(`docker-d2-bad-${seeder.runId}`);
    const badLoginResult = await run(
      'crane',
      ['auth', 'login', registryHost(), '-u', token.username, '--password-stdin'],
      {
        cwd: badWork,
        env: craneEnv(badHome),
        timeoutMs: 30_000,
        label: 'docker-d2-bad-login',
        input: 'not-the-real-token\n',
      },
    );
    expect(
      badLoginResult.exitCode,
      'crane auth login itself never fails (no server round trip)',
    ).toBe(0);
    const badPushResult = await run(
      'crane',
      ['push', built.dir, imageRef(repo.name, image, `${tag}-2`)],
      {
        cwd: badWork,
        env: craneEnv(badHome),
        timeoutMs: 60_000,
        label: 'docker-d2-bad-push',
      },
    );
    expect(badPushResult.exitCode, 'a push fronted by a wrong password fails').not.toBe(0);
  },
);

test(
  'docker > D3: pull by digest and crane digest, incl. HEAD-by-digest (candidate B1)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const credential = adminCredential();
    const image = `e2e-${seeder.runId}-bydigest`;
    const tag = dockerAdapter.version('release');
    const target: Coordinates = { packageName: image, version: tag };
    const scenario: Scenario = {
      id: 'd3-by-digest',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'docker',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await docker.publish(world);
    expect(published.outcome, `publish: http ${published.httpStatus}`).toBe('ok');
    expect(published.clientExitCode, `crane push: ${published.command}`).toBe(0);

    const ref = imageRef(repo.name, image, tag);
    const { home, work } = await isolatedWorkDir(`docker-d3-digest-${seeder.runId}`);
    await renderDockerConfig(home, credential);
    const digestResult = await run('crane', ['digest', ref], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 30_000,
      label: 'docker-d3-digest-by-tag',
    });
    expect(digestResult.exitCode, `crane digest (by tag): ${digestResult.command}`).toBe(0);
    expect(digestResult.stdout.trim()).toBe(`sha256:${published.contentSha256}`);

    const { home: home2, work: work2 } = await isolatedWorkDir(`docker-d3-pull-${seeder.runId}`);
    await renderDockerConfig(home2, credential);
    const pullByDigestRef = `${registryHost()}/${repo.name}/${image}@sha256:${published.contentSha256}`;
    const pullResult = await run(
      'crane',
      ['pull', '--format=oci', pullByDigestRef, path.join(work2, 'pulled')],
      { cwd: work2, env: craneEnv(home2), timeoutMs: 60_000, label: 'docker-d3-pull-by-digest' },
    );
    expect(pullResult.exitCode, `crane pull by digest: ${pullResult.command}`).toBe(0);

    // GET by digest works (the raw layer, confirming B1 is scoped to HEAD only).
    const getByDigest = await rawGetManifest(
      repo.name,
      credential,
      image,
      `sha256:${published.contentSha256}`,
    );
    expect(getByDigest.status, 'GET by digest succeeds').toBe(200);

    // Candidate B1, confirmed live (docker-raw.ts's file header): HEAD by digest is 404 even though
    // GET by digest just served it above.
    const headByDigest = await rawHeadManifest(
      repo.name,
      credential,
      image,
      `sha256:${published.contentSha256}`,
    );
    expect(
      headByDigest.status,
      'RPS-1215 (B1): HEAD by digest should mirror GET (spec: "HEAD MUST mirror GET"), ' +
        'but AbstractDockerManifestCheckProtocolMethodHandler only ever resolves a TAG row, never a ' +
        'digest -- a real `crane digest <ref>@sha256:<digest>` therefore also fails (exit 1), even ' +
        'though `crane pull`/a raw GET of the very same digest just succeeded above',
    ).toBe(404);

    // B1 does NOT surface through the real client here: ggcr's own `remote.Head` (what `crane
    // digest` calls) falls back to a GET when the HEAD fails (confirmed live: stderr shows "HEAD
    // request failed, falling back on GET"), so `crane digest <ref>@sha256:<digest>` still succeeds
    // -- the discrepancy is only visible to a caller that trusts HEAD's status code directly (the
    // raw probe above, or any client without ggcr's specific fallback).
    const digestByDigestResult = await run('crane', ['digest', pullByDigestRef], {
      cwd: work,
      env: craneEnv(home),
      timeoutMs: 30_000,
      label: 'docker-d3-digest-by-digest',
    });
    expect(
      digestByDigestResult.exitCode,
      'crane digest by digest succeeds anyway, via its own HEAD-then-GET fallback',
    ).toBe(0);
  },
);

test('docker > D4: a real-client retag (crane tag) resolves both tags to the same digest', async ({
  seeder,
}) => {
  const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
  const credential = adminCredential();
  const image = `e2e-${seeder.runId}-retag`;
  const tag1 = dockerAdapter.version('release');
  const tag2 = `${tag1}-b`;

  const { home, work } = await isolatedWorkDir(`docker-d4-${seeder.runId}`);
  const built = await buildImage({ dir: path.join(work, 'image'), marker: 'd4-retag' });
  await renderDockerConfig(home, credential);

  const ref1 = imageRef(repo.name, image, tag1);
  const pushResult = await run('crane', ['push', built.dir, ref1], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: 60_000,
    label: 'docker-d4-push',
  });
  expect(pushResult.exitCode, `crane push: ${pushResult.command}`).toBe(0);

  const tagResult = await run('crane', ['tag', ref1, tag2], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: 30_000,
    label: 'docker-d4-tag',
  });
  expect(tagResult.exitCode, `crane tag: ${tagResult.command}`).toBe(0);

  const get1 = await rawGetManifest(repo.name, credential, image, tag1);
  const get2 = await rawGetManifest(repo.name, credential, image, tag2);
  expect(get1.status).toBe(200);
  expect(get2.status).toBe(200);
  expect(get1.digestHeader, 'both tags resolve to the same digest after retagging').toBe(
    get2.digestHeader,
  );
  expect(get1.body.equals(get2.body), 'both tags serve byte-identical manifests').toBe(true);
});
