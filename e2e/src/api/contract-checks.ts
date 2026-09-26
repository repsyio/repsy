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
 * What every `tests/<protocol>/panel-api.spec.ts` (RPS-1483) shares: calling a panel operation BY ITS
 * `operationId` (the path template comes from `openapi-spec.yaml`, so a renamed route fails the spec
 * instead of being silently skipped), checking the answer against the spec's response schema
 * (`spec-contract.ts`), the bounded paging sweep of a list operation, and the coverage check that a spec
 * names every operation of its protocol.
 */
import { expect } from '@playwright/test';

import type { World } from '../scenarios/world.js';
import type { Scenario } from '../scenarios/types.js';
import { adminBearer, apiUrl, type EdgeResponse, edgeRequest } from '../clients/edge-raw.js';
import { contractProblems, specContract } from './spec-contract.js';
import { loadSpecOperations, type SpecOperation } from './spec-ops.js';

let operations: Map<string, SpecOperation> | undefined;

function operationOf(operationId: string): SpecOperation {
  operations ??= new Map(loadSpecOperations().map((op) => [op.operationId, op]));
  const op = operations.get(operationId);
  if (!op) {
    throw new Error(`openapi-spec.yaml has no operation "${operationId}"`);
  }
  return op;
}

let bearer: Promise<string> | undefined;

export interface CallOptions {
  /** Extra query string, without the `?` (`page=1&size=2`). */
  query?: string;
  /** Send no `Authorization` header. */
  anonymous?: boolean;
}

/**
 * Calls the panel operation `operationId` as the harness admin (or with no credentials). `values` are its
 * path parameters by name; each is URL-encoded.
 */
export async function callOperation(
  operationId: string,
  values: Readonly<Record<string, string>>,
  options: CallOptions = {},
): Promise<EdgeResponse> {
  const op = operationOf(operationId);
  const missing = op.pathParams.filter((name) => values[name] === undefined);
  expect(missing, `${operationId}: path parameters given`).toEqual([]);
  const path = op.path.replace(/\{([^}]+)\}/g, (_, name: string) =>
    encodeURIComponent(values[name] as string),
  );
  bearer ??= adminBearer();
  return edgeRequest(apiUrl(options.query ? `${path}?${options.query}` : path), {
    method: op.method,
    headers: options.anonymous ? {} : { Authorization: `Bearer ${await bearer}` },
  });
}

/**
 * Asserts `res` has `status` and a body that is exactly what the spec declares for it: the schema holds,
 * and no property outside the schema is present. Returns the envelope's `data`.
 */
export function expectContract(operationId: string, res: EdgeResponse, status = 200): unknown {
  expect(res.status, `${operationId}: ${res.text.slice(0, 300)}`).toBe(status);
  expect(res.json, `${operationId}: a JSON body`).toBeDefined();
  expect(contractProblems(operationId, status, res.json), `${operationId} ${status}`).toEqual([]);
  expect(
    specContract().undeclaredProperties(operationId, status, res.json),
    `${operationId} ${status}: properties the spec does not declare`,
  ).toEqual([]);
  return (res.json as { data?: unknown }).data;
}

/** Asserts a declared failure: `status`, the spec's error schema, and the stable `msgId`. */
export function expectFailure(
  operationId: string,
  res: EdgeResponse,
  status: number,
  msgId: string,
): void {
  expectContract(operationId, res, status);
  expect(res.json, `${operationId} ${status}`).toMatchObject({ type: 'ERROR', msgId });
}

export interface Page<T> {
  content: T[];
  page: { size: number; number: number; totalElements: number; totalPages: number };
}

export interface SortCheck<T> {
  /** The `sort` property the operation documents. */
  property: string;
  value: (item: T) => string;
}

export interface PagingSweep<T> {
  operationId: string;
  values: Readonly<Record<string, string>>;
  /** How many rows the list holds (the test seeded them). */
  total: number;
  /** A key that identifies one row. */
  keyOf: (item: T) => string;
  /** Sort properties that order the seeded rows the same way in any collation (names that differ only in a trailing number). */
  sorts: SortCheck<T>[];
}

async function page<T>(sweep: PagingSweep<T>, query: string): Promise<Page<T>> {
  const res = await callOperation(sweep.operationId, sweep.values, { query });
  return expectContract(sweep.operationId, res) as Page<T>;
}

