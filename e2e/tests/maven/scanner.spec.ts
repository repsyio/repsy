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
 * `@scanner` (RPS-1484): a package published by the REAL `mvn deploy` is scanned through the real
 * backend-to-scanner path, against the stub scanner of the scanner overlay (`./run.sh local up
 * --scanner`, README.md "Scanner stack"). One scan per deploy (a jar, a pom, checksums and a metadata
 * file are uploaded), of the artifact's `groupId:artifactId` name and version, with the JAR as the file
 * the scanner gets; the panel API then holds the scan the scanner reported and its findings.
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
import { mavenAdapter } from '../../src/clients/maven-adapter.js';

test.describe('a mvn deploy is scanned (stub scanner)', { tag: [SCANNER_TAG] }, () => {
  skipUnlessScannerOptedIn();
  test.describe.configure({ timeout: 180_000 });

  test(
    'the artifact a real mvn deploy published is submitted to the scanner as its jar, and its scan shows in the panel API',
    { tag: ['@maven'] },
    async ({ world, scanner, panelApi }) => {
      const w = await world(WIRE_SCENARIO, mavenAdapter);
      const { packageName: name, version } = w.publishTarget;
      // The catalog's names carry no directive of the stub's rules, so the scan is scripted by name.
      await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

      // The pre-publish is the real client alone (no raw companion probe), which is all this needs.
      await mavenAdapter.seedPublish(w);

      const call = await expectScanReported(panelApi, scanner, {
        repoName: w.repoName,
        name,
        version,
      });
      expect(call).toMatchObject({
        repoType: 'MAVEN',
        artifactName: name,
        artifactVersion: version,
        fileName: `${name.split(':')[1]}-${version}.jar`,
        dockerImageReference: null,
      });
      expect(call.fileSize, 'the scanner got the jar itself').toBeGreaterThan(0);
    },
  );
});
