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
 * `/:repo/settings`: the densest page of the panel, one class per section (`toggles.ts`,
 * `deploy-tokens.ts`, `pgp.ts`, `repo-info.ts`, `danger-zone.ts`) composed here. Only a repo manager
 * (an admin, in this edition) gets the page; anyone else is redirected to `/repositories`. The
 * Vulnerability Scanning toggle is hidden without a scanner and is not modelled here (RPS-1259).
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { UiPage } from '../base.js';
import { Shell } from '../shell.js';
import { DeleteRepoSection, OrphanLayersSection, StorageSection } from './danger-zone.js';
import { DeployTokensSection } from './deploy-tokens.js';
import { PgpSection } from './pgp.js';
import { RepoInfoSection } from './repo-info.js';
import { PackageOverrideSection, VersionAllowanceSection, VisibilitySection } from './toggles.js';

export class RepoSettingsPage extends UiPage {
  readonly shell: Shell;
  readonly root: Locator;
  readonly title: Locator;
  /** Shown instead of the sections when the repo's settings cannot be loaded. */
  readonly notFound: Locator;

  readonly visibility: VisibilitySection;
  readonly packageOverride: PackageOverrideSection;
  readonly allowance: VersionAllowanceSection;
  readonly pgp: PgpSection;
  readonly tokens: DeployTokensSection;
  readonly storage: StorageSection;
  readonly info: RepoInfoSection;
  readonly orphanLayers: OrphanLayersSection;
  readonly deleteRepo: DeleteRepoSection;

  constructor(
    page: Page,
    readonly repoName: string,
  ) {
    super(page);
    this.shell = new Shell(page);
    this.root = this.tid('settings-page');
    this.title = this.tid('settings-title');
    this.notFound = this.tid('settings-not-found');

    this.visibility = new VisibilitySection(page);
    this.packageOverride = new PackageOverrideSection(page);
    this.allowance = new VersionAllowanceSection(page);
    this.pgp = new PgpSection(page);
    this.tokens = new DeployTokensSection(page);
    this.storage = new StorageSection(page);
    this.info = new RepoInfoSection(page);
    this.orphanLayers = new OrphanLayersSection(page);
    this.deleteRepo = new DeleteRepoSection(page);
  }

  /** `/<repo>/settings`. */
  get path(): string {
    return `/${this.repoName}/settings`;
  }

  async goto(): Promise<void> {
    await this.page.goto(this.path);
    await this.expectLoaded();
  }

  /** The settings page rendered (the layout hides the router outlet for 500 ms after load). */
  async expectLoaded(): Promise<void> {
    await expect(this.root).toBeVisible();
    await expect(this.title).toHaveText('Repository Settings');
  }

  /** Reloads the page and waits until it is rendered again. */
  async reload(): Promise<void> {
    await this.page.reload();
    await this.expectLoaded();
  }
}
