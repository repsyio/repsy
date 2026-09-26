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
 * `@scanner` (RPS-1484): the audit commands of the npm-family clients against a scan the STUB scanner
 * (`./run.sh local up --scanner`, README.md "Scanner stack") made of a package published with the same
 * real client. `registry-endpoints.spec.ts` proves the audit endpoints answer an empty report; this is
 * the only place a real `npm audit`, `pnpm audit`, `yarn npm audit` and `bun audit` are shown an
 * advisory.
 *
 * What makes it possible: Repsy answers an audit from the findings of the repository's scans by the
 * finding's package NAME and VERSION (`NpmAdvisorySourceImpl`), and the stub's own findings are made-up
 * `stub-lib-*` packages, so it is scripted (`PUT /control/scripts`, `src/stubs/scanner/`) to report a
 * finding on the very package the test publishes.
 *
 * Per client, on one repository:
 *  - `lib@1.0.0` is published and scanned with a scripted HIGH finding on `lib@1.0.0`; the scan and its
 *    finding are read back from the panel API (the script reached the backend);
 *  - the script is cleared and `lib@2.0.0` (the "fixed" version) is published and scanned: COMPLETED, clean;
 *  - a consumer of `lib@1.0.0` runs the client's audit: exit code non-zero and the advisory (severity,
 *    title, url, vulnerable version) in the client's own report format; the same audit with a threshold
 *    above `high` exits 0 (the report still lists the advisory where the client prints it);
 *  - a consumer of `lib@2.0.0` audits clean, exit 0.
 *
 * npm alone also checks that the report follows the repository's scan setting: with it off, the very
 * same audit that just failed reports nothing (README "Auditing npm packages").
 */
import path from 'node:path';

import { bunClient, bunExec } from '../../../src/clients/npm-family/bun-client.js';
import type { ClientCtx, NpmFamilyClient } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient, runNpm } from '../../../src/clients/npm-family/npm-client.js';
import { runPnpm } from '../../../src/clients/npm-family/pnpm-client.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { execYarn } from '../../../src/clients/npm-family/yarn-berry-client.js';
import type { RunResult } from '../../../src/clients/exec.js';
import type { PanelBackend } from '../../../src/api/panel-backend.js';
import type { Seeder, SeededRepo } from '../../../src/seed/seeder.js';
import {
  SCANNER_TAG,
  SCANNER_VERSION,
  expect,
  finishedScan,
  skipUnlessScannerOptedIn,
  submittedCalls,
  test,
  type ScannerStubClient,
} from '../../../src/scenarios/scanner-fixtures.js';

const CVE = 'CVE-2099-7001';
const DESCRIPTION = 'Prototype pollution in lib';
const VULNERABLE = '1.0.0';
const FIXED = '2.0.0';

/** What every client's report says about the one advisory, whatever its format. */
interface Advisory {
  severity: string;
  title: string;
  url: string;
  vulnerableVersions: string;
}

/** A client's audit at a threshold ("nothing below critical counts"), and how it prints its report. */
interface AuditDriver {
  /** `<client> audit` with the severity threshold set to `critical`, in the client's own flag. */
  atCritical(ctx: ClientCtx): Promise<RunResult>;
  /** The advisories of `package` in the report `client.audit` printed. */
  advisories(stdout: string, packageName: string): Advisory[];
  /** Whether the report says the tree has no vulnerability at all. */
  isClean(stdout: string): boolean;
}

const NPM_ARGS = (ctx: ClientCtx): string[] => [
  '--userconfig',
  path.join(ctx.home, '.npmrc'),
  '--cache',
  path.join(ctx.home, 'npm-cache'),
];

