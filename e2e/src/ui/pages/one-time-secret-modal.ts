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

export interface OneTimeSecretModalIds {
  root: string;
  /** Whose secret it is (the username). */
  subject: string;
  /** The read-only input holding the secret. */
  value: string;
  /** The show/hide button of the input. */
  toggle: string;
  /** The primary "Close" button. */
  done: string;
  /** The X in the header. */
  close: string;
}

export const RESET_PASSWORD_MODAL_IDS: OneTimeSecretModalIds = {
  root: 'user-reset-password-modal',
  subject: 'user-reset-password-username',
  value: 'user-reset-password-value',
  toggle: 'user-reset-password-toggle',
  done: 'user-reset-password-done',
  close: 'user-reset-password-close',
};

export class OneTimeSecretModal {
  readonly root: Locator;
  readonly subject: Locator;
  readonly value: Locator;
  readonly toggle: Locator;
  readonly done: Locator;
  readonly close: Locator;
  /** The "save it now, you will not see it again" notice (no test id: matched by its text). */
  readonly warning: Locator;

  constructor(page: Page, ids: OneTimeSecretModalIds = RESET_PASSWORD_MODAL_IDS) {
    this.root = page.getByTestId(ids.root);
    this.subject = page.getByTestId(ids.subject);
    this.value = page.getByTestId(ids.value);
    this.toggle = page.getByTestId(ids.toggle);
    this.done = page.getByTestId(ids.done);
    this.close = page.getByTestId(ids.close);
    this.warning = this.root.getByText(/won't be able to see it again/i);
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
   * Clicks the eye and waits until the input is a plain text field. The click event is dispatched:
   * this predates RPS-1402, when the eye was a Font Awesome glyph from a CDN the suite blocks, so the
   * button had no box and a real click was refused as "not visible". The icon is bundled now.
   */
  async reveal(): Promise<void> {
    await this.toggle.dispatchEvent('click');
    await expect(this.value).toHaveAttribute('type', 'text');
  }

  /** Closes it with the "Close" button and waits until it is gone. */
  async dismiss(): Promise<void> {
    await this.done.click();
    await this.expectClosed();
  }
}
