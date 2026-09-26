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
 * The lifecycle of a Docker image around its last tag and its last manifest (RPS-1288 item 5), with the
 * real `crane` client: deleting the last TAG removes only the tag (OCI: the manifest stays pullable by
 * digest) and the image stays; deleting the last MANIFEST by digest removes the image from the panel;
 * and a `crane push` of a new tag afterwards works, into a new image.
 *
 * Every credential is a fresh read-write deploy token for the pushes and `crane manifest` reads, the
 * admin's for the panel-side deletes (they need MANAGE), like `registry-rules.spec.ts`'s R15.
 */
import path from 'node:path';

import { PanelHttpError, RepoType } from '../../src/api/panel-backend.js';
import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import { buildImage } from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  rawDeleteManifest,
  rawGetManifest,
  rawPutManifest,
} from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

test(
  'docker > the last tag keeps the image and its manifest pullable by digest, the last manifest removes the image, a new push recreates it (RPS-1288)',
  { tag: ['@settings'] },
  async ({ seeder, panelApi }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential: MaterializedCredential = {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    };
    const image = `e2e-${seeder.runId}-lifecycle`;
    const { home, work } = await isolatedWorkDir(`docker-lifecycle-${seeder.runId}`);
    await renderDockerConfig(home, credential);
    const admin = adminCredential();

    const crane = async (args: string[], label: string) =>
      run('crane', args, { cwd: work, env: craneEnv(home), timeoutMs: 60_000, label });
    const push = async (tag: string, marker: string) => {
      const built = await buildImage({ dir: path.join(work, tag), marker });
      const result = await crane(
        ['push', built.dir, imageRef(repo.name, image, tag)],
        `docker-lifecycle-push-${tag}`,
      );
      expect(result.exitCode, `crane push ${tag}: ${result.command}`).toBe(0);
      return built;
    };

    // One tag: the image is listed with it.
    const first = await push('v1', 'lifecycle-one');
    const withTag = await panelApi.getDockerImageSummary(repo.name, image);
    expect(withTag.tagCount).toBe(1);
    expect(withTag.untaggedManifestCount).toBe(0);

    // Delete the last tag on the wire: only the tag goes.
    const byTag = await rawDeleteManifest(repo.name, admin, image, 'v1');
    expect(byTag.status, 'DELETE by tag').toBe(202);
    expect((await rawGetManifest(repo.name, admin, image, 'v1')).status, 'the tag is gone').toBe(
      404,
    );

    // The manifest is still pullable by digest, with the real client too.
    const byDigestRef = `${imageRef(repo.name, image, 'v1').split(':').slice(0, -1).join(':')}@${first.manifestDigest}`;
    const pulled = await crane(['manifest', byDigestRef], 'docker-lifecycle-manifest-by-digest');
    expect(pulled.exitCode, `crane manifest by digest: ${pulled.command}`).toBe(0);
    expect(JSON.parse(pulled.stdout).config.digest).toBe(first.configDigest);

    // The image is still there, shown as having no tags, with the manifest it keeps.
    const noTags = await panelApi.getDockerImageSummary(repo.name, image);
    expect(noTags.tagCount).toBe(0);
    expect(noTags.untaggedManifestCount).toBe(1);
    expect(noTags.untaggedSize, 'the bytes it keeps, not 0').toBeGreaterThan(0);
    expect(noTags.size, 'what its tags reach').toBe(0);

    // Delete the last manifest by digest: the image goes from the panel, and the digest is gone.
    const byDigest = await rawDeleteManifest(repo.name, admin, image, first.manifestDigest);
    expect(byDigest.status, 'DELETE by digest').toBe(202);
    expect(
      (await rawGetManifest(repo.name, admin, image, first.manifestDigest)).status,
      'the manifest is gone',
    ).toBe(404);
    const gone = await panelApi.getDockerImageSummary(repo.name, image).catch((e: unknown) => e);
    expect(gone).toBeInstanceOf(PanelHttpError);
    expect((gone as PanelHttpError).status, 'the image went with its last manifest').toBe(404);

    // A push of a new tag afterwards works and creates the image again.
    const second = await push('v2', 'lifecycle-two');
    const again = await panelApi.getDockerImageSummary(repo.name, image);
    expect(again.tagCount).toBe(1);
    expect(again.untaggedManifestCount).toBe(0);
    expect((await rawGetManifest(repo.name, admin, image, 'v2')).status).toBe(200);
    expect(
      (await rawGetManifest(repo.name, admin, image, second.manifestDigest)).status,
      'and by digest',
    ).toBe(200);
  },
);

test(
  'docker > a manifest push that fails leaves no image behind (RPS-1350)',
  { tag: ['@negative'] },
  async ({ seeder, panelApi }) => {
    const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
    const image = `e2e-${seeder.runId}-failedpush`;
    const admin = adminCredential();
    const { work } = await isolatedWorkDir(`docker-failedpush-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(work, 'v1'), marker: 'failed-push' });

    // No blob was uploaded: the manifest push is refused, after the registry looked the image up.
    const refused = await rawPutManifest(
      repo.name,
      admin,
      image,
      'v1',
      built.manifestBytes,
      built.manifestMediaType,
    );
    expect(refused.status, `PUT manifest without its blobs: ${refused.body}`).toBe(404);

    // The refused push did not leave an image with no manifest in the panel.
    const afterFailure = await panelApi
      .getDockerImageSummary(repo.name, image)
      .catch((e: unknown) => e);
    expect(afterFailure, 'no image after a failed first push').toBeInstanceOf(PanelHttpError);
    expect((afterFailure as PanelHttpError).status).toBe(404);
  },
);
