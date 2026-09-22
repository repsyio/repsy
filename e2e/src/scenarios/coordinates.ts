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
 * Small helpers a protocol adapter's `packageName()` builds coordinates with. Moved out of
 * `fixtures.ts` (step 3a, RPS-294): npm needs `slugify` too, for its own package-name generation,
 * and it has nothing to do with fixtures/Playwright.
 */

/** `password-admin`, `no-override`, ... -> `password-admin`, `no-override` (already slug-safe). */
export function slugify(id: string): string {
  return id.replace(/[^a-z0-9-]/gi, '-').toLowerCase();
}
