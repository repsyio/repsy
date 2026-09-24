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

import { UI_REPO_TYPES, type UiRepoType } from '../repo-types.js';
import { UiPage } from './base.js';
import { DangerModal, DesktopList, EmptyList, Pagination, Spinner, Toasts } from './components.js';
import { RepoCreateModal } from './repo-create-modal.js';

const INFO_URL = /\/api\/repos\/[A-Z]+\/info(\?|$)/;
const PROFILE_URL = /\/api\/profile(\?|$)/;

/** Rows per page of the list; the pagination is client-side (`RepositoryComponent.pageSize`). */
export const REPO_PAGE_SIZE = 10;

/**
 * Every desktop row's name, read from its `repo-row-<name>` test id. The row ids are the raw repo
 * names, and (unlike the ellipsised `row-name` text) untruncated.
 */
export async function rowNames(rows: Locator): Promise<string[]> {
  const ids = await rows.evaluateAll((elements) =>
    elements.map((element) => element.getAttribute('data-testid') ?? ''),
  );
  return ids.map((id) => id.replace(/^repo-row-/, ''));
}

/**
 * The repository list (`/repositories`).
 *
 * How the page loads, which every wait here is built on: it fires one `GET /api/repos/<TYPE>/info`
 * per type (nine for "All", one for a type), shows the spinner until the FIRST answer, and then
 * renders rows as each further answer arrives. Search, type filter and pagination are all
 * client-side over what has arrived, so a query typed before the last answer would be lost: every
 * navigating method resolves only once all of its `info` answers are in, plus `settle()`.
 * Several parallel tests share the stack, so the unfiltered list is only ever asserted by size or
 * through a search string unique to the test (`search(runId)`), never by position.
 */
export class RepositoriesPage extends UiPage {
  readonly toasts: Toasts;
  readonly dangerModal: DangerModal;
  readonly modal: RepoCreateModal;
  readonly list: DesktopList;
  readonly pagination: Pagination;
  readonly emptyList: EmptyList;
  readonly spinner: Spinner;
  readonly title: Locator;
  readonly searchInput: Locator;
  readonly typeFilterToggle: Locator;
  readonly refreshButton: Locator;
  readonly createButton: Locator;
  readonly error: Locator;
  /** Mobile cards (`repo-card-<name>`), present in the DOM next to the desktop rows. */
  readonly cards: Locator;

  constructor(page: Page) {
    super(page);
    this.toasts = new Toasts(page);
    this.dangerModal = new DangerModal(page);
    this.modal = new RepoCreateModal(page);
    this.list = new DesktopList(page, 'repo');
    this.pagination = new Pagination(page);
    this.emptyList = new EmptyList(page);
    this.spinner = new Spinner(page);
    this.title = this.tid('repo-title');
    this.searchInput = this.tid('repo-search').getByTestId('search-input');
    this.typeFilterToggle = this.tid('repo-type-filter').getByTestId('selector-toggle');
    this.refreshButton = this.tid('repo-refresh');
    this.createButton = this.tid('repo-create');
    this.error = this.tid('repo-error');
    this.cards = this.tid('repo-cards');
  }

  /**
   * Runs `action` and resolves once the `info` answers it causes are all in (`types`, default every
   * type) and Angular has rendered them.
   */
  async afterInfoResponses<T>(
    action: () => Promise<T>,
    types: readonly UiRepoType[] = UI_REPO_TYPES,
  ): Promise<T> {
    const answers = Promise.all(
      types.map(({ type }) =>
        this.page.waitForResponse(
          (response) => INFO_URL.test(response.url()) && response.url().includes(`/${type}/info`),
        ),
      ),
    );
    // If `action` throws, nobody awaits `answers`; keep its later timeout from being unhandled.
    answers.catch(() => undefined);
    const result = await action();
    await answers;
    await this.settle();
    return result;
  }

  /**
   * Two animation frames: Angular renders the answer that a `waitForResponse` just saw a tick
   * later, so a negative assertion ("no Create button", "row gone") made straight after the last
   * response could pass on the stale view. Positive assertions retry on their own and need no settle.
   */
  async settle(): Promise<void> {
    await this.page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
    );
  }

  /**
   * Opens `/repositories` and waits for the profile (which decides the admin-only controls) and
   * every `info` answer. A pre-selected type only comes from the dashboard's count rows
   * (`history.state`), never from a URL.
   */
  async goto(): Promise<void> {
    await this.afterInfoResponses(async () => {
      const profile = this.page.waitForResponse((response) => PROFILE_URL.test(response.url()));
      profile.catch(() => undefined);
      await this.page.goto('/repositories');
      await profile;
    });
    await expect(this.title).toBeVisible();
    await expect(this.spinner.root).toBeHidden();
  }

  /** Types `text` into the search box (client-side substring filter, case-insensitive). */
  async search(text: string): Promise<void> {
    await this.searchInput.fill(text);
  }

  /** Picks a type in the toolbar's selector; it fetches only that type's list. */
  async selectType(type: UiRepoType | 'all'): Promise<void> {
    const types = type === 'all' ? UI_REPO_TYPES : [type];
    const option = type === 'all' ? 'all' : type.slug;
    await this.afterInfoResponses(async () => {
      await this.typeFilterToggle.click();
      await this.tid('repo-type-filter').getByTestId(`selector-option-${option}`).click();
    }, types);
  }

  /** Clicks refresh and waits for the reload (of the selected type, or of all nine). */
  async refresh(type: UiRepoType | 'all' = 'all'): Promise<void> {
    const types = type === 'all' ? UI_REPO_TYPES : [type];
    await this.afterInfoResponses(() => this.refreshButton.click(), types);
  }

  /** Opens the create modal from the toolbar's button. */
  async openCreateModal(): Promise<RepoCreateModal> {
    await this.createButton.click();
    await this.modal.expectOpen();
    return this.modal;
  }

  row(name: string): Locator {
    return this.list.row(name);
  }

  card(name: string): Locator {
    return this.tid(`repo-card-${name}`);
  }

  /** Every desktop row currently rendered (one page). */
  rows(): Locator {
    return this.list.rows();
  }

  /** Row menus (the admin-only `...` dropdown), one per row. */
  rowMenus(): Locator {
    return this.list.container.getByTestId('row-menu');
  }

  /** Opens the row's menu and clicks Delete; the danger modal then asks for confirmation. */
  async startDelete(name: string): Promise<void> {
    const menu = await this.list.openRowMenu(name);
    await menu.getByTestId('row-delete').click();
    await this.dangerModal.expectOpen('Delete Repository');
  }

  /** Confirms the danger modal and waits for the reload the deletion triggers. */
  async confirmDelete(): Promise<void> {
    await this.afterInfoResponses(() => this.dangerModal.confirm());
  }

  /** The type selector's shown value (`All`, `Maven`, ...). */
  typeFilterText(): Locator {
    return this.typeFilterToggle;
  }

  /** The Private/Public cell of one row. */
  visibility(name: string): Locator {
    return this.list.inRow(name, 'row-visibility');
  }
}
