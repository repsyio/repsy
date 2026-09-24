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
 * The admin users page (`/users`) and its three modals (create, edit, reset password). The list is
 * SERVER-paged (10 a page, newest first) and SERVER-searched (case-insensitive substring of the
 * username; typing is debounced, so one request follows a pause in the typing), so the page object waits
 * for the list response that a search or refresh triggers instead of assuming the view is already updated.
 *
 * Rows are addressed by username through the desktop grid (`DesktopList`); the mobile card list is a
 * hidden duplicate with other ids. Methods that delete a user or reset a password call
 * `assertNotAdmin(username)`: the harness admin must never be the target.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { assertNotAdmin } from '../session.js';
import { UiPage } from './base.js';
import { DesktopList, Pagination } from './components.js';
import { OneTimeSecretModal } from './one-time-secret-modal.js';
import { Shell } from './shell.js';

export type UserRole = 'ADMIN' | 'USER';
export type UsernameValidator = 'required' | 'minlength' | 'maxlength' | 'pattern';
export type PasswordValidator = UsernameValidator;
export type ConfirmValidator = 'required' | 'mismatch';

export interface NewUser {
  username: string;
  password: string;
  /** Defaults to `password`. */
  confirmPassword?: string;
  admin?: boolean;
}

/** The create-user modal (`user-create-*`). */
export class UserCreateModal {
  readonly root: Locator;
  readonly username: Locator;
  readonly password: Locator;
  readonly confirmPassword: Locator;
  /** The clickable switch (the label): the sr-only `toggle-input` inside it is covered by the slider. */
  readonly roleToggle: Locator;
  /** The checkbox behind the switch: assert `toBeChecked()`/`toBeDisabled()` on it, never click it. */
  readonly roleSwitch: Locator;
  readonly roleLabel: Locator;
  readonly submit: Locator;
  readonly cancel: Locator;
  readonly close: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('user-create-modal');
    this.username = page.getByTestId('user-create-username');
    this.password = page.getByTestId('user-create-password');
    this.confirmPassword = page.getByTestId('user-create-confirm-password');
    const role = page.getByTestId('user-create-role');
    this.roleToggle = role.getByTestId('toggle');
    this.roleSwitch = role.getByTestId('toggle-input');
    this.roleLabel = role.getByTestId('toggle-label');
    this.submit = page.getByTestId('user-create-submit');
    this.cancel = page.getByTestId('user-create-cancel');
    this.close = page.getByTestId('user-create-close');
  }

  /** One inline validation message; the `confirm-password` field only has `required` and `mismatch`. */
  error(field: 'username', validator: UsernameValidator): Locator;
  error(field: 'password', validator: PasswordValidator): Locator;
  error(field: 'confirm-password', validator: ConfirmValidator): Locator;
  error(field: string, validator: string): Locator {
    return this.page.getByTestId(`user-create-${field}-error-${validator}`);
  }

  async fill(user: NewUser): Promise<void> {
    await this.username.fill(user.username);
    await this.password.fill(user.password);
    await this.confirmPassword.fill(user.confirmPassword ?? user.password);
    if (user.admin) {
      await this.roleToggle.click();
      await expect(this.roleSwitch).toBeChecked();
    }
  }
}

/** The edit-user modal (`user-edit-*`): username and role. */
export class UserEditModal {
  readonly root: Locator;
  readonly username: Locator;
  /** The clickable switch (the label): the sr-only `toggle-input` inside it is covered by the slider. */
  readonly roleToggle: Locator;
  /** The checkbox behind the switch: assert `toBeChecked()`/`toBeDisabled()` on it, never click it. */
  readonly roleSwitch: Locator;
  readonly roleLabel: Locator;
  readonly lastAdminWarning: Locator;
  readonly submit: Locator;
  readonly cancel: Locator;
  readonly close: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('user-edit-modal');
    this.username = page.getByTestId('user-edit-username');
    const role = page.getByTestId('user-edit-role');
    this.roleToggle = role.getByTestId('toggle');
    this.roleSwitch = role.getByTestId('toggle-input');
    this.roleLabel = role.getByTestId('toggle-label');
    this.lastAdminWarning = page.getByTestId('user-edit-last-admin-warning');
    this.submit = page.getByTestId('user-edit-submit');
    this.cancel = page.getByTestId('user-edit-cancel');
    this.close = page.getByTestId('user-edit-close');
  }

  error(validator: UsernameValidator): Locator {
    return this.page.getByTestId(`user-edit-username-error-${validator}`);
  }
}

export class UsersPage extends UiPage {
  readonly shell: Shell;
  readonly list: DesktopList;
  readonly pagination: Pagination;
  readonly empty: Locator;
  readonly emptyMessage: Locator;
  readonly title: Locator;
  readonly searchInput: Locator;
  readonly refreshButton: Locator;
  readonly createButton: Locator;
  readonly createModal: UserCreateModal;
  readonly editModal: UserEditModal;
  readonly resetPasswordModal: OneTimeSecretModal;

