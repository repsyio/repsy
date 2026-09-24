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
import { expect, type Locator, type Page, type Request } from '@playwright/test';

import { UI_REPO_TYPES, type UiRepoType } from '../repo-types.js';
import { UiPage } from './base.js';
import { RepoCreateModal } from './repo-create-modal.js';

const USAGES_URL = /\/api\/usages(\?|$)/;
const PROFILE_URL = /\/api\/profile(\?|$)/;
const COUNTS_URL = /\/api\/repos\/counts(\?|$)/;
const LIST_URL = /\/api\/repos(\?|$)/;
/** The per-repository usage call the dashboard used to make for every recent repository (gone since RPS-1268). */
const REPO_USAGE_URL = /\/api\/repos\/[^/?]+\/usage(\?|$)/;

/** How many rows Recent Activity keeps (`DashboardContentComponent`: `RECENT_REPOSITORY_COUNT`, the list's `size`). */
export const RECENT_ACTIVITY_SIZE = 6;

/**
 * The dashboard (`/`). Cards: welcome, Total Disk Usage, Security Overview, the nine per-type
 * repository counts (one `GET /api/repos/counts` for every role) and Recent Activity (the six
 * newest repositories from one `GET /api/repos?size=6`, each row a link to the repository).
 */
export class DashboardPage extends UiPage {
  readonly welcomeCard: Locator;
  readonly welcomeUsername: Locator;
  readonly cards: Locator;
  readonly createButton: Locator;
  readonly diskUsageCard: Locator;
  readonly diskUsageTotal: Locator;
  readonly diskUsageRepoCount: Locator;
  readonly securityCard: Locator;
  readonly securityCriticalHigh: Locator;
  readonly securityTotal: Locator;
  readonly repoCountCard: Locator;
  readonly recentActivity: Locator;
  readonly modal: RepoCreateModal;

  constructor(page: Page) {
    super(page);
    this.welcomeCard = this.tid('welcome-card');
    this.welcomeUsername = this.tid('welcome-username');
    this.cards = this.tid('dashboard-cards');
    this.createButton = this.tid('dashboard-create-repo');
    this.diskUsageCard = this.tid('disk-usage-card');
    this.diskUsageTotal = this.tid('disk-usage-total');
    this.diskUsageRepoCount = this.tid('disk-usage-repo-count');
    this.securityCard = this.tid('security-overview-card');
    this.securityCriticalHigh = this.tid('security-overview-critical-high');
    this.securityTotal = this.tid('security-overview-total');
    this.repoCountCard = this.tid('repo-count-card');
    this.recentActivity = this.tid('recent-activity');
    this.modal = new RepoCreateModal(page);
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await this.expectLoaded();
  }

  /** The welcome card is on screen. */
  async expectLoaded(): Promise<void> {
    await expect(this.welcomeCard).toBeVisible();
  }

  /**
   * Opens `/` and resolves once the answers that drive the cards are in: the profile (admin or
   * not), the usage, the repository counts and the recent-repositories list (both for every role
   * since RPS-1268).
   */
  async open(): Promise<void> {
    const answers = [
      this.page.waitForResponse((response) => PROFILE_URL.test(response.url())),
      this.page.waitForResponse((response) => USAGES_URL.test(response.url())),
      this.page.waitForResponse((response) => COUNTS_URL.test(response.url())),
      this.page.waitForResponse(
        (response) => response.request().method() === 'GET' && LIST_URL.test(response.url()),
      ),
    ];
    const all = Promise.all(answers);
    all.catch(() => undefined);
    await this.page.goto('/');
    await all;
    await this.expectLoaded();
    await this.settle();
  }

  /** Two animation frames, so a negative assertion does not run on the view before the last answer. */
  async settle(): Promise<void> {
    await this.page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
    );
  }

  /** The number in one type's row of the repository card (`repo-count-value-<slug>`). */
  countValue(type: UiRepoType): Locator {
    return this.tid(`repo-count-value-${type.slug}`);
  }

  /** The clickable row of one type; it opens `/repositories` filtered to the type. */
  countRow(type: UiRepoType): Locator {
    return this.tid(`repo-count-row-${type.slug}`);
  }

  /** What the nine count rows display right now, by slug. */
  async displayedCounts(): Promise<Record<string, number>> {
    const counts: Record<string, number> = {};
    for (const type of UI_REPO_TYPES) {
      counts[type.slug] = Number((await this.countValue(type).innerText()).trim());
    }
    return counts;
  }

  /** One Recent Activity row, by repo name. */
  recentRow(name: string): Locator {
    return this.tid(`recent-activity-row-${name}`);
  }

  /** Every Recent Activity row. */
  recentRows(): Locator {
    return this.recentActivity.locator('[data-testid^="recent-activity-row-"]');
  }

  /** Opens the create modal from the dashboard's button. */
  async openCreateModal(): Promise<RepoCreateModal> {
    await this.createButton.click();
    await this.modal.expectOpen();
    return this.modal;
  }

  /**
   * Starts recording the repository requests of this page from now on (call before `open()`):
   * `counts` are `GET /api/repos/counts`, `lists` are `GET /api/repos?...`, `usages` are the
   * per-repository `GET /api/repos/<name>/usage` calls the dashboard no longer makes.
   */
  trackRepoRequests(): { counts: () => string[]; lists: () => string[]; usages: () => string[] } {
    const counts: string[] = [];
    const lists: string[] = [];
    const usages: string[] = [];
    const record = (request: Request): void => {
      if (COUNTS_URL.test(request.url())) {
        counts.push(request.url());
      } else if (LIST_URL.test(request.url()) && request.method() === 'GET') {
        lists.push(request.url());
      } else if (REPO_USAGE_URL.test(request.url())) {
        usages.push(request.url());
      }
    };
    this.page.on('request', record);
    return { counts: () => [...counts], lists: () => [...lists], usages: () => [...usages] };
  }
}
