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
 * Typed `page.route` stubs for every security-scanning call of the panel (RPS-1259).
 *
 * Why stubs: all scanner UI (badges, the scan section, the Vulnerability Scanning toggle, the content
 * of `/security`) is gated by `GET /api/security/supported-repo-types`, which the e2e stack answers
 * with `[]` (`SECURITY_SCANNER=disabled`), and no scan endpoint has anything to say without a scanner.
 * A test stubs the calls it needs and lets everything else (repos, packages, settings, login) hit the
 * real backend.
 *
 * Typing: every body is built from the models generated out of `openapi-spec.yaml`
 * (`src/api/generated`, `pnpm gen:api`), and every helper takes those models as parameters, so a spec
 * change that renames or retypes a field breaks `tsc` here instead of silently rendering nothing.
 * The backend's `{ msgId, type, data }` envelope is the matching `RestResponse*` model.
 *
 * Rules a stub follows (so a test can rely on them):
 *  - Register it BEFORE the navigation that triggers the call: `SecurityScanSupportService` fetches
 *    `supported-repo-types` once per SPA load, and a list fetches its summary as soon as it renders.
 *    After a `page.reload()` the SPA asks again, and the newest registered route for a URL wins, so a
 *    test can re-stub and reload to flip an answer.
 *  - It is idempotent: the answer depends on the request and on the state object the test passed in,
 *    never on how many times it was asked (the SPA may load twice and polls on its own timer). Handles
 *    only COUNT calls, for assertions.
 *  - It answers `GET` only (`POST` for the scan trigger) and `fallback()`s anything else to the
 *    next route or the real backend.
 *  - `fixtures`' teardown (`security-fixtures.ts`) unroutes everything the page registered.
 */
import { randomUUID } from 'node:crypto';

import type { Page, Route } from '@playwright/test';

import {
  FixStatus,
  RepoType,
  ResponseType,
  ScanStatus,
  Severity,
  type PagedModelVulnerabilityFindingInfo,
  type PagedModelVulnerabilityScanInfo,
  type RecentScannedVersion,
  type RepoSecurityDetail,
  type RepoSecuritySummary,
  type RestResponseListString,
  type RestResponsePagedModelVulnerabilityFindingInfo,
  type RestResponsePagedModelVulnerabilityScanInfo,
  type RestResponseRepoSecurityDetail,
  type RestResponseScanOverview,
  type RestResponseSecurityScansSummary,
  type RestResponseSecuritySummary,
  type RestResponseVersionSecuritySummaryMap,
  type RestResponseVulnerabilityScanDetail,
  type RestResponseVulnerabilityScanInfo,
  type ScanOverview,
  type SecurityScansSummary,
  type VersionSecuritySummary,
  type VulnerabilityFindingInfo,
  type VulnerabilityScanDetail,
  type VulnerabilityScanInfo,
} from '../api/generated/index.js';

export { FixStatus, RepoType, ScanStatus, Severity };
export type {
  RecentScannedVersion,
  RepoSecurityDetail,
  RepoSecuritySummary,
  ScanOverview,
  SecurityScansSummary,
  VersionSecuritySummary,
  VulnerabilityFindingInfo,
  VulnerabilityScanDetail,
  VulnerabilityScanInfo,
};

// ---------------------------------------------------------------------------------------------
// Plumbing
// ---------------------------------------------------------------------------------------------

/** What a stub tells a test: the requests it has answered (in order), for "the panel asked for X". */
export interface StubHandle {
  /** Every request URL the stub answered, oldest first. */
  readonly calls: readonly URL[];
  /** How many requests it answered. */
  readonly count: number;
  /** The newest answered URL, or undefined before the first request. */
  last(): URL | undefined;
}

class Handle implements StubHandle {
  readonly calls: URL[] = [];

  get count(): number {
    return this.calls.length;
  }

  last(): URL | undefined {
    return this.calls[this.calls.length - 1];
  }
}

/** A JSON success response of model `R` (`data` is checked against the model's own `data` type). */
function success<R extends { type?: ResponseType; data?: unknown }>(
  data: NonNullable<R['data']>,
): R {
  return { type: ResponseType.SUCCESS, data } as R;
}

