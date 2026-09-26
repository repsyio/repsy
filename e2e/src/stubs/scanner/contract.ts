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
 * The raw HTTP contract of the scanner service that Repsy's `TrivyVulnerabilityScanner` and
 * `TrivyScannerStatusClient` rely on, as cases that can be run against ANY implementation of it
 * (RPS-1484): `tests/api/trivy-contract.spec.ts` runs them against the real `repsy-scanner-trivy`
 * (`./run.sh local up --trivy`) and `tests/skeleton/scanner-stub.spec.ts` against the stub scanner
 * (`server.ts`). One list, two implementations, so a change to the real service that the stub does not
 * follow fails the trivy leg, and a stub that stops following the list fails the skeleton run.
 *
 * Only what the real service does and the backend depends on is pinned: statuses, the 401 and 404
 * bodies, the JSON shape of a job and the request checks and answer shape of `POST /advisories`
 * (RPS-1613; what it FINDS is not common: the real one reads Trivy's database, the stub a script). The text of a 400/415 body is Spring's own (and differs between the
 * two: the real one has none of its own `message` for a missing part), so it is not compared.
 */
import zlib from 'node:zlib';

import { expect } from '@playwright/test';

import { buildTar } from '../../clients/docker-image.js';

export interface ContractTarget {
  base: string;
  apiKey: string;
}

export interface ContractCase {
  name: string;
  run(target: ContractTarget): Promise<void>;
}

const KEY_HEADER = 'x-scanner-api-key';

/** A tiny npm-shaped tarball (`package/package.json` only): nothing in it for a scanner to find. */
const EMPTY_TARBALL = zlib.gzipSync(
  buildTar([
    {
      name: 'package/package.json',
      data: Buffer.from('{"name":"e2e-contract-empty","version":"1.0.0"}', 'utf8'),
    },
  ]),
);

export function uniqueScanId(prefix = 'contract'): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function form(
  fields: Record<string, string>,
  file: Uint8Array | null,
  fileName = 'a.tgz',
): FormData {
  const body = new FormData();
  for (const [name, value] of Object.entries(fields)) {
    body.append(name, value);
  }
  if (file !== null) {
    body.append(
      'file',
      new Blob([new Uint8Array(file)], { type: 'application/octet-stream' }),
      fileName,
    );
  }
  return body;
}

/** `POST /scan` with the key, the four required fields (overridable) and the given file part. */
export async function postScan(
  target: ContractTarget,
  fields: Record<string, string>,
  file: Uint8Array | null,
  fileName?: string,
): Promise<Response> {
  return fetch(`${target.base}/scan`, {
    method: 'POST',
    headers: { [KEY_HEADER]: target.apiKey },
    body: form(
      { repoType: 'NPM', artifactName: 'x', artifactVersion: '1.0.0', ...fields },
      file,
      fileName,
    ),
    signal: AbortSignal.timeout(30_000),
  });
}

/** `GET /scan/{id}` with the key. */
export async function getScan(target: ContractTarget, scanId: string): Promise<Response> {
  return fetch(`${target.base}/scan/${encodeURIComponent(scanId)}`, {
    headers: { [KEY_HEADER]: target.apiKey },
    signal: AbortSignal.timeout(30_000),
  });
}

/** The body of `GET /scan/{id}` (`ScanJobStatusResponse`). */
export interface ScanJobBody {
  scanId: string;
  status: string;
  result: { findings: Record<string, unknown>[]; scannerVersion: string | null } | null;
  errorMessage: string | null;
}

/** The fields of one finding (`ScannerFinding`), in the order the service declares them. */
export const FINDING_FIELDS = [
  'cveId',
  'severity',
  'packageName',
  'packageVersion',
  'fixedVersion',
  'description',
  'referenceUrl',
  'fixStatus',
  'cvssScore',
  'cvssVector',
] as const;

export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];

/**
 * Polls `GET /scan/{id}` until the job is COMPLETED or FAILED (bounded: `timeoutMs`), and returns it.
 * The message names the phase the job was stuck in, which is the useful part of a red leg.
 */
