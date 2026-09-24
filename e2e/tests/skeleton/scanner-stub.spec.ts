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
 * Unit tests of the stub scanner (RPS-1270, `src/stubs/scanner/`): its rule table, its findings and
 * its HTTP contract. No Repsy stack is needed: the stub is started in this process on a free port,
 * with an injected clock so a job can be moved through its phases without waiting.
 *
 * They pin what the `@scanner` UI specs (`tests/ui/security-real/`) rely on, so a change to a rule
 * fails here, on its own, before a slow browser test fails on it.
 */
import type { AddressInfo } from 'node:net';

import { expect, test } from '@playwright/test';

import { ScannerStubClient } from '../../src/stubs/scanner/client.ts';
import { boundaryOf, parseMultipart } from '../../src/stubs/scanner/multipart.ts';
import {
  FAIL_MESSAGE,
  SCANNER_VERSION,
  UNAVAILABLE_MESSAGE,
  VULN_PROFILES,
  countBySeverity,
  findingsFor,
  planFor,
  type StubSeverity,
} from '../../src/stubs/scanner/rules.ts';
import { createScannerStub, type StubOptions } from '../../src/stubs/scanner/server.ts';

const KEY = 'unit-test-key';

test.describe('stub scanner rule table', () => {
  const severities = (name: string, version = '1.0.0'): StubSeverity[] =>
    planFor(name, version).severities;

  test('a name without a directive is clean and completes fast', () => {
    expect(planFor('@e2e-abc123/pkg-1', '1.0.0')).toEqual({
      submitSeconds: 0,
      queueSeconds: 1,
      runSeconds: 1,
      outcome: 'completed',
      severities: [],
      errorMessage: FAIL_MESSAGE,
    });
    expect(severities('e2e-abc123-clean')).toEqual([]);
  });

  const profiles: Array<[string, StubSeverity[]]> = [
    ['e2e-abc123-vuln-critical', ['CRITICAL', 'CRITICAL', 'HIGH']],
    ['@e2e-abc123/vuln-high', ['HIGH', 'HIGH', 'MEDIUM']],
    ['io.repsy.e2e.abc123:vuln-medium-lib', ['MEDIUM']],
    ['e2e_abc123_vuln-low_pkg', ['LOW']],
    ['e2e-abc123-vuln-mixed', ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']],
  ];
  for (const [name, expected] of profiles) {
    test(`${name} reports ${expected.join(', ')}`, () => {
      expect(severities(name)).toEqual(expected);
    });
  }

  test('vuln-many reports twelve findings: 2 critical, 3 high, 4 medium, 2 low, 1 unknown', () => {
    expect(countBySeverity(findingsFor(severities('e2e-abc123-vuln-many')))).toEqual({
      CRITICAL: 2,
      HIGH: 3,
      MEDIUM: 4,
      LOW: 2,
      UNKNOWN: 1,
    });
  });

  test('the version counts as much as the name', () => {
    expect(severities('e2e-abc123-pkg', '1.0.0-vuln-high')).toEqual(['HIGH', 'HIGH', 'MEDIUM']);
    expect(planFor('e2e-abc123-pkg', 'fail').outcome).toBe('failed');
  });

  test('the first profile of the table wins when several are named', () => {
    expect(VULN_PROFILES.map(([token]) => token)).toEqual([
      'vuln-many',
      'vuln-mixed',
      'vuln-critical',
      'vuln-high',
      'vuln-medium',
      'vuln-low',
    ]);
    expect(severities('x-vuln-low-vuln-critical')).toEqual(['CRITICAL', 'CRITICAL', 'HIGH']);
    expect(severities('x-vuln-critical-vuln-many')).toHaveLength(12);
    expect(severities('x-clean-vuln-low')).toEqual(['LOW']);
  });

  test('fail ends the job FAILED, unavailable refuses the submit, unavailable beats fail', () => {
    expect(planFor('e2e-abc123-fail', '1.0.0').outcome).toBe('failed');
    expect(planFor('e2e-abc123-unavailable', '1.0.0').outcome).toBe('unavailable');
    expect(planFor('e2e-abc123-fail-unavailable', '1.0.0').outcome).toBe('unavailable');
    // Findings are still worked out for a failing name, but a failed job reports none of them.
    expect(planFor('e2e-abc123-fail-vuln-high', '1.0.0').severities).toEqual([
      'HIGH',
      'HIGH',
      'MEDIUM',
    ]);
  });

  test('slow holds every state for seconds, and explicit timings beat it', () => {
    expect(planFor('e2e-abc123-slow', '1.0.0')).toMatchObject({
      submitSeconds: 5,
      queueSeconds: 6,
      runSeconds: 6,
    });
    expect(planFor('e2e-abc123-slow-queue2', '1.0.0')).toMatchObject({
      submitSeconds: 5,
      queueSeconds: 2,
      runSeconds: 6,
    });
    expect(planFor('e2e-abc123-submit3-queue4-run7', '1.0.0')).toMatchObject({
      submitSeconds: 3,
      queueSeconds: 4,
      runSeconds: 7,
    });
  });

  test('timings are capped: submit at 8 s (the backend gives up at 10 s), the phases at 120 s', () => {
    expect(planFor('e2e-abc123-submit99-queue999-run999', '1.0.0')).toMatchObject({
      submitSeconds: 8,
      queueSeconds: 120,
      runSeconds: 120,
    });
  });

  test('a token only counts as a whole word, so a run id or a longer name never triggers it', () => {
    for (const name of [
      'e2e-x4fail9-pkg',
      'e2e-abc123-failure',
      'e2e-abc123-unavailability',
      'e2e-abc123-slowly',
      'e2e-abc123-vuln-highest',
      'e2e-abc123-resubmit3',
      'e2e-abc123-queues',
      'e2e-abc123-run',
      'e2e-abc123-rerun5',
    ]) {
      expect(planFor(name, '1.0.0'), name).toEqual(planFor('e2e-abc123-pkg', '1.0.0'));
    }
  });

  test('matching ignores case', () => {
    expect(severities('E2E-ABC123-VULN-CRITICAL')).toEqual(['CRITICAL', 'CRITICAL', 'HIGH']);
  });
});

test.describe('stub scanner findings', () => {
  test('are deterministic and carry the fields of the scanner contract', () => {
    const findings = findingsFor(['CRITICAL', 'CRITICAL', 'HIGH', 'UNKNOWN']);
    expect(findingsFor(['CRITICAL', 'CRITICAL', 'HIGH', 'UNKNOWN'])).toEqual(findings);
    expect(findings.map((f) => f.cveId)).toEqual([
      'CVE-2099-1001',
      'CVE-2099-1002',
      'CVE-2099-2001',
      'CVE-2099-5001',
    ]);
    expect(findings[0]).toEqual({
      cveId: 'CVE-2099-1001',
      severity: 'CRITICAL',
      packageName: 'stub-lib-critical-1',
      packageVersion: '1.1.0',
      fixedVersion: '1.1.1',
      description: 'Stub critical finding CVE-2099-1001',
      referenceUrl: 'https://scanner-stub.invalid/advisories/CVE-2099-1001',
      fixStatus: 'FIXED',
      cvssScore: 9.8,
      cvssVector: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
    });
    // An UNKNOWN finding has no fix and no score.
    expect(findings[3]).toMatchObject({
      severity: 'UNKNOWN',
      fixedVersion: null,
      fixStatus: 'AFFECTED',
      cvssScore: null,
      cvssVector: null,
    });
  });

  test('every CVE id of a profile is unique', () => {
    for (const [token, list] of VULN_PROFILES) {
      const ids = findingsFor(list).map((f) => f.cveId);
      expect(new Set(ids).size, token).toBe(ids.length);
    }
  });
});

test.describe('stub scanner multipart reader', () => {
  test('reads text fields and the size of a file part, binary bytes included', () => {
    const boundary = 'xBOUNDARYx';
    const file = Buffer.from([0, 1, 2, 13, 10, 45, 45, 255]);
    const body = Buffer.concat([
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="scanId"\r\n\r\nabc\r\n` +
          `--${boundary}\r\nContent-Disposition: form-data; name="artifactName"\r\n\r\n@s/ü\r\n` +
          `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.tgz"\r\n` +
          'Content-Type: application/octet-stream\r\n\r\n',
      ),
      file,
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]);

    const form = parseMultipart(body, boundary);

    expect(form.fields).toEqual({ scanId: 'abc', artifactName: '@s/ü' });
    expect(form.files).toEqual([{ fieldName: 'file', fileName: 'a.tgz', size: file.length }]);
  });

  test('finds the boundary in a content type, quoted or not, and only for multipart', () => {
    expect(boundaryOf('multipart/form-data; boundary=abc')).toBe('abc');
    expect(boundaryOf('multipart/form-data;boundary="a b"; charset=utf-8')).toBe('a b');
    expect(boundaryOf('application/json')).toBeUndefined();
    expect(boundaryOf(undefined)).toBeUndefined();
  });
});

