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
 * The "shown once" modals: a secret the server generated is displayed in a masked read-only input, the
 * user is told to save it now, and closing the modal discards it (the panel keeps no way to read it
 * again). Today that is the reset-password modal of the users page; the ids are parameterised so
 * another one-time modal (a deploy token) can reuse the class with its own ids.
 */
import { expect, type Locator, type Page } from '@playwright/test';

export interface OneTimeSecretModalIds extends SecretModalIds {
  /** Whose secret it is (the username). */
  subject: string;
  /** The primary "Close" button. */
  done: string;
  /** The copy button in the footer, next to "Close" (RPS-1623). */
  copyFooter: string;
}

export const RESET_PASSWORD_MODAL_IDS: OneTimeSecretModalIds = {
  root: 'user-reset-password-modal',
  subject: 'user-reset-password-username',
  value: 'user-reset-password-value',
  toggle: 'user-reset-password-toggle',
  done: 'user-reset-password-done',
  close: 'user-reset-password-close',
  copyValue: 'user-reset-password-copy-password',
  copyFooter: 'user-reset-password-copy-footer',
};

/** What every "shown once" modal has: the root, the masked secret, its eye, the X and the copy button. */
export interface SecretModalIds {
  root: string;
  /** The read-only input holding the secret. */
  value: string;
  /** The show/hide button of the input. */
  toggle: string;
  /** The X in the header. */
  close: string;
  /** The copy button next to the secret. */
  copyValue: string;
  /** The "save it now" notice, matched by its text (the modals carry no test id on it). */
  warning?: RegExp;
}

const DEFAULT_WARNING = /won't be able to see it again/i;

/**
 * The part of a "shown once" modal that is the same everywhere (the access token modal uses it as it is;
 * `OneTimeSecretModal` adds the subject and the footer buttons of the reset-password modal).
 */
export class SecretModal {
  readonly root: Locator;
  readonly value: Locator;
  readonly toggle: Locator;
  readonly close: Locator;
  readonly copyValue: Locator;
  /** The "save it now, you will not see it again" notice (no test id: matched by its text). */
  readonly warning: Locator;

  constructor(page: Page, ids: SecretModalIds) {
    this.root = page.getByTestId(ids.root);
    this.value = page.getByTestId(ids.value);
    this.toggle = page.getByTestId(ids.toggle);
    this.close = page.getByTestId(ids.close);
    this.copyValue = page.getByTestId(ids.copyValue);
    this.warning = this.root.getByText(ids.warning ?? DEFAULT_WARNING);
  }

  async expectOpen(): Promise<void> {
    await expect(this.root).toBeVisible();
    await expect(this.warning).toBeVisible();
  }

  async expectClosed(): Promise<void> {
    await expect(this.root).toBeHidden();
    // The modal is removed from the DOM, not hidden: the secret must not linger anywhere on the page.
    await expect(this.value).toHaveCount(0);
  }

  /** The secret, read from the input (it is masked on screen, the value is still the real one). */
  async secret(): Promise<string> {
    await expect(this.value).not.toHaveValue('');
    return this.value.inputValue();
  }

  /**
   * Clicks the eye (a real click: the icon is a bundled remixicon glyph since RPS-1402, RPS-1617)
   * and waits until the input is a plain text field.
   */
  async reveal(): Promise<void> {
    await this.toggle.click();
    await expect(this.value).toHaveAttribute('type', 'text');
  }
}

export class OneTimeSecretModal extends SecretModal {
  readonly subject: Locator;
  readonly done: Locator;
  readonly copyFooter: Locator;

  constructor(page: Page, ids: OneTimeSecretModalIds = RESET_PASSWORD_MODAL_IDS) {
    super(page, ids);
    this.subject = page.getByTestId(ids.subject);
    this.done = page.getByTestId(ids.done);
    this.copyFooter = page.getByTestId(ids.copyFooter);
  }

  /** Closes it with the "Close" button and waits until it is gone. */
  async dismiss(): Promise<void> {
    await this.done.click();
    await this.expectClosed();
  }
}