export async function untilFinished(
  target: ContractTarget,
  scanId: string,
  timeoutMs: number,
): Promise<ScanJobBody> {
  let last: ScanJobBody | undefined;
  await expect
    .poll(
      async () => {
        const response = await getScan(target, scanId);
        expect(response.status, 'GET /scan/{id} of a submitted scan').toBe(200);
        last = (await response.json()) as ScanJobBody;
        return last.status;
      },
      {
        message: `scan ${scanId} finished (COMPLETED or FAILED)`,
        timeout: timeoutMs,
        intervals: [500, 1_000, 2_000, 5_000],
      },
    )
    .toMatch(/^(COMPLETED|FAILED)$/);
  return last as ScanJobBody;
}

/** The JSON shape every job answer has, whatever its state. */
export function expectJobShape(body: ScanJobBody, scanId: string): void {
  expect(Object.keys(body).sort(), 'the fields of a job').toEqual([
    'errorMessage',
    'result',
    'scanId',
    'status',
  ]);
  expect(body.scanId).toBe(scanId);
  expect(['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED']).toContain(body.status);
  if (body.status === 'COMPLETED') {
    expect(body.errorMessage, 'a completed job has no error').toBeNull();
    expect(body.result, 'a completed job has its result').not.toBeNull();
    expect(Object.keys(body.result as object).sort()).toEqual(['findings', 'scannerVersion']);
    expect(Array.isArray(body.result?.findings)).toBe(true);
  } else if (body.status === 'FAILED') {
    expect(typeof body.errorMessage, 'a failed job says why').toBe('string');
    expect(body.result, 'a failed job has no result').toBeNull();
  } else {
    expect(body.result, `a ${body.status} job has no result yet`).toBeNull();
    expect(body.errorMessage).toBeNull();
  }
}

/** The fields (and their types) of one finding, whatever the implementation. */
export function expectFindingShape(finding: Record<string, unknown>): void {
  expect(Object.keys(finding), 'the fields of a finding, in the service order').toEqual([
    ...FINDING_FIELDS,
  ]);
  for (const field of ['cveId', 'packageName', 'packageVersion'] as const) {
    expect(typeof finding[field], field).toBe('string');
  }
  expect(SEVERITIES, 'severity').toContain(finding.severity);
  expect(typeof finding.fixStatus, 'fixStatus').toBe('string');
  for (const field of ['fixedVersion', 'description', 'referenceUrl', 'cvssVector'] as const) {
    expect(['string', 'object'], field).toContain(typeof finding[field]);
  }
  expect(['number', 'object'], 'cvssScore').toContain(typeof finding.cvssScore);
}

/** `POST /advisories` with the key; `body` as given (a string is sent as is), JSON unless said otherwise. */
export async function postAdvisories(
  target: ContractTarget,
  body: unknown,
  contentType = 'application/json',
): Promise<Response> {
  return fetch(`${target.base}/advisories`, {
    method: 'POST',
    headers: { [KEY_HEADER]: target.apiKey, 'content-type': contentType },
    body: typeof body === 'string' ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(60_000),
  });
}

/** The body of a 200 of `POST /advisories`. */
export interface AdvisoryBody {
  dbUpdatedAt: string | null;
  scannerVersion: string | null;
  findings: Record<string, unknown>[];
}

const NPM_PAIR = { name: 'left-pad', version: '1.3.0' };

/** How long the accepted scan of the last case may take (the real scanner runs Trivy on it). */
export const ACCEPTED_SCAN_TIMEOUT_MS = 180_000;

