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

/**
 * Validation helpers for the login form, composed on top of `LoginPage` (which RPS-1250 owns and
 * this story must not edit). The form validates client side and shows one inline message per field,
 * and only once the field has been touched (blurred): `LoginValidation.enter()` types a value and
 * blurs it, so the message a user would see is on screen.
 *
 * The messages are keyed by validator (`login-<field>-error-<validator>`); the visible texts are
 * asserted once here, so a spec names the validator it expects, not a sentence.
 */
import { expect, type Page } from '@playwright/test';

import { LOGIN_USERNAME_TEXT, PASSWORD_TEXT } from '../credential-messages.js';
import { type LoginField, LoginPage, type LoginValidator } from '../pages/login.js';

/**
 * The visible text of every inline message, as `login.component.html` renders it. The sentences are the
 * shared credential ones (RPS-1265): the backend's LoginForm holds the password to the same rule as a new
 * one, so the login form has the create-user password rule and messages.
 */
export const LOGIN_ERROR_TEXT: Record<LoginField, Record<LoginValidator, string>> = {
  username: LOGIN_USERNAME_TEXT,
  password: PASSWORD_TEXT,
};

const VALIDATORS = Object.keys(LOGIN_ERROR_TEXT.username) as LoginValidator[];

export class LoginValidation {
  constructor(private readonly login: LoginPage) {}

  /** Types `value` into `field` and blurs it (an empty value still counts as touched). */
  async enter(field: LoginField, value: string): Promise<void> {
    const input = field === 'username' ? this.login.username : this.login.password;
    await input.fill(value);
    await input.blur();
  }

  /**
   * Clicks the password eye button. The button holds only a Font Awesome icon, whose font is a
   * third-party CDN resource the suite blocks (`defaults.ts`): without it the button has no size and
   * Playwright refuses to click it as "not visible". The DOM click is what the app handles.
   */
  async togglePasswordVisibility(): Promise<void> {
    await this.login.passwordToggle.dispatchEvent('click');
  }

  /**
   * The field shows exactly the message of `validator` (with its text) and no other: the form
   * shows one message at a time, in a fixed order (required, pattern, minlength, maxlength).
   */
  async expectOnlyError(field: LoginField, validator: LoginValidator): Promise<void> {
    const shown = this.login.error(field, validator);
    await expect(shown).toBeVisible();
    await expect(shown).toContainText(LOGIN_ERROR_TEXT[field][validator]);
    for (const other of VALIDATORS.filter((v) => v !== validator)) {
      await expect(this.login.error(field, other)).toHaveCount(0);
    }
  }

  /** The field shows no message at all. */
  async expectNoError(field: LoginField): Promise<void> {
    for (const validator of VALIDATORS) {
      await expect(this.login.error(field, validator)).toHaveCount(0);
    }
  }
}

/**
 * Counts the `POST /api/auth/login` requests the page sends from now on, to prove that an invalid
 * form never reaches the server (pressing Enter submits a form whose button is disabled).
 */
export function countLoginRequests(page: Page): () => number {
  let count = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/auth/login') {
      count += 1;
    }
  });
  return () => count;
}
