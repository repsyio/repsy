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
 * Page objects for the panel's SHARED components (the ones every page reuses): the toast stack, the
 * danger (confirm-delete) modal, pagination, the empty state, the spinner and a desktop list. The ids
 * they use are fixed by the shared component itself; a page that hosts two instances tells them apart
 * with a host id (`getByTestId('repo-search').getByTestId('search-input')`).
 */
import { expect, type Locator, type Page } from '@playwright/test';

type ToastType = 'success' | 'error';

/**
 * The toast stack. A toast lives 3 s and at most 3 are kept, so assert it right after the action that
 * raises it, with nothing awaited in between.
 */
export class Toasts {
  readonly stack: Locator;

  constructor(private readonly page: Page) {
    this.stack = page.getByTestId('toast-stack');
  }

  /** Every toast, or only those of `type`, or only those whose message matches `text`. */
  toast(type?: ToastType, text?: string | RegExp): Locator {
    let toasts = this.page.getByTestId('toast');
    if (type) {
      toasts = toasts.and(this.page.locator(`[data-toast-type="${type}"]`));
    }
    if (text !== undefined) {
      toasts = toasts.filter({
        has: this.page.getByTestId('toast-message').filter({ hasText: text }),
      });
    }
    return toasts;
  }

  success(text?: string | RegExp): Locator {
    return this.toast('success', text);
  }

  error(text?: string | RegExp): Locator {
    return this.toast('error', text);
  }

  async expectSuccess(text: string | RegExp): Promise<void> {
    await expect(this.success(text).first()).toBeVisible();
  }

  async expectError(text: string | RegExp): Promise<void> {
    await expect(this.error(text).first()).toBeVisible();
  }
}

/** The confirm-delete modal (`DangerModalComponent`). */
export class DangerModal {
  readonly root: Locator;
  readonly title: Locator;
  readonly message: Locator;
  readonly confirmButton: Locator;
  readonly cancelButton: Locator;
  readonly closeButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('danger-modal');
    this.title = page.getByTestId('danger-modal-title');
    this.message = page.getByTestId('danger-modal-message');
    this.confirmButton = page.getByTestId('danger-modal-confirm');
    this.cancelButton = page.getByTestId('danger-modal-cancel');
    this.closeButton = page.getByTestId('danger-modal-close');
  }

  async expectOpen(title?: string | RegExp): Promise<void> {
    await expect(this.root).toBeVisible();
    if (title !== undefined) {
      await expect(this.title).toContainText(title);
    }
  }

  async expectClosed(): Promise<void> {
    await expect(this.root).toBeHidden();
  }

  async confirm(): Promise<void> {
    await this.confirmButton.click();
  }

  async cancel(): Promise<void> {
    await this.cancelButton.click();
  }

  async close(): Promise<void> {
    await this.closeButton.click();
  }
}

/**
 * `PaginationComponent`. The element exists (hidden) even when there is a single page, so assert
 * `toBeVisible()`/`toBeHidden()` on `root` to say whether paging shows.
 */
export class Pagination {
  readonly root: Locator;
  readonly prev: Locator;
  readonly next: Locator;

  /** `scope` is the page, or the container of one list when a page has several paginations. */
  constructor(scope: Page | Locator) {
    this.root = scope.getByTestId('pagination');
    this.prev = this.root.getByTestId('pagination-prev');
    this.next = this.root.getByTestId('pagination-next');
  }

  /** The button of page `n`, 1-based (the ids are `pagination-page-<n>`). */
  page(n: number): Locator {
    return this.root.getByTestId(`pagination-page-${n}`);
  }
}

/** `EmptyListComponent`. */
export class EmptyList {
  readonly root: Locator;

  constructor(scope: Page | Locator) {
    this.root = scope.getByTestId('empty-list');
  }
}

/** `SpinnerComponent`. */
export class Spinner {
  readonly root: Locator;

  constructor(scope: Page | Locator) {
    this.root = scope.getByTestId('spinner');
  }
}

/**
 * A list scoped to its DESKTOP container. Every list is rendered twice, a desktop grid and a mobile
 * card list, with different ids on purpose (`<key>-table`/`<key>-row-<id>` vs `<key>-cards`/
 * `<key>-card-<id>`), so scoping to the desktop ones keeps a locator from matching the hidden mobile
 * duplicate. In-row ids are short and shared by every row (`row-name`, `row-menu`, ...): always reach
 * them through a row (`inRow`).
 *
 * `pageKey` is the prefix the page's template uses, e.g. `repo` for `repo-table`/`repo-row-<name>`.
 */
export class DesktopList {
  readonly container: Locator;

  constructor(
    private readonly page: Page,
    readonly pageKey: string,
  ) {
    this.container = page.getByTestId(`${pageKey}-table`);
  }

  /** The desktop row of one entity; `key` is the raw identity value (repo name, username, ...). */
  row(key: string): Locator {
    return this.container.getByTestId(`${this.pageKey}-row-${key}`);
  }

  /** Every desktop row. */
  rows(): Locator {
    return this.container.locator(`[data-testid^="${this.pageKey}-row-"]`);
  }

  /** An element inside one row, e.g. `inRow('my-repo', 'row-name')`. */
  inRow(key: string, id: string): Locator {
    return this.row(key).getByTestId(id);
  }

  /**
   * Opens a row's `...` menu (`app-dropdown` with host id `row-menu`) and returns the menu itself, so
   * a page object can click its items. The dropdown is an outer `dropdown` div with a
   * `dropdown-toggle` button and, while open, a `dropdown-menu`.
   */
  async openRowMenu(key: string): Promise<Locator> {
    const menu = this.inRow(key, 'row-menu');
    await menu.getByTestId('dropdown-toggle').click();
    const items = menu.getByTestId('dropdown-menu');
    await expect(items).toBeVisible();
    return items;
  }
}
