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
 * The access token UI (RPS-1905, RPS-1906, RPS-2002): the section that lists and revokes a user's personal
 * access tokens (on the Settings page of Repsy OS; on the account page of Repsy Cloud until it moves, see
 * `accessTokensRoute()`), its create form in a modal, the one-time modal that shows a secret once, and the
 * `/cli/auth` page the Repsy CLI opens. The ids are in the README, "UI test ids".
 */
import { expect, type Locator, type Page } from '@playwright/test';

import type { AccessTokenScope } from '../access-token-api.js';
import { accessTokensRoute, settingsRoute } from '../routes.js';
import { UiPage } from './base.js';
import { DangerModal } from './components.js';
import { SecretModal } from './one-time-secret-modal.js';
import { Shell } from './shell.js';

export type TokenNameError = 'required' | 'maxlength';

/** The create form: the same component sits in the create modal and on `/cli/auth`, scoped by `scope`. */
export class AccessTokenForm {
  readonly root: Locator;
  readonly blocked: Locator;
  readonly name: Locator;
  readonly scopes: Locator;
  readonly scopesError: Locator;
  readonly expiration: Locator;
  readonly expirationError: Locator;
  readonly submit: Locator;
  readonly cancel: Locator;

  constructor(scope: Page | Locator) {
    this.root = scope.getByTestId('access-token-form');
    this.blocked = this.root.getByTestId('access-token-blocked');
    this.name = this.root.getByTestId('access-token-name');
    this.scopes = this.root.getByTestId('access-token-scopes');
    this.scopesError = this.root.getByTestId('access-token-scopes-error');
    this.expiration = this.root.getByTestId('access-token-expiration');
    this.expirationError = this.root.getByTestId('access-token-expiration-error');
    this.submit = this.root.getByTestId('access-token-submit');
    this.cancel = this.root.getByTestId('access-token-cancel');
  }

  nameError(validator: TokenNameError): Locator {
    return this.root.getByTestId(`access-token-name-error-${validator}`);
  }

  scope(scope: string): Locator {
    return this.root.getByTestId(`access-token-scope-${scope}`);
  }

  /** The scope checkboxes the form offers. */
  get offeredScopes(): Locator {
    return this.root.locator('[data-testid^="access-token-scope-"]');
  }

  /** Marks a field as touched (a validation message only shows for a touched control). */
  async touch(field: Locator): Promise<void> {
    await field.focus();
    await field.blur();
  }

  /** Ticks exactly `scopes` (the checkboxes the form offers; the others are cleared). */
  async chooseScopes(scopes: readonly AccessTokenScope[]): Promise<void> {
    for (const offered of ['repo:read', 'repo:write', 'repo:manage']) {
      await this.scope(offered).setChecked(scopes.includes(offered as AccessTokenScope));
    }
  }

  async fill(values: {
    name?: string;
    scopes?: readonly AccessTokenScope[];
    expirationDate?: string;
  }): Promise<void> {
    if (values.name !== undefined) {
      await this.name.fill(values.name);
    }
    if (values.scopes !== undefined) {
      await this.chooseScopes(values.scopes);
    }
    if (values.expirationDate !== undefined) {
      await this.expiration.fill(values.expirationDate);
    }
  }
}

export class AccessTokenCreateModal extends AccessTokenForm {
  readonly modal: Locator;
  readonly closeButton: Locator;

  constructor(page: Page) {
    const modal = page.getByTestId('access-token-create-modal');
    super(modal);
    this.modal = modal;
    this.closeButton = modal.getByTestId('access-token-create-close');
  }

  async expectOpen(): Promise<void> {
    await expect(this.modal).toBeVisible();
  }

  async expectClosed(): Promise<void> {
    await expect(this.modal).toBeHidden();
  }
}

/** The "shown once" modal after a create: the secret, masked until asked for. */
export class AccessTokenInfoModal extends SecretModal {
  constructor(page: Page) {
    super(page, {
      root: 'access-token-info-modal',
      value: 'access-token-info-token',
      toggle: 'access-token-info-toggle',
      close: 'access-token-info-close',
      copyValue: 'access-token-info-copy',
      warning: /view this access token once/i,
    });
  }
}

