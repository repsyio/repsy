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
 * Page objects for the security-scanning UI (RPS-1259): the `/security` page, the scan section of a
 * version detail page, the security badges of the lists and their modals, and the Vulnerability
 * Scanning section of the repository settings. All of it only exists while
 * `GET /api/security/supported-repo-types` lists the repo's type, so a spec stubs that first
 * (`../security-stubs.ts`).
 *
 * The badges are shared components with fixed ids (`security-badge` around a `severity-badge`); a row
 * of a list carries the host id `row-security`, so a badge is always reached through its row.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import type { Severity } from '../security-stubs.js';
import { UiPage } from './base.js';
import { Pagination } from './components.js';
import { Shell } from './shell.js';

/** A `severity-badge` of `severity` inside `scope` (the badge carries `data-severity`). */
export function severityBadge(scope: Page | Locator, severity: Severity): Locator {
  return scope.locator(`[data-testid="severity-badge"][data-severity="${severity}"]`);
}

/** The clickable `security-badge` a list row or card renders around its severity badge. */
export function securityBadgeIn(row: Locator): Locator {
  return row.getByTestId('row-security').getByTestId('security-badge');
}

/** The text of a badge: `Critical`, `High`, ..., `Clean`, `Scanning...`, `Scan failed` (+ a count). */
export function badgeText(badge: Locator): Locator {
  return badge.getByTestId('severity-badge');
}

/** `SelectorComponent` (`selector-toggle` + `selector-option-<raw value>`), scoped by its host id. */
export class SelectorControl {
  readonly toggle: Locator;
  readonly menu: Locator;

  constructor(readonly root: Locator) {
    this.toggle = root.getByTestId('selector-toggle');
    this.menu = root.getByTestId('selector-menu');
  }

  option(value: string): Locator {
    return this.menu.getByTestId(`selector-option-${value}`);
  }

  /** Opens the menu and picks `value`. */
  async choose(value: string): Promise<void> {
    await this.toggle.click();
    await expect(this.menu).toBeVisible();
    await this.option(value).click();
    await expect(this.menu).toBeHidden();
  }

  /** The raw option values the open menu offers, in order. */
  async options(): Promise<string[]> {
    await this.toggle.click();
    await expect(this.menu).toBeVisible();
    const ids = await this.menu
      .locator('[data-testid^="selector-option-"]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid') ?? ''));
    await this.toggle.click();
    await expect(this.menu).toBeHidden();
    return ids.map((id) => id.replace('selector-option-', ''));
  }
}

/**
 * `/security` (admin only): the Severity Distribution card (doughnut + one badge per severity with
 * findings, or the "No known vulnerabilities found." line), the filters (repository search, severity,
 * repo type, refresh) and the list of scans (10 a page). A row opens the scanned version's page at
 * `#security`.
 */
export class SecurityPage extends UiPage {
  readonly shell: Shell;
  readonly title: Locator;
  readonly distribution: Locator;
  readonly chart: Locator;
  readonly chartCanvas: Locator;
  readonly distributionEmpty: Locator;
  readonly searchInput: Locator;
  readonly severityFilter: SelectorControl;
  readonly typeFilter: SelectorControl;
  readonly refreshButton: Locator;
  readonly list: Locator;
  readonly empty: Locator;
  readonly pagination: Pagination;
  /** Every spinner on the page (the card's and the list's). */
  readonly spinners: Locator;

  constructor(page: Page) {
    super(page);
    this.shell = new Shell(page);
    this.title = this.tid('security-title');
    this.distribution = this.tid('security-distribution');
    this.chart = this.tid('security-chart');
    this.chartCanvas = this.chart.locator('canvas');
    this.distributionEmpty = this.tid('security-distribution-empty');
    this.searchInput = this.tid('security-search').getByTestId('search-input');
    this.severityFilter = new SelectorControl(this.tid('security-severity-filter'));
    this.typeFilter = new SelectorControl(this.tid('security-type-filter'));
    this.refreshButton = this.tid('security-refresh');
    this.list = this.tid('security-scan-list');
    this.empty = this.tid('security-empty');
    this.pagination = new Pagination(page);
    this.spinners = page.getByTestId('spinner');
  }

  get path(): string {
    return '/security';
  }

  /** Opens `/security` and waits until the card and the list are both out of their spinner. */
  async goto(): Promise<void> {
    await this.page.goto(this.path);
    await this.expectLoaded();
  }

  async expectLoaded(): Promise<void> {
    await expect(this.title).toBeVisible();
    await expect(this.spinners).toHaveCount(0);
    await expect(this.list.or(this.empty)).toBeVisible();
  }

