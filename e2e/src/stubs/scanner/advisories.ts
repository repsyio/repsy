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
 * The advisory lookup of the stub scanner (RPS-1613): `POST /advisories`, the synchronous "which
 * advisories does the database hold for these exact (name, version) pairs" that Repsy's
 * `TrivyVulnerabilityAdvisoryLookup` asks for when an `npm audit` arrives (RPS-1612). The contract is
 * `repsy-scanner-trivy/README.md`, "Advisory lookup", and `AdvisoryLookupService` /
 * `AdvisoryController`; what the stub mirrors of it is the request validation (the same 400, 413 and
 * 415, so a bad request is refused the same way by both) and the answer shape.
 *
 * What the lookup FINDS is scripted, never derived from a name: a test registers, for the exact
 * package name it is about to audit, which findings the "database" holds for which versions and how the
 * lookup answers (`PUT /control/advisories`, see `server.ts`). A pair no script mentions has no
 * advisory, so an unrelated audit (the other specs of the scanner stack) finds nothing.
 *
 * Pure (no I/O, no clock), imports only `rules.ts`, so it runs by Node's own type stripping in the stub
 * image and is unit-tested by `tests/skeleton/scanner-stub.spec.ts`.
 */
import {
  SEVERITIES,
  findingsFor,
  type StubFinding,
  type StubFindingSpec,
  type StubSeverity,
} from './rules.ts';

/**
 * How a lookup answers, for the pairs of one package name:
 *  - `ok` (default): 200 with the scripted findings;
 *  - `unavailable`: 503, as while the scanner runs a scan and holds its database;
 *  - `timeout`: 504, as when the lookup took longer than the scanner allows itself;
 *  - `garbled`: 200 whose body is not the JSON of an answer.
 */
export type AdvisoryOutcome = 'ok' | 'unavailable' | 'timeout' | 'garbled';

export const ADVISORY_OUTCOMES: readonly AdvisoryOutcome[] = [
  'ok',
  'unavailable',
  'timeout',
  'garbled',
];

/** One finding of the scripted "database": the version it is on and what `StubFindingSpec` says. */
export interface AdvisoryFindingSpec extends Omit<
  StubFindingSpec,
  'packageName' | 'packageVersion'
> {
  version: string;
}

export interface AdvisoryScript {
  outcome?: AdvisoryOutcome;
  findings?: AdvisoryFindingSpec[];
}

export interface AdvisoryPair {
  name: string;
  version: string;
}

/** The limits of `POST /advisories` (`AdvisoryProperties`): the pairs of one request and its body. */
export const ADVISORY_MAX_PAIRS = 20_000;
export const ADVISORY_MAX_BODY_BYTES = 10 * 1024 * 1024;

/** The date the stub says its "database" was published. */
export const STUB_DB_UPDATED_AT = '2026-01-01T00:00:00Z';

export const ADVISORY_UNAVAILABLE_MESSAGE = 'stub scanner: the vulnerability database is in use';
export const ADVISORY_TIMEOUT_MESSAGE = 'stub scanner: the advisory lookup took too long';

/** What the "garbled" outcome sends with a 200: valid HTTP, a JSON content type, no JSON. */
export const GARBLED_BODY = '{"dbUpdatedAt": "stub", "findings": [ <<not json';

const MAX_NAME_LENGTH = 214;
const MAX_VERSION_LENGTH = 256;
const TEXT_FIELDS = ['cveId', 'description'] as const;

/** A request the lookup refuses, with the status the real service answers. */
export class AdvisoryRequestError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function requireText(value: unknown, field: string, maxLength: number): string {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new AdvisoryRequestError(400, `${field} must not be blank`);
  }
  if (value.length > maxLength) {
    throw new AdvisoryRequestError(400, `${field} must not be longer than ${maxLength}`);
  }
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) {
    throw new AdvisoryRequestError(400, `${field} must not contain control characters`);
  }
  return value;
}

/** An npm name is `name` or `@scope/name`: one slash at most, and only in the scoped form. */
function requireNpmName(value: unknown): string {
  const name = requireText(value, 'name', MAX_NAME_LENGTH);
  const slash = name.indexOf('/');
  const scoped = name.startsWith('@');
  if (
    scoped !== slash >= 0 ||
    name.indexOf('/', slash + 1) >= 0 ||
    (scoped && (slash < 2 || slash === name.length - 1))
  ) {
    throw new AdvisoryRequestError(400, 'name is not an npm package name');
  }
  return name;
}

