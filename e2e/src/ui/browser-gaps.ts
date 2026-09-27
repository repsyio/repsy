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
 * Differences of the panel between browsers that are product defects, pinned instead of hidden (RPS-1651).
 * A pin is `test.fail`: the test fails inside while the defect exists (Playwright reports an expected failure)
 * and fails with "Expected to fail, but passed" when it is fixed, which is the moment to delete the pin.
 */
import { test } from '@playwright/test';

/**
 * Firefox replicates `localStorage` between tabs (content processes) asynchronously: a value one tab wrote
 * is read as missing by another tab for a few milliseconds, also right after the other tab released a Web
 * Lock (probed: 17 of 20 first reads right after the lock was granted returned the OLD value in Firefox, none
 * in Chromium and WebKit). `AuthService` re-reads the session from `localStorage` once it holds the refresh
 * lock, and adopts the pair another tab rotated instead of spending the spent refresh token; in Firefox that
 * read is stale, so the second tab refreshes with the spent token (two refresh calls instead of one), which
 * the backend answers by revoking the whole token family: both tabs are logged out. The `localStorage` lock
 * of a plain-HTTP origin is not affected (its settle delay covers it). The Karma spec of `AuthService` cannot
 * see it, no other engine shows it.
 */
export const FIREFOX_STALE_LOCALSTORAGE_IN_REFRESH_LOCK =
  'Firefox: localStorage is replicated between tabs asynchronously, so a tab that just got the Web Lock re-reads a stale session and refreshes with a spent token (no ticket yet: proposed in the PR of RPS-1651, put its key here)';

/** Marks the current test as an expected failure in Firefox, for the two-tab refresh under a Web Lock. */
export function pinFirefoxWebLockRefresh(browserName: string): void {
  test.fail(browserName === 'firefox', FIREFOX_STALE_LOCALSTORAGE_IN_REFRESH_LOCK);
}
