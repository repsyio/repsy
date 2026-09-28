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

/**
 * `@scanner` (RPS-1484): an image pushed by the REAL `crane push` is scanned through the real
 * backend-to-scanner path, against the stub scanner of the scanner overlay (`./run.sh local up
 * --scanner`, README.md "Scanner stack"). Docker is the one protocol that sends no file: the backend
 * hands the scanner the image REFERENCE to pull (`<registry>/<repo>/<image>:<tag>`) and a token for the
 * registry, and the scanner pulls it itself (the stub pulls nothing; the real scanner's pull is B8's).
 * The panel API then holds the scan the scanner reported and its findings.
 */
import path from 'node:path';

import {
  SCANNER_TAG,
  SCRIPTED_SEVERITIES,
  WIRE_SCENARIO,
  expect,
  expectScanReported,
  skipUnlessScannerOptedIn,
  test,
} from '../../src/scenarios/scanner-fixtures.js';
import { RepoType } from '../../src/api/panel-api.js';
import { craneEnv, dockerAdapter, renderDockerConfig } from '../../src/clients/docker.js';
import { buildIndexImage } from '../../src/clients/docker-image.js';
import { imageRef } from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { repoPath } from '../../src/repo-url.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

test.describe('a crane push is scanned (stub scanner)', { tag: [SCANNER_TAG] }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 180_000 });

  test(
    'an image a real crane push published is scanned by reference, with the registry token, and its scan shows in the panel API',
    { tag: ['@docker'] },
    async ({ world, scanner, panelApi }) => {
      const w = await world(WIRE_SCENARIO, dockerAdapter);
      const { packageName: name, version } = w.publishTarget;
      // The catalog's names carry no directive of the stub's rules, so the scan is scripted by name.
      await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

      // The pre-publish is the real client alone (no raw companion probe), which is all this needs.
      await dockerAdapter.seedPublish(w);

      const call = await expectScanReported(panelApi, scanner, {
        repoName: w.repoName,
        name,
        version,
      });
      expect(call).toMatchObject({
        repoType: 'DOCKER',
        artifactName: name,
        artifactVersion: version,
        fileName: null,
        fileSize: null,
        hasRegistryAuthToken: true,
      });
      expect(call.dockerImageReference).toMatch(
        new RegExp(`/${repoPath(w.repoName)}/${name}:${version}$`),
      );
    },
  );

  test(
    'pushing a multi-platform index triggers exactly ONE scan, not one per platform child',
    { tag: ['@docker'] },
    async ({ seeder, scanner, panelApi }) => {
      // Set up repo and credential like protocol-specific tests do
      const repo = await seeder.createRepo(RepoType.DOCKER, { privateRepo: true });
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const credential: MaterializedCredential = {
        transport: 'basic',
        username: token.username,
        password: token.token,
        kind: 'token',
      };

      const imageName = `e2e-${seeder.runId}-scan-multi`;
      const multiTag = 'multi';

      // Script the scan
      await scanner.script(imageName, { findings: [...SCRIPTED_SEVERITIES] });

      // Build a multi-platform index with 2 children
      const { home, work } = await isolatedWorkDir(`scanner-multi-${seeder.runId}`);
      await renderDockerConfig(home, credential);

      const index = await buildIndexImage({
        dir: path.join(work, 'multi-index'),
        marker: 'scanner-multi',
        platforms: [
          { os: 'linux', arch: 'amd64' },
          { os: 'linux', arch: 'arm64' },
        ],
      });

      // Push the multi-platform index using crane
      const ref = imageRef(repo.name, imageName, multiTag);
      const pushResult = await run('crane', ['push', index.dir, ref], {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 60_000,
        label: 'scanner-push-multi',
      });
      expect(pushResult.exitCode, `crane push multi-platform: ${pushResult.command}`).toBe(0);

      // Check that exactly ONE scan was submitted to the scanner (not one per platform)
      const call = await expectScanReported(panelApi, scanner, {
        repoName: repo.name,
        name: imageName,
        version: multiTag,
      });

      // Verify the scan result
      expect(call).toMatchObject({
        repoType: 'DOCKER',
        artifactName: imageName,
        artifactVersion: multiTag,
        fileName: null,
        fileSize: null,
        hasRegistryAuthToken: true,
      });
    },
  );
});
