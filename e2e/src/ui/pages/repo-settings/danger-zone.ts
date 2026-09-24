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
 * The remaining sections of `/:repo/settings`: Repository Storage (read only), Untagged Manifests
 * and Orphan Layers (Docker only) and Delete Repository. The last three act through the danger modal.
 */
import { type Locator, type Page } from '@playwright/test';

export class StorageSection {
  readonly root: Locator;
  /** The formatted size, e.g. `0 B` or `1.2 KB`. */
  readonly diskUsed: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-storage');
    this.diskUsed = this.root.getByTestId('settings-storage-disk-used');
  }
}

export class UntaggedManifestsSection {
  readonly root: Locator;
  /** The heading's self-link (`#delete-untagged-manifests`). */
  readonly link: Locator;
  readonly deleteButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-untagged-manifests');
    this.link = this.root.getByRole('link', { name: 'Untagged Manifests' });
    this.deleteButton = this.root.getByTestId('settings-untagged-manifests-delete');
  }

  /** Clicks "Delete Untagged Manifests" with a normal (actionability-checked) click. */
  async delete(): Promise<void> {
    await this.deleteButton.click();
  }
}

export class OrphanLayersSection {
  readonly root: Locator;
  /** The heading's self-link (`#delete-orphan-layers`). */
  readonly link: Locator;
  readonly deleteButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-orphan-layers');
    this.link = this.root.getByRole('link', { name: 'Orphan Layers' });
    this.deleteButton = this.root.getByTestId('settings-orphan-layers-delete');
  }

  /** Clicks "Delete Orphan Layers" with a normal (actionability-checked) click. */
  async delete(): Promise<void> {
    await this.deleteButton.click();
  }
}

export class DeleteRepoSection {
  readonly root: Locator;
  readonly deleteButton: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-delete-repo');
    this.deleteButton = this.root.getByTestId('settings-delete-repo-submit');
  }
}
