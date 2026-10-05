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
 * `@scanner` (RPS-1962): a SCOPED npm package (`@scope/name`) published by the real `npm publish` is
 * scanned through the real backend-to-scanner path, against the stub scanner of the scanner overlay
 * (`./run.sh local up --scanner`, README.md "Scanner stack"). Scoped packages have their own panel
 * routes (`/api/repos/{repoName}/scopes/{scope}/artifacts/{artifactName}/versions/{version}/scans`),
 * which `panelApi.listVersionScans` picks for an `@scope/name`: the scan the scanner reported and its
 * findings must be reachable through them, and the scanner must have been given the whole scoped name.
 */
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import {
  SCANNER_TAG,
  SCRIPTED_SEVERITIES,
  expect,
  expectScanReported,
  skipUnlessScannerOptedIn,
  test,
} from '../../../src/scenarios/scanner-fixtures.js';

test.describe(
  'a scoped npm package is scanned (real npm, stub scanner)',
  { tag: [SCANNER_TAG] },
  () => {
    skipUnlessScannerOptedIn();
    test.describe.configure({ timeout: 180_000 });

    test(
      'the tarball of @scope/name is submitted under its scoped name, and its scan shows on the scoped panel routes',
      { tag: ['@npm', '@scoped'] },
      async ({ seeder, scanner, panelApi }) => {
        const repo = await newRepo(seeder);
        const name = packageNameFor(seeder, 'scoped-scanned', true);
        const version = '1.0.0';
        expect(name, 'a scoped name').toMatch(/^@[^/]+\/[^/]+$/);
        await scanner.script(name, { findings: [...SCRIPTED_SEVERITIES] });

        const publisher = await npmClient.prepare('scoped-scan-pub', [
          await tokenBinding(seeder, repo.name, { readOnly: false }),
        ]);
        const published = await publishPackage(npmClient, publisher, {
          packageName: name,
          version,
        });
        expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

        const call = await expectScanReported(panelApi, scanner, {
          repoName: repo.name,
          name,
          version,
        });
        expect(call).toMatchObject({
          repoType: 'NPM',
          artifactName: name,
          artifactVersion: version,
          dockerImageReference: null,
        });
        expect(call.fileSize, 'the scanner got the tarball').toBeGreaterThan(0);
      },
    );
  },
);
