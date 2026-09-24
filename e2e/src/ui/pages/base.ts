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
 * The base of every page object of the UI suite. Selectors are `data-testid` first, `getByRole`/label
 * second, never CSS classes or visible text alone (the panel renders a desktop grid and a mobile card
 * list at the same time, and Playwright's strict mode counts hidden matches too). The id conventions
 * are in README.md "UI test ids (data-testid conventions)".
 */
import type { Locator, Page } from '@playwright/test';

export abstract class UiPage {
  constructor(readonly page: Page) {}

  /** `getByTestId` on the page, or scoped to `scope` (a row, a modal, ...). */
  protected tid(id: string, scope: Page | Locator = this.page): Locator {
    return scope.getByTestId(id);
  }

  /**
   * Navigates to the page and waits for the element that proves the view rendered, never a fixed
   * sleep: `PanelLayoutComponent` hides `<router-outlet>` for a fixed 500 ms after load, and a
   * splash screen covers it, so "navigation finished" does not mean "view is there".
   */
  abstract goto(): Promise<void>;
}
