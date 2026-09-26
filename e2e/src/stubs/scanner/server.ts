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
 * The stub scanner (RPS-1270): a deterministic stand-in for `repsy-scanner-trivy`, speaking the same
 * HTTP contract the Repsy backend's `TrivyVulnerabilityScanner` / `TrivyScannerStatusClient` use, so
 * the real backend-to-scanner path (submit, poll, states, findings) runs without Trivy and its
 * vulnerability database.
 *
 * The contract (from `repsy-scanner-trivy`'s `ScanController` and `ApiKeyAuthFilter`):
 *  - `GET /health` -> `{"status":"ok"}`, no key needed.
 *  - Everything else needs the header `X-Scanner-Api-Key` (else 401 `{"message":"unauthorized"}`).
 *  - `POST /scan` (multipart: `scanId`, `repoType`, `artifactName`, `artifactVersion`, then either a
 *    `file` part or `dockerImageReference` [+ `registryAuthToken`, `registryInsecure`]) ->
 *    `{"scanId","status":"QUEUED"}`; 400 `{"message"}` for a missing field or an empty file.
 *  - `GET /scan/{scanId}` -> `{"scanId","status":QUEUED|RUNNING|COMPLETED|FAILED,"result":
 *    {"findings":[...],"scannerVersion"}|null,"errorMessage":string|null}`; 404 `{"message"}` for an
 *    unknown job (which the backend reads as "not submitted yet" while PENDING, "job lost" after).
 *
 * What a scan reports is decided by the artifact's name and version (`rules.ts`, documented in
 * `e2e/README.md`), with no clock or randomness: a job's status is a pure function of the plan and the
 * time since the job was accepted.
 *
 * Test controls, under `/control` and behind the same API key (the stub only exists in the e2e stack's
 * scanner overlay, `docker-compose.stack-scanner.yml`, and is published on loopback only):
 *  - `GET /control/calls[?artifactName=]` -> `{"calls":[RecordedCall]}`: every `POST /scan` seen, oldest first.
 *  - `PUT /control/scripts` `{"artifactName", "script": StubScript}` overrides the name rules for
 *    every later scan of that exact artifact name (a test names its artifacts after its run id, so a
 *    parallel test never sees it); `DELETE /control/scripts[?artifactName=]` removes one or all.
 *  - `POST /control/reset` forgets scripts, calls AND jobs (the backend then reads a running scan as
 *    "job lost"): for a whole-session reset, never from a test that runs beside others.
 * `SCANNER_STUB_CONTROL=disabled` turns `/control` off (404).
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { setTimeout as sleep } from 'node:timers/promises';

import { boundaryOf, parseMultipart } from './multipart.ts';
import {
  SCANNER_VERSION,
  SEVERITIES,
  UNAVAILABLE_MESSAGE,
  findingsFor,
  planFor,
  type ScanPlan,
  type StubFinding,
  type StubFindingSpec,
  type StubOutcome,
  type StubSeverity,
} from './rules.ts';

export const API_KEY_HEADER = 'x-scanner-api-key';

/** A run-time override of the name rules for one artifact name; unset fields keep the rule's value. */
export interface StubScript {
  submitSeconds?: number;
  queueSeconds?: number;
  runSeconds?: number;
  outcome?: StubOutcome;
  /**
   * What a completed scan reports (worst first is not required): a severity each, e.g.
   * `['CRITICAL', 'LOW']`, or `{severity, packageName, packageVersion, cveId, description}` to say
   * which package (and version) the finding is on, e.g. the artifact itself so that its npm audit
   * shows it (only `severity` is required).
   */
  findings?: Array<StubSeverity | StubFindingSpec>;
  errorMessage?: string;
}

