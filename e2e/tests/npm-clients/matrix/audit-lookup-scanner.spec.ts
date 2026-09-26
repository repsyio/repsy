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
 * `@scanner` (RPS-1613): `npm audit` and `pnpm audit` of name/version pairs the repository does NOT
 * store, answered from the scanner's advisory lookup (`POST /advisories`, RPS-1610; Repsy asks it for
 * every pair an audit sends, RPS-1612, README "Auditing npm packages"), against the STUB scanner
 * (`./run.sh local up --scanner`). `matrix/audit-scanner.spec.ts` is the audit of what a scan stored;
 * here the stub's scripted `/advisories` (`src/stubs/scanner/advisories.ts`) is the only source, or one
 * of two.
 *
 * The consumers have a lockfile and nothing installed (`renderLockfileOnlyConsumer`): the pair they
 * audit exists in no registry, which is the point. npm and pnpm are the clients that audit a lockfile;
 * yarn berry and bun need their own lockfile formats and stay with the stored-findings spec.
 *
 * Per client:
 *  - a pair no scan and no version of the repository knows is reported from the lookup alone (severity,
 *    title, url, vulnerable version), the stub saw exactly that pair asked, another version of the same
 *    name is clean, and the repository was never asked to scan anything;
 *  - a finding the repository's scan stored and the lookup reports for the same (CVE, package, version)
 *    is reported once, as the stored one, beside a finding only the lookup knows;
 *  - with the lookup down (the stub answers 503, then 504, then a body that is not JSON) the audit is
 *    answered from the stored findings only, with the same exit code and no error, and an audit of a
 *    pair that only the lookup knows is clean and exits 0;
 *  - with the repository's security scan setting off nothing is reported and the stub is not asked at
 *    all (its record of lookups does not grow); on again, the pair is reported and asked for.
 */
import {
  DRIVERS,
  renderLockfileOnlyConsumer,
} from '../../../src/clients/npm-family/audit-drivers.js';
import type { ClientCtx, NpmFamilyClient } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { pnpmClient } from '../../../src/clients/npm-family/pnpm-client.js';
import type { AdvisoryOutcome } from '../../../src/stubs/scanner/advisories.js';
import type { PanelBackend } from '../../../src/api/panel-backend.js';
import type { Seeder, SeededRepo } from '../../../src/seed/seeder.js';
import {
  SCANNER_TAG,
  expect,
  finishedScan,
  skipUnlessScannerOptedIn,
  test,
  type ScannerStubClient,
} from '../../../src/scenarios/scanner-fixtures.js';

const VERSION = '1.0.0';
const OTHER_VERSION = '2.0.0';
const STORED_CVE = 'CVE-2099-7101';
const LOOKUP_CVE = 'CVE-2099-7102';
const STORED_DESCRIPTION = 'Path traversal in the stored scan';
const LOOKUP_DESCRIPTION = 'Denial of service known to the database only';
const REFERENCE = (cve: string): string => `https://scanner-stub.invalid/advisories/${cve}`;

/** The clients whose audit reads a lockfile, so that it needs no installed (and no existing) package. */
const CLIENTS: readonly NpmFamilyClient[] = [npmClient, pnpmClient];

/** A repository with a token, and the consumer that audits `dependencies` through it. */
async function auditedIn(
  client: NpmFamilyClient,
  seeder: Seeder,
  repo: SeededRepo,
  dependencies: Record<string, string>,
  label: string,
): Promise<ClientCtx> {
  const consumer = await client.prepare(label, [
    await tokenBinding(seeder, repo.name, { readOnly: true }),
  ]);
  await renderLockfileOnlyConsumer(client.id, consumer.work, dependencies);
  return consumer;
}

/** The report of `client`'s audit: the advisories of `name` in it, and whether it is clean, after checking it is a report at all. */
async function audit(
  client: NpmFamilyClient,
  consumer: ClientCtx,
  name: string,
): Promise<{ exitCode: number | undefined; titles: string[]; clean: boolean; stdout: string }> {
  const driver = DRIVERS[client.id];
  if (!driver || !client.audit) {
    throw new Error(`no audit driver for ${client.id}`);
  }
  const result = await client.audit(consumer);
  // A client that could not audit prints an error, not a report: the driver's parse would throw.
  const advisories = driver.advisories(result.stdout, name);
  return {
    exitCode: result.exitCode,
    titles: advisories
      .map((advisory) => `${advisory.severity} ${advisory.title} ${advisory.url}`)
      .sort(),
    clean: driver.isClean(result.stdout),
    stdout: result.stdout,
  };
}

