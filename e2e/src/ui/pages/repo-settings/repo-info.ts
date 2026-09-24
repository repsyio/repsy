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
 * The Repository Info section: rename (a form of its own, confirmed through the danger modal) and
 * the description (save/reset, no confirmation). The rename input is `#name` and the description
 * `#description`, ids the create-token modal reuses, so both are reached by their `settings-*` ids.
 */
import { type Locator, type Page } from '@playwright/test';

export type RenameError = 'required' | 'maxlength' | 'pattern';

export class RepoInfoSection {
  readonly root: Locator;
  readonly renameInput: Locator;
  readonly renameSubmit: Locator;
  readonly descriptionInput: Locator;
  readonly descriptionSave: Locator;
  readonly descriptionReset: Locator;
  readonly descriptionError: Locator;
  readonly descriptionCounter: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-info');
    this.renameInput = this.root.getByTestId('settings-rename-input');
    this.renameSubmit = this.root.getByTestId('settings-rename-submit');
    this.descriptionInput = this.root.getByTestId('settings-description-input');
    this.descriptionSave = this.root.getByTestId('settings-description-save');
    this.descriptionReset = this.root.getByTestId('settings-description-reset');
    this.descriptionError = this.root.getByTestId('settings-description-error-maxlength');
    this.descriptionCounter = this.root.getByTestId('settings-description-counter');
  }

  renameError(validator: RenameError): Locator {
    return this.root.getByTestId(`settings-rename-error-${validator}`);
  }

  /** Types a new name and leaves the field, so its validation messages (touched-only) can show. */
  async typeName(name: string): Promise<void> {
    await this.renameInput.fill(name);
    await this.renameInput.blur();
  }
}
