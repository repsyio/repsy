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
 * Where the panel (the Angular single-page app) is served from: the `baseURL` of the `ui` project
 * (RPS-1638).
 *
 * Deliberately a pure function of the variables it is given, with no import: `playwright.config.ts`
 * calls it (it must load without `REPSY_ADMIN_PASSWORD`, which `src/env.ts` requires at import on an
 * OS target), and so does `target.ui.frontendBaseUrl()`. A consumer's config can call it too, from
 * `repsy-e2e/src/ui/base-url.js`.
 *
 * Chain (first set wins):
 *  1. `REPSY_UI_BASE_URL`: the UI runner's own variable, on every target;
 *  2. Repsy Cloud only (`cloud`, by default whether `REPSY_TARGET` is `cloud-*`): `REPSY_FRONTEND_BASE_URL`, because Repsy
 *     Cloud serves its panel from a different host than its API (the Cloud e2e package already names
 *     it that way). Ignored on an OS target, so a stray value there changes nothing;
 *  3. `REPSY_API_BASE_URL`: Repsy OS serves the panel on the API port (8080, never the protocol port);
 *  4. `http://localhost:8080`.
 */
export function uiBaseUrlFrom(
  vars: Readonly<Record<string, string | undefined>>,
  cloud: boolean = Boolean(vars.REPSY_TARGET?.startsWith('cloud-')),
): string {
  return (
    vars.REPSY_UI_BASE_URL ||
    (cloud ? vars.REPSY_FRONTEND_BASE_URL : undefined) ||
    vars.REPSY_API_BASE_URL ||
    'http://localhost:8080'
  );
}
