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
import {
  SCANNER_TAG,
  SCRIPTED_SEVERITIES,
  WIRE_SCENARIO,
  expect,
  expectScanReported,
  skipUnlessScannerOptedIn,
  test,
} from '../../src/scenarios/scanner-fixtures.js';
import { dockerAdapter } from '../../src/clients/docker.js';

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
      expect(call.dockerImageReference).toMatch(new RegExp(`/${w.repoName}/${name}:${version}$`));
    },
  );
});
