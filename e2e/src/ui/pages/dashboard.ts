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
 * The dashboard (`/`). Minimal on purpose: RPS-1250 only needs "the welcome card rendered" for the
 * smoke tests; the repositories/dashboard story (RPS-1252) owns this file from here and adds the rest
 * (disk usage, security overview, per-type counts, recent activity).
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { UiPage } from './base.js';

export class DashboardPage extends UiPage {
  readonly welcomeCard: Locator;
  readonly welcomeUsername: Locator;

  constructor(page: Page) {
    super(page);
    this.welcomeCard = this.tid('welcome-card');
    this.welcomeUsername = this.tid('welcome-username');
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await this.expectLoaded();
  }

  /** The welcome card is on screen (the layout hides the router outlet for 500 ms after load). */
  async expectLoaded(): Promise<void> {
    await expect(this.welcomeCard).toBeVisible();
  }
}