interface Started {
  base: string;
  client: ScannerStubClient;
  stub: ReturnType<typeof createScannerStub>;
  clock: { now: number };
  close(): Promise<void>;
}

async function start(options: Partial<StubOptions> = {}): Promise<Started> {
  const clock = { now: 1_000_000 };
  const stub = createScannerStub({ apiKey: KEY, now: () => clock.now, ...options });
  await new Promise<void>((resolve) => stub.server.listen(0, '127.0.0.1', resolve));
  const { port } = stub.server.address() as AddressInfo;
  const base = `http://127.0.0.1:${port}`;
  return {
    base,
    client: new ScannerStubClient(base, KEY),
    stub,
    clock,
    close: () =>
      new Promise<void>((resolve) => {
        stub.server.close(() => resolve());
        stub.server.closeAllConnections();
      }),
  };
}

function scanForm(
  fields: Record<string, string>,
  file: { name: string; bytes: Uint8Array } | null,
): FormData {
  const form = new FormData();
  for (const [name, value] of Object.entries(fields)) {
    form.append(name, value);
  }
  if (file) {
    form.append(
      'file',
      new Blob([new Uint8Array(file.bytes)], { type: 'application/octet-stream' }),
      file.name,
    );
  }
  return form;
}

const ARTIFACT = { name: 'a.tgz', bytes: new Uint8Array([1, 2, 3, 4]) };

