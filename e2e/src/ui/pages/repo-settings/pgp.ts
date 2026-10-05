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
 * Also includes the PGP Public Keys section (RPS-1803) with add/list/delete functionality.
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
  /** `settings-pgp-verify-all-toggle`: on = every signature is verified, off = the `.pom.asc` only. */
  readonly verifyAllToggle: Locator;
  readonly verifyAllInput: Locator;
  readonly verifyAllLabel: Locator;
  /** `settings-pgp-keyserver-lookup-toggle`: on = an unregistered signer is checked against the key servers. */
  readonly keyServerLookupToggle: Locator;
  readonly keyServerLookupInput: Locator;
  readonly keyServerLookupLabel: Locator;
  /** `settings-pgp-public-key-input`: textarea for adding armored public keys (RPS-1803). */
  readonly publicKeyInput: Locator;
  /** `settings-pgp-public-key-add`: button to add a public key (RPS-1803). */
  readonly publicKeyAddButton: Locator;
  /** `settings-pgp-public-keys`: container for the list of registered public keys (RPS-1803). */
  readonly publicKeys: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-pgp');
    this.serverSelector = this.root.getByTestId('settings-pgp-server-selector');
    this.selectorToggle = this.serverSelector.getByTestId('selector-toggle');
    this.selectorMenu = this.serverSelector.getByTestId('selector-menu');
    this.addButton = this.root.getByTestId('settings-pgp-add');
    this.keyStores = this.root.getByTestId('settings-pgp-keystores');
    this.builtIn = this.root.getByTestId('settings-pgp-builtin');
    this.verifyAllToggle = this.root.getByTestId('settings-pgp-verify-all-toggle');
    this.verifyAllInput = this.verifyAllToggle.getByTestId('toggle-input');
    this.verifyAllLabel = this.verifyAllToggle.getByTestId('toggle-label');
    this.keyServerLookupToggle = this.root.getByTestId('settings-pgp-keyserver-lookup-toggle');
    this.keyServerLookupInput = this.keyServerLookupToggle.getByTestId('toggle-input');
    this.keyServerLookupLabel = this.keyServerLookupToggle.getByTestId('toggle-label');
    this.publicKeyInput = this.root.getByTestId('settings-pgp-public-key-input');
    this.publicKeyAddButton = this.root.getByTestId('settings-pgp-public-key-add');
    this.publicKeys = this.root.getByTestId('settings-pgp-public-keys');
  }

  /** Flips the switch, the same way `ToggleSection.flip()` does for the other settings toggles. */
  async flipVerifyAll(): Promise<void> {
    await this.verifyAllLabel.click();
  }

  async flipKeyServerLookup(): Promise<void> {
    await this.keyServerLookupLabel.click();
  }

  async expectVerifyAllChecked(checked: boolean): Promise<void> {
    if (checked) {
      await expect(this.verifyAllInput).toBeChecked();
    } else {
      await expect(this.verifyAllInput).not.toBeChecked();
    }
  }

  async expectKeyServerLookupChecked(checked: boolean): Promise<void> {
    if (checked) {
      await expect(this.keyServerLookupInput).toBeChecked();
    } else {
      await expect(this.keyServerLookupInput).not.toBeChecked();
    }
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

  /**
   * Adds a public key by filling the textarea with the armored key and clicking the add button (RPS-1803).
   * Does NOT wait for the toast or the list to update; use `expectPublicKeyCount` after calling this.
   */
  async addPublicKey(armoredKey: string): Promise<void> {
    await this.publicKeyInput.fill(armoredKey);
    await this.publicKeyAddButton.click();
  }

  /** The number of registered public keys visible in the list. */
  async publicKeyCount(): Promise<number> {
    return this.publicKeys.locator('[data-testid^="settings-pgp-public-key-delete-"]').count();
  }

  /** Expects the public key count to match (RPS-1803). */
  async expectPublicKeyCount(count: number): Promise<void> {
    if (count === 0) {
      // The list container is only rendered with keys; the empty state is a paragraph beside it.
      await expect(this.root).toContainText('No public keys registered yet.');
    } else {
      await expect(
        this.publicKeys.locator('[data-testid^="settings-pgp-public-key-delete-"]'),
      ).toHaveCount(count);
    }
  }

  /** Deletes the public key at the given UUID (RPS-1803). */
  async deletePublicKey(uuid: string): Promise<void> {
    await this.publicKeys.getByTestId(`settings-pgp-public-key-delete-${uuid}`).click();
  }

  /** Returns the UUIDs of all visible public keys (RPS-1803). */
  async publicKeyUuids(): Promise<string[]> {
    const buttons = await this.publicKeys
      .locator('[data-testid^="settings-pgp-public-key-delete-"]')
      .all();
    const uuids = [];
    for (const button of buttons) {
      const testId = await button.getAttribute('data-testid');
      if (testId) {
        uuids.push(testId.replace('settings-pgp-public-key-delete-', ''));
      }
    }
    return uuids;
  }
}