/** One `POST /scan` the stub received. */
export interface RecordedCall {
  scanId: string;
  repoType: string;
  artifactName: string;
  artifactVersion: string;
  /** The uploaded file's name and size; null for a Docker scan by reference. */
  fileName: string | null;
  fileSize: number | null;
  dockerImageReference: string | null;
  registryInsecure: boolean | null;
  hasRegistryAuthToken: boolean;
  /** `accepted`, `refused` (the 503 of `unavailable`) or `rejected` (a 400 for a bad request). */
  result: 'accepted' | 'refused' | 'rejected';
  plan: ScanPlan | null;
  at: string;
}

type JobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

interface Job {
  scanId: string;
  plan: ScanPlan;
  findings: StubFinding[];
  /** When the job became known to `GET /scan/{id}`: after the submit delay. */
  acceptedAt: number;
}

export interface StubOptions {
  apiKey: string;
  /** Clock in ms, injectable so a test can move a job through its phases. Default `Date.now`. */
  now?: () => number;
  /** `/control` on (default) or off. */
  controlEnabled?: boolean;
  /** Largest accepted request body. Default 64 MiB. */
  maxBodyBytes?: number;
}

export interface ScannerStub {
  server: Server;
  /** The status a job has right now, or undefined for an unknown job. For unit tests. */
  statusOf(scanId: string): ReturnType<typeof jobResponse> | undefined;
}

const DEFAULT_MAX_BODY_BYTES = 64 * 1024 * 1024;

class HttpError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** The plan for a scan: the name rules, then the artifact's script over them. */
export function resolvePlan(
  artifactName: string,
  artifactVersion: string,
  script: StubScript | undefined,
): ScanPlan {
  const base = planFor(artifactName, artifactVersion);
  const specs = (script?.findings ?? []).map((finding) =>
    typeof finding === 'string' ? { severity: finding } : finding,
  );
  return {
    ...base,
    submitSeconds: script?.submitSeconds ?? base.submitSeconds,
    queueSeconds: script?.queueSeconds ?? base.queueSeconds,
    runSeconds: script?.runSeconds ?? base.runSeconds,
    outcome: script?.outcome ?? base.outcome,
    errorMessage: script?.errorMessage ?? base.errorMessage,
    severities: script?.findings ? specs.map((spec) => spec.severity) : base.severities,
    ...(script?.findings
      ? {
          overrides: specs.map(({ severity: _severity, ...rest }) => rest),
        }
      : {}),
  };
}

/** The `GET /scan/{id}` body of `job` at time `now` (ms). */
function jobResponse(
  job: Job,
  now: number,
): {
  scanId: string;
  status: JobStatus;
  result: { findings: StubFinding[]; scannerVersion: string } | null;
  errorMessage: string | null;
} {
  const elapsed = (now - job.acceptedAt) / 1000;
  const { queueSeconds, runSeconds, outcome, errorMessage } = job.plan;
  let status: JobStatus;
  if (elapsed < queueSeconds) {
    status = 'QUEUED';
  } else if (elapsed < queueSeconds + runSeconds) {
    status = 'RUNNING';
  } else {
    status = outcome === 'failed' ? 'FAILED' : 'COMPLETED';
  }
  return {
    scanId: job.scanId,
    status,
    result:
      status === 'COMPLETED' ? { findings: job.findings, scannerVersion: SCANNER_VERSION } : null,
    errorMessage: status === 'FAILED' ? errorMessage : null,
  };
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(payload),
  });
  response.end(payload);
}

