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
 * The panel's routes, as the specs and page objects spell them (RPS-1638). What differs between the
 * panel of Repsy OS and the panel of Repsy Cloud is asked from `target.ui` (`src/target.ts`); these are
 * the short forms of it, plus the assertion helper that goes with them. A route that is the same on both
 * (`/`, `/login`, `/repositories`, `/security`, `/not-found`) stays a literal.
 *
 * Every function reads the target when it is CALLED, never at import (README, "Import time is not run
 * time"): Repsy Cloud's `repoRoute` needs `REPSY_REPO_OWNER`, which a `--list` run does not have.
 */
import { target } from '../target.js';

/** `/<repo>` (Repsy OS) or `/<owner>/<repo>` (Repsy Cloud), then `segments`: `target.ui.repoRoute`. */
export function repoRoute(repo: string, ...segments: string[]): string {
  return target.ui.repoRoute(repo, ...segments);
}

/** The signed-in account's own page: `/profile` (Repsy OS) or `/account` (Repsy Cloud). */
export function profileRoute(): string {
  return target.ui.profilePath;
}

/** A URL whose path (and query) ends in `path`, whatever the base URL is: for `expect(page).toHaveURL(...)`. */
export function urlEndsWith(path: string): RegExp {
  return new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
}

/**
 * The URL's pathname is exactly `path`, whatever its query string carries: for `expect(page).toHaveURL(...)`
 * on a list page (RPS-1668: the repositories, package and users lists keep their search, sort, page and
 * type in the query string), where a test means "still on this page", not "with no query string at all".
 */
export function urlHasPath(path: string): (url: URL) => boolean {
  return (url) => url.pathname === path;
}
