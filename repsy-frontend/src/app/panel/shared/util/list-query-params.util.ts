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

import { ActivatedRoute, Router } from '@angular/router';

/**
 * Keeps a server-paged list's search, sort, page and type in the URL's query string (RPS-1668), so a
 * reload or the browser's Back button lands where the user left the list instead of on an empty,
 * unfiltered, first page. Every list (repositories, a package's versions, the users list) reads its
 * initial state from the query params once (`readListQueryParam`/`readListPageParam`) and, from then
 * on, pushes every state change back into them with `updateListQueryParams`.
 */

/**
 * One query-param update: a value replaces the param, `null` or `undefined` drops it from the URL
 * (the default of a filter is left out, so the URL only carries what the user actually chose).
 */
export type ListQueryParamPatch = Record<string, string | number | null | undefined>;

/** The raw string of `key` in the current URL's query string, or `null` when it is not there. */
export function readListQueryParam(route: ActivatedRoute, key: string): string | null {
  return route.snapshot.queryParamMap.get(key);
}

/**
 * `key` read as a page number (a non-negative integer): `fallback` when it is absent, or not one, so a
 * hand-edited or stale URL never produces a negative or fractional page.
 */
export function readListPageParam(route: ActivatedRoute, key: string, fallback: number): number {
  const raw = readListQueryParam(route, key);
  if (raw === null) {
    return fallback;
  }
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

/**
 * Merges `patch` into the current URL's query params, in place: `replaceUrl: true` so filtering,
 * sorting and paging a list never push a new entry onto the browser history (only a real navigation,
 * such as opening a row, should do that). A patch that would not change anything already in the URL
 * is skipped, which keeps this safe to call from a component's constructor (the initial load) without
 * racing the navigation that is still activating it.
 */
export function updateListQueryParams(router: Router, route: ActivatedRoute, patch: ListQueryParamPatch): void {
  const current = route.snapshot.queryParamMap;
  const changed = Object.entries(patch).some(([key, value]) => {
    const normalized = value === null || value === undefined ? null : String(value);
    return current.get(key) !== normalized;
  });
  if (!changed) {
    return;
  }

  void router.navigate([], {
    relativeTo: route,
    queryParams: patch,
    queryParamsHandling: 'merge',
    replaceUrl: true,
  });
}
