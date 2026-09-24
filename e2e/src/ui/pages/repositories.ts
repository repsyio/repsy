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

import { expect, type Locator, type Page, type Response } from '@playwright/test';

import type { UiRepoType } from '../repo-types.js';
import { UiPage } from './base.js';
import { DangerModal, DesktopList, EmptyList, Pagination, Spinner, Toasts } from './components.js';
import { RepoCreateModal } from './repo-create-modal.js';

/** `GET /api/repos` (the list); `/api/repos/counts` and `/api/repos/security-summary` do not match. */
export const LIST_URL = /\/api\/repos(\?|$)/;
export const SECURITY_SUMMARY_URL = /\/api\/repos\/security-summary(\?|$)/;
const PROFILE_URL = /\/api\/profile(\?|$)/;

/** Rows per page of the list: the page size the SPA asks the server for (`RepositoryComponent.pageSize`). */
export const REPO_PAGE_SIZE = 10;

/**
 * What one `GET /api/repos` request must carry for `afterListResponse` to take it as the answer to
 * an action. A field left out is not checked; `q: ''` means "no search".
 */
export interface ListQuery {
  /** The type filter (`all` = no `type` parameter). */
  type?: UiRepoType | 'all';
  q?: string;
  /** The zero-based `page` parameter. */
  page?: number;
}

/** Whether `response` is a successful-or-not `GET /api/repos` that carries every field of `query`. */
export function isListResponse(response: Response, query: ListQuery = {}): boolean {
  if (response.request().method() !== 'GET' || !LIST_URL.test(response.url())) {
    return false;
  }
  const params = new URL(response.url()).searchParams;
  if (query.type !== undefined) {
    const wanted = query.type === 'all' ? null : query.type.type;
    if (params.get('type') !== wanted) {
      return false;
    }
  }
  if (query.q !== undefined && (params.get('q') ?? '') !== query.q) {
    return false;
  }
  if (query.page !== undefined && params.get('page') !== String(query.page)) {
    return false;
  }
  return true;
}

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
 * How the page loads, which every wait here is built on (RPS-1268): ONE `GET /api/repos?type=&q=
 * &page=&size=10&sort=createdAt,desc` per load, answered with the page of rows plus its page count;
 * type filter, search and paging are all server-side. The spinner shows for a first load, a type
 * change and a refresh; a search or a page change keeps the rows until the answer is in. Typing in
 * the search box waits for a short pause (250 ms) before the request goes out, and a newer request
 * cancels the one still running. Once the rows are in, the page calls
 * `GET /api/repos/security-summary?repoNames=` once, with only the names on the page, and again
 * while a scan is unfinished. A failing list request is the error state (`repo-error`) with the
 * refresh button as the retry: there is no partial state any more. Every navigating method here
 * resolves once ITS list answer is in, plus `settle()`. Several parallel tests share the stack, so
 * the unfiltered list is only ever asserted by size or through a search string unique to the test
 * (`search(runId)`), never by position.
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
   * Runs `action` and resolves once the list answer it causes is in (the one matching `query`;
   * default: any) and Angular has rendered it. The answer's `Response` is returned for tests that
   * inspect the request.
   */
  async afterListResponse(
    action: () => Promise<unknown>,
    query: ListQuery = {},
  ): Promise<Response> {
    const answer = this.page.waitForResponse((response) => isListResponse(response, query));
    // If `action` throws, nobody awaits `answer`; keep its later timeout from being unhandled.
    answer.catch(() => undefined);
    await action();
    const response = await answer;
    await this.settle();
    return response;
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
   * the list answer. A pre-selected type only comes from the dashboard's count rows
   * (`history.state`), never from a URL.
   */
  async goto(): Promise<void> {
    await this.afterListResponse(async () => {
      const profile = this.page.waitForResponse((response) => PROFILE_URL.test(response.url()));
      profile.catch(() => undefined);
      await this.page.goto('/repositories');
      await profile;
    });
    await expect(this.title).toBeVisible();
    await expect(this.spinner.root).toBeHidden();
  }

  /**
   * Types `text` into the search box and waits for the server's answer to it (the request goes out
   * 250 ms after the typing pauses, from the first page). Empty `text` clears the search.
   */
  async search(text: string): Promise<Response> {
    return this.afterListResponse(() => this.searchInput.fill(text), { q: text, page: 0 });
  }

  /** Picks a type in the toolbar's selector; the server answers with that type's first page. */
  async selectType(type: UiRepoType | 'all'): Promise<Response> {
    const option = type === 'all' ? 'all' : type.slug;
    return this.afterListResponse(
      async () => {
        await this.typeFilterToggle.click();
        await this.tid('repo-type-filter').getByTestId(`selector-option-${option}`).click();
      },
      { type, q: '', page: 0 },
    );
  }

  /**
   * Clicks refresh and waits for the reload. The reload keeps the selected type (`type`, `all` by
   * default) and drops the search and the page.
   */
  async refresh(type: UiRepoType | 'all' = 'all'): Promise<Response> {
    return this.afterListResponse(() => this.refreshButton.click(), { type, q: '', page: 0 });
  }

  /** Clicks a page number of the pager (1-based, as displayed) and waits for that page's answer. */
  async goToPage(n: number): Promise<Response> {
    return this.afterListResponse(() => this.pagination.page(n).click(), { page: n - 1 });
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
    await this.afterListResponse(() => this.dangerModal.confirm());
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