/**
 * The pairs of a `POST /advisories` body, without repeats, in the order given. Throws what the real
 * service answers: 400 for a body that is not JSON, another ecosystem, no list or a malformed pair (a
 * bad request is refused as a whole), 413 for more than `ADVISORY_MAX_PAIRS`.
 */
export function parseAdvisoryRequest(raw: string): AdvisoryPair[] {
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new AdvisoryRequestError(400, 'The body is not a valid advisory request');
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new AdvisoryRequestError(400, 'The body is not a valid advisory request');
  }
  const { ecosystem, packages } = body as { ecosystem?: unknown; packages?: unknown };
  if (ecosystem !== 'npm') {
    throw new AdvisoryRequestError(400, 'ecosystem must be "npm"');
  }
  if (!Array.isArray(packages)) {
    throw new AdvisoryRequestError(400, 'packages must be a list');
  }
  if (packages.length > ADVISORY_MAX_PAIRS) {
    throw new AdvisoryRequestError(
      413,
      `packages must not hold more than ${ADVISORY_MAX_PAIRS} entries`,
    );
  }
  const unique = new Map<string, AdvisoryPair>();
  for (const pair of packages as unknown[]) {
    if (typeof pair !== 'object' || pair === null || Array.isArray(pair)) {
      throw new AdvisoryRequestError(400, 'packages must not contain null');
    }
    const name = requireNpmName((pair as { name?: unknown }).name);
    const version = requireText(
      (pair as { version?: unknown }).version,
      'version',
      MAX_VERSION_LENGTH,
    );
    unique.set(JSON.stringify([name, version]), { name, version });
  }
  return [...unique.values()];
}

/** Checks a `PUT /control/advisories` body, throwing a 400 that says what is wrong. */
export function parseAdvisoryScriptRequest(raw: string): {
  packageName: string;
  script: AdvisoryScript;
} {
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new AdvisoryRequestError(400, 'The body must be JSON');
  }
  const { packageName, script } = (body ?? {}) as { packageName?: unknown; script?: unknown };
  if (typeof packageName !== 'string' || packageName.length === 0) {
    throw new AdvisoryRequestError(400, 'packageName must be a non-empty string');
  }
  const input = (script ?? {}) as Record<string, unknown>;
  if (
    input.outcome !== undefined &&
    !ADVISORY_OUTCOMES.includes(input.outcome as AdvisoryOutcome)
  ) {
    throw new AdvisoryRequestError(400, `outcome must be one of ${ADVISORY_OUTCOMES.join(', ')}`);
  }
  if (input.findings !== undefined) {
    if (!Array.isArray(input.findings)) {
      throw new AdvisoryRequestError(400, 'findings must be a list');
    }
    for (const finding of input.findings as unknown[]) {
      const spec = (finding ?? {}) as Record<string, unknown>;
      if (typeof spec.version !== 'string' || spec.version === '') {
        throw new AdvisoryRequestError(400, 'each finding needs the version it is on');
      }
      if (!(SEVERITIES as readonly unknown[]).includes(spec.severity)) {
        throw new AdvisoryRequestError(400, `severity must be one of ${SEVERITIES.join(', ')}`);
      }
      for (const field of TEXT_FIELDS) {
        if (spec[field] !== undefined && (typeof spec[field] !== 'string' || spec[field] === '')) {
          throw new AdvisoryRequestError(400, `${field} of a finding must be a non-empty string`);
        }
      }
    }
  }
  return { packageName, script: input as AdvisoryScript };
}

/**
 * The first non-`ok` outcome among the scripts of the requested names (a request is answered as one, so
 * a failing script fails the request that mentions its package), or `ok`.
 */
export function outcomeFor(
  pairs: readonly AdvisoryPair[],
  scripts: ReadonlyMap<string, AdvisoryScript>,
): AdvisoryOutcome {
  for (const pair of pairs) {
    const outcome = scripts.get(pair.name)?.outcome ?? 'ok';
    if (outcome !== 'ok') {
      return outcome;
    }
  }
  return 'ok';
}

/** The findings the scripted database holds for `pairs`, each on the pair it was scripted for. */
export function advisoryFindingsFor(
  pairs: readonly AdvisoryPair[],
  scripts: ReadonlyMap<string, AdvisoryScript>,
): StubFinding[] {
  return pairs.flatMap((pair) => {
    const specs = (scripts.get(pair.name)?.findings ?? []).filter(
      (spec) => spec.version === pair.version,
    );
    const severities: StubSeverity[] = specs.map((spec) => spec.severity);
    return findingsFor(
      severities,
      specs.map(({ severity: _severity, version: _version, ...rest }) => ({
        ...rest,
        packageName: pair.name,
        packageVersion: pair.version,
      })),
    );
  });
}
