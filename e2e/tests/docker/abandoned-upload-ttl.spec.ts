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
 * Abandoned Docker and Helm OCI blob uploads are cleaned up after their TTL expires (RPS-1718).
 * An abandoned upload is one that was started (POST to get an upload UUID) but never finished
 * (never PUT to upload the blob), which happens when a docker push or helm chart push is aborted.
 * After the TTL passes, the cleanup task deletes the incomplete upload and releases the disk space.
 *
 * This suite runs only with the `--upload-ttl` stack overlay, which sets a short TTL (5 seconds)
 * and short cleanup intervals (initial delay 1 second, interval 2 seconds) so tests do not have
 * to wait 24 hours.
 *
 * Test strategy:
 * - Start a Docker blob upload (POST /v2/<repo>/<image>/blobs/uploads/) and capture the upload UUID
 * - Wait for the TTL + cleanup interval to pass
 * - Attempt to check the status of the abandoned upload; it should no longer exist (404 or 410)
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  rawStartUpload,
  rawUploadStatus,
} from '../../src/clients/docker-raw.js';
import { expect, optedIn, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Helper to wait for a duration (in milliseconds) with a delay. */
async function waitMs(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test.describe('docker > abandoned upload cleanup', () => {
  test.beforeEach(({ skip }) => {
    // This suite only runs with the upload-ttl overlay, which provides a short TTL.
    if (!optedIn('upload-ttl')) {
      skip();
    }
  });

  test(
    'D-TTL1: an abandoned OCI blob upload is cleaned up after the TTL expires',
    { tag: ['@upload-ttl'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
      const credential = adminCredential();
      const image = `e2e-${seeder.runId}-abandoned`;

      // Step 1: Start a blob upload but do not finish it (no PUT to complete)
      const startRes = await rawStartUpload(repo.name, credential, image);
      expect(startRes.status, 'POST /blobs/uploads/ to start upload').toBe(202);
      expect(startRes.uploadUuid, 'upload UUID from Docker-Upload-UUID header').toBeDefined();

      const uploadUuid = startRes.uploadUuid!;

      // Verify the upload session exists by checking its status immediately
      const statusBeforeRes = await rawUploadStatus(repo.name, credential, image, uploadUuid);
      expect(statusBeforeRes.status, 'HEAD to check upload status before TTL').toBe(204);

      // Step 2: Wait for the TTL (5 seconds) + cleanup interval (2 seconds) + buffer (1 second)
      // Total: 8 seconds of waiting ensures the cleanup task has run.
      await waitMs(8000);

      // Step 3: Attempt to check the status of the abandoned upload; it should be gone
      const statusAfterRes = await rawUploadStatus(repo.name, credential, image, uploadUuid);
      expect(
        statusAfterRes.status,
        'HEAD to abandoned upload after TTL should return 404 or 410',
      ).toMatch(/40[4|]/);
    },
  );

  test(
    'D-TTL2: concurrent abandoned uploads are all cleaned up',
    { tag: ['@upload-ttl'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
      const credential = adminCredential();

      // Start multiple abandoned uploads for different images
      const images = [
        `e2e-${seeder.runId}-abandoned-1`,
        `e2e-${seeder.runId}-abandoned-2`,
        `e2e-${seeder.runId}-abandoned-3`,
      ];
      const uploads: { image: string; uploadUuid: string }[] = [];

      for (const image of images) {
        const startRes = await rawStartUpload(repo.name, credential, image);
        expect(startRes.status, `start upload for ${image}`).toBe(202);
        uploads.push({
          image,
          uploadUuid: startRes.uploadUuid!,
        });
      }

      // Verify all uploads exist
      for (const { image, uploadUuid } of uploads) {
        const statusRes = await rawUploadStatus(repo.name, credential, image, uploadUuid);
        expect(statusRes.status, `upload ${image} exists before TTL`).toBe(204);
      }

      // Wait for TTL + cleanup
      await waitMs(8000);

      // Verify all uploads are cleaned up
      for (const { image, uploadUuid } of uploads) {
        const statusRes = await rawUploadStatus(repo.name, credential, image, uploadUuid);
        expect(statusRes.status, `upload ${image} cleaned up after TTL`).toMatch(/40[4|]/);
      }
    },
  );
});
