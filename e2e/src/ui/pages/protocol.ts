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
 * Descriptor-driven page objects for the package pages of ALL nine protocols (RPS-1255). One
 * `ProtocolListPage` serves every list-like level (the package list, maven's artifact list, npm's
 * scope list, versions, docker's manifests), one `VersionDetailPage` every version detail; what
 * differs per protocol is the descriptor (`protocols/<proto>.ts`), so a scenario template written once
 * (`registerPackageScenarios(descriptor)`) runs over all of them.
 *
 * Start from `protocolPages(page, DESCRIPTORS[protocol], repoName)`:
 *
 * ```ts
 * const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
 * const list = pages.list();          // /:repo
 * await list.goto();
 * await list.expectRow(seeded);       // desktop row keyed by the package identity
 * const versions = await list.openLink(seeded, 'versions');  // -> VersionsPage
 * ```
 *
 * Selector rules (README "UI test ids"): rows and cards have different ids (`-row-` vs `-card-`), and
 * both lists are in the DOM at once, so a row is always looked up in its own container and in-row ids
 * (`row-*`) are always scoped to the row.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import type { PackageProtocol, PackageRef } from '../../seed/packages.js';
import { UiPage } from './base.js';
import { DangerModal, DesktopList, EmptyList, Pagination, Spinner, Toasts } from './components.js';
import { cargoDescriptor } from './protocols/cargo.js';
import { dockerDescriptor } from './protocols/docker.js';
import { golangDescriptor } from './protocols/golang.js';
import { helmDescriptor } from './protocols/helm.js';
import { mavenDescriptor } from './protocols/maven.js';
import { npmDescriptor } from './protocols/npm.js';
import { nugetDescriptor } from './protocols/nuget.js';
import { pypiDescriptor } from './protocols/pypi.js';
import { rubyDescriptor } from './protocols/ruby.js';
import type {
  DetailLevel,
  LevelName,
  ListLevel,
  ListLevelName,
  ProtocolDescriptor,
} from './protocols/types.js';

export type {
  DeleteAffordance,
  DetailLevel,
  LevelName,
  ListLevel,
  ListLevelName,
  ProtocolDescriptor,
} from './protocols/types.js';

/** Every protocol's descriptor. A wrong descriptor is fixed in its own `protocols/<proto>.ts`. */
export const DESCRIPTORS: Record<PackageProtocol, ProtocolDescriptor> = {
  maven: mavenDescriptor,
  npm: npmDescriptor,
  docker: dockerDescriptor,
  pypi: pypiDescriptor,
  cargo: cargoDescriptor,
  nuget: nugetDescriptor,
  helm: helmDescriptor,
  golang: golangDescriptor,
  ruby: rubyDescriptor,
};

/** The `data-testid` prefix of a list level: `pkg-list`, `pkg-sublist`, `pkg-versions`, `pkg-manifests`. */
function prefixOf(level: ListLevelName): string {
  return `pkg-${level}`;
}

/** The descriptor's level of `name`, or a clear error when the protocol has no such page. */
export function listLevelOf(descriptor: ProtocolDescriptor, name: ListLevelName): ListLevel {
  const level = descriptor.levels[name];
  if (!level) {
    throw new Error(`${descriptor.protocol} has no "${name}" page`);
  }
  return level;
}

/**
 * Any list-like page. `target` is needed for every level except `list`: the package (and, for
 * `versions`, its identity) the page belongs to. Row lookups take a `PackageRef` (a `SeededPackage`
 * is one), or a bare version/name through `PackageRef` literals.
 */
export class ProtocolListPage extends UiPage {
  readonly level: ListLevel;
  readonly prefix: string;
  readonly desktop: DesktopList;
  readonly toolbar: Locator;
  readonly searchBox: Locator;
  readonly searchInput: Locator;
  readonly sort: Locator;
  readonly refreshButton: Locator;
  readonly configureButton: Locator;
  readonly settingsButton: Locator;
  readonly browseFilesButton: Locator;
  /** Docker's install bar above the tag and manifest lists. */
  readonly installBar: Locator;
  readonly installBarText: Locator;
  /** The page's own cards wrapper (mobile list). */
  readonly cards: Locator;
  readonly pagination: Pagination;
  readonly emptyList: EmptyList;
  readonly spinner: Spinner;
  readonly error: Locator;
  readonly errorMessage: Locator;
  readonly toasts: Toasts;
  readonly dangerModal: DangerModal;

  constructor(
    page: Page,
    readonly descriptor: ProtocolDescriptor,
    readonly levelName: ListLevelName,
    readonly repoName: string,
    readonly target?: PackageRef,
  ) {
    super(page);
    this.level = listLevelOf(descriptor, levelName);
    this.prefix = prefixOf(levelName);
    this.desktop = new DesktopList(page, this.prefix);
    this.toolbar = this.tid('pkg-toolbar');
    this.searchBox = this.tid('pkg-search');
    this.searchInput = this.tid('search-input', this.searchBox);
    this.sort = this.tid('pkg-sort');
    this.refreshButton = this.tid('pkg-refresh');
    this.configureButton = this.tid('pkg-configure');
    this.settingsButton = this.tid('pkg-settings');
    this.browseFilesButton = this.tid('pkg-browse-files');
    this.installBar = this.tid('pkg-install-snippet');
    this.installBarText = this.tid('pkg-install-snippet-text');
    this.cards = this.tid(`${this.prefix}-cards`);
    this.pagination = new Pagination(page);
    this.emptyList = new EmptyList(page);
    this.spinner = new Spinner(page);
    this.error = this.tid('pkg-error');
    this.errorMessage = this.tid('pkg-error-message');
    this.toasts = new Toasts(page);
    this.dangerModal = new DangerModal(page);
  }

  /** The route of this page, including any query string. */
  path(): string {
    return this.level.path(this.repoName, this.target);
  }

  async goto(): Promise<void> {
    await this.page.goto(this.path());
    await this.expectLoaded();
  }

  /**
   * The page has rendered its data: the toolbar is there, the spinner is gone, and one of the desktop
   * table, the mobile cards, the empty state or the error block shows. Never a fixed sleep.
   */
  async expectLoaded(): Promise<void> {
    await expect(this.toolbar).toBeVisible();
    await expect(this.spinner.root).toBeHidden();
    // Both the desktop table and the mobile cards are in the DOM; whichever the viewport shows is the one.
    await expect(
      this.desktop.container
        .or(this.cards)
        .or(this.emptyList.root)
        .or(this.error)
        .filter({ visible: true })
        .first(),
    ).toBeVisible();
  }

  /** The raw key of `target`'s row at this level. */
  keyOf(target: PackageRef): string {
    return this.level.rowKey(target);
  }

  /** The DESKTOP row of `target`. */
  row(target: PackageRef): Locator {
    return this.desktop.row(this.keyOf(target));
  }

  /** The MOBILE card of `target` (only in the DOM at a mobile viewport's width; see `level.mobileCards`). */
  card(target: PackageRef): Locator {
    return this.cards.getByTestId(`${this.prefix}-card-${this.keyOf(target)}`);
  }

  /** Every desktop row. */
  rows(): Locator {
    return this.desktop.rows();
  }

  /** An in-row element of `target`'s desktop row (`row-name`, `row-updated`, ...). */
  inRow(target: PackageRef, id: string): Locator {
    return this.desktop.inRow(this.keyOf(target), id);
  }

  async expectRow(target: PackageRef): Promise<void> {
    await expect(this.row(target)).toBeVisible();
  }

  async expectNoRow(target: PackageRef): Promise<void> {
    await expect(this.row(target)).toHaveCount(0);
  }

  /** Types into the search box; the list reloads on its own, so assert on rows afterwards. */
  async search(text: string): Promise<void> {
    if (!this.level.search) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} page has no search box`);
    }
    await this.searchInput.fill(text);
  }

  /** Searches for the term that finds `target` at this level (`level.search.term`, see its doc). */
  async searchFor(target: PackageRef): Promise<void> {
    if (!this.level.search) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} page has no search box`);
    }
    await this.search(this.level.search.term(target));
  }

  /**
   * Picks a sort option by name (`Newest`, `Oldest`, `Name (A-Z)`, ...). The sort menu stays OPEN after
   * a choice (it only closes on an outside click), so this opens it only when it is closed: calling it
   * twice in a row must not toggle it shut.
   */
  async sortBy(option: string): Promise<void> {
    if (!this.level.sort) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} page has no sort`);
    }
    const menu = this.tid('sort-selector-menu', this.sort);
    if (!(await menu.isVisible())) {
      await this.tid('sort-selector-toggle', this.sort).click();
      await expect(menu).toBeVisible();
    }
    await this.tid(`sort-option-${option}`, this.sort).click();
    // The toggle shows the chosen option's name.
    await expect(this.tid('sort-selector-toggle', this.sort)).toContainText(option);
  }

  async refresh(): Promise<void> {
    await this.refreshButton.click();
  }

  /**
   * Clicks the row itself (the whole row is a `role=button` div, not a link) and returns the page the
   * descriptor says it opens. The row's `rowOpens` must be set.
   */
  async openRow(target: PackageRef): Promise<ProtocolPage> {
    const opens = this.level.rowOpens;
    if (!opens) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} rows are not clickable`);
    }
    await this.row(target).click();
    return this.pageFor(opens, target);
  }

  /** Clicks an in-row link (`level.rowLinks[to]`), e.g. `row-package-link`, and returns that page. */
  async openLink<L extends LevelName>(target: PackageRef, to: L): Promise<PageOfLevel<L>> {
    const linkId = this.level.rowLinks[to];
    if (!linkId) {
      throw new Error(
        `the ${this.descriptor.protocol} ${this.levelName} rows have no link to "${to}"`,
      );
    }
    await this.inRow(target, linkId).click();
    return this.pageFor(to, target) as PageOfLevel<L>;
  }

  /** Opens the row's menu and returns the open `dropdown-menu`. */
  async openRowMenu(target: PackageRef): Promise<Locator> {
    return this.desktop.openRowMenu(this.keyOf(target));
  }

  /**
   * Opens the row menu, clicks Delete, and returns the open danger modal after checking its title is
   * the descriptor's. Confirm or cancel it yourself.
   */
  async openDeleteDialog(target: PackageRef): Promise<DangerModal> {
    const affordance = this.level.rowDelete;
    if (!affordance) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} rows cannot be deleted`);
    }
    const menu = await this.openRowMenu(target);
    // Not a mouse click: the menu opens over the NEXT row, and that row's grid (its `fade-in-down`
    // class keeps `animation: ... forwards`, so it is a stacking context of its own) paints above the
    // menu, so a real click on the middle of "Delete" lands on the next row (and Playwright refuses it:
    // "<div class=grid ...> intercepts pointer events"). The button's own click handler is what the
    // user's click would reach, so dispatch that. (RPS-1299, not fixed here.)
    await this.tid('row-delete', menu).dispatchEvent('click');
    await this.dangerModal.expectOpen(affordance.dialogTitle);
    return this.dangerModal;
  }

  /** The full row delete: menu, Delete, confirm, then the success toast (asserted at once: they last 3 s). */
  async deleteRow(target: PackageRef): Promise<void> {
    const affordance = this.level.rowDelete;
    if (!affordance) {
      throw new Error(`the ${this.descriptor.protocol} ${this.levelName} rows cannot be deleted`);
    }
    const modal = await this.openDeleteDialog(target);
    await modal.confirm();
    await this.toasts.expectSuccess(affordance.successToast);
  }

  private pageFor(level: LevelName, target: PackageRef): ProtocolPage {
    return pageOf(this.page, this.descriptor, level, this.repoName, target);
  }
}

/** The versions page (a `ProtocolListPage` on the `versions` level). */
export class VersionsPage extends ProtocolListPage {
  constructor(page: Page, descriptor: ProtocolDescriptor, repoName: string, target: PackageRef) {
    super(page, descriptor, 'versions', repoName, target);
  }
}

/** One version's detail page (docker: the tag detail). */
export class VersionDetailPage extends UiPage {
  readonly detail: DetailLevel;
  readonly root: Locator;
  readonly install: Locator;
  readonly installText: Locator;
  readonly copyButton: Locator;
  readonly name: Locator;
  readonly deleteButton: Locator;
  readonly readme: Locator;
  readonly error: Locator;
  readonly errorMessage: Locator;
  readonly spinner: Spinner;
  readonly toasts: Toasts;
  readonly dangerModal: DangerModal;

  constructor(
    page: Page,
    readonly descriptor: ProtocolDescriptor,
    readonly repoName: string,
    readonly target: PackageRef,
  ) {
    super(page);
    this.detail = descriptor.levels.detail;
    this.root = this.tid('pkg-detail');
    this.install = this.tid('pkg-detail-install');
    this.installText = this.tid('pkg-detail-install-text');
    this.copyButton = this.tid('copy-button', this.install);
    this.name = this.tid('pkg-detail-name');
    this.deleteButton = this.tid('pkg-detail-delete');
    this.readme = this.tid('readme');
    this.error = this.tid('pkg-error');
    this.errorMessage = this.tid('pkg-error-message');
    this.spinner = new Spinner(page);
    this.toasts = new Toasts(page);
    this.dangerModal = new DangerModal(page);
  }

  path(): string {
    return this.detail.path(this.repoName, this.target);
  }

  async goto(): Promise<void> {
    await this.page.goto(this.path());
    await this.expectLoaded();
  }

  /** The spinner is gone and either the detail or the error block shows. */
  async expectLoaded(): Promise<void> {
    await expect(this.spinner.root).toBeHidden();
    await expect(this.root.or(this.error).first()).toBeVisible();
  }

  /** A `pkg-detail-snippet-<slug>` block (`descriptor.levels.detail.snippets`). */
  snippet(slug: string): Locator {
    return this.tid(`pkg-detail-snippet-${slug}`);
  }

  /** A protocol-specific `pkg-detail-*` id (`descriptor.levels.detail.extraIds`). */
  byId(id: string): Locator {
    return this.tid(id);
  }

  /** Opens the Delete dialog (managers only) and returns it after checking its title. */
  async openDeleteDialog(): Promise<DangerModal> {
    const affordance = this.detail.delete;
    if (!affordance) {
      throw new Error(`the ${this.descriptor.protocol} detail page cannot delete`);
    }
    await expect(this.deleteButton).toBeVisible();
    // Playwright's own hit-target check refuses this button on every detail page ("<app-...-version-
    // detail> intercepts pointer events": the page's custom-element host is reported above it by
    // `elementsFromPoint`), although a real mouse click at the same point works. `force` skips that
    // check only; the click is still a real mouse click on the button's centre. (RPS-1288 item 1, not fixed here.)
    await this.deleteButton.click({ force: true });
    await this.dangerModal.expectOpen(affordance.dialogTitle);
    return this.dangerModal;
  }

  /** The full delete: button, confirm, success toast (asserted at once: toasts last 3 s). */
  async delete(): Promise<void> {
    const affordance = this.detail.delete;
    if (!affordance) {
      throw new Error(`the ${this.descriptor.protocol} detail page cannot delete`);
    }
    const modal = await this.openDeleteDialog();
    await modal.confirm();
    await this.toasts.expectSuccess(affordance.successToast);
  }
}

export type ProtocolPage = ProtocolListPage | VersionDetailPage;

/** The page object class of a level: detail, versions, or any other list-like page. */
export type PageOfLevel<L extends LevelName> = L extends 'detail'
  ? VersionDetailPage
  : L extends 'versions'
    ? VersionsPage
    : ProtocolListPage;

/** The page object of any level of a package. */
export function pageOf(
  page: Page,
  descriptor: ProtocolDescriptor,
  level: LevelName,
  repoName: string,
  target?: PackageRef,
): ProtocolPage {
  if (level === 'detail') {
    if (!target) {
      throw new Error('the detail page needs a target');
    }
    return new VersionDetailPage(page, descriptor, repoName, target);
  }
  if (level === 'versions') {
    if (!target) {
      throw new Error('the versions page needs a target');
    }
    return new VersionsPage(page, descriptor, repoName, target);
  }
  return new ProtocolListPage(page, descriptor, level, repoName, target);
}

/** A factory bound to one page and repo, so a spec never repeats them. */
export class ProtocolPages {
  constructor(
    readonly page: Page,
    readonly descriptor: ProtocolDescriptor,
    readonly repoName: string,
  ) {}

  list(): ProtocolListPage {
    return new ProtocolListPage(this.page, this.descriptor, 'list', this.repoName);
  }

  /** maven's artifact list of a group, npm's package list of a scope. */
  sublist(target: PackageRef): ProtocolListPage {
    return new ProtocolListPage(this.page, this.descriptor, 'sublist', this.repoName, target);
  }

  versions(target: PackageRef): VersionsPage {
    return new VersionsPage(this.page, this.descriptor, this.repoName, target);
  }

  /** docker only: the manifests of one tag. */
  manifests(target: PackageRef): ProtocolListPage {
    return new ProtocolListPage(this.page, this.descriptor, 'manifests', this.repoName, target);
  }

  detail(target: PackageRef): VersionDetailPage {
    return new VersionDetailPage(this.page, this.descriptor, this.repoName, target);
  }

  /** A route that is not a level (maven's `browser`), as a path. */
  extraPath(name: string): string {
    const build = this.descriptor.extraPaths[name];
    if (!build) {
      throw new Error(`${this.descriptor.protocol} has no extra route "${name}"`);
    }
    return build(this.repoName);
  }
}

/** Shorthand: `protocolPages(page, DESCRIPTORS.maven, repo.name)`. */
export function protocolPages(
  page: Page,
  descriptor: ProtocolDescriptor,
  repoName: string,
): ProtocolPages {
  return new ProtocolPages(page, descriptor, repoName);
}