  /** One scan row, by the scan id (`security-scan-row-<id>`). */
  row(id: string): Locator {
    return this.tid(`security-scan-row-${id}`);
  }

  rows(): Locator {
    return this.list.locator('[data-testid^="security-scan-row-"]');
  }

  /** `<repo> / <artifact>` of a row. */
  artifactOf(id: string): Locator {
    return this.row(id).getByTestId('row-artifact');
  }

  /** `<REPOTYPE> · <version>` of a row. */
  metaOf(id: string): Locator {
    return this.row(id).getByTestId('row-meta');
  }

  /** The distribution card's badge of one severity (`Critical 2`), which exists only when it has findings. */
  distributionBadge(severity: Severity): Locator {
    return severityBadge(this.distribution, severity);
  }

  /** Types into the repository search (it fetches on every input event). */
  async search(text: string): Promise<void> {
    await this.searchInput.fill(text);
  }

  /** True once the canvas holds at least one drawn (non-transparent) pixel: Chart.js has rendered. */
  async chartHasPixels(): Promise<boolean> {
    return this.chartCanvas.evaluate((canvas: HTMLCanvasElement) => {
      const context = canvas.getContext('2d');
      if (!context || canvas.width === 0 || canvas.height === 0) {
        return false;
      }
      const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
      for (let alpha = 3; alpha < data.length; alpha += 4) {
        if (data[alpha] !== 0) {
          return true;
        }
      }
      return false;
    });
  }
}

/**
 * `<app-security-scan-section>` (`#security`) of a version detail page: a collapsible section with a
 * one-line summary (badge and finding count, `Clean`, `Not scanned yet`, or a note like `Rescanning...`),
 * and, once expanded, the newest scan's status, its severity badges, the findings table (10 a page,
 * sortable by severity), and the scan history (5 a page). Owners of the repo can `Scan Now`/`Re-scan`.
 */
export class ScanSection {
  readonly root: Locator;
  readonly toggle: Locator;
  readonly neverScanned: Locator;
  readonly findingsCount: Locator;
  readonly clean: Locator;
  readonly rescanNote: Locator;
  /** The reason a failed scan failed (RPS-1339), only while the shown scan is FAILED. */
  readonly failureReason: Locator;
  readonly status: Locator;
  readonly pollingIndicator: Locator;
  readonly emptyBody: Locator;
  readonly rescanButton: Locator;
  readonly scanNowButton: Locator;
  readonly findingsTable: Locator;
  readonly findingsCards: Locator;
  readonly sortBySeverity: Locator;
  readonly findingsPagination: Pagination;
  readonly history: Locator;
  readonly historyPagination: Pagination;
  /** The "See Security Details" link the breadcrumb bar shows (host id `breadcrumb-security-link`). */
  readonly detailsLink: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('scan-section');
    this.toggle = this.root.getByTestId('scan-section-toggle');
    this.neverScanned = this.root.getByTestId('scan-section-never-scanned');
    this.findingsCount = this.root.getByTestId('scan-section-findings-count');
    this.clean = this.root.getByTestId('scan-section-clean');
    this.rescanNote = this.root.getByTestId('rescan-note');
    this.failureReason = this.root.getByTestId('scan-failure-reason-text');
    this.status = this.root.getByTestId('scan-section-status');
    this.pollingIndicator = this.root.getByTestId('status-polling-indicator');
    this.emptyBody = this.root.getByTestId('scan-section-empty');
    this.rescanButton = this.root.getByTestId('scan-section-rescan');
    this.scanNowButton = this.root.getByTestId('scan-section-scan-now');
    this.findingsTable = this.root.getByTestId('scan-findings-table');
    this.findingsCards = this.root.getByTestId('scan-findings-cards');
    this.sortBySeverity = this.root.getByTestId('scan-findings-sort-severity');
    this.findingsPagination = new Pagination(this.root.getByTestId('scan-findings-pagination'));
    this.history = this.root.getByTestId('scan-history');
    this.historyPagination = new Pagination(this.root.getByTestId('scan-history-pagination'));
    this.detailsLink = page
      .getByTestId('breadcrumb-security-link')
      .getByTestId('security-details-link');
  }

  /** The summary badge in the section's header (compact, next to `Security`). */
  headerBadge(): Locator {
    return this.toggle.getByTestId('severity-badge');
  }

  /**
   * The severity-with-count badge of the expanded body (`High 2`): not the compact one in the header
   * and not the per-finding ones inside the findings table and cards.
   */
  bodyBadge(severity: Severity): Locator {
    const outside = ['scan-section-toggle', 'scan-findings-table', 'scan-findings-cards']
      .map((id) => `not(ancestor::*[@data-testid="${id}"])`)
      .join(' and ');
    return severityBadge(this.root, severity).locator(`xpath=self::*[${outside}]`);
  }

  /** A finding row of the desktop table, by finding id. */
  findingRow(id: string): Locator {
    return this.findingsTable.getByTestId(`scan-finding-row-${id}`);
  }

  findingRows(): Locator {
    return this.findingsTable.locator('[data-testid^="scan-finding-row-"]');
  }

  /** A history entry (`scan-history-item-<scan id>`). */
  historyItem(id: string): Locator {
    return this.history.getByTestId(`scan-history-item-${id}`);
  }

  /** The body is open when it shows either the scan status or the "no scans yet" line. */
  async expectExpanded(): Promise<void> {
    await expect(this.status.or(this.emptyBody)).toBeVisible();
  }

  async expectCollapsed(): Promise<void> {
    await expect(this.status).toHaveCount(0);
    await expect(this.emptyBody).toHaveCount(0);
  }

  /** Opens the body if it is closed. */
  async expand(): Promise<void> {
    if ((await this.status.count()) === 0 && (await this.emptyBody.count()) === 0) {
      await this.toggle.click();
    }
    await this.expectExpanded();
  }
}

