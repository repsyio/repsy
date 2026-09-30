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
 * `@scanner` (RPS-1484, and RPS-1719 batch C7: Helm had no `@scanner` spec despite
 * `HelmArtifactStorageResolver` already existing): a chart pushed by the REAL `helm cm-push` is
 * scanned through the real backend-to-scanner path, against the stub scanner of the scanner
 * overlay (`./run.sh local up --scanner`, README.md "Scanner stack"): one scan of the chart name
 * and version, with the chart `.tgz` as the file the scanner gets -- the same shape as
 * `tests/pypi/scanner.spec.ts` (a real client publishes a file, no image reference the way Docker's
 * spec has). The panel API then holds the scan the scanner reported and its findings.
 *
 * Both publish routes are covered. The classic one (`helmClassicAdapter`, `cm-push`) stores
 * `charts/<name>-<version>.tgz`. The OCI one (`helmAdapter`, `helm push`) stores the archive as the
 * chart layer blob under `oci/blobs/<digest>`, which used to leave the scan silently never
 * submitted (RPS-1736, the same classic-then-OCI order RPS-1217 gave the download): the push event
 * now names the blob and the scanner is sent it as `<name>-<version>.tgz`.
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
import { helmClassicAdapter } from '../../src/clients/helm-classic.js';
import { helmAdapter } from '../../src/clients/helm.js';

test.describe('a helm cm-push is scanned (stub scanner)', { tag: [SCANNER_TAG] }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 180_000 });

  test(
    'the chart a real helm cm-push published is submitted to the scanner, and its scan shows in the panel API',
    { tag: ['@helm'] },
    async ({ world, scanner, panelApi }) => {
      const w = await world(WIRE_SCENARIO, helmClassicAdapter);
      const { packageName: name, version } = w.publishTarget;
      // The catalog's names carry no directive of the stub's rules, so the scan is scripted by name.
      await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

      // The pre-publish is the real client alone (no raw companion probe), which is all this needs.
      await helmClassicAdapter.seedPublish(w);

      const call = await expectScanReported(panelApi, scanner, {
        repoName: w.repoName,
        name,
        version,
      });
      expect(call).toMatchObject({
        repoType: 'HELM',
        artifactName: name,
        artifactVersion: version,
        dockerImageReference: null,
      });
      expect(call.fileName, 'the scanner got the chart .tgz').toMatch(/\.tgz$/);
      expect(call.fileSize).toBeGreaterThan(0);
    },
  );

  test(
    'a chart pushed ONLY through OCI (helm push) is submitted to the scanner as <name>-<version>.tgz (RPS-1736)',
    { tag: ['@helm'] },
    async ({ world, scanner, panelApi }) => {
      const w = await world(WIRE_SCENARIO, helmAdapter);
      const { packageName: name, version } = w.publishTarget;
      await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

      await helmAdapter.seedPublish(w);

      const call = await expectScanReported(panelApi, scanner, {
        repoName: w.repoName,
        name,
        version,
      });
      expect(call).toMatchObject({
        repoType: 'HELM',
        artifactName: name,
        artifactVersion: version,
        dockerImageReference: null,
      });
      expect(call.fileName).toBe(`${name}-${version}.tgz`);
      expect(call.fileSize).toBeGreaterThan(0);
    },
  );
});