export const CONTRACT_CASES: ContractCase[] = [
  {
    name: 'GET /health needs no key and answers {"status":"ok"}',
    async run(target) {
      const response = await fetch(`${target.base}/health`);
      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({ status: 'ok' });
    },
  },
  {
    name: 'a call without the right key is a 401 {"message":"unauthorized"}',
    async run(target) {
      for (const [method, path] of [
        ['POST', '/scan'],
        ['GET', '/scan/x'],
        ['POST', '/advisories'],
      ]) {
        const anonymous = await fetch(`${target.base}${path}`, { method });
        expect(anonymous.status, `${method} ${path} without a key`).toBe(401);
        expect(await anonymous.json()).toEqual({ message: 'unauthorized' });

        const wrong = await fetch(`${target.base}${path}`, {
          method,
          headers: { [KEY_HEADER]: `${target.apiKey}-wrong` },
        });
        expect(wrong.status, `${method} ${path} with a wrong key`).toBe(401);
      }
    },
  },
  {
    name: 'a submit without a required field is a 400 and makes no job',
    async run(target) {
      const scanId = uniqueScanId('missing');
      const response = await fetch(`${target.base}/scan`, {
        method: 'POST',
        headers: { [KEY_HEADER]: target.apiKey },
        body: form({ scanId, repoType: 'NPM', artifactVersion: '1.0.0' }, EMPTY_TARBALL),
        signal: AbortSignal.timeout(30_000),
      });
      expect(response.status).toBe(400);
      expect(response.headers.get('content-type')).toContain('application/json');
      expect((await getScan(target, scanId)).status, 'no job was made').toBe(404);
    },
  },
  {
    name: 'an empty file part, and no file at all, are a 400 {"message":"file must not be empty"}',
    async run(target) {
      for (const [label, file] of [
        ['an empty file', new Uint8Array()],
        ['no file part', null],
      ] as const) {
        const scanId = uniqueScanId('nofile');
        const response = await postScan(target, { scanId }, file);
        expect(response.status, label).toBe(400);
        expect(await response.json(), label).toEqual({ message: 'file must not be empty' });
        expect((await getScan(target, scanId)).status, `${label}: no job was made`).toBe(404);
      }
    },
  },
  {
    name: 'a body that is not multipart is a 415',
    async run(target) {
      const response = await fetch(`${target.base}/scan`, {
        method: 'POST',
        headers: { [KEY_HEADER]: target.apiKey, 'content-type': 'application/json' },
        body: '{}',
        signal: AbortSignal.timeout(30_000),
      });
      expect(response.status).toBe(415);
    },
  },
  {
    name: 'an unknown scan id is a 404 {"message":"No scan job found for scanId: <id>"}',
    async run(target) {
      const scanId = uniqueScanId('unknown');
      const response = await getScan(target, scanId);
      expect(response.status).toBe(404);
      expect(await response.json()).toEqual({ message: `No scan job found for scanId: ${scanId}` });
    },
  },
  {
    name: 'an accepted scan is QUEUED, has the job shape while it runs and ends COMPLETED with a findings list',
    async run(target) {
      const scanId = uniqueScanId('accepted');
      const accepted = await postScan(target, { scanId }, EMPTY_TARBALL);
      expect(accepted.status).toBe(200);
      expect(await accepted.json()).toEqual({ scanId, status: 'QUEUED' });

      const first = await getScan(target, scanId);
      expect(first.status, 'a submitted scan is known at once').toBe(200);
      expectJobShape((await first.json()) as ScanJobBody, scanId);

      const done = await untilFinished(target, scanId, ACCEPTED_SCAN_TIMEOUT_MS);
      expect(done.status, `an empty package is scanned: ${done.errorMessage}`).toBe('COMPLETED');
      expectJobShape(done, scanId);
      expect(done.result?.findings, 'nothing to find in an empty package').toEqual([]);
    },
  },
  {
    name: 'POST /advisories: a body that is not JSON is a 415, and every malformed request a 400 with a message',
    async run(target) {
      const notJson = await postAdvisories(target, 'ecosystem=npm', 'text/plain');
      expect(notJson.status, 'a body that is not JSON').toBe(415);

      const requests: Array<[string, unknown]> = [
        ['a JSON syntax error', '{"ecosystem":'],
        ['an empty JSON body', ''],
        ['another ecosystem', { ecosystem: 'maven', packages: [] }],
        ['no ecosystem', { packages: [] }],
        ['no packages', { ecosystem: 'npm' }],
        ['a blank name', { ecosystem: 'npm', packages: [{ name: ' ', version: '1.0.0' }] }],
        ['a blank version', { ecosystem: 'npm', packages: [{ name: 'a', version: '' }] }],
        [
          'a name with a slash and no scope',
          { ecosystem: 'npm', packages: [{ name: 'a/b', version: '1' }] },
        ],
        ['a scope and no name', { ecosystem: 'npm', packages: [{ name: '@a/', version: '1' }] }],
        ['a second slash', { ecosystem: 'npm', packages: [{ name: '@a/b/c', version: '1' }] }],
        [
          'a name of 215 characters',
          { ecosystem: 'npm', packages: [{ name: 'a'.repeat(215), version: '1' }] },
        ],
        [
          'a version of 257 characters',
          { ecosystem: 'npm', packages: [{ name: 'a', version: '1'.repeat(257) }] },
        ],
        [
          'a control character in the version',
          { ecosystem: 'npm', packages: [{ name: 'a', version: '1\u0007' }] },
        ],
        ['a null pair', { ecosystem: 'npm', packages: [null] }],
      ];
      for (const [label, body] of requests) {
        const response = await postAdvisories(target, body);
        expect(response.status, label).toBe(400);
        const answer = (await response.json()) as { message?: unknown };
        expect(typeof answer.message, `${label}: the 400 says why`).toBe('string');
      }
    },
  },
  {
    name: 'POST /advisories: more than 20,000 pairs, or a body over 10 MiB, is a 413; exactly 20,000 pairs is not',
    async run(target) {
      const pairs = (count: number): unknown => ({
        ecosystem: 'npm',
        packages: Array.from({ length: count }, (_, index) => ({
          name: 'contract-many',
          version: `1.0.${index}`,
        })),
      });
      const tooMany = await postAdvisories(target, pairs(20_001));
      expect(tooMany.status).toBe(413);
      expect(typeof ((await tooMany.json()) as { message?: unknown }).message).toBe('string');

      const tooBig = await postAdvisories(target, ' '.repeat(10 * 1024 * 1024 + 1));
      expect(tooBig.status, 'a body of 10 MiB and a byte').toBe(413);

      // At the limit the request is valid: it is answered (200), or refused as busy (503) by a real
      // scanner that is running a scan at that moment, but never as too large or malformed.
      const atLimit = await postAdvisories(target, pairs(20_000));
      expect([200, 503, 504]).toContain(atLimit.status);
    },
  },
  {
    name: 'POST /advisories: an empty list is a 200 {dbUpdatedAt, scannerVersion, findings: []}',
    async run(target) {
      const response = await postAdvisories(target, { ecosystem: 'npm', packages: [] });
      expect(response.status).toBe(200);
      const body = (await response.json()) as AdvisoryBody;
      expect(Object.keys(body).sort(), 'the fields of the answer').toEqual([
        'dbUpdatedAt',
        'findings',
        'scannerVersion',
      ]);
      expect(body.findings).toEqual([]);
      expect(['string', 'object'], 'dbUpdatedAt').toContain(typeof body.dbUpdatedAt);
      expect(['string', 'object'], 'scannerVersion').toContain(typeof body.scannerVersion);
    },
  },
  {
    name: 'POST /advisories: a pair the database knows nothing of is a 200 with no finding, and the answer has the fields of the contract',
    async run(target) {
      // Retried for a real scanner that answers 503 while a scan holds its database.
      let response = await postAdvisories(target, { ecosystem: 'npm', packages: [NPM_PAIR] });
      for (let attempt = 0; response.status === 503 && attempt < 30; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 1_000));
        response = await postAdvisories(target, { ecosystem: 'npm', packages: [NPM_PAIR] });
      }
      expect(response.status).toBe(200);
      const body = (await response.json()) as AdvisoryBody;
      expect(Object.keys(body).sort()).toEqual(['dbUpdatedAt', 'findings', 'scannerVersion']);
      expect(typeof body.dbUpdatedAt, 'a lookup names the database it read').toBe('string');
      expect(Number.isNaN(Date.parse(body.dbUpdatedAt as string))).toBe(false);
      expect(body.findings.filter((finding) => finding.packageName === NPM_PAIR.name)).toEqual([]);
      for (const finding of body.findings) {
        expectFindingShape(finding);
      }
    },
  },
];