/**
 * The version-security badge of a version row, a package's badge of a package row and the repo
 * badge of a repository row all open a modal: `repo-security-modal`, `package-security-modal` or
 * `version-security-modal`. They share this shape; `kind` picks the id prefix.
 */
export class SecurityModal {
  readonly root: Locator;
  readonly backdrop: Locator;
  readonly closeButton: Locator;
  readonly breakdown: Locator;
  /** The reason the newest scan failed (RPS-1339); the version modal only. */
  readonly failureReason: Locator;
  readonly breakdownEmpty: Locator;
  readonly viewAll: Locator;
  readonly viewDetails: Locator;

  constructor(
    private readonly page: Page,
    readonly kind: 'repo' | 'package' | 'version',
  ) {
    const prefix = `${kind}-security-modal`;
    this.root = page.getByTestId(prefix);
    this.backdrop = page.getByTestId(`${prefix}-backdrop`);
    this.closeButton = page.getByTestId(`${prefix}-close`);
    this.breakdown = this.root.getByTestId('severity-breakdown');
    this.failureReason = this.root.getByTestId('scan-failure-reason-text');
    this.breakdownEmpty = this.root.getByTestId('severity-breakdown-empty');
    this.viewAll = page.getByTestId('package-security-modal-view-all');
    this.viewDetails = page.getByTestId('version-security-modal-view-details');
  }

  /** A "Recently Scanned Versions" row: repo modal keys it `<artifact>@<version>`, package modal `<version>`. */
  recentScan(key: string): Locator {
    return this.root.getByTestId(`security-recent-scan-${key}`);
  }

  async expectOpen(): Promise<void> {
    await expect(this.root).toBeVisible();
  }

  /** The X. NOTE it also reaches the row the modal sits in (see the pinned tests in badges.spec.ts). */
  async close(): Promise<void> {
    await this.closeButton.click();
    await expect(this.root).toBeHidden();
  }

  /**
   * A click on the backdrop, away from the dialog in the middle of the screen. Unlike the X it stops
   * the event, so it closes the modal without also opening the row underneath.
   */
  async closeViaBackdrop(): Promise<void> {
    await this.backdrop.click({ position: { x: 5, y: 5 } });
    await expect(this.root).toBeHidden();
  }
}

/**
 * The Vulnerability Scanning section of `/:repo/settings` (`settings-scanning`): a section that only
 * renders when the repo's type has a scanner, with an Allow/Deny toggle for "scan every push".
 * Composed here, not in `repo-settings/toggles.ts`, because it exists only behind the stub.
 */
export class VulnerabilityScanningSection {
  readonly root: Locator;
  readonly toggle: Locator;
  /** The `role="switch"` checkbox; read it with `toBeChecked()`, flip it through `label`. */
  readonly input: Locator;
  readonly label: Locator;

  constructor(page: Page) {
    this.root = page.getByTestId('settings-scanning');
    this.toggle = this.root.getByTestId('settings-scanning-toggle');
    this.input = this.toggle.getByTestId('toggle-input');
    this.label = this.toggle.getByTestId('toggle-label');
  }

  /** Flips the switch through its label (the checkbox itself is `sr-only` under the drawn switch). */
  async flip(): Promise<void> {
    await this.label.click();
  }

  async expectChecked(checked: boolean): Promise<void> {
    if (checked) {
      await expect(this.input).toBeChecked();
    } else {
      await expect(this.input).not.toBeChecked();
    }
  }
}