export class AccessTokensPage extends UiPage {
  readonly shell: Shell;
  readonly root: Locator;
  readonly createButton: Locator;
  readonly limit: Locator;
  readonly list: Locator;
  readonly empty: Locator;
  readonly revokeAllButton: Locator;
  readonly revokeAllReport: Locator;
  readonly createModal: AccessTokenCreateModal;
  readonly infoModal: AccessTokenInfoModal;
  readonly dangerModal: DangerModal;

  constructor(page: Page) {
    super(page);
    this.shell = new Shell(page);
    this.root = this.tid('access-tokens-section');
    this.createButton = this.tid('access-token-create', this.root);
    this.limit = this.tid('access-token-limit', this.root);
    this.list = this.tid('access-token-list', this.root);
    this.empty = this.tid('access-token-empty', this.root);
    this.revokeAllButton = this.tid('access-token-revoke-all', this.root);
    this.revokeAllReport = this.tid('access-token-revoke-all-report', this.root);
    this.createModal = new AccessTokenCreateModal(page);
    this.infoModal = new AccessTokenInfoModal(page);
    this.dangerModal = new DangerModal(page);
  }

  /** A direct navigation; waits until the section has rendered its list or its empty state. */
  async goto(): Promise<void> {
    await this.page.goto(accessTokensRoute());
    await this.expectLoaded();
  }

  async expectLoaded(): Promise<void> {
    await expect(this.root).toBeVisible();
    await expect(this.list.or(this.empty)).toBeVisible();
  }

  row(name: string): Locator {
    return this.tid(`access-token-row-${name}`, this.root);
  }

  cell(
    name: string,
    id: 'row-name' | 'row-expired' | 'row-scopes' | 'row-last-used' | 'row-expires',
  ): Locator {
    return this.tid(id, this.row(name));
  }

  get rows(): Locator {
    return this.root.locator('[data-testid^="access-token-row-"]');
  }

  /** Opens the create modal. */
  async openCreateModal(): Promise<void> {
    await this.createButton.click();
    await this.createModal.expectOpen();
  }

  /** Revokes one token through the danger modal and waits until its row is gone. */
  async revoke(name: string): Promise<void> {
    await this.tid('row-revoke', this.row(name)).click();
    await this.dangerModal.expectOpen('Revoke Access Token');
    await this.dangerModal.confirm();
    await expect(this.row(name)).toHaveCount(0);
  }
}

/** The Settings page of Repsy OS (RPS-2002): a title, one navigation entry per section, the sections. */
export class SettingsPage extends UiPage {
  readonly shell: Shell;
  readonly title: Locator;
  readonly nav: Locator;
  readonly tokens: AccessTokensPage;

  constructor(page: Page) {
    super(page);
    this.shell = new Shell(page);
    this.title = this.tid('settings-title');
    this.nav = this.tid('settings-nav');
    this.tokens = new AccessTokensPage(page);
  }

  navEntry(section: string): Locator {
    return this.tid(`settings-nav-${section}`, this.nav);
  }

  async goto(): Promise<void> {
    await this.page.goto(settingsRoute());
    await expect(this.title).toBeVisible();
    await this.tokens.expectLoaded();
  }
}

/** `/cli/auth`: the page the Repsy CLI opens with `?name=&scopes=&state=` to get a token created. */
export class CliAuthPage extends UiPage {
  readonly title: Locator;
  readonly form: AccessTokenForm;
  readonly token: Locator;
  readonly copy: Locator;
  readonly manageLink: Locator;
  readonly loading: Locator;
  readonly error: Locator;

  constructor(page: Page) {
    super(page);
    this.title = this.tid('cli-auth-title');
    this.form = new AccessTokenForm(page);
    this.token = this.tid('cli-auth-token');
    this.copy = this.tid('cli-auth-copy');
    this.manageLink = this.tid('cli-auth-manage');
    this.loading = this.tid('cli-auth-loading');
    this.error = this.tid('cli-auth-error');
  }

  /** Opens `/cli/auth` with `query` (a direct navigation) and waits for the form. */
  async goto(query = ''): Promise<void> {
    await this.page.goto(`/cli/auth${query}`);
    await this.expectLoaded();
  }

  /** The form is there and the count of the user's live tokens has been read (the create button is final). */
  async expectLoaded(): Promise<void> {
    await expect(this.title).toBeVisible();
    await expect(this.form.root).toBeVisible();
    await expect(this.loading).toHaveCount(0);
  }
}