/**
 * The bounded paging sweep of ONE list operation: `size` pages cover every row once with the right
 * `page` block, a page past the end is empty, `sort` (both directions) orders and `desc` is the reverse
 * of `asc`, and every parameter outside its documented range is a 400 `validationError` naming it.
 */
export async function expectPagingSweep<T>(sweep: PagingSweep<T>): Promise<void> {
  const { total } = sweep;
  const size = 2;
  const pages = Math.ceil(total / size);

  const seen: string[] = [];
  for (let number = 0; number < pages; number++) {
    const current = await page(sweep, `page=${number}&size=${size}`);
    expect(current.page, `page ${number}`).toEqual({
      size,
      number,
      totalElements: total,
      totalPages: pages,
    });
    expect(current.content.length, `rows of page ${number}`).toBe(
      Math.min(size, total - number * size),
    );
    seen.push(...current.content.map(sweep.keyOf));
  }
  expect(new Set(seen).size, 'no row twice across the pages').toBe(total);

  const beyond = await page(sweep, `page=${pages + 5}&size=${size}`);
  expect(beyond.content).toEqual([]);
  expect(beyond.page).toEqual({ size, number: pages + 5, totalElements: total, totalPages: pages });

  const defaults = await page(sweep, '');
  expect(defaults.page.size, 'the default page size').toBe(10);
  expect(defaults.page.number).toBe(0);
  expect(defaults.content.length).toBe(Math.min(10, total));
  const biggest = await page(sweep, 'size=100');
  expect(biggest.page.size).toBe(100);
  expect(biggest.content.length).toBe(total);

  for (const sort of sweep.sorts) {
    const ascending = (await page(sweep, `sort=${sort.property},asc&size=100`)).content;
    const descending = (await page(sweep, `sort=${sort.property},desc&size=100`)).content;
    const ascValues = ascending.map(sort.value);
    expect(ascValues, `sort=${sort.property},asc`).toEqual([...ascValues].sort());
    expect(descending.map(sweep.keyOf), `sort=${sort.property},desc is asc reversed`).toEqual(
      ascending.map(sweep.keyOf).reverse(),
    );
    const paged = [
      ...(await page(sweep, `sort=${sort.property},asc&size=${size}&page=0`)).content,
      ...(await page(sweep, `sort=${sort.property},asc&size=${size}&page=1`)).content,
    ];
    expect(
      paged.map(sweep.keyOf),
      `sort=${sort.property},asc: the first two pages are the head of the full order`,
    ).toEqual(ascending.slice(0, size * 2).map(sweep.keyOf));
  }

  for (const [query, offending] of [
    ['size=0', 'size'],
    ['size=101', 'size'],
    ['page=-1', 'page'],
    ['sort=noSuchProperty,asc', 'sort'],
    [`sort=${sweep.sorts[0]?.property ?? 'id'},sideways`, 'sort'],
  ] as const) {
    const res = await callOperation(sweep.operationId, sweep.values, { query });
    expectFailure(sweep.operationId, res, 400, 'validationError');
    expect((res.json as { data?: string }).data, `${query}: the offending parameter`).toBe(
      offending,
    );
  }
}

/**
 * The scenario every hand-built `World` of a contract spec carries: an admin publisher on a private repo. The
 * label of the client's temporary directories is derived from its id.
 */
export const CONTRACT_SCENARIO: Scenario = {
  id: 'panel-contract',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

/** A `World` that publishes and resolves `packageName@version` in `repoName` as the harness admin. */
export function contractWorld(
  protocol: string,
  repoName: string,
  packageName: string,
  version: string,
  credential: World['credential'],
): World {
  const target = { packageName, version };
  return {
    scenario: CONTRACT_SCENARIO,
    protocol,
    repoName,
    credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

/**
 * The operations of the spec whose path starts with one of `prefixes`: what a protocol's contract spec has
 * to cover. A route added to the spec that the spec file does not name fails `expectCovers`.
 */
export function operationsUnder(...prefixes: string[]): string[] {
  return loadSpecOperations()
    .filter((op) => prefixes.some((prefix) => op.path.startsWith(prefix)))
    .map((op) => op.operationId)
    .sort();
}

export function expectCovers(exercised: readonly string[], ...prefixes: string[]): void {
  const inSpec = operationsUnder(...prefixes);
  expect(inSpec.length, `operations under ${prefixes.join(', ')}`).toBeGreaterThan(0);
  expect([...exercised].sort(), 'the operations this spec exercises').toEqual(inSpec);
}