async function submit(
  s: Started,
  scanId: string,
  artifactName: string,
  extra: Record<string, string> = {},
  file: { name: string; bytes: Uint8Array } | null = ARTIFACT,
): Promise<Response> {
  return fetch(`${s.base}/scan`, {
    method: 'POST',
    headers: { 'x-scanner-api-key': KEY },
    body: scanForm(
      { scanId, repoType: 'NPM', artifactName, artifactVersion: '1.0.0', ...extra },
      file,
    ),
  });
}

async function status(s: Started, scanId: string): Promise<Response> {
  return fetch(`${s.base}/scan/${scanId}`, { headers: { 'x-scanner-api-key': KEY } });
}

test.describe('stub scanner HTTP contract', () => {
  let s: Started;

  test.beforeEach(async () => {
    s = await start();
  });

  test.afterEach(async () => {
    await s.close();
  });

  test('GET /health needs no key; everything else does', async () => {
    const health = await fetch(`${s.base}/health`);
    expect(health.status).toBe(200);
    expect(await health.json()).toEqual({ status: 'ok' });

    for (const [method, path] of [
      ['POST', '/scan'],
      ['GET', '/scan/x'],
      ['GET', '/control/calls'],
    ]) {
      const denied = await fetch(`${s.base}${path}`, { method });
      expect(denied.status, `${method} ${path}`).toBe(401);
      expect(await denied.json()).toEqual({ message: 'unauthorized' });
    }
    const wrong = await fetch(`${s.base}/scan/x`, { headers: { 'x-scanner-api-key': 'nope' } });
    expect(wrong.status).toBe(401);
  });

  test('a scan is QUEUED, then RUNNING, then COMPLETED with its findings', async () => {
    const accepted = await submit(s, 'scan-1', 'e2e-abc123-vuln-critical');
    expect(accepted.status).toBe(200);
    expect(await accepted.json()).toEqual({ scanId: 'scan-1', status: 'QUEUED' });

    const at = async (seconds: number) => {
      s.clock.now = 1_000_000 + seconds * 1000;
      return (await (await status(s, 'scan-1')).json()) as {
        scanId: string;
        status: string;
        result: { findings: { cveId: string }[]; scannerVersion: string } | null;
        errorMessage: string | null;
      };
    };

    expect(await at(0)).toEqual({
      scanId: 'scan-1',
      status: 'QUEUED',
      result: null,
      errorMessage: null,
    });
    expect((await at(0.9)).status).toBe('QUEUED');
    expect(await at(1)).toMatchObject({ status: 'RUNNING', result: null, errorMessage: null });
    expect((await at(1.9)).status).toBe('RUNNING');
    const done = await at(2);
    expect(done.status).toBe('COMPLETED');
    expect(done.errorMessage).toBeNull();
    expect(done.result?.scannerVersion).toBe(SCANNER_VERSION);
    expect(done.result?.findings.map((f) => f.cveId)).toEqual([
      'CVE-2099-1001',
      'CVE-2099-1002',
      'CVE-2099-2001',
    ]);
    // Stable once done.
    expect(await at(3600)).toEqual(done);
  });

  test('a clean scan completes with an empty findings list, not with none', async () => {
    await submit(s, 'scan-clean', 'e2e-abc123-pkg');
    s.clock.now += 5_000;

    const body = (await (await status(s, 'scan-clean')).json()) as {
      status: string;
      result: { findings: unknown[] };
    };

    expect(body.status).toBe('COMPLETED');
    expect(body.result.findings).toEqual([]);
  });

  test('a fail scan ends FAILED with the message and no result', async () => {
    await submit(s, 'scan-fail', 'e2e-abc123-fail-vuln-high');
    s.clock.now += 5_000;

    expect(await (await status(s, 'scan-fail')).json()).toEqual({
      scanId: 'scan-fail',
      status: 'FAILED',
      result: null,
      errorMessage: FAIL_MESSAGE,
    });
  });

  test('an unavailable scan is refused with 503 and never becomes a job', async () => {
    const refused = await submit(s, 'scan-down', 'e2e-abc123-unavailable');

    expect(refused.status).toBe(503);
    expect(await refused.json()).toEqual({ message: UNAVAILABLE_MESSAGE });
    expect((await status(s, 'scan-down')).status).toBe(404);
    expect((await s.client.calls('e2e-abc123-unavailable'))[0]).toMatchObject({
      scanId: 'scan-down',
      result: 'refused',
    });
  });

  test('an unknown job is 404 with a message', async () => {
    const response = await status(s, 'nope');

    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ message: 'Scan job not found: nope' });
  });

  test('a submit with a missing field or an empty file is a 400, and no job is made', async () => {
    const missing = await fetch(`${s.base}/scan`, {
      method: 'POST',
      headers: { 'x-scanner-api-key': KEY },
      body: scanForm({ scanId: 's', repoType: 'NPM', artifactVersion: '1' }, ARTIFACT),
    });
    expect(missing.status).toBe(400);
    expect(await missing.json()).toEqual({
      message: "Required part 'artifactName' is not present.",
    });

    const empty = await submit(
      s,
      'scan-empty',
      'x',
      {},
      { name: 'a.tgz', bytes: new Uint8Array() },
    );
    expect(empty.status).toBe(400);
    expect(await empty.json()).toEqual({ message: 'file must not be empty' });

    const noFile = await submit(s, 'scan-nofile', 'x', {}, null);
    expect(noFile.status).toBe(400);

    const notMultipart = await fetch(`${s.base}/scan`, {
      method: 'POST',
      headers: { 'x-scanner-api-key': KEY, 'content-type': 'application/json' },
      body: '{}',
    });
    expect(notMultipart.status).toBe(400);

    expect((await status(s, 'scan-empty')).status).toBe(404);
    expect((await s.client.calls()).map((call) => call.result)).toEqual([
      'rejected',
      'rejected',
      'rejected',
    ]);
  });

  test('a Docker scan by image reference needs no file, and records the reference', async () => {
    const response = await submit(
      s,
      'scan-docker',
      'e2e-abc123-vuln-low',
      {
        repoType: 'DOCKER',
        dockerImageReference: 'localhost:9090/repo/img:1.0.0',
        registryInsecure: 'true',
        registryAuthToken: 'secret',
      },
      null,
    );

    expect(response.status).toBe(200);
    expect((await s.client.calls())[0]).toMatchObject({
      scanId: 'scan-docker',
      repoType: 'DOCKER',
      fileName: null,
      fileSize: null,
      dockerImageReference: 'localhost:9090/repo/img:1.0.0',
      registryInsecure: true,
      hasRegistryAuthToken: true,
      result: 'accepted',
    });
    // The token itself is never kept.
    expect(JSON.stringify(await s.client.calls())).not.toContain('secret');
  });

  test('calls record what the backend sent, in order, and can be filtered by artifact', async () => {
    await submit(s, 'c-1', 'first');
    await submit(s, 'c-2', 'second');

    const all = await s.client.calls();
    expect(all.map((call) => call.scanId)).toEqual(['c-1', 'c-2']);
    expect(all[0]).toMatchObject({
      repoType: 'NPM',
      artifactName: 'first',
      artifactVersion: '1.0.0',
      fileName: 'a.tgz',
      fileSize: 4,
      result: 'accepted',
    });
    expect((await s.client.calls('second')).map((call) => call.scanId)).toEqual(['c-2']);
    expect(await s.client.calls('nobody')).toEqual([]);
  });

  test('a script overrides the name rules for that artifact only, until it is cleared', async () => {
    await s.client.script('scripted', { findings: ['LOW'], queueSeconds: 0, runSeconds: 0 });

    await submit(s, 'sc-1', 'scripted');
    await submit(s, 'sc-2', 'other-vuln-high');
    const scripted = (await (await status(s, 'sc-1')).json()) as {
      status: string;
      result: { findings: { severity: string }[] };
    };
    const other = (await (await status(s, 'sc-2')).json()) as { status: string };

    expect(scripted.status).toBe('COMPLETED');
    expect(scripted.result.findings.map((f) => f.severity)).toEqual(['LOW']);
    expect(other.status).toBe('QUEUED');

    await s.client.script('scripted', { outcome: 'failed', errorMessage: 'scripted failure' });
    await submit(s, 'sc-3', 'scripted');
    s.clock.now += 5_000;
    expect(await (await status(s, 'sc-3')).json()).toMatchObject({
      status: 'FAILED',
      errorMessage: 'scripted failure',
    });

    await s.client.script('scripted', { outcome: 'unavailable' });
    expect((await submit(s, 'sc-4', 'scripted')).status).toBe(503);

    await s.client.clearScript('scripted');
    await submit(s, 'sc-5', 'scripted');
    s.clock.now += 5_000;
    expect(await (await status(s, 'sc-5')).json()).toMatchObject({
      status: 'COMPLETED',
      result: { findings: [] },
    });
  });

  test('a script is checked: a bad field is a 400 that names it', async () => {
    const put = (body: unknown) =>
      fetch(`${s.base}/control/scripts`, {
        method: 'PUT',
        headers: { 'x-scanner-api-key': KEY, 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });

    expect((await put({ script: {} })).status).toBe(400);
    const badSeconds = await put({ artifactName: 'a', script: { runSeconds: -1 } });
    expect(badSeconds.status).toBe(400);
    expect(await badSeconds.json()).toEqual({
      message: 'runSeconds must be a number of seconds >= 0',
    });
    expect((await put({ artifactName: 'a', script: { outcome: 'maybe' } })).status).toBe(400);
    expect((await put({ artifactName: 'a', script: { findings: ['SEVERE'] } })).status).toBe(400);
  });

  test('the submit answer is held for submitSeconds, and the job exists only after it', async () => {
    await s.client.script('held', { submitSeconds: 0.3 });
    const startedAt = Date.now();

    const pending = submit(s, 'held-1', 'held');
    await new Promise((resolve) => setTimeout(resolve, 100));
    // While the answer is held the scan is unknown: the backend reads that as "not submitted yet".
    expect(s.stub.statusOf('held-1')).toBeUndefined();
    const accepted = await pending;

    expect(accepted.status).toBe(200);
    expect(Date.now() - startedAt).toBeGreaterThanOrEqual(280);
    expect(s.stub.statusOf('held-1')?.status).toBe('QUEUED');
  });

  test('a repeated scan id keeps the first job, like the real service', async () => {
    await submit(s, 'dup', 'e2e-abc123-vuln-low');
    s.clock.now += 5_000;
    await submit(s, 'dup', 'e2e-abc123-vuln-high');

    const body = (await (await status(s, 'dup')).json()) as {
      result: { findings: { severity: string }[] };
    };

    expect(body.result.findings.map((f) => f.severity)).toEqual(['LOW']);
  });

  test('reset forgets scripts, calls and jobs', async () => {
    await s.client.script('scripted', { findings: ['LOW'] });
    await submit(s, 'r-1', 'scripted');

    await fetch(`${s.base}/control/reset`, {
      method: 'POST',
      headers: { 'x-scanner-api-key': KEY },
    });

    expect(await s.client.calls()).toEqual([]);
    expect((await status(s, 'r-1')).status).toBe(404);
    await submit(s, 'r-2', 'scripted');
    s.clock.now += 5_000;
    expect(await (await status(s, 'r-2')).json()).toMatchObject({ result: { findings: [] } });
  });

  test('an unknown route is a 404 with a message', async () => {
    const response = await fetch(`${s.base}/elsewhere`, { headers: { 'x-scanner-api-key': KEY } });

    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ message: 'No endpoint GET /elsewhere' });
  });
});

test.describe('stub scanner with the control API disabled', () => {
  test('/control is a 404 while /scan still works', async () => {
    const s = await start({ controlEnabled: false });
    try {
      const control = await fetch(`${s.base}/control/calls`, {
        headers: { 'x-scanner-api-key': KEY },
      });
      expect(control.status).toBe(404);

      expect((await submit(s, 'x-1', 'anything')).status).toBe(200);
    } finally {
      await s.close();
    }
  });
});
