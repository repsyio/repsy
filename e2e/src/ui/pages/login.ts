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
 * The login page (`/login`). Client-side validation shows inline errors (`login-<field>-error-
 * <validator>`) and keeps the form from submitting; a wrong password is a 401 with a toast instead.
 * The password only has `required` and `maxlength` messages (RPS-1308): a weak password is submitted.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { UiPage } from './base.js';

export type LoginField = 'username' | 'password';
export type LoginValidator = 'required' | 'pattern' | 'minlength' | 'maxlength';

export class LoginPage extends UiPage {
  readonly root: Locator;
  readonly form: Locator;
  readonly username: Locator;
  readonly password: Locator;
  readonly passwordToggle: Locator;
  readonly submit: Locator;

  constructor(page: Page) {
    super(page);
    this.root = this.tid('login-page');
    this.form = this.tid('login-form');
    this.username = this.tid('login-username');
    this.password = this.tid('login-password');
    this.passwordToggle = this.tid('login-password-toggle');
    this.submit = this.tid('login-submit');
  }

  /** One inline validation message of a field. */
  error(field: LoginField, validator: LoginValidator): Locator {
    return this.tid(`login-${field}-error-${validator}`);
  }

  async goto(): Promise<void> {
    await this.page.goto('/login');
    await expect(this.submit).toBeVisible();
  }

  /** Types the credentials and submits. Does not wait for the outcome: assert the URL or a toast after it. */
  async login(username: string, password: string): Promise<void> {
    await this.username.fill(username);
    await this.password.fill(password);
    await this.submit.click();
  }
}