const DRIVERS: Record<string, AuditDriver> = {
  // npm 7+ report: `vulnerabilities.<name>.via[]` holds the advisories, `metadata.vulnerabilities` the counts.
  npm: {
    atCritical: (ctx) =>
      runNpm(ctx, 'npm-audit-critical', [
        'audit',
        '--json',
        '--audit-level=critical',
        ...NPM_ARGS(ctx),
      ]),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as {
        vulnerabilities: Record<
          string,
          { via: { title?: string; url?: string; severity: string; range: string }[] }
        >;
      };
      return (report.vulnerabilities[name]?.via ?? []).map((via) => ({
        severity: via.severity,
        title: via.title ?? '',
        url: via.url ?? '',
        vulnerableVersions: via.range,
      }));
    },
    isClean: (stdout) => {
      const report = JSON.parse(stdout) as { metadata: { vulnerabilities: { total: number } } };
      return report.metadata.vulnerabilities.total === 0;
    },
  },
  // pnpm answers the npm 6 report: `advisories.<id>` and per-severity counts.
  pnpm: {
    atCritical: (ctx) =>
      runPnpm(ctx, 'pnpm-audit-critical', ['audit', '--json', '--audit-level', 'critical']),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as {
        advisories: Record<
          string,
          {
            module_name: string;
            title: string;
            url: string;
            severity: string;
            vulnerable_versions: string;
          }
        >;
      };
      return Object.values(report.advisories)
        .filter((advisory) => advisory.module_name === name)
        .map((advisory) => ({
          severity: advisory.severity,
          title: advisory.title,
          url: advisory.url,
          vulnerableVersions: advisory.vulnerable_versions,
        }));
    },
    isClean: (stdout) =>
      Object.keys((JSON.parse(stdout) as { advisories: object }).advisories).length === 0,
  },
  // bun prints the bare map `{<name>: [advisory]}`, not npm's report.
  bun: {
    // Text mode: `--json --audit-level=critical` still lists the advisory and exits 1 (bun 1.3.14).
    atCritical: (ctx) => bunExec(ctx, 'bun-audit-critical', ['audit', '--audit-level=critical']),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as Record<
        string,
        { title: string; url: string; severity: string; vulnerable_versions: string }[]
      >;
      return (report[name] ?? []).map((advisory) => ({
        severity: advisory.severity,
        title: advisory.title,
        url: advisory.url,
        vulnerableVersions: advisory.vulnerable_versions,
      }));
    },
    isClean: (stdout) => Object.keys(JSON.parse(stdout) as object).length === 0,
  },
  // berry has no JSON report worth parsing here: its own tree of `Issue:` / `URL:` / ... lines.
  'yarn-berry': {
    atCritical: (ctx) =>
      execYarn(ctx, 'yarn4-audit-critical', ['npm', 'audit', '--severity', 'critical']),
    advisories: (stdout, name) => {
      const block = stdout.split(/^└─ |^├─ /m).find((part) => part.startsWith(name));
      if (!block) {
        return [];
      }
      const field = (label: string): string =>
        new RegExp(`${label}: (.*)`).exec(block)?.[1]?.trim() ?? '';
      return [
        {
          severity: field('Severity'),
          title: field('Issue'),
          url: field('URL'),
          vulnerableVersions: field('Vulnerable Versions'),
        },
      ];
    },
    isClean: (stdout) => stdout.includes('No audit suggestions'),
  },
};

/** The clients under test: every one whose matrix has an audit, plus bun (its own report shape). */
const AUDIT_CLIENTS: readonly NpmFamilyClient[] = [...clientsWith('auditCmd'), bunClient];

interface Published {
  repo: SeededRepo;
  lib: string;
  /** The install of `version` of the package in a fresh consumer of `client`. */
  consumerOf(version: string, label: string, baseUrl?: string): Promise<ClientCtx>;
}

/**
 * Publishes `lib@1.0.0` with a scripted HIGH finding on itself and `lib@2.0.0` with none, each with
 * `client`, and waits until the backend has both scans (the panel API says what they found).
 */
async function publishScanned(
  client: NpmFamilyClient,
  seeder: Seeder,
  scanner: ScannerStubClient,
  panelApi: PanelBackend,
): Promise<Published> {
  const repo = await newRepo(seeder);
  expect((await panelApi.getSettings(repo.name)).securityScanEnabled, 'scanning is on').toBe(true);
  const lib = packageNameFor(seeder, 'audited');
  const publisher = await client.prepare('audit-pub', [
    await tokenBinding(seeder, repo.name, { readOnly: false }),
  ]);

  await scanner.script(lib, {
    findings: [
      {
        severity: 'HIGH',
        packageName: lib,
        packageVersion: VULNERABLE,
        cveId: CVE,
        description: DESCRIPTION,
      },
    ],
  });
  const vulnerable = await publishPackage(client, publisher, {
    packageName: lib,
    version: VULNERABLE,
  });
  expect(vulnerable.result.exitCode, `publish: ${vulnerable.result.command}`).toBe(0);
  const scan = await finishedScan(panelApi, repo.name, lib, VULNERABLE);
  expect(scan).toMatchObject({
    status: 'COMPLETED',
    highestSeverity: 'HIGH',
    scannerVersion: SCANNER_VERSION,
  });
  const findings = await panelApi.listScanFindings(repo.name, scan.id ?? '');
  expect(findings, 'the finding the script named reached the backend').toMatchObject([
    { cveId: CVE, severity: 'HIGH', packageName: lib, packageVersion: VULNERABLE },
  ]);

  // The "fixed" release: the same package, scanned clean.
  await scanner.script(lib, { findings: [] });
  const fixed = await publishPackage(client, publisher, { packageName: lib, version: FIXED });
  expect(fixed.result.exitCode, `publish: ${fixed.result.command}`).toBe(0);
  const cleanScan = await finishedScan(panelApi, repo.name, lib, FIXED);
  expect(cleanScan).toMatchObject({ status: 'COMPLETED', scannerVersion: SCANNER_VERSION });
  expect(cleanScan.highestSeverity).toBeFalsy();

  // What the backend handed the scanner: each version, as the client published it.
  const calls = await submittedCalls(scanner, lib, 2);
  expect(calls.map((call) => [call.repoType, call.artifactName, call.artifactVersion])).toEqual([
    ['NPM', lib, VULNERABLE],
    ['NPM', lib, FIXED],
  ]);
  for (const call of calls) {
    expect(call.fileSize, `the scanner got the tarball of ${call.artifactVersion}`).toBeGreaterThan(
      0,
    );
  }

  return {
    repo,
    lib,
    consumerOf: async (version, label, baseUrl) => {
      const consumer = await client.prepare(label, [
        await tokenBinding(seeder, repo.name, { readOnly: true, ...(baseUrl ? { baseUrl } : {}) }),
      ]);
      await renderConsumer(consumer.work, 'audit-consumer', { [lib]: version });
      const installed = await client.install(consumer, { frozen: false });
      expect(installed.exitCode, `install: ${installed.command}\n${installed.stderr}`).toBe(0);
      return consumer;
    },
  };
}

