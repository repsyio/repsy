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
 * The self-service profile page (`/profile`, any role): the password form, the username form and the
 * delete-account button. Every action goes through the shared danger modal. Reach the page with
 * `goto()` (a direct navigation, fewer moving parts than the header's Profile link, which PRO-04
 * covers).
 *
 * `submit*` methods call `assertPageNotAdmin`: they change the logged-in user's own credentials or
 * delete the account, and the harness admin must never be that user. A username change ends in
 * `location.reload()` (the SPA reloads with the new tokens), which `confirmUsernameChange` waits for.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { assertPageNotAdmin } from '../session.js';
import { UiPage } from './base.js';
import { Shell } from './shell.js';

export type PasswordField = 'new-password' | 'password-confirmation';
export type PasswordValidator = 'required' | 'minlength' | 'maxlength' | 'pattern' | 'mismatch';
export type ProfileUsernameValidator = 'required' | 'minlength' | 'maxlength' | 'pattern';

export class ProfilePage extends UiPage {
  readonly shell: Shell;
  readonly title: Locator;
  readonly newPassword: Locator;
  readonly newPasswordToggle: Locator;
  readonly passwordConfirmation: Locator;
  readonly passwordConfirmationToggle: Locator;
  readonly passwordSubmit: Locator;
  readonly username: Locator;
  readonly usernameSubmit: Locator;
  readonly usernameWarning: Locator;
  readonly deleteAccountButton: Locator;

  constructor(page: Page) {
    super(page);
    this.shell = new Shell(page);
    this.title = this.tid('profile-title');
    this.newPassword = this.tid('profile-new-password');
    this.newPasswordToggle = this.tid('profile-new-password-toggle');
    this.passwordConfirmation = this.tid('profile-password-confirmation');
    this.passwordConfirmationToggle = this.tid('profile-password-confirmation-toggle');
    this.passwordSubmit = this.tid('profile-password-submit');
    this.username = this.tid('profile-username');
    this.usernameSubmit = this.tid('profile-username-submit');
    this.usernameWarning = this.tid('profile-username-warning');
    this.deleteAccountButton = this.tid('profile-delete-account-submit');
  }

  /** A direct navigation that waits for the username to be filled in. */
  async goto(): Promise<void> {
    await this.page.goto('/profile');
    await expect(this.title).toBeVisible();
    await expect(this.username).not.toHaveValue('');
  }

  /** One password-form validation message. */
  passwordError(field: PasswordField, validator: PasswordValidator): Locator {
    return this.tid(`profile-${field}-error-${validator}`);
  }

  usernameError(validator: ProfileUsernameValidator): Locator {
    return this.tid(`profile-username-error-${validator}`);
  }

  /**
   * Clicks an eye button. It is a Font Awesome glyph and the suite's network allow-list blocks the Font
   * Awesome CDN, so the button has no box and a real click is refused as "not visible": the click
   * event is dispatched instead.
   */
  async toggleVisibility(toggle: Locator): Promise<void> {
    await toggle.dispatchEvent('click');
  }

  /** Types both password fields (the confirmation defaults to the same value) and leaves them touched. */
  async fillPasswords(password: string, confirmation = password): Promise<void> {
    await this.newPassword.fill(password);
    await this.passwordConfirmation.fill(confirmation);
    await this.passwordConfirmation.blur();
  }

  /** Fills the form, submits it and waits for the confirmation modal. Nothing is changed yet. */
  async submitPasswordChange(password: string, confirmation = password): Promise<void> {
    await assertPageNotAdmin(this.page);
    await this.fillPasswords(password, confirmation);
    await expect(this.passwordSubmit).toBeEnabled();
    await this.passwordSubmit.click();
    await this.shell.dangerModal.expectOpen('Update Password');
  }

  /** Changes the password through the confirmation modal. Assert the toast right after this returns. */
  async changePassword(password: string, confirmation = password): Promise<void> {
    await this.submitPasswordChange(password, confirmation);
    await this.shell.dangerModal.confirm();
  }

  /** Types the new username, submits and waits for the confirmation modal. Nothing is changed yet. */
  async submitUsernameChange(username: string): Promise<void> {
    await assertPageNotAdmin(this.page);
    await this.username.fill(username);
    await expect(this.usernameSubmit).toBeEnabled();
    await this.usernameSubmit.click();
    await this.shell.dangerModal.expectOpen('Change Username');
  }

  /**
   * Confirms the username change and waits for the page reload that follows a success. The success
   * toast is raised in the same tick as `location.reload()`, so it is not observable: assert the
   * outcome (the stored username, the dashboard) instead.
   */
  async changeUsername(username: string): Promise<void> {
    await this.submitUsernameChange(username);
    const reloaded = this.page.waitForEvent('load');
    await this.shell.dangerModal.confirm();
    await reloaded;
    await expect(this.title).toBeVisible();
  }

  /** Clicks Delete and waits for the confirmation modal. Nothing is deleted yet. */
  async requestAccountDeletion(): Promise<void> {
    await assertPageNotAdmin(this.page);
    await this.deleteAccountButton.click();
    await this.shell.dangerModal.expectOpen('Delete Account');
  }
}
