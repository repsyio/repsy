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
 * The rule table of the stub scanner (RPS-1270): what a scan of an artifact reports, derived from the
 * artifact's NAME and VERSION alone, so a test decides the outcome by how it names what it publishes
 * and never has to configure the scanner first.
 *
 * The backend sends the scanner only `repoType`, `artifactName` and `artifactVersion` (never the repo
 * name), so those are the only inputs. Both are lower-cased and searched as one string,
 * `<artifactName> <artifactVersion>`, for the directive tokens below. A token matches only as a whole
 * word (delimited by anything that is not a letter or digit, or by the string's ends), so a run id
 * such as `e2e-x4fail9-...` never triggers `fail`.
 *
 * | Token                                         | Effect                                                     |
 * | --------------------------------------------- | ---------------------------------------------------------- |
 * | (none) or `clean`                             | scan completes with no findings                            |
 * | `vuln-critical`                               | 2 CRITICAL + 1 HIGH                                        |
 * | `vuln-high`                                   | 2 HIGH + 1 MEDIUM                                          |
 * | `vuln-medium`                                 | 1 MEDIUM                                                   |
 * | `vuln-low`                                    | 1 LOW                                                      |
 * | `vuln-mixed`                                  | 1 each of CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN             |
 * | `vuln-many`                                   | 12 findings (2 C, 3 H, 4 M, 2 L, 1 U): two pages of them   |
 * | `fail`                                        | the job ends FAILED (`FAIL_MESSAGE`)                       |
 * | `unavailable`                                 | `POST /scan` is refused with 503: the scan never starts    |
 * | `slow`                                        | `submit5 queue6 run6` (every state lasts several s)  |
 * | `submit<N>`                                   | the `POST /scan` answer is delayed N s (backend: PENDING)  |
 * | `queue<N>`                                    | the job stays QUEUED for N s                               |
 * | `run<N>`                                      | the job stays RUNNING for N s                              |
 *
 * When several `vuln-*` tokens are present the first row above wins (`vuln-many` first). `fail` and
 * `unavailable` win over any findings; `unavailable` wins over `fail`. An explicit `submit`/`queue`/
 * `run` beats what `slow` implies. `submit` is capped at 8 s (the backend gives up on a submit after
 * 10 s), `queue` and `run` at 120 s. Without timing tokens a scan is QUEUED for 1 s and RUNNING for 1 s.
 *
 * A test can override any of it for one artifact name at run time (`PUT /control/scripts`, see
 * `server.ts`); a script beats the name rules.
 *
 * This module is pure (no I/O, no clock), so `tests/skeleton/scanner-stub.spec.ts` pins the table.
 * It imports nothing and is run by Node's own type stripping in the stub image.
 */

/** The scanner's severities, worst first (`io.repsy.scanner.trivy.dtos.Severity`). */
export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'] as const;
export type StubSeverity = (typeof SEVERITIES)[number];

/** `io.repsy.scanner.trivy.dtos.ScannerFinding`, exactly as it goes over the wire. */
export interface StubFinding {
  cveId: string;
  severity: StubSeverity;
  packageName: string;
  packageVersion: string;
  fixedVersion: string | null;
  description: string | null;
  referenceUrl: string | null;
  fixStatus: 'FIXED' | 'AFFECTED';
  cvssScore: number | null;
  cvssVector: string | null;
}

/**
 * What a script (`PUT /control/scripts`) can say about one finding beyond its severity: which package
 * and version it is on (the default is a made-up `stub-lib-<severity>-<n>`), its CVE id and its
 * description (a panel row's text, and the title of an npm advisory). Unset fields keep the default.
 * Naming the finding's package is what lets a real client's audit of a published package show it
 * (RPS-1484): npm advisories match on the package name and version of the finding.
 */
export interface StubFindingSpec {
  severity: StubSeverity;
  packageName?: string;
  packageVersion?: string;
  cveId?: string;
  description?: string;
}

export type StubOutcome = 'completed' | 'failed' | 'unavailable';

/** What one scan does: the phases in seconds, and how it ends. */
export interface ScanPlan {
  /** The `POST /scan` answer is held this long (the backend's scan is PENDING meanwhile). */
  submitSeconds: number;
  queueSeconds: number;
  runSeconds: number;
  outcome: StubOutcome;
  /** Reported by a `completed` scan, worst first. */
  severities: StubSeverity[];
  /**
   * Set by a script only (`resolvePlan`): what the script says about each finding, parallel to
   * `severities` (a script finding given as a bare severity has `{}`).
   */
  overrides?: Array<Omit<StubFindingSpec, 'severity'>>;
  /** Reported by a `failed` scan. */
  errorMessage: string;
}

export const FAIL_MESSAGE = 'stub scanner: simulated scan failure';
export const UNAVAILABLE_MESSAGE = 'stub scanner unavailable';
export const SCANNER_VERSION = 'stub-scanner-1.0.0';

export const DEFAULT_QUEUE_SECONDS = 1;
export const DEFAULT_RUN_SECONDS = 1;
export const SLOW_SUBMIT_SECONDS = 5;
export const SLOW_QUEUE_SECONDS = 6;
export const SLOW_RUN_SECONDS = 6;
export const MAX_SUBMIT_SECONDS = 8;
export const MAX_PHASE_SECONDS = 120;

/** `vuln-*` tokens in priority order, and the findings each one reports. */
export const VULN_PROFILES: ReadonlyArray<readonly [string, readonly StubSeverity[]]> = [
  [
    'vuln-many',
    [
      'CRITICAL',
      'CRITICAL',
      'HIGH',
      'HIGH',
      'HIGH',
      'MEDIUM',
      'MEDIUM',
      'MEDIUM',
      'MEDIUM',
      'LOW',
      'LOW',
      'UNKNOWN',
    ],
  ],
  ['vuln-mixed', ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']],
  ['vuln-critical', ['CRITICAL', 'CRITICAL', 'HIGH']],
  ['vuln-high', ['HIGH', 'HIGH', 'MEDIUM']],
  ['vuln-medium', ['MEDIUM']],
  ['vuln-low', ['LOW']],
];

function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** True when `token` occurs in `text` as a whole word. */
function hasToken(text: string, token: string): boolean {
  return new RegExp(`(^|[^a-z0-9])${escapeForRegExp(token)}([^a-z0-9]|$)`).test(text);
}

/** The N of the first `<prefix><N>` token in `text`, or undefined. */
function numberedToken(text: string, prefix: string): number | undefined {
  const match = new RegExp(`(^|[^a-z0-9])${escapeForRegExp(prefix)}(\\d+)([^a-z0-9]|$)`).exec(text);
  return match ? Number(match[2]) : undefined;
}

function clamp(value: number, max: number): number {
  return Math.min(Math.max(value, 0), max);
}

/**
 * The plan for a scan of `artifactName` at `artifactVersion`. Deterministic: the same inputs always
 * give the same plan.
 */
export function planFor(artifactName: string, artifactVersion: string): ScanPlan {
  const text = `${artifactName} ${artifactVersion}`.toLowerCase();
  const slow = hasToken(text, 'slow');

  let outcome: StubOutcome = 'completed';
  if (hasToken(text, 'unavailable')) {
    outcome = 'unavailable';
  } else if (hasToken(text, 'fail')) {
    outcome = 'failed';
  }

  const profile = VULN_PROFILES.find(([token]) => hasToken(text, token));

  return {
    submitSeconds: clamp(
      numberedToken(text, 'submit') ?? (slow ? SLOW_SUBMIT_SECONDS : 0),
      MAX_SUBMIT_SECONDS,
    ),
    queueSeconds: clamp(
      numberedToken(text, 'queue') ?? (slow ? SLOW_QUEUE_SECONDS : DEFAULT_QUEUE_SECONDS),
      MAX_PHASE_SECONDS,
    ),
    runSeconds: clamp(
      numberedToken(text, 'run') ?? (slow ? SLOW_RUN_SECONDS : DEFAULT_RUN_SECONDS),
      MAX_PHASE_SECONDS,
    ),
    outcome,
    severities: profile ? [...profile[1]] : [],
    errorMessage: FAIL_MESSAGE,
  };
}

const RANK: Record<StubSeverity, number> = {
  CRITICAL: 1,
  HIGH: 2,
  MEDIUM: 3,
  LOW: 4,
  UNKNOWN: 5,
};

const CVSS: Record<StubSeverity, number | null> = {
  CRITICAL: 9.8,
  HIGH: 8.1,
  MEDIUM: 5.3,
  LOW: 3.1,
  UNKNOWN: null,
};

/**
 * The findings for a list of severities, in that order. The n-th finding of a severity is always the
 * same one: CVE id `CVE-2099-<rank><nnn>` (CRITICAL 1001, 1002; HIGH 2001 ...), package
 * `stub-lib-<severity>-<n>` at `1.<n>.0`, fixed in `1.<n>.1` (UNKNOWN has no fix and is AFFECTED).
 * So a test can name the exact row it expects. A script can replace the package, version, CVE id and
 * description of a finding (`overrides[i]` is the i-th finding's, see `StubFindingSpec`).
 */
export function findingsFor(
  severities: readonly StubSeverity[],
  overrides: ReadonlyArray<Omit<StubFindingSpec, 'severity'>> = [],
): StubFinding[] {
  const seen: Partial<Record<StubSeverity, number>> = {};
  return severities.map((severity, index) => {
    const n = (seen[severity] ?? 0) + 1;
    seen[severity] = n;
    const override = overrides[index] ?? {};
    const cveId = override.cveId ?? `CVE-2099-${RANK[severity]}${String(n).padStart(3, '0')}`;
    const known = severity !== 'UNKNOWN';
    const score = CVSS[severity];
    return {
      cveId,
      severity,
      packageName: override.packageName ?? `stub-lib-${severity.toLowerCase()}-${n}`,
      packageVersion: override.packageVersion ?? `1.${n}.0`,
      fixedVersion: known ? `1.${n}.1` : null,
      description: override.description ?? `Stub ${severity.toLowerCase()} finding ${cveId}`,
      referenceUrl: `https://scanner-stub.invalid/advisories/${cveId}`,
      fixStatus: known ? 'FIXED' : 'AFFECTED',
      cvssScore: score,
      cvssVector: score === null ? null : 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
    };
  });
}

/** How many findings of each severity a list has. */
export function countBySeverity(findings: readonly StubFinding[]): Record<StubSeverity, number> {
  const counts: Record<StubSeverity, number> = {
    CRITICAL: 0,
    HIGH: 0,
    MEDIUM: 0,
    LOW: 0,
    UNKNOWN: 0,
  };
  for (const finding of findings) {
    counts[finding.severity] += 1;
  }
  return counts;
}
