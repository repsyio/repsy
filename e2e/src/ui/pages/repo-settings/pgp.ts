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
 * The PGP Signature Key Stores section (Maven repos only): a selector of the key servers the
 * instance allows plus an add button, the list of servers registered on this repo (each with a
 * delete button, confirmed through the danger modal) and the built-in servers, which are read-only.
 */
import { expect, type Locator, type Page } from '@playwright/test';

export class PgpSection {
  readonly root: Locator;
  readonly serverSelector: Locator;
  readonly selectorToggle: Locator;
  readonly selectorMenu: Locator;
  readonly addButton: Locator;
  readonly keyStores: Locator;
  readonly builtIn: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-pgp');
    this.serverSelector = this.root.getByTestId('settings-pgp-server-selector');
    this.selectorToggle = this.serverSelector.getByTestId('selector-toggle');
    this.selectorMenu = this.serverSelector.getByTestId('selector-menu');
    this.addButton = this.root.getByTestId('settings-pgp-add');
    this.keyStores = this.root.getByTestId('settings-pgp-keystores');
    this.builtIn = this.root.getByTestId('settings-pgp-builtin');
  }

  /** The registered server row of `host`. */
  keyStore(host: string): Locator {
    return this.root.getByTestId(`settings-pgp-keystore-${host}`);
  }

  deleteButton(host: string): Locator {
    return this.keyStore(host).getByTestId('row-delete');
  }

  builtInServer(host: string): Locator {
    return this.root.getByTestId(`settings-pgp-builtin-${host}`);
  }

  /** The labels (`<display name> (<host>)`) the selector offers, in order. */
  async serverLabels(): Promise<string[]> {
    await this.selectorToggle.click();
    await expect(this.selectorMenu).toBeVisible();
    const ids = await this.selectorMenu
      .locator('[data-testid^="selector-option-"]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid') ?? ''));
    await this.selectorToggle.click();
    await expect(this.selectorMenu).toBeHidden();
    return ids.map((id) => id.replace('selector-option-', ''));
  }

  /** Picks the server labelled `label` in the selector (it does not add it yet). */
  async select(label: string): Promise<void> {
    await this.selectorToggle.click();
    await expect(this.selectorMenu).toBeVisible();
    await this.selectorMenu.getByTestId(`selector-option-${label}`).click();
    await expect(this.selectorMenu).toBeHidden();
  }
}