async function readBody(request: IncomingMessage, limit: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of request) {
    const buffer = chunk as Buffer;
    total += buffer.length;
    if (total > limit) {
      throw new HttpError(400, 'Upload too large');
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}

function isNonNegativeNumber(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

const FINDING_TEXT_FIELDS = ['packageName', 'packageVersion', 'cveId', 'description'] as const;

/** A scripted finding is a severity, or an object with a `severity` and optional text fields. */
function validateFinding(finding: unknown): void {
  const message = `each finding must be one of ${SEVERITIES.join(', ')} or an object with a severity and optional ${FINDING_TEXT_FIELDS.join(', ')}`;
  if (typeof finding === 'string') {
    if (!(SEVERITIES as readonly string[]).includes(finding)) {
      throw new HttpError(400, message);
    }
    return;
  }
  if (typeof finding !== 'object' || finding === null || Array.isArray(finding)) {
    throw new HttpError(400, message);
  }
  const spec = finding as Record<string, unknown>;
  if (!(SEVERITIES as readonly unknown[]).includes(spec.severity)) {
    throw new HttpError(400, message);
  }
  for (const field of FINDING_TEXT_FIELDS) {
    if (spec[field] !== undefined && (typeof spec[field] !== 'string' || spec[field] === '')) {
      throw new HttpError(400, `${field} of a finding must be a non-empty string`);
    }
  }
}

/** Checks a `PUT /control/scripts` body, throwing a 400 that says what is wrong. */
function parseScriptRequest(raw: string): { artifactName: string; script: StubScript } {
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new HttpError(400, 'The body must be JSON');
  }
  const { artifactName, script } = (body ?? {}) as { artifactName?: unknown; script?: unknown };
  if (typeof artifactName !== 'string' || artifactName.length === 0) {
    throw new HttpError(400, 'artifactName must be a non-empty string');
  }
  const input = (script ?? {}) as Record<string, unknown>;
  for (const key of ['submitSeconds', 'queueSeconds', 'runSeconds']) {
    if (input[key] !== undefined && !isNonNegativeNumber(input[key])) {
      throw new HttpError(400, `${key} must be a number of seconds >= 0`);
    }
  }
  if (
    input.outcome !== undefined &&
    !['completed', 'failed', 'unavailable'].includes(String(input.outcome))
  ) {
    throw new HttpError(400, 'outcome must be completed, failed or unavailable');
  }
  if (input.findings !== undefined) {
    if (!Array.isArray(input.findings)) {
      throw new HttpError(400, `findings must be a list of ${SEVERITIES.join(', ')}`);
    }
    for (const finding of input.findings as unknown[]) {
      validateFinding(finding);
    }
  }
  if (input.errorMessage !== undefined && typeof input.errorMessage !== 'string') {
    throw new HttpError(400, 'errorMessage must be a string');
  }
  return { artifactName, script: input as StubScript };
}

/** Builds the stub. Nothing listens until the caller calls `server.listen`. */
export function createScannerStub(options: StubOptions): ScannerStub {
  const now = options.now ?? Date.now;
  const controlEnabled = options.controlEnabled ?? true;
  const maxBodyBytes = options.maxBodyBytes ?? DEFAULT_MAX_BODY_BYTES;

  const jobs = new Map<string, Job>();
  const scripts = new Map<string, StubScript>();
  let calls: RecordedCall[] = [];

  async function submit(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const boundary = boundaryOf(request.headers['content-type']);
    if (!boundary) {
      throw new HttpError(400, 'Content-Type must be multipart/form-data');
    }
    const form = parseMultipart(await readBody(request, maxBodyBytes), boundary);
    const file = form.files.find((part) => part.fieldName === 'file');
    const field = (name: string): string => (form.fields[name] ?? '').trim();

    const call: RecordedCall = {
      scanId: field('scanId'),
      repoType: field('repoType'),
      artifactName: field('artifactName'),
      artifactVersion: field('artifactVersion'),
      fileName: file?.fileName ?? null,
      fileSize: file?.size ?? null,
      dockerImageReference: field('dockerImageReference') || null,
      registryInsecure:
        form.fields.registryInsecure === undefined ? null : field('registryInsecure') === 'true',
      hasRegistryAuthToken: field('registryAuthToken').length > 0,
      result: 'rejected',
      plan: null,
      at: new Date(now()).toISOString(),
    };
    calls.push(call);

    for (const name of ['scanId', 'repoType', 'artifactName', 'artifactVersion']) {
      if (field(name).length === 0) {
        throw new HttpError(400, `Required part '${name}' is not present.`);
      }
    }
    if (call.dockerImageReference === null && (!file || file.size === 0)) {
      throw new HttpError(400, 'file must not be empty');
    }

    const plan = resolvePlan(
      call.artifactName,
      call.artifactVersion,
      scripts.get(call.artifactName),
    );
    call.plan = plan;
    if (plan.outcome === 'unavailable') {
      call.result = 'refused';
      sendJson(response, 503, { message: UNAVAILABLE_MESSAGE });
      return;
    }

    call.result = 'accepted';
    if (plan.submitSeconds > 0) {
      await sleep(plan.submitSeconds * 1000);
    }
    // Like the real service's putIfAbsent: a repeated scanId keeps the first job.
    if (!jobs.has(call.scanId)) {
      jobs.set(call.scanId, {
        scanId: call.scanId,
        plan,
        findings: findingsFor(plan.severities, plan.overrides),
        acceptedAt: now(),
      });
    }
    sendJson(response, 200, { scanId: call.scanId, status: 'QUEUED' });
  }

  async function control(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
  ): Promise<void> {
    const method = request.method ?? 'GET';
    if (method === 'GET' && url.pathname === '/control/calls') {
      const artifactName = url.searchParams.get('artifactName');
      sendJson(response, 200, {
        calls: artifactName ? calls.filter((call) => call.artifactName === artifactName) : calls,
      });
      return;
    }
    if (method === 'PUT' && url.pathname === '/control/scripts') {
      const { artifactName, script } = parseScriptRequest(
        (await readBody(request, maxBodyBytes)).toString('utf8'),
      );
      scripts.set(artifactName, script);
      sendJson(response, 200, { artifactName, script });
      return;
    }
    if (method === 'DELETE' && url.pathname === '/control/scripts') {
      const artifactName = url.searchParams.get('artifactName');
      if (artifactName) {
        scripts.delete(artifactName);
      } else {
        scripts.clear();
      }
      sendJson(response, 200, { scripts: scripts.size });
      return;
    }
    if (method === 'POST' && url.pathname === '/control/reset') {
      scripts.clear();
      jobs.clear();
      calls = [];
      sendJson(response, 200, { reset: true });
      return;
    }
    throw new HttpError(404, `No control endpoint ${method} ${url.pathname}`);
  }

  async function route(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? '/', 'http://stub');
    const method = request.method ?? 'GET';

    if (method === 'GET' && url.pathname === '/health') {
      sendJson(response, 200, { status: 'ok' });
      return;
    }
    if (request.headers[API_KEY_HEADER] !== options.apiKey) {
      sendJson(response, 401, { message: 'unauthorized' });
      return;
    }
    if (url.pathname.startsWith('/control/')) {
      if (!controlEnabled) {
        throw new HttpError(404, 'The control API is disabled');
      }
      await control(request, response, url);
      return;
    }
    if (method === 'POST' && url.pathname === '/scan') {
      await submit(request, response);
      return;
    }
    const scanId = /^\/scan\/([^/]+)$/.exec(url.pathname)?.[1];
    if (method === 'GET' && scanId !== undefined) {
      const job = jobs.get(decodeURIComponent(scanId));
      if (!job) {
        throw new HttpError(404, `Scan job not found: ${decodeURIComponent(scanId)}`);
      }
      sendJson(response, 200, jobResponse(job, now()));
      return;
    }
    throw new HttpError(404, `No endpoint ${method} ${url.pathname}`);
  }

  const server = createServer((request, response) => {
    route(request, response).catch((error: unknown) => {
      const status = error instanceof HttpError ? error.status : 500;
      const message = error instanceof Error ? error.message : String(error);
      if (!response.headersSent) {
        sendJson(response, status, { message });
      } else {
        response.destroy();
      }
    });
  });

  return {
    server,
    statusOf: (scanId) => {
      const job = jobs.get(scanId);
      return job ? jobResponse(job, now()) : undefined;
    },
  };
}