async function json(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

/**
 * Routes `GET`s (or `method`) whose pathname matches `pattern` to `answer`. Regex groups are the
 * URL-DECODED path parameters (`%40scope%2Fname` -> `@scope/name`). Any other method falls through.
 */
async function stub(
  page: Page,
  pattern: RegExp,
  answer: (route: Route, url: URL, params: string[]) => Promise<void>,
  method: 'GET' | 'POST' = 'GET',
): Promise<Handle> {
  const handle = new Handle();
  await page.route(
    (url) => pattern.test(url.pathname),
    async (route) => {
      const request = route.request();
      if (request.method() !== method) {
        await route.fallback();
        return;
      }
      const url = new URL(request.url());
      const match = pattern.exec(url.pathname);
      const params = (match?.slice(1) ?? []).map((part) => decodeURIComponent(part));
      handle.calls.push(url);
      await answer(route, url, params);
    },
  );
  return handle;
}

const SEG = '([^/]+)';

/** Path patterns of every security call, straight from `openapi-spec.yaml`. */
export const SECURITY_PATHS = {
  supportedRepoTypes: /^\/api\/security\/supported-repo-types$/,
  scans: /^\/api\/security\/scans$/,
  scansSummary: /^\/api\/security\/scans\/summary$/,
  repoSummary: /^\/api\/repos\/security-summary$/,
  artifactsSummary: new RegExp(`^/api/repos/${SEG}/artifacts/security-summary$`),
  versionsSummary: new RegExp(`^/api/repos/${SEG}/artifacts/${SEG}/security-summary$`),
  repoDetail: new RegExp(`^/api/repos/${SEG}/security-detail$`),
  artifactDetail: new RegExp(`^/api/repos/${SEG}/artifacts/${SEG}/security-detail$`),
  versionScans: new RegExp(`^/api/repos/${SEG}/artifacts/${SEG}/versions/${SEG}/scans$`),
  scanOverview: new RegExp(`^/api/repos/${SEG}/artifacts/${SEG}/versions/${SEG}/scan-overview$`),
  triggerScan: new RegExp(`^/api/repos/${SEG}/artifacts/${SEG}/versions/${SEG}/scan$`),
  scanDetail: new RegExp(`^/api/repos/${SEG}/scans/${SEG}$`),
  scanFindings: new RegExp(`^/api/repos/${SEG}/scans/${SEG}/findings$`),
} as const;

// ---------------------------------------------------------------------------------------------
// Builders (models with sensible defaults, so a test states only what it asserts on)
// ---------------------------------------------------------------------------------------------

const SEVERITY_RANK: Record<Severity, number> = {
  [Severity.CRITICAL]: 0,
  [Severity.HIGH]: 1,
  [Severity.MEDIUM]: 2,
  [Severity.LOW]: 3,
  [Severity.UNKNOWN]: 4,
};

/** Severity counters of a list of findings (the shape of `ScanOverview`/`SecurityScansSummary`). */
export function severityCounts(severities: readonly (Severity | undefined)[]): {
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  unknownCount: number;
  totalCount: number;
} {
  const count = (severity: Severity): number => severities.filter((s) => s === severity).length;
  return {
    criticalCount: count(Severity.CRITICAL),
    highCount: count(Severity.HIGH),
    mediumCount: count(Severity.MEDIUM),
    lowCount: count(Severity.LOW),
    unknownCount: count(Severity.UNKNOWN),
    totalCount: severities.length,
  };
}

/** A finding. `cveId` defaults to a unique-looking `CVE-2026-<n>`. */
export function finding(
  severity: Severity,
  overrides: Partial<VulnerabilityFindingInfo> = {},
): VulnerabilityFindingInfo {
  const id = overrides.id ?? randomUUID();
  return {
    id,
    cveId: `CVE-2026-${1000 + (Math.abs(hash(id)) % 9000)}`,
    severity,
    packageName: 'lodash',
    packageVersion: '4.17.10',
    fixedVersion: '4.17.21',
    description: 'A stubbed finding',
    referenceUrl: null,
    fixStatus: FixStatus.FIXED,
    cvssScore: null,
    cvssVector: null,
    ...overrides,
  };
}

function hash(text: string): number {
  let value = 0;
  for (const char of text) {
    value = (value * 31 + char.charCodeAt(0)) | 0;
  }
  return value;
}

/** Findings for a list of severities, e.g. `findings(['CRITICAL', 'HIGH', 'HIGH'])`. */
export function findings(severities: readonly Severity[]): VulnerabilityFindingInfo[] {
  return severities.map((severity, index) =>
    finding(severity, { cveId: `CVE-2026-${String(index + 1).padStart(4, '0')}` }),
  );
}

/** A row of the cross-repo scan list (`/security`). */
export function scanInfo(overrides: Partial<VulnerabilityScanInfo> = {}): VulnerabilityScanInfo {
  return {
    id: randomUUID(),
    repoName: 'stub-repo',
    repoType: RepoType.NPM,
    artifactName: 'stub-package',
    artifactVersion: '1.0.0',
    status: ScanStatus.COMPLETED,
    highestSeverity: Severity.HIGH,
    scannerName: 'stub-scanner',
    scannerVersion: '1.0.0',
    errorMessage: null,
    createdAt: '2026-09-01T10:00:00Z',
    startedAt: '2026-09-01T10:00:01Z',
    completedAt: '2026-09-01T10:00:09Z',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------------------------
// Gate: which repo types have a scanner
// ---------------------------------------------------------------------------------------------

/**
 * `GET /api/security/supported-repo-types`: the single gate of all scanner UI. `[]` is the real
 * answer with the scanner disabled (the e2e default), which hides every scanner control.
 */
export function stubSupportedRepoTypes(
  page: Page,
  types: readonly RepoType[],
): Promise<StubHandle> {
  const body = success<RestResponseListString>([...types]);
  return stub(page, SECURITY_PATHS.supportedRepoTypes, (route) => json(route, body));
}

// ---------------------------------------------------------------------------------------------
// Summaries behind the badges and the dashboard
// ---------------------------------------------------------------------------------------------

/**
 * `GET /api/repos/security-summary[?repoNames=a,b]`: one entry per repo. With `repoNames` (the repo
 * list) only the entries asked for are returned; without (the dashboard) all of them. A repo not in
 * `summaries` has no entry, exactly like a repo the scanner never saw.
 */
export function stubRepoSecuritySummary(
  page: Page,
  summaries: Readonly<Record<string, RepoSecuritySummary>>,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.repoSummary, (route, url) => {
    const asked = url.searchParams.get('repoNames');
    const names = asked ? asked.split(',') : Object.keys(summaries);
    const data: Record<string, RepoSecuritySummary> = {};
    for (const name of names) {
      if (name in summaries) {
        data[name] = summaries[name];
      }
    }
    return json(route, success<RestResponseSecuritySummary>(data));
  });
}

/**
 * `GET /api/repos/{repo}/artifacts/security-summary`: one entry per artifact of `repoName`, keyed by
 * the panel's artifact key (maven `group:artifact`, npm `@scope/name`, docker image, pypi name).
 */
export function stubArtifactSecuritySummary(
  page: Page,
  repoName: string,
  summaries: Readonly<Record<string, VersionSecuritySummary>>,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.artifactsSummary, (route, _url, [repo]) =>
    repo === repoName
      ? json(route, success<RestResponseVersionSecuritySummaryMap>({ ...summaries }))
      : route.fallback(),
  );
}

/**
 * `GET /api/repos/{repo}/artifacts/{artifact}/security-summary`: one entry per VERSION (docker: per
 * tag) of any artifact of `repoName`, keyed by the version name.
 */
export function stubVersionSecuritySummary(
  page: Page,
  repoName: string,
  summaries: Readonly<Record<string, VersionSecuritySummary>>,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.versionsSummary, (route, _url, [repo]) =>
    repo === repoName
      ? json(route, success<RestResponseVersionSecuritySummaryMap>({ ...summaries }))
      : route.fallback(),
  );
}

