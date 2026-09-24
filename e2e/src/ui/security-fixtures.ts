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
 * The `test` of every security-scanning spec (RPS-1259): `package-fixtures.ts`'s `test` (so
 * `seedPackage` and the rest are there) plus an automatic teardown that unroutes every `page.route`
 * stub of `security-stubs.ts` the test registered on the built-in `page` (which `adminPage` is).
 * The context-level network allow-list of `defaults.ts` is not touched: it is not a page route.
 *
 * The page is closed after the test anyway; the explicit unroute keeps a stub from answering a
 * request that the harness itself makes late (a trace flush, a page that is still polling) and makes
 * "no stub outlives its test" a fact instead of a side effect.
 */
import { expect, test as packageTest } from './package-fixtures.js';

export const test = packageTest.extend<{ unstubOnTeardown: void }>({
  unstubOnTeardown: [
    async ({ page }, use) => {
      await use();
      await page.unrouteAll({ behavior: 'ignoreErrors' });
    },
    { auto: true },
  ],
});

export { expect };