  constructor(page: Page) {
    super(page);
    this.shell = new Shell(page);
    this.list = new DesktopList(page, 'user');
    this.pagination = new Pagination(page);
    // `user-empty` wraps the shared `empty-list`, which shows `empty-list-message` (RPS-1267).
    this.empty = this.tid('user-empty');
    this.emptyMessage = this.tid('empty-list-message', this.empty);
    this.title = this.tid('user-title');
    this.searchInput = this.tid('user-search').getByTestId('search-input');
    this.refreshButton = this.tid('user-refresh');
    this.createButton = this.tid('user-create');
    this.createModal = new UserCreateModal(page);
    this.editModal = new UserEditModal(page);
    this.resetPasswordModal = new OneTimeSecretModal(page);
  }

  async goto(): Promise<void> {
    // The page asks the server for the admin count next to the first list (RPS-1246); the edit and
    // delete guards depend on it, so a test starts only once it is in.
    const adminCount = this.page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/users/admin-count',
    );
    await this.page.goto('/users');
    await expect(this.title).toBeVisible();
    await adminCount;
  }

  /** The desktop row of one user. */
  row(username: string): Locator {
    return this.list.row(username);
  }

  /** Every desktop row currently shown. */
  rows(): Locator {
    return this.list.rows();
  }

  role(username: string): Locator {
    return this.list.inRow(username, 'row-role');
  }

  /** Resolves with the next `GET /api/users` answered for this exact search text (`''` = no search). */
  listResponse(search: string, pageIndex?: number) {
    return this.page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        response.request().method() === 'GET' &&
        url.pathname === '/api/users' &&
        (url.searchParams.get('q') ?? '') === search &&
        (pageIndex === undefined || url.searchParams.get('page') === String(pageIndex))
      );
    });
  }

  /** Types into the search box and waits for the filtered list (sent once the typing pauses, 250 ms). */
  async search(text: string): Promise<void> {
    const response = this.listResponse(text, 0);
    await this.searchInput.fill(text);
    await response;
  }

  /**
   * Two animation frames: Angular renders an answer a tick after the response arrived, so a negative
   * assertion (a stale answer did not replace the rows) made straight after it could pass on the old view.
   */
  async settle(): Promise<void> {
    await this.page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
    );
  }

  /** The reload button: page 0 with no search, and the search box is emptied too. */
  async refresh(): Promise<void> {
    const response = this.listResponse('', 0);
    await this.refreshButton.click();
    await response;
  }

  /** Goes to page `n` (1-based) of the pagination and waits for it. */
  async goToPage(n: number): Promise<void> {
    await this.pagination.page(n).click();
    await expect(this.pagination.page(n)).toBeDisabled();
  }

  async openCreateModal(): Promise<void> {
    await this.createButton.click();
    await expect(this.createModal.root).toBeVisible();
  }

  /** Opens the modal, types the user and submits. Does not wait for the outcome. */
  async createUser(user: NewUser): Promise<void> {
    await this.openCreateModal();
    await this.createModal.fill(user);
    await expect(this.createModal.submit).toBeEnabled();
    await this.createModal.submit.click();
  }

  /** Opens the row's Edit modal. */
  async openEdit(username: string): Promise<void> {
    const menu = await this.list.openRowMenu(username);
    await menu.getByTestId('row-edit').click();
    await expect(this.editModal.root).toBeVisible();
    await expect(this.editModal.username).toHaveValue(username);
  }

  /** Clicks the row's Delete item. The confirmation modal (or, for a lone admin, a toast) follows. */
  async clickDelete(username: string): Promise<void> {
    assertNotAdmin(username);
    const menu = await this.list.openRowMenu(username);
    await menu.getByTestId('row-delete').click();
  }

  /** Deletes a user through the confirmation modal. */
  async deleteUser(username: string): Promise<void> {
    await this.clickDelete(username);
    await this.shell.dangerModal.expectOpen('Delete User');
    await this.shell.dangerModal.confirm();
  }

  /** Clicks the row's key button; the confirmation modal follows. */
  async clickResetPassword(username: string): Promise<void> {
    assertNotAdmin(username);
    await this.list.inRow(username, 'row-reset-password').click();
    await this.shell.dangerModal.expectOpen('Reset Password');
  }

  /** Resets a user's password through the confirmation modal and waits for the one-time modal. */
  async resetPassword(username: string): Promise<void> {
    await this.clickResetPassword(username);
    await this.shell.dangerModal.confirm();
    await this.resetPasswordModal.expectOpen();
  }
}
