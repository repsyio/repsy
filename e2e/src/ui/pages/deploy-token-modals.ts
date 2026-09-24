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
 * The two modals of the Deploy Tokens section: the create form (`DeployTokenCreateModalComponent`,
 * ids `token-create-*`) and the one-time information modal (`DeployTokenInfoModalComponent`, ids
 * `token-info-*`) that shows a token's secret once, after a create or a rotate.
 */
import { expect, type Locator, type Page } from '@playwright/test';

export type NameError = 'required' | 'maxlength';
export type UsernameError = 'minlength' | 'maxlength' | 'pattern';

export interface TokenFormValues {
  name?: string;
  username?: string;
  description?: string;
  /** `true` picks Read Only, `false` Read/Write (the default). */
  readOnly?: boolean;
  /** `YYYY-MM-DD`, the value of the `<input type="date">`; the form defaults to one year out. */
  expirationDate?: string;
}

export class TokenCreateModal {
  readonly root: Locator;
  readonly closeButton: Locator;
  readonly form: Locator;
  readonly name: Locator;
  readonly username: Locator;
  readonly description: Locator;
  readonly expiration: Locator;
  readonly submitButton: Locator;
  readonly cancelButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('token-create-modal');
    this.closeButton = this.root.getByTestId('token-create-close');
    this.form = this.root.getByTestId('token-create-form');
    this.name = this.root.getByTestId('token-create-name');
    this.username = this.root.getByTestId('token-create-username');
    this.description = this.root.getByTestId('token-create-description');
    this.expiration = this.root.getByTestId('token-create-expiration');
    this.submitButton = this.root.getByTestId('token-create-submit');
    this.cancelButton = this.root.getByTestId('token-create-cancel');
  }

  async expectOpen(): Promise<void> {
    await expect(this.root).toBeVisible();
  }

  async expectClosed(): Promise<void> {
    await expect(this.root).toBeHidden();
  }

  /** The Read/Write (`false`) or Read Only (`true`) radio of the access type. */
  access(readOnly: boolean): Locator {
    return this.root.getByTestId('token-create-access').getByTestId(`radio-option-${readOnly}`);
  }

  nameError(validator: NameError): Locator {
    return this.root.getByTestId(`token-create-name-error-${validator}`);
  }

  usernameError(validator: UsernameError): Locator {
    return this.root.getByTestId(`token-create-username-error-${validator}`);
  }

  descriptionError(): Locator {
    return this.root.getByTestId('token-create-description-error-maxlength');
  }

  /** Fills the given fields only; anything left out keeps the form's default. */
  async fill(values: TokenFormValues): Promise<void> {
    if (values.name !== undefined) {
      await this.name.fill(values.name);
    }
    if (values.username !== undefined) {
      await this.username.fill(values.username);
    }
    if (values.description !== undefined) {
      await this.description.fill(values.description);
    }
    if (values.readOnly !== undefined) {
      await this.access(values.readOnly).check();
    }
    if (values.expirationDate !== undefined) {
      await this.expiration.fill(values.expirationDate);
    }
  }

  /** Fills the form and submits it; the modal closes and the info modal opens on success. */
  async create(values: TokenFormValues): Promise<void> {
    await this.fill(values);
    await expect(this.submitButton).toBeEnabled();
    await this.submitButton.click();
  }

  /** Marks a field as touched (validation messages only show for a touched control). */
  async touch(field: Locator): Promise<void> {
    await field.focus();
    await field.blur();
  }
}

export class TokenInfoModal {
  readonly root: Locator;
  readonly closeButton: Locator;
  readonly username: Locator;
  readonly token: Locator;
  readonly tokenToggle: Locator;
  readonly copyUsername: Locator;
  readonly copyToken: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('token-info-modal');
    this.closeButton = this.root.getByTestId('token-info-close');
    this.username = this.root.getByTestId('token-info-username');
    this.token = this.root.getByTestId('token-info-token');
    this.tokenToggle = this.root.getByTestId('token-info-token-toggle');
    // The host carries the id, the button (with `data-copied`) is the shared component's own.
    this.copyUsername = this.root
      .getByTestId('token-info-copy-username')
      .getByTestId('copy-button');
    this.copyToken = this.root.getByTestId('token-info-copy-token').getByTestId('copy-button');
  }

  async expectOpen(): Promise<void> {
    await expect(this.root).toBeVisible();
  }

  async expectClosed(): Promise<void> {
    await expect(this.root).toBeHidden();
  }

  /** The username and secret the modal shows (the secret is masked on screen, not in the value). */
  async values(): Promise<{ username: string; token: string }> {
    await this.expectOpen();
    return {
      username: ((await this.username.textContent()) ?? '').trim(),
      token: await this.token.inputValue(),
    };
  }

  /**
   * Shows or hides the secret. The eye icon is a Font Awesome glyph loaded from a CDN, which the UI
   * suite blocks (`src/ui/defaults.ts`: no third-party requests), so the button has no size and
   * cannot be clicked by position; the click event is dispatched to it directly instead.
   */
  async toggleTokenVisibility(): Promise<void> {
    await this.tokenToggle.dispatchEvent('click');
  }

  async close(): Promise<void> {
    await this.closeButton.click();
    await this.expectClosed();
  }
}
