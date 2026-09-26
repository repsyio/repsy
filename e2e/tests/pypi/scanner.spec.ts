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
 * `@scanner` (RPS-1484): a package published by the REAL `twine upload` is scanned through the real
 * backend-to-scanner path, against the stub scanner of the scanner overlay (`./run.sh local up
 * --scanner`, README.md "Scanner stack"): one scan of the package name and version, with the wheel as
 * the file the scanner gets; the panel API then holds the scan the scanner reported and its findings.
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
import { pypiAdapter } from '../../src/clients/pypi.js';

test.describe('a twine upload is scanned (stub scanner)', { tag: [SCANNER_TAG] }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 180_000 });

  test(
    'the wheel a real twine upload published is submitted to the scanner, and its scan shows in the panel API',
    { tag: ['@pypi'] },
    async ({ world, scanner, panelApi }) => {
      const w = await world(WIRE_SCENARIO, pypiAdapter);
      const { packageName: name, version } = w.publishTarget;
      // The catalog's names carry no directive of the stub's rules, so the scan is scripted by name.
      await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

      // The pre-publish is the real client alone (no raw companion probe), which is all this needs.
      await pypiAdapter.seedPublish(w);

      const call = await expectScanReported(panelApi, scanner, {
        repoName: w.repoName,
        name,
        version,
      });
      expect(call).toMatchObject({
        repoType: 'PYPI',
        artifactName: name,
        artifactVersion: version,
        dockerImageReference: null,
      });
      expect(call.fileName, 'the scanner got the wheel').toMatch(/\.whl$/);
      expect(call.fileSize).toBeGreaterThan(0);
    },
  );
});
