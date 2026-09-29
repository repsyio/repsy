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
 * Published through the CLASSIC protocol (`helmClassicAdapter`, `cm-push`), not OCI
 * (`helmAdapter`, `helm push`): confirmed live that `HelmArtifactStorageResolver` (the class this
 * spec was added to finally exercise) resolves a scanned chart's bytes only through
 * `HelmStorageService.getChartRelativePath`/`getResource`, the CLASSIC storage path -- the same
 * one the README's "B-H1"/RPS-1217 finding already documents an OCI-only push never writes to
 * (`AbstractHelmOciManifestPushProtocolMethodHandler` writes chart bytes keyed by OCI digest under
 * `oci/blobs/`, never the classic `charts/<name>-<version>.tgz` layout). A chart published purely
 * via OCI therefore has nothing for the resolver to find: the scan is silently never submitted (no
 * error, no findings, no failed status -- `ArtifactPushedEventPostProcessor` fires the event fine,
 * but `ArtifactScanListener`'s resolve step comes back empty and skips the submit). That gap is the
 * same storage-path unification RPS-1217 already tracks, not something to fix in this batch --
 * publishing through the classic protocol here exercises the resolver on the storage layout it
 * actually reads today, which is a genuine, currently-working scan path.
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
});
