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
 * The Deploy Tokens section of `/:repo/settings` and the two modals it opens. Page size is 3.
 * Every row is reached through `row(name)`: the ids inside a row (`row-name`, `row-rotate`, ...)
 * repeat per row, and the mobile card list repeats them again, so the desktop table scopes them.
 *
 * `#name`, `#username` and `#description` exist BOTH in the rename form of Repository Info and in
 * the create-token modal on the same page, so the modal's fields are only ever reached through its
 * own `token-create-*` ids, never a label or an `#id`.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { Pagination } from '../components.js';
import { TokenCreateModal, TokenInfoModal } from '../deploy-token-modals.js';

/** The expiry colour classes `getBorderForExpireStatus` puts on the row's Expires cell. */
export const EXPIRES_WARNING_CLASS = /(^|\s)text-warning-500(\s|$)/;
export const EXPIRES_ERROR_CLASS = /(^|\s)text-error-500(\s|$)/;

export class DeployTokensSection {
  readonly root: Locator;
  readonly createButton: Locator;
  readonly table: Locator;
  readonly rows: Locator;
  readonly empty: Locator;
  readonly pagination: Pagination;
  readonly createModal: TokenCreateModal;
  readonly infoModal: TokenInfoModal;

  constructor(page: Page) {
    this.root = page.getByTestId('token-section');
    this.createButton = this.root.getByTestId('token-create');
    this.table = this.root.getByTestId('token-table');
    this.rows = this.table.locator('[data-testid^="token-row-"]');
    this.empty = this.root.getByTestId('token-empty');
    this.pagination = new Pagination(this.root);
    this.createModal = new TokenCreateModal(page);
    this.infoModal = new TokenInfoModal(page);
  }

  /** The desktop row of the token called `name`. */
  row(name: string): Locator {
    return this.table.getByTestId(`token-row-${name}`);
  }

  cell(
    name: string,
    id: 'row-name' | 'row-username' | 'row-created' | 'row-expires' | 'row-permission',
  ): Locator {
    return this.row(name).getByTestId(id);
  }

  /**
   * Asserts a cell's text. Names and usernames longer than 10 characters are cut to `abcdefghij...`
   * in the cell and only shown in full in the tooltip popup on hover, so this hovers first.
   */
  async expectCellText(
    name: string,
    id: 'row-name' | 'row-username' | 'row-expires',
    text: string,
  ): Promise<void> {
    const cell = this.cell(name, id);
    await cell.hover();
    await expect(cell).toContainText(text);
  }

  rotateButton(name: string): Locator {
    return this.row(name).getByTestId('row-rotate');
  }

  configureButton(name: string): Locator {
    return this.row(name).getByTestId('row-configure');
  }

  revokeButton(name: string): Locator {
    return this.row(name).getByTestId('row-revoke');
  }

  /** The pagination button of page `n` (1-based). */
  numberedButton(n: number): Locator {
    return this.pagination.page(n);
  }

  /** The names of the rows on screen, read off their `token-row-<name>` ids. */
  async rowNames(): Promise<string[]> {
    const ids = await this.rows.evaluateAll((nodes) =>
      nodes.map((node) => node.getAttribute('data-testid') ?? ''),
    );
    return ids.map((id) => id.replace('token-row-', ''));
  }

  async expectRowCount(count: number): Promise<void> {
    await expect(this.rows).toHaveCount(count);
  }

  async openCreateModal(): Promise<TokenCreateModal> {
    await this.createButton.click();
    await this.createModal.expectOpen();
    return this.createModal;
  }
}
