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
 * Small helpers a protocol adapter's `packageName()`/`version()` builds coordinates with. Moved out
 * of `fixtures.ts` (step 3a, RPS-294): npm needs `slugify` too, for its own package-name generation,
 * and it has nothing to do with fixtures/Playwright.
 */

/** `password-admin`, `no-override`, ... -> `password-admin`, `no-override` (already slug-safe). */
export function slugify(id: string): string {
  return id.replace(/[^a-z0-9-]/gi, '-').toLowerCase();
}

/**
 * 2026-01-01T00:00:00Z: correction #3 (npm, plan section 2.3/step 3a) -- a bounded version scheme,
 * since npm's `PackageUtils.extractVersionNameFromPayload` and Cargo's `CrateUtils.validateVersion`
 * both parse with semver4j 3.1.0, which stores parts as Java `Integer`s (still `int` in the newer
 * 6.0.0 jar too, per `javap`). A raw millisecond timestamp (maven's `0.0.<Date.now()>` scheme)
 * overflows that. Seconds since this epoch stays well under 2^31 for the lifetime of this harness.
 * Moved here (step 3b, RPS-294) from `clients/npm.ts` so `clients/cargo.ts` can share it without
 * importing npm's own module.
 */
const VERSION_EPOCH_MS = Date.UTC(2026, 0, 1, 0, 0, 0, 0);

let versionSeq = 0;

/** `0.<seconds since VERSION_EPOCH_MS>.<seq>`; neither npm nor Cargo distinguishes release/snapshot. */
export function boundedSemverVersion(): string {
  versionSeq += 1;
  const seconds = Math.floor((Date.now() - VERSION_EPOCH_MS) / 1000);
  return `0.${seconds}.${versionSeq}`;
}