/** Publishes `lib@1.0.0` with a stored scan finding on itself; resolves when that scan is done. */
async function publishWithStoredFinding(
  client: NpmFamilyClient,
  seeder: Seeder,
  scanner: ScannerStubClient,
  panelApi: PanelBackend,
  repo: SeededRepo,
  lib: string,
): Promise<void> {
  await scanner.script(lib, {
    findings: [
      {
        severity: 'HIGH',
        packageName: lib,
        packageVersion: VERSION,
        cveId: STORED_CVE,
        description: STORED_DESCRIPTION,
      },
    ],
  });
  const publisher = await client.prepare('lookup-pub', [
    await tokenBinding(seeder, repo.name, { readOnly: false }),
  ]);
  const published = await publishPackage(client, publisher, { packageName: lib, version: VERSION });
  expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);
  const scan = await finishedScan(panelApi, repo.name, lib, VERSION);
  expect(scan).toMatchObject({ status: 'COMPLETED', highestSeverity: 'HIGH' });
}

test.describe(
  'the audit of pairs the repository does not store (real clients, stub scanner lookup)',
  { tag: [SCANNER_TAG] },
  () => {
    skipUnlessScannerOptedIn();
    test.describe.configure({ timeout: 180_000 });

    for (const client of CLIENTS) {
      test.describe(client.label, { tag: [client.tag, '@audit'] }, () => {
        test('a pair that is stored nowhere is reported from the lookup, another version of the name is not', async ({
          seeder,
          scanner,
        }) => {
          const repo = await newRepo(seeder);
          const lib = packageNameFor(seeder, 'lookup-only');
          await scanner.advisories(lib, {
            findings: [
              {
                version: VERSION,
                severity: 'HIGH',
                cveId: LOOKUP_CVE,
                description: LOOKUP_DESCRIPTION,
              },
            ],
          });

          const vulnerable = await auditedIn(
            client,
            seeder,
            repo,
            { [lib]: VERSION },
            'vulnerable',
          );
          const found = await audit(client, vulnerable, lib);
          expect(found.clean, 'the report is not clean').toBe(false);
          expect(found.titles).toEqual([
            `high ${LOOKUP_CVE}: ${LOOKUP_DESCRIPTION} ${REFERENCE(LOOKUP_CVE)}`,
          ]);
          expect(found.exitCode, 'a reported advisory fails the audit').not.toBe(0);

          const clean = await audit(
            client,
            await auditedIn(client, seeder, repo, { [lib]: OTHER_VERSION }, 'other-version'),
            lib,
          );
          expect(clean.exitCode, 'another version of the name is not reported').toBe(0);
          expect(clean.clean).toBe(true);

          // Asked once per audit (one call for each of the two), for exactly the pair of the lockfile; nothing was ever scanned.
          const calls = await scanner.advisoryCalls(lib);
          expect(calls.map((call) => [call.outcome, call.packages])).toEqual([
            ['ok', [{ name: lib, version: VERSION }]],
            ['ok', [{ name: lib, version: OTHER_VERSION }]],
          ]);
          expect(await scanner.calls(lib), 'the repository was never asked to scan it').toEqual([]);
        });

        test('a finding both the stored scan and the lookup report is listed once, as the stored one, beside one only the lookup knows', async ({
          seeder,
          scanner,
          panelApi,
        }) => {
          const repo = await newRepo(seeder);
          const lib = packageNameFor(seeder, 'both');
          await publishWithStoredFinding(client, seeder, scanner, panelApi, repo, lib);
          await scanner.advisories(lib, {
            findings: [
              // The same (CVE, package, version) as the stored finding, told differently.
              {
                version: VERSION,
                severity: 'CRITICAL',
                cveId: STORED_CVE,
                description: 'the lookup says it another way',
              },
              {
                version: VERSION,
                severity: 'MEDIUM',
                cveId: LOOKUP_CVE,
                description: LOOKUP_DESCRIPTION,
              },
            ],
          });

          const consumer = await auditedIn(client, seeder, repo, { [lib]: VERSION }, 'both');
          const report = await audit(client, consumer, lib);

          expect(report.titles, 'the stored finding once, the lookup-only one once').toEqual(
            [
              `high ${STORED_CVE}: ${STORED_DESCRIPTION} ${REFERENCE(STORED_CVE)}`,
              `moderate ${LOOKUP_CVE}: ${LOOKUP_DESCRIPTION} ${REFERENCE(LOOKUP_CVE)}`,
            ].sort(),
          );
          const calls = await scanner.advisoryCalls(lib);
          expect(calls.map((call) => [call.outcome, call.findings])).toEqual([['ok', 2]]);
        });

        test('with the lookup down the audit is answered from the stored findings alone, with the same exit code and no error', async ({
          seeder,
          scanner,
          panelApi,
        }) => {
          const repo = await newRepo(seeder);
          const lib = packageNameFor(seeder, 'down');
          await publishWithStoredFinding(client, seeder, scanner, panelApi, repo, lib);
          const consumer = await auditedIn(client, seeder, repo, { [lib]: VERSION }, 'down');
          const lookupOnly = packageNameFor(seeder, 'down-only');
          const lookupOnlyConsumer = await auditedIn(
            client,
            seeder,
            repo,
            { [lookupOnly]: VERSION },
            'down-only',
          );
          const lookupFinding = {
            version: VERSION,
            severity: 'MEDIUM' as const,
            cveId: LOOKUP_CVE,
            description: LOOKUP_DESCRIPTION,
          };
          const storedTitle = `high ${STORED_CVE}: ${STORED_DESCRIPTION} ${REFERENCE(STORED_CVE)}`;

          // The lookup up: the stored finding and the lookup's own.
          await scanner.advisories(lib, { findings: [lookupFinding] });
          const baseline = await audit(client, consumer, lib);
          expect(baseline.titles).toHaveLength(2);
          expect(baseline.exitCode, 'advisories fail the audit').not.toBe(0);

          for (const outcome of [
            'unavailable',
            'timeout',
            'garbled',
          ] as const satisfies readonly AdvisoryOutcome[]) {
            await scanner.advisories(lib, { outcome, findings: [lookupFinding] });
            await scanner.advisories(lookupOnly, { outcome, findings: [lookupFinding] });

            const report = await audit(client, consumer, lib);
            expect(report.titles, `${outcome}: the stored finding only`).toEqual([storedTitle]);
            expect(report.exitCode, `${outcome}: the same exit code`).toBe(baseline.exitCode);

            const nothing = await audit(client, lookupOnlyConsumer, lookupOnly);
            expect(nothing.exitCode, `${outcome}: a pair only the lookup knows exits 0`).toBe(0);
            expect(nothing.clean, `${outcome}: and reports none`).toBe(true);
          }

          const outcomes = (await scanner.advisoryCalls(lib)).map((call) => call.outcome);
          expect(outcomes, 'the stub was asked each time and answered as scripted').toEqual([
            'ok',
            'unavailable',
            'timeout',
            'garbled',
          ]);
        });

        test('with the security scan setting off nothing is reported and the scanner is not asked; on again, it is', async ({
          seeder,
          scanner,
          panelApi,
        }) => {
          const repo = await newRepo(seeder);
          const lib = packageNameFor(seeder, 'setting');
          await scanner.advisories(lib, {
            findings: [
              {
                version: VERSION,
                severity: 'HIGH',
                cveId: LOOKUP_CVE,
                description: LOOKUP_DESCRIPTION,
              },
            ],
          });
          const consumer = await auditedIn(client, seeder, repo, { [lib]: VERSION }, 'setting');
          const expected = [`high ${LOOKUP_CVE}: ${LOOKUP_DESCRIPTION} ${REFERENCE(LOOKUP_CVE)}`];

          expect((await audit(client, consumer, lib)).titles, 'scanning on').toEqual(expected);
          expect(await scanner.advisoryCalls(lib)).toHaveLength(1);

          await panelApi.updateSettings(repo.name, { securityScanEnabled: false });
          const off = await audit(client, consumer, lib);
          expect(off.exitCode, 'scanning off: exit 0').toBe(0);
          expect(off.clean, 'scanning off: nothing reported').toBe(true);
          expect(off.titles).toEqual([]);
          expect(
            await scanner.advisoryCalls(lib),
            'scanning off: the scanner was not asked',
          ).toHaveLength(1);

          await panelApi.updateSettings(repo.name, { securityScanEnabled: true });
          expect((await audit(client, consumer, lib)).titles, 'scanning on again').toEqual(
            expected,
          );
          expect(await scanner.advisoryCalls(lib)).toHaveLength(2);
        });
      });
    }
  },
);
