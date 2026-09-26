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
 * The panel API's operations, read from `openapi-spec.yaml` (the single source of truth of the API), so
 * a spec that has to cover "every operation" (the role sweep, RPS-1483) cannot go stale: a new route is
 * in the list the moment it is in the spec. The file is found the way a runner sees it: the checkout
 * has it at `../repsy-backend/...`, a runner container mounts it read-only under `/repsy-backend/...`.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse } from 'yaml';

const SPEC_RELATIVE = 'repsy-backend/src/main/resources/openapi/openapi-spec.yaml';
const HTTP_METHODS = ['get', 'put', 'post', 'delete', 'patch'] as const;

/**
 * How a caller has to authenticate, from the operation's `security`:
 *  - `required`: no override, the global `bearerAuth`;
 *  - `optional`: `[{}, {bearerAuth}]`, readable anonymously when the repository is public, else it
 *    needs the token;
 *  - `public`: `security: []`.
 */
export type SecurityKind = 'required' | 'optional' | 'public';

export interface SpecOperation {
  method: 'GET' | 'PUT' | 'POST' | 'DELETE' | 'PATCH';
  /** The path template, e.g. `/api/repos/{repoName}/settings`. */
  path: string;
  operationId: string;
  /** True when the operation documents a `403` response: a MANAGE route or an admin-only one. */
  declares403: boolean;
  security: SecurityKind;
  /** The `{name}` placeholders of `path`, in order. */
  pathParams: string[];
  /** Names of the query parameters marked `required` (a `$ref`d parameter is a paging one, optional). */
  requiredQuery: string[];
  hasBody: boolean;
}

interface RawOperation {
  operationId: string;
  responses: Record<string, unknown>;
  security?: unknown[];
  parameters?: { name?: string; in?: string; required?: boolean }[];
  requestBody?: unknown;
}

function specPath(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  const candidates = [
    resolve(here, '../../..', SPEC_RELATIVE),
    resolve('/', SPEC_RELATIVE),
    resolve(process.cwd(), '..', SPEC_RELATIVE),
  ];
  const found = candidates.find((candidate) => existsSync(candidate));
  if (!found) {
    throw new Error(`openapi-spec.yaml not found, looked in: ${candidates.join(', ')}`);
  }
  return found;
}

function securityKind(security: unknown[] | undefined): SecurityKind {
  if (security === undefined) {
    return 'required';
  }
  return security.length === 0 ? 'public' : 'optional';
}

export function loadSpecOperations(): SpecOperation[] {
  const spec = parse(readFileSync(specPath(), 'utf8')) as {
    paths: Record<string, Record<string, unknown>>;
  };
  const operations: SpecOperation[] = [];

  for (const [path, item] of Object.entries(spec.paths)) {
    for (const method of HTTP_METHODS) {
      const raw = item[method] as RawOperation | undefined;
      if (!raw) {
        continue;
      }
      operations.push({
        method: method.toUpperCase() as SpecOperation['method'],
        path,
        operationId: raw.operationId,
        declares403: '403' in raw.responses,
        security: securityKind(raw.security),
        pathParams: [...path.matchAll(/\{([^}]+)\}/g)].map((match) => match[1] as string),
        requiredQuery: (raw.parameters ?? [])
          .filter((param) => param.in === 'query' && param.required === true)
          .map((param) => param.name as string),
        hasBody: raw.requestBody !== undefined,
      });
    }
  }
  return operations;
}

/** A UUID nothing has: the id of a user, deploy token, key store, public key or scan. */
export const NO_SUCH_UUID = '00000000-0000-4000-8000-000000000000';

/** A repository name that is valid (`^[a-zA-Z0-9_][a-zA-Z0-9_-]*$`) and never created. */
export const NO_SUCH_REPO = 'e2e-sweep-no-such-repo';

const ID_PARAMS = new Set(['userId', 'tokenId', 'keyStoreId', 'publicKeyId', 'scanId']);

/** The value a path or required-query parameter takes when the request must not depend on any data. */
export function placeholderFor(name: string): string {
  if (name === 'repoName') {
    return NO_SUCH_REPO;
  }
  if (ID_PARAMS.has(name)) {
    return NO_SUCH_UUID;
  }
  if (name === 'digest') {
    return `sha256:${'0'.repeat(64)}`;
  }
  if (name === 'version') {
    return '1.0.0';
  }
  return 'e2e-sweep-none';
}

export interface OperationRequest {
  /** Path plus query, to be prefixed with the api base URL. */
  target: string;
  method: SpecOperation['method'];
  /** The JSON body, or `undefined` for an operation without one. */
  body: string | undefined;
}

export interface RequestOptions {
  /** Values for named path or required-query parameters; every other one takes its placeholder. */
  values?: Readonly<Record<string, string>>;
  /** The JSON body of an operation that has one; `{}` when not given. */
  body?: unknown;
}

/** The request that calls `op`: path parameters from `values` or placeholders, and a JSON body if it takes one. */
export function requestFor(op: SpecOperation, options: RequestOptions = {}): OperationRequest {
  const value = (name: string): string => options.values?.[name] ?? placeholderFor(name);
  const path = op.path.replace(/\{([^}]+)\}/g, (_, name: string) =>
    encodeURIComponent(value(name)),
  );
  const query = op.requiredQuery
    .map((name) => `${encodeURIComponent(name)}=${encodeURIComponent(value(name))}`)
    .join('&');
  return {
    target: query ? `${path}?${query}` : path,
    method: op.method,
    body: op.hasBody ? JSON.stringify(options.body ?? {}) : undefined,
  };
}
