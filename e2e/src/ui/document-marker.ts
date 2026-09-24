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
 * Proof that the SPA did not load a new document (a reload, a plain `href` navigation): a flag on
 * `window` that only survives while the same document does. Set it, act, then check it survived.
 */
import type { Page } from '@playwright/test';

interface MarkedWindow {
  __e2eDocument?: boolean;
}

/** Sets the flag on the page's current document. */
export async function markDocument(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as MarkedWindow).__e2eDocument = true;
  });
}

/** True while the document that was marked is still the page's document. */
export async function documentIsMarked(page: Page): Promise<boolean> {
  return page.evaluate(() => (window as unknown as MarkedWindow).__e2eDocument === true);
}