/** `GET /api/repos/{repo}/security-detail`: the repo badge's modal (breakdown + recent scans). */
export function stubRepoSecurityDetail(
  page: Page,
  repoName: string,
  detail: RepoSecurityDetail,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.repoDetail, (route, _url, [repo]) =>
    repo === repoName
      ? json(route, success<RestResponseRepoSecurityDetail>(detail))
      : route.fallback(),
  );
}

/** `GET /api/repos/{repo}/artifacts/{artifact}/security-detail`: the package badge's modal. */
export function stubArtifactSecurityDetail(
  page: Page,
  repoName: string,
  detail: RepoSecurityDetail,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.artifactDetail, (route, _url, [repo]) =>
    repo === repoName
      ? json(route, success<RestResponseRepoSecurityDetail>(detail))
      : route.fallback(),
  );
}

// ---------------------------------------------------------------------------------------------
// The scan section of a version detail page
// ---------------------------------------------------------------------------------------------

/** One scan of a version, newest first in a `ScanScript`. */
export interface ScriptedScan {
  id: string;
  status: ScanStatus;
  /** What the scan reports once (and only once) it is COMPLETED. */
  findings: VulnerabilityFindingInfo[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ScriptedScanInit {
  status: ScanStatus;
  findings?: VulnerabilityFindingInfo[];
}

/**
 * The scan history of one version (empty = never scanned), driven by the TEST: `setStatus()` moves the newest scan along
 * PENDING -> QUEUED -> RUNNING -> COMPLETED/FAILED, `rescan()` adds a newer scan, and every stubbed
 * endpoint (`stubVersionScans`) answers from this one state. The SPA polls the scan detail every 3 s
 * while it is unfinished, so a test flips a status and then waits (bounded) for the panel to show it.
 *
 * Semantics mirrored from the backend's contract (`openapi-spec.yaml`): the overview's `scanId` and
 * `status` describe the NEWEST scan of any status, its counters describe the newest COMPLETED scan
 * (all zero, `lastCompletedAt` null, while nothing has completed), and a scan lists findings only
 * once it is COMPLETED.
 */
export class ScanScript {
  /** Newest first. */
  readonly scans: ScriptedScan[];
  /** Requests answered per endpoint, for "the panel polled". */
  readonly calls = { list: 0, overview: 0, detail: 0, findings: 0, trigger: 0 };
  private clock = Date.parse('2026-09-01T10:00:00Z');

  /** `initial` is newest first. */
  constructor(...initial: ScriptedScanInit[]) {
    // Created oldest first so the clock advances with age; kept newest first.
    this.scans = [...initial]
      .reverse()
      .map((init) => this.makeScan(init))
      .reverse();
  }

  private makeScan(init: ScriptedScanInit): ScriptedScan {
    this.clock += 60_000;
    const created = new Date(this.clock).toISOString();
    const done = init.status === ScanStatus.COMPLETED || init.status === ScanStatus.FAILED;
    const started =
      init.status === ScanStatus.PENDING || init.status === ScanStatus.QUEUED ? null : created;
    return {
      id: randomUUID(),
      status: init.status,
      findings: init.findings ?? [],
      createdAt: created,
      startedAt: started,
      completedAt: done ? new Date(this.clock + 8_000).toISOString() : null,
    };
  }

  /** The newest scan of any status; a script with no scans is a version that was never scanned. */
  get newest(): ScriptedScan {
    if (this.scans.length === 0) {
      throw new Error('this ScanScript has no scans yet (a version that was never scanned)');
    }
    return this.scans[0];
  }

  /** Moves the NEWEST scan to `status` (COMPLETED reveals its findings). */
  setStatus(status: ScanStatus): void {
    const scan = this.newest;
    scan.status = status;
    scan.startedAt =
      status === ScanStatus.PENDING || status === ScanStatus.QUEUED
        ? null
        : (scan.startedAt ?? new Date(Date.parse(scan.createdAt) + 1_000).toISOString());
    scan.completedAt =
      status === ScanStatus.COMPLETED || status === ScanStatus.FAILED
        ? new Date(Date.parse(scan.createdAt) + 8_000).toISOString()
        : null;
  }

  /** Adds a newer scan (what `POST .../scan` does) and returns it. */
  rescan(init: ScriptedScanInit = { status: ScanStatus.PENDING }): ScriptedScan {
    const scan = this.makeScan(init);
    this.scans.unshift(scan);
    return scan;
  }

  scanById(id: string): ScriptedScan | undefined {
    return this.scans.find((scan) => scan.id === id);
  }

  /** The findings a scan currently reports: its own once COMPLETED, none before. */
  reportedFindings(scan: ScriptedScan): VulnerabilityFindingInfo[] {
    return scan.status === ScanStatus.COMPLETED ? scan.findings : [];
  }

  private newestCompleted(): ScriptedScan | undefined {
    return this.scans.find((scan) => scan.status === ScanStatus.COMPLETED);
  }

  overview(): ScanOverview {
    if (this.scans.length === 0) {
      // The panel only asks for the overview of a version that has scans; never-scanned is `{}`.
      return {};
    }
    const newest = this.newest;
    const completed = this.newestCompleted();
    return {
      scanId: newest.id,
      status: newest.status,
      scannedAt: newest.completedAt,
      lastCompletedAt: completed?.completedAt ?? null,
      ...severityCounts((completed?.findings ?? []).map((f) => f.severity)),
    };
  }

  detail(scan: ScriptedScan, repoName: string, artifactName: string, version: string) {
    const detail: VulnerabilityScanDetail = {
      id: scan.id,
      repoName,
      artifactName,
      artifactVersion: version,
      status: scan.status,
      scannerName: 'stub-scanner',
      scannerVersion: '1.0.0',
      errorMessage: scan.status === ScanStatus.FAILED ? 'stubbed scanner failure' : null,
      createdAt: scan.createdAt,
      startedAt: scan.startedAt,
      completedAt: scan.completedAt,
    };
    return detail;
  }

  info(
    scan: ScriptedScan,
    repoName: string,
    artifactName: string,
    version: string,
    repoType: RepoType,
  ): VulnerabilityScanInfo {
    const worst = [...this.reportedFindings(scan)].sort(
      (a, b) => SEVERITY_RANK[a.severity!] - SEVERITY_RANK[b.severity!],
    )[0];
    return {
      id: scan.id,
      repoName,
      repoType,
      artifactName,
      artifactVersion: version,
      status: scan.status,
      highestSeverity: worst?.severity,
      scannerName: 'stub-scanner',
      scannerVersion: '1.0.0',
      errorMessage: scan.status === ScanStatus.FAILED ? 'stubbed scanner failure' : null,
      createdAt: scan.createdAt,
      startedAt: scan.startedAt,
      completedAt: scan.completedAt,
    };
  }
}

/** Reads the flat `page`, `size` and `sort` query parameters every paged panel list takes (RPS-1269). */
export function pagingOf(url: URL): {
  page: number;
  size: number;
  sortDirection: 'ASC' | 'DESC';
} {
  const sort = url.searchParams.get('sort') ?? '';
  return {
    page: Number(url.searchParams.get('page') ?? 0),
    size: Number(url.searchParams.get('size') ?? 10),
    sortDirection: /,\s*desc/i.test(sort) ? 'DESC' : 'ASC',
  };
}

function pageOf<T>(items: readonly T[], page: number, size: number) {
  return {
    content: items.slice(page * size, (page + 1) * size),
    page: {
      size,
      number: page,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    },
  };
}

export interface VersionScansHandle {
  readonly script: ScanScript;
  readonly list: StubHandle;
  readonly overview: StubHandle;
  readonly detail: StubHandle;
  readonly findings: StubHandle;
  readonly trigger: StubHandle;
}

/**
 * Every call the scan section makes for ANY version of ANY artifact of `repoName` (a test seeds one
 * package in a fresh repo, so the artifact key needs no spelling out; pypi normalises names):
 * the history, the overview, one scan's detail, its findings, and `POST .../scan` (which adds a
 * PENDING scan to the script, like the backend's "Re-scan").
 *
 * `repoType` is only echoed into the history rows.
 */
export async function stubVersionScans(
  page: Page,
  repoName: string,
  script: ScanScript,
  repoType: RepoType = RepoType.NPM,
): Promise<VersionScansHandle> {
  const mine = (repo: string): boolean => repo === repoName;

  const list = await stub(
    page,
    SECURITY_PATHS.versionScans,
    (route, url, [repo, artifact, version]) => {
      if (!mine(repo)) {
        return route.fallback();
      }
      script.calls.list++;
      const { page: pageNumber, size } = pagingOf(url);
      const rows = script.scans.map((scan) => script.info(scan, repo, artifact, version, repoType));
      return json(
        route,
        success<RestResponsePagedModelVulnerabilityScanInfo>(
          pageOf(rows, pageNumber, size) satisfies PagedModelVulnerabilityScanInfo,
        ),
      );
    },
  );

  const overview = await stub(page, SECURITY_PATHS.scanOverview, (route, _url, [repo]) => {
    if (!mine(repo)) {
      return route.fallback();
    }
    script.calls.overview++;
    return json(route, success<RestResponseScanOverview>(script.overview()));
  });

  const detail = await stub(page, SECURITY_PATHS.scanDetail, async (route, _url, [repo, id]) => {
    const scan = script.scanById(id);
    if (!mine(repo) || !scan) {
      return route.fallback();
    }
    script.calls.detail++;
    // The path carries no artifact or version; the panel only reads status and timestamps here.
    return json(
      route,
      success<RestResponseVulnerabilityScanDetail>(
        script.detail(scan, repo, 'artifact', 'version'),
      ),
    );
  });

  const findingsHandle = await stub(page, SECURITY_PATHS.scanFindings, (route, url, [repo, id]) => {
    const scan = script.scanById(id);
    if (!mine(repo) || !scan) {
      return route.fallback();
    }
    script.calls.findings++;
    const { page: pageNumber, size, sortDirection } = pagingOf(url);
    const ordered = [...script.reportedFindings(scan)].sort(
      (a, b) =>
        (SEVERITY_RANK[a.severity!] - SEVERITY_RANK[b.severity!]) *
        (sortDirection === 'ASC' ? 1 : -1),
    );
    return json(
      route,
      success<RestResponsePagedModelVulnerabilityFindingInfo>(
        pageOf(ordered, pageNumber, size) satisfies PagedModelVulnerabilityFindingInfo,
      ),
    );
  });

  const trigger = await stub(
    page,
    SECURITY_PATHS.triggerScan,
    (route, _url, [repo, artifact, version]) => {
      if (!mine(repo)) {
        return route.fallback();
      }
      script.calls.trigger++;
      const scan = script.rescan();
      return json(
        route,
        success<RestResponseVulnerabilityScanInfo>(
          script.info(scan, repo, artifact, version, repoType),
        ),
      );
    },
    'POST',
  );

  return { script, list, overview, detail, findings: findingsHandle, trigger };
}

// ---------------------------------------------------------------------------------------------
// The /security page
// ---------------------------------------------------------------------------------------------

/**
 * `GET /api/security/scans`: the cross-repo scan list, filtered and paged like the backend does it
 * (`severity` = the scan's highest severity, `repoType`, `repoName` = case-insensitive substring,
 * `page`, `size`). The substring rule and the newest-first order are THIS stub's assumptions; what a
 * test asserts about them is the request the panel sent (`handle.last()`) and the rows it drew.
 */
export function stubSecurityScans(
  page: Page,
  scans: readonly VulnerabilityScanInfo[],
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.scans, (route, url) => {
    const severity = url.searchParams.get('severity');
    const repoType = url.searchParams.get('repoType');
    const repoName = url.searchParams.get('repoName')?.toLowerCase();
    const size = Number(url.searchParams.get('size') ?? 10);
    const pageNumber = Number(url.searchParams.get('page') ?? 0);
    const matching = scans
      .filter((scan) => !severity || scan.highestSeverity === severity)
      .filter((scan) => !repoType || scan.repoType === repoType)
      .filter((scan) => !repoName || scan.repoName?.toLowerCase().includes(repoName))
      .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? ''));
    return json(
      route,
      success<RestResponsePagedModelVulnerabilityScanInfo>(pageOf(matching, pageNumber, size)),
    );
  });
}

/** `GET /api/security/scans/summary`: the Severity Distribution card (doughnut and badges). */
export function stubSecurityScansSummary(
  page: Page,
  summary: SecurityScansSummary,
): Promise<StubHandle> {
  return stub(page, SECURITY_PATHS.scansSummary, (route) =>
    json(route, success<RestResponseSecurityScansSummary>(summary)),
  );
}
