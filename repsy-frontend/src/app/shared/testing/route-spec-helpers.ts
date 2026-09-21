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

// Test-only helper shared by the route-file specs. This file is not a spec, and nothing under src/app imports it, so
// it is never part of the application bundle.

import { Type } from '@angular/core';
import { Route, Routes } from '@angular/router';

/** One route as a spec author writes it; `path` is the full path from the root of the tree, `''` for the root. */
export interface ExpectedRoute {
  path: string;
  /** Left out for a route that only groups children (`:package`, the root of a lazy tree, ...). */
  component?: Type<unknown>;
  title?: string;
  /** True when the route declares `pathMatch: 'full'`. */
  full?: boolean;
}

interface RouteRow {
  path: string;
  component: unknown;
  title: unknown;
  full: boolean;
}

function toRow(path: string, route: Pick<Route, 'component' | 'title'>, full: boolean): RouteRow {
  return { path, component: route.component, title: route.title, full };
}

/** Depth-first list of every route in the tree: full path, component, title and whether it matches in full. */
export function flattenRoutes(routes: Routes, parentPath = ''): RouteRow[] {
  return routes.flatMap((route) => {
    const path = [parentPath, route.path ?? ''].filter((part) => part !== '').join('/');
    return [toRow(path, route, route.pathMatch === 'full'), ...flattenRoutes(route.children ?? [], path)];
  });
}

/**
 * Registers the check that a routes constant is exactly the `expected` tree, in declaration order (the first match
 * wins, so a `:param` route has to stay behind the static routes at its level).
 */
export function describeRouteTree(routes: () => Routes, expected: ExpectedRoute[]): void {
  it('declares exactly this tree of paths, components and titles, in order', () => {
    expect(flattenRoutes(routes())).toEqual(expected.map((route) => toRow(route.path, route, route.full ?? false)));
  });

  it('has a single root route with an empty path', () => {
    expect(routes().map((route) => route.path)).toEqual(['']);
  });
}