test.describe(
  'the audit of a scanned package (real clients, stub scanner)',
  {
    tag: [SCANNER_TAG],
  },
  () => {
    skipUnlessScannerOptedIn();
    test.describe.configure({ timeout: 180_000 });

    for (const client of AUDIT_CLIENTS) {
      const driver = DRIVERS[client.id];
      if (!driver) {
        throw new Error(`no audit driver for ${client.id}`);
      }

      test(
        `${client.label} audit reports the advisory of the scanned version, and nothing for the clean one`,
        { tag: [client.tag, '@audit'] },
        async ({ seeder, scanner, panelApi }) => {
          const { repo, lib, consumerOf } = await publishScanned(client, seeder, scanner, panelApi);

          // Through the recorder, so the audit requests themselves are seen. npm and pnpm send a token
          // only to the host its registry URL names, so the recorder also answers for the tarballs of
          // the packument (which a lockfile then records; fine for a throw-away tree).
          const recorder = await startWireRecorder({ rewriteTarballUrls: client.id !== 'bun' });
          try {
            const vulnerable = await consumerOf(VULNERABLE, 'audit-vulnerable', recorder.baseUrl);
            const audited = await client.audit?.(vulnerable);
            expect(audited?.exitCode, `audit: ${audited?.command}\n${audited?.stderr}`).not.toBe(0);
            expect(driver.isClean(audited?.stdout ?? ''), 'the report is not clean').toBe(false);
            expect(driver.advisories(audited?.stdout ?? '', lib)).toEqual([
              {
                severity: 'high',
                title: `${CVE}: ${DESCRIPTION}`,
                url: `https://scanner-stub.invalid/advisories/${CVE}`,
                vulnerableVersions: VULNERABLE,
              },
            ]);

            // A threshold above the advisory's severity: the client no longer fails on it.
            const lenient = await driver.atCritical(vulnerable);
            expect(
              lenient.exitCode,
              `audit at critical: ${lenient.command}\n${lenient.stderr}`,
            ).toBe(0);
          } finally {
            await recorder.stop();
          }
          // Every client asks the bulk endpoint of THIS repository, once per audit, with its token.
          expect(
            recorder.entries
              .filter((entry) => entry.method === 'POST')
              .map((entry) => `${entry.path} ${entry.authScheme} ${entry.status}`),
            'the audit requests',
          ).toEqual(Array(2).fill(`/${repo.name}/-/npm/v1/security/advisories/bulk Bearer 200`));

          const fixed = await consumerOf(FIXED, 'audit-fixed');
          const clean = await client.audit?.(fixed);
          expect(clean?.exitCode, `audit: ${clean?.command}\n${clean?.stderr}`).toBe(0);
          expect(driver.isClean(clean?.stdout ?? ''), 'the fixed version is clean').toBe(true);
          expect(driver.advisories(clean?.stdout ?? '', lib)).toEqual([]);
        },
      );
    }

    test(
      'npm audit follows the repository scan setting: off reports nothing, on reports the advisory again',
      { tag: ['@npm', '@audit'] },
      async ({ seeder, scanner, panelApi }) => {
        const npm = npmClient;
        const driver = DRIVERS.npm as AuditDriver;
        const { repo, lib, consumerOf } = await publishScanned(npm, seeder, scanner, panelApi);
        const consumer = await consumerOf(VULNERABLE, 'audit-setting');

        const before = await npm.audit?.(consumer);
        expect(before?.exitCode, 'the scanned version fails the audit').not.toBe(0);
        expect(driver.advisories(before?.stdout ?? '', lib)).toHaveLength(1);

        await panelApi.updateSettings(repo.name, { securityScanEnabled: false });
        const off = await npm.audit?.(consumer);
        expect(off?.exitCode, `audit, scanning off: ${off?.command}\n${off?.stderr}`).toBe(0);
        expect(
          driver.isClean(off?.stdout ?? ''),
          'a repository that is not scanned reports none',
        ).toBe(true);

        await panelApi.updateSettings(repo.name, { securityScanEnabled: true });
        const on = await npm.audit?.(consumer);
        expect(on?.exitCode, 'the findings of the earlier scan are reported again').not.toBe(0);
        expect(driver.advisories(on?.stdout ?? '', lib)).toHaveLength(1);
      },
    );
  },
);
