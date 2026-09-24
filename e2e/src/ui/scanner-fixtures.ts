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
 * The `test` of the `@scanner` specs (RPS-1270, `tests/ui/security-real/`), which run against a stack
 * started with the stub scanner overlay (`./run.sh local up --scanner`, README.md "Scanner stack").
 *
 * `security-fixtures.ts`'s `test` (so `seedPackage` and the stub-free security page objects are there)
 * plus `scanner`, a client of the stub's `/control` API. The fixture FAILS, it does not skip, when the
 * stack has no scanner: a run that opted in with `REPSY_UI_OPT_IN=scanner` on the wrong stack has to be
 * loud. Not opting in skips every spec cleanly (`skipUnlessScannerOptedIn()`), before any fixture runs.
 *
 * Package names carry the stub's directives (`src/stubs/scanner/rules.ts`): `scanPackageName()` builds
 * a name that is valid for the protocol and contains `<directive>` as a whole word, so what a scan
 * reports is decided by how the test names what it publishes.
 */
import { expect, test as securityTest } from './security-fixtures.js';
import type { PackageProtocol, PackageRef, SeededPackage } from '../seed/packages.js';
import { ScannerStubClient, type RecordedCall } from '../stubs/scanner/client.ts';
import type { PanelApi, VulnerabilityScanInfo } from '../api/panel-api.js';
import { optedIn } from './session.js';

export { ScannerStubClient };
export const SCANNER_TAG = '@scanner';

/** What the stub says about itself in a completed scan (`SCANNER_VERSION` in `rules.ts`). */
export { SCANNER_VERSION } from '../stubs/scanner/rules.ts';

/** How long a spec waits for a scan to finish end to end: submit, the backend's poll, the panel's poll. */
export const SCAN_TIMEOUT = { timeout: 60_000 } as const;

/** The panel polls an unfinished scan every 3 s, so one visible step needs a little more than that. */
export const POLL_STEP = { timeout: 20_000 } as const;

/**
 * Skips every test of the enclosing `describe` unless the run opted into the scanner suite. Call it
 * first in the describe body. A skip, not a failure: the default stack has no scanner.
 */
export function skipUnlessScannerOptedIn(): void {
  securityTest.skip(
    !optedIn('scanner'),
    'opt-in: needs a stack started with the stub scanner (./run.sh local up --scanner); run with ' +
      'REPSY_UI_OPT_IN=scanner (README "Scanner stack")',
  );
}

/** A directive of the stub scanner that a package name can carry (rules.ts). */
export type Directive = string;

/**
 * A package identity that is valid for `protocol`, unique per test and holds `directive` as a whole
 * word, e.g. `vuln-critical` or `fail`. `variant` tells apart two packages of one test that carry the
 * same directive. The scanner only sees the name and version (never the repo), and this is the name.
 */
export function scanPackageName(
  protocol: PackageProtocol,
  runId: string,
  directive: Directive,
  variant = '',
): string {
  const suffix = variant ? `-${variant}` : '';
  const compact = runId.replace(/[^a-z0-9]/gi, '').toLowerCase();
  switch (protocol) {
    case 'maven':
      return `io.repsy.e2e.${compact}:${directive}${suffix}`;
    case 'npm':
      return `@e2e-${runId}/${directive}${suffix}`;
    case 'pypi':
    case 'docker':
      return `e2e-${runId}-${directive}${suffix}`;
    default:
      throw new Error(`scanPackageName: ${protocol} has no scanner`);
  }
}

export interface ScannerFixtures {
  scanner: ScannerStubClient;
}

export const test = securityTest.extend<ScannerFixtures>({
  scanner: [
    async ({ panelApi }, use) => {
      const client = ScannerStubClient.fromEnv();
      if (!(await client.isUp())) {
        throw new Error(
          `The stub scanner is not reachable at ${client.baseUrl} but this run opted into "scanner". ` +
            'Start the stack with the scanner overlay (./run.sh local up --scanner) and point ' +
            'REPSY_SCANNER_STUB_URL / REPSY_E2E_SCANNER_PORT at it (README "Scanner stack").',
        );
      }
      const types = await panelApi.supportedScanRepoTypes();
      if (types.length === 0) {
        throw new Error(
          'GET /api/security/supported-repo-types is empty: this Repsy runs with SECURITY_SCANNER=disabled. ' +
            'Start the stack with the scanner overlay (./run.sh local up --scanner).',
        );
      }
      await use(client);
    },
    // Automatic: every @scanner test checks the stack first, so a wrong stack fails at once and clearly.
    { auto: true },
  ],
});

export { expect };

/** The name the backend sent the scanner for `pkg`: the stub's own record of the submit. */
export async function submittedNameOf(
  scanner: ScannerStubClient,
  pkg: Pick<SeededPackage, 'name' | 'version'>,
): Promise<string> {
  // The backend may spell an artifact differently from the panel's key (maven `group:artifact`); the
  // run id inside the name is what identifies the test's own submit.
  let found: string | undefined;
  await expect
    .poll(
      async () => {
        const calls = await scanner.calls();
        found = calls.find(
          (call) => call.artifactName === pkg.name && call.artifactVersion === pkg.version,
        )?.artifactName;
        return found;
      },
      { message: `the scanner received a scan of ${pkg.name}@${pkg.version}`, ...SCAN_TIMEOUT },
    )
    .toBeDefined();
  return found as string;
}

/**
 * Asserts what the stub recorded for the one submit of `pkg`: the backend handed the scanner the
 * artifact itself (a file part with content), or, for Docker, the image reference to pull, which
 * the scanner resolves against the registry (no file at all).
 */
export function expectSubmitted(
  call: RecordedCall,
  submitted: { protocol: PackageProtocol; repoName: string; repoType: string; pkg: PackageRef },
): void {
  const { protocol, repoName, repoType, pkg } = submitted;
  expect(call).toMatchObject({
    repoType,
    artifactName: pkg.name,
    artifactVersion: pkg.version,
    result: 'accepted',
  });
  if (protocol === 'docker') {
    expect(call.fileName).toBeNull();
    expect(call.dockerImageReference).toMatch(
      new RegExp(`/${repoName}/${pkg.name}:${pkg.version}$`),
    );
  } else {
    expect(call.fileName).toBeTruthy();
    expect(call.fileSize).toBeGreaterThan(0);
  }
}

/**
 * Waits (bounded) until the NEWEST scan of a version is FINISHED (COMPLETED or FAILED) and returns
 * it. `scanCount` waits for that many scans to exist first (a re-scan adds a second one).
 */
export async function newestFinishedScan(
  panelApi: PanelApi,
  repoName: string,
  pkg: PackageRef,
  scanCount = 1,
): Promise<VulnerabilityScanInfo> {
  let newest: VulnerabilityScanInfo | undefined;
  await expect
    .poll(
      async () => {
        const scans = await panelApi.listVersionScans(repoName, pkg.name, pkg.version);
        newest = scans[0];
        return scans.length >= scanCount && ['COMPLETED', 'FAILED'].includes(newest?.status ?? '');
      },
      {
        message: `the newest scan of ${pkg.name}@${pkg.version} in ${repoName} finished`,
        ...SCAN_TIMEOUT,
      },
    )
    .toBe(true);
  return newest as VulnerabilityScanInfo;
}
