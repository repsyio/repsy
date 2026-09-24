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

import { expect, type Locator, type Page } from '@playwright/test';

import type { UiRepoType } from '../repo-types.js';
import { UiPage } from './base.js';

/** The validators the name field reports, keyed the way the template ids are (`...-error-<validator>`). */
export type NameValidator = 'required' | 'maxlength' | 'pattern';

/**
 * The create-repository modal, shared by the dashboard ("Create Repository") and the repository
 * list ("Create"): both open the same `<app-repository-modal>`, so one page object serves both.
 * It is not a page of its own, so `goto()` is not supported; open it through the page that hosts it
 * (`RepositoriesPage.openCreateModal()`).
 */
export class RepoCreateModal extends UiPage {
  readonly root: Locator;
  readonly closeButton: Locator;
  readonly backdrop: Locator;
  readonly typeToggle: Locator;
  readonly nameInput: Locator;
  readonly visibilityToggle: Locator;
  readonly visibilityLabel: Locator;
  readonly descriptionInput: Locator;
  readonly cancelButton: Locator;
  readonly submitButton: Locator;

  constructor(page: Page) {
    super(page);
    this.root = this.tid('repo-create-modal');
    this.backdrop = this.tid('repo-create-backdrop');
    this.closeButton = this.tid('repo-create-close', this.root);
    this.typeToggle = this.tid('repo-create-type', this.root).getByTestId('selector-toggle');
    this.nameInput = this.tid('repo-create-name', this.root);
    // The toggle's checkbox is visually hidden (`sr-only`) and a sibling span covers it, so a click
    // on the input itself is refused by Playwright's actionability check: read its checked state
    // from here, but toggle it through its label text (`visibilityLabel`). Checked means Public.
    this.visibilityToggle = this.tid('repo-create-visibility', this.root).getByTestId(
      'toggle-input',
    );
    this.visibilityLabel = this.tid('repo-create-visibility', this.root).getByTestId(
      'toggle-label',
    );
    this.descriptionInput = this.tid('repo-create-description', this.root);
    this.cancelButton = this.tid('repo-create-cancel', this.root);
    this.submitButton = this.tid('repo-create-submit', this.root);
  }

  async goto(): Promise<void> {
    throw new Error(
      'RepoCreateModal has no route: open it from the dashboard or the repository list',
    );
  }

  async expectOpen(): Promise<void> {
    await expect(this.root).toBeVisible();
  }

  async expectClosed(): Promise<void> {
    await expect(this.root).toBeHidden();
  }

  /** Opens the type selector and picks `type` (the options are `selector-option-<slug>`). */
  async selectType(type: UiRepoType): Promise<void> {
    await this.typeToggle.click();
    await this.tid('repo-create-type', this.root)
      .getByTestId(`selector-option-${type.slug}`)
      .click();
    await expect(this.typeToggle).toHaveText(type.label);
  }

  /** Fills the name and leaves the field, which is what makes the form show its validation errors. */
  async fillName(name: string): Promise<void> {
    await this.nameInput.fill(name);
    await this.nameInput.blur();
  }

  async fillDescription(description: string): Promise<void> {
    await this.descriptionInput.fill(description);
    await this.descriptionInput.blur();
  }

  /** Sets the visibility toggle to `isPrivate` (the modal starts private; checked means Public). */
  async setPrivate(isPrivate: boolean): Promise<void> {
    if ((await this.visibilityToggle.isChecked()) === isPrivate) {
      await this.visibilityLabel.click();
    }
    await expect(this.visibilityLabel).toHaveText(isPrivate ? 'Private' : 'Public');
  }

  /** The validation message of the name field for `validator`. */
  nameError(validator: NameValidator): Locator {
    return this.tid(`repo-create-name-error-${validator}`, this.root);
  }

  /**
   * The reserved-name message. It is the one message without a test id (the template prints it
   * bare), so it is found by its text, inside the modal.
   */
  reservedNameError(): Locator {
    return this.root.getByText('This name is reserved for the panel');
  }

  descriptionError(): Locator {
    return this.tid('repo-create-description-error-maxlength', this.root);
  }

  /** The "n/500" character counter under the description. */
  descriptionCounter(): Locator {
    return this.tid('repo-create-description-counter', this.root);
  }

  /** Every validation message currently on screen, whichever field and validator. */
  anyError(): Locator {
    return this.root.locator(
      '[data-testid^="repo-create-name-error-"], [data-testid="repo-create-description-error-maxlength"]',
    );
  }

  /** Fills the form and submits it; the caller waits for what the submit causes (toast, list). */
  async create(options: {
    type?: UiRepoType;
    name: string;
    description?: string;
    isPrivate?: boolean;
  }): Promise<void> {
    if (options.type) {
      await this.selectType(options.type);
    }
    await this.fillName(options.name);
    if (options.description !== undefined) {
      await this.fillDescription(options.description);
    }
    if (options.isPrivate !== undefined) {
      await this.setPrivate(options.isPrivate);
    }
    await this.submitButton.click();
  }
}
