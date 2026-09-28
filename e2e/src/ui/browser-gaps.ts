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
 *
 * Nothing is pinned here right now: the one entry this file held, the Firefox stale-`localStorage` read in
 * the two-tab refresh lock (RPS-1672), was fixed and removed. See
 * `tests/ui/auth/multi-tab.spec.ts` and `tests/ui/tls/panel-over-https.spec.ts` for the comments that still
 * cite it where the pin used to be, and add the next `test.fail` entry here rather than inline when one is
 * needed again.
 */
