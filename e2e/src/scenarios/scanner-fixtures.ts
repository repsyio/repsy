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
 * The `test` of the `@scanner` specs of the WIRE runners (RPS-1484: npm-clients, docker, maven, pypi),
 * which run against a stack started with the stub scanner overlay (`./run.sh local up --scanner`,
 * README.md "Scanner stack") and publish with a REAL client. The ui runner's `@scanner` specs have
 * their own `test` (`src/ui/scanner-fixtures.ts`, page objects and all); this one has none of the
 * browser and imports nothing from `src/ui`.
 *
 * `test` is the scenario suite's (`panelApi`, `seeder`) plus `scanner`, a client of the stub's `/control`
 * API. The fixture FAILS, it does not skip, when the stack has no scanner: a run that opted in on the
 * wrong stack has to be loud. Not opting in skips a describe cleanly (`skipUnlessScannerOptedIn`).
 *
 * The stub decides what a scan of an artifact reports by its NAME (`src/stubs/scanner/rules.ts`) or by a
 * script registered for that exact name (`scanner.script(name, ...)`): scripts are what let a finding
 * name the published package itself, so `npm audit` can show it.
 */
import { SCENARIOS } from './catalog.js';
import { expect, test as scenarioTest } from './fixtures.js';
import type { Scenario } from './types.js';
import type { PanelBackend, VulnerabilityScanInfo } from '../api/panel-backend.js';
import { optedIn } from '../stack-overlays.js';
import { ScannerStubClient, type RecordedCall } from '../stubs/scanner/client.ts';
import { SCANNER_VERSION } from '../stubs/scanner/rules.ts';

export { ScannerStubClient };
export const SCANNER_TAG = '@scanner';

/** What the stub says about itself in a completed scan (`SCANNER_VERSION` in `rules.ts`). */
export { SCANNER_VERSION };

/** How long a spec waits for a scan to finish end to end: submit, the backend's poll (1 s here). */
export const SCAN_TIMEOUT_MS = 60_000;

export interface ScannerFixtures {
  scanner: ScannerStubClient;
}

export const test = scenarioTest.extend<ScannerFixtures>({
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
      if ((await panelApi.supportedScanRepoTypes()).length === 0) {
        throw new Error(
          'GET /api/security/supported-repo-types is empty: this Repsy runs with SECURITY_SCANNER=disabled. ' +
            'Start the stack with the scanner overlay (./run.sh local up --scanner).',
        );
      }
      await use(client);
    },
    { auto: true },
  ],
});

export { expect };

/**
 * Skips every test of the enclosing `describe` unless the run opted into the scanner suite (the
 * default stack has no scanner). Call it first in the describe body.
 */
export function skipUnlessScannerOptedIn(): void {
  test.skip(
    !optedIn('scanner'),
    'opt-in: needs a stack started with the stub scanner (./run.sh local up --scanner); run with ' +
      'REPSY_E2E_SCANNER=1 ./run.sh test (README "Scanner stack")',
  );
}

/**
 * Waits (bounded) until the newest scan of a version is FINISHED (COMPLETED or FAILED) and returns it.
 * A wire client can be done before the backend has even submitted the artifact, so the poll also
 * covers "no scan yet".
 */
export async function finishedScan(
  panelApi: PanelBackend,
  repoName: string,
  artifactName: string,
  version: string,
): Promise<VulnerabilityScanInfo> {
  let newest: VulnerabilityScanInfo | undefined;
  await expect
    .poll(
      async () => {
        const scans = await panelApi.listVersionScans(repoName, artifactName, version);
        newest = scans[0];
        return ['COMPLETED', 'FAILED'].includes(newest?.status ?? '');
      },
      {
        message: `the newest scan of ${artifactName}@${version} in ${repoName} finished`,
        timeout: SCAN_TIMEOUT_MS,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(true);
  return newest as VulnerabilityScanInfo;
}

/** The scans the stub was asked for `artifactName` (all its versions), waiting for `count` of them. */
export async function submittedCalls(
  scanner: ScannerStubClient,
  artifactName: string,
  count = 1,
): Promise<RecordedCall[]> {
  let calls: RecordedCall[] = [];
  await expect
    .poll(
      async () => {
        calls = await scanner.calls(artifactName);
        return calls.length;
      },
      {
        message: `the scanner received ${count} scan(s) of ${artifactName}`,
        timeout: SCAN_TIMEOUT_MS,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBeGreaterThanOrEqual(count);
  return calls;
}

/** The severities the wire specs script for a package whose name carries no directive. */
export const SCRIPTED_SEVERITIES = ['CRITICAL', 'HIGH', 'HIGH'] as const;

/** The CVE ids of `SCRIPTED_SEVERITIES`, sorted (rows of one severity have no fixed order). */
export const SCRIPTED_CVE_IDS = ['CVE-2099-1001', 'CVE-2099-2001', 'CVE-2099-2002'];

export interface ScannedArtifact {
  repoName: string;
  /** The name exactly as the protocol and the panel spell it (maven `group:artifact`, pypi the name...). */
  name: string;
  version: string;
}

/**
 * The end of a `@scanner` wire spec: `artifact` was just published by a real client with a script of
 * `SCRIPTED_SEVERITIES` registered for `name`. Waits for the scan and asserts, from the stub's own
 * record and from the panel API: the backend submitted exactly one scan of it, it COMPLETED with the
 * scanner's version and the scripted worst severity, and the panel lists exactly the scripted
 * findings. Returns the stub's record of the submit, for the protocol's own assertions.
 */
export async function expectScanReported(
  panelApi: PanelBackend,
  scanner: ScannerStubClient,
  artifact: ScannedArtifact,
): Promise<RecordedCall> {
  const { repoName, name, version } = artifact;
  await submittedCalls(scanner, name);
  const scan = await finishedScan(panelApi, repoName, name, version);
  // Read after the scan finished, so a second (duplicate) submit would already be recorded.
  const calls = (await scanner.calls(name)).filter((call) => call.artifactVersion === version);
  expect(calls, `one scan of ${name}@${version} was submitted`).toHaveLength(1);

  expect(scan).toMatchObject({
    repoName,
    artifactName: name,
    artifactVersion: version,
    status: 'COMPLETED',
    highestSeverity: 'CRITICAL',
    scannerVersion: SCANNER_VERSION,
  });
  const findings = await panelApi.listScanFindings(repoName, scan.id ?? '');
  expect(findings.map((finding) => finding.cveId).sort()).toEqual(SCRIPTED_CVE_IDS);
  expect(findings.map((finding) => finding.severity).sort()).toEqual(['CRITICAL', 'HIGH', 'HIGH']);
  return calls[0] as RecordedCall;
}

/**
 * The catalog scenario the wire specs publish under: a private repo and the admin's password, which
 * publishes. Only its repo and credential are used (`world(WIRE_SCENARIO, adapter)`, then the
 * adapter's `seedPublish`, the real client alone), never its expectations.
 */
export const WIRE_SCENARIO: Scenario = (() => {
  const scenario = SCENARIOS.find((candidate) => candidate.id === 'password-admin');
  if (!scenario) {
    throw new Error('the scenario catalog has no password-admin scenario');
  }
  return scenario;
})();
