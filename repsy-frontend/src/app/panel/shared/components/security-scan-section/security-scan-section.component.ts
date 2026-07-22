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

import { CommonModule, ViewportScroller } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { finalize, map } from 'rxjs/operators';

import {
  ScanOverview,
  ScanStatus,
  Severity,
  VulnerabilityFindingInfo,
  VulnerabilityScanControllerService,
  VulnerabilityScanDetail,
  VulnerabilityScanInfo,
} from '../../../../../generated/api';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { pollUntilTerminal } from '../../util/poll-until-terminal.util';
import { EllipsisPipe } from '../ellipsis/ellipsis.pipe';
import { PaginationComponent } from '../pagination/pagination.component';
import { SeverityBadgeComponent } from '../severity-badge/severity-badge.component';
import { StatusPollingIndicatorComponent } from '../status-polling-indicator/status-polling-indicator.component';
import { ToastService } from '../toast/toast.service';
import { TooltipComponent } from '../tooltip/tooltip.component';

const PAGE_SIZE = 5;
const FINDINGS_PAGE_SIZE = 10;
const SECURITY_FRAGMENT = 'security';
const POLL_INTERVAL_MS = 3000;
const TERMINAL_STATUSES: ScanStatus[] = [ScanStatus.Completed, ScanStatus.Failed];
/**
 * Content above this section (syntax-highlighted manifest/POM blocks, images, etc.) can still be
 * reflowing asynchronously after {@link isSupported$} resolves and this element first renders, which
 * would shift it further down the page than a single immediate scroll attempt accounts for — so
 * {@link scrollToSecurityWithRetries} re-issues the scroll a few more times as that settles, instead
 * of guessing one "safe enough" delay.
 */
const SCROLL_RETRY_DELAYS_MS = [0, 300, 800];

@Component({
  selector: 'app-security-scan-section',
  standalone: true,
  imports: [
    CommonModule,
    SpinnerComponent,
    PaginationComponent,
    SeverityBadgeComponent,
    TooltipComponent,
    EllipsisPipe,
    StatusPollingIndicatorComponent,
  ],
  templateUrl: './security-scan-section.component.html',
})
export class SecurityScanSectionComponent implements OnInit, OnChanges, OnDestroy {
  @Input({ required: true }) public repoType: string;
  @Input({ required: true }) public repoName: string;
  @Input({ required: true }) public artifactName: string;
  @Input({ required: true }) public artifactVersion: string;
  @Input() public canTriggerScan = false;

  public isSupported$: Observable<boolean>;
  public expanded = false;
  public loading = true;
  public triggering = false;
  public neverScanned = false;
  public overview: ScanOverview | null = null;
  public scans: VulnerabilityScanInfo[] = [];
  public selectedScan: VulnerabilityScanDetail | null = null;
  public pageNum = 0;
  public totalPages = 0;

  public findings: VulnerabilityFindingInfo[] = [];
  public loadingFindings = false;
  public findingsPageNum = 0;
  public findingsTotalPages = 0;
  public findingsTotalCount = 0;
  public findingsSortDirection: 'ASC' | 'DESC' = 'ASC';

  protected readonly ScanStatus = ScanStatus;

  private readonly fragmentChanges$: Subscription;
  private isSupportedSub: Subscription | null = null;
  private pollingSub: Subscription | null = null;

  constructor(
    private readonly vulnerabilityScanControllerService: VulnerabilityScanControllerService,
    private readonly toastService: ToastService,
    private readonly route: ActivatedRoute,
    private readonly securityScanSupportService: SecurityScanSupportService,
    private readonly viewportScroller: ViewportScroller,
  ) {
    // Arriving from an external link (Security page, version-list, repo-security modal) always
    // carries #security — those links exist specifically so a user who already cares about
    // vulnerabilities lands with the section open, not just scrolled to a collapsed header.
    this.fragmentChanges$ = this.route.fragment.subscribe((fragment) => {
      if (fragment === SECURITY_FRAGMENT) {
        this.expanded = true;
      }
    });
  }

  public ngOnInit(): void {
    this.isSupported$ = this.securityScanSupportService.isSupported(this.repoType);

    // Arriving on a fresh page load (cross-page nav from the Security page, a modal's "recently
    // scanned" link, etc.) races Angular's own one-shot, native fragment scroll: it fires on
    // `NavigationEnd`, before `isSupported$` (an HTTP round trip) resolves and this `#security`
    // element even exists in the DOM — so it silently scrolls nowhere. Once support is confirmed
    // (this element is about to render), retry the scroll ourselves (see
    // scrollToSecurityWithRetries). A same-page fragment change (e.g. the "See Security Details"
    // button) never hits this: the element already exists, so Angular's own native scroll already
    // succeeds and this never needs to fire again for that navigation.
    this.isSupportedSub = this.isSupported$.subscribe((isSupported) => {
      if (isSupported && this.route.snapshot.fragment === SECURITY_FRAGMENT) {
        this.scrollToSecurityWithRetries();
      }
    });
  }

  public ngOnDestroy(): void {
    this.fragmentChanges$.unsubscribe();
    this.isSupportedSub?.unsubscribe();
    this.stopPolling();
  }

  private scrollToSecurityWithRetries(): void {
    for (const delay of SCROLL_RETRY_DELAYS_MS) {
      setTimeout(() => this.viewportScroller.scrollToAnchor(SECURITY_FRAGMENT), delay);
    }
  }

  public ngOnChanges(changes: SimpleChanges): void {
    const coordinatesChanged = changes['repoName'] || changes['artifactName'] || changes['artifactVersion'];

    if (coordinatesChanged && this.repoName && this.artifactName && this.artifactVersion) {
      this.pageNum = 0;
      this.refresh();
    }
  }

  public toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  /**
   * Drives ONLY the collapsed header's quick-glance badge — the latest/active scan's own worst
   * severity, independent of whichever scan {@link selectedScan} currently points to. The expanded
   * panel's own severity summary uses {@link selectedScanSeverityCounts} instead.
   */
  public get worstSeverity(): Severity | null {
    if (!this.overview) {
      return null;
    }
    if ((this.overview.criticalCount ?? 0) > 0) {
      return Severity.Critical;
    }
    if ((this.overview.highCount ?? 0) > 0) {
      return Severity.High;
    }
    if ((this.overview.mediumCount ?? 0) > 0) {
      return Severity.Medium;
    }
    if ((this.overview.lowCount ?? 0) > 0) {
      return Severity.Low;
    }
    if ((this.overview.unknownCount ?? 0) > 0) {
      return Severity.Unknown;
    }
    return null;
  }

  /**
   * The expanded panel's status/severity summary always describes {@link selectedScan} — the scan
   * the user actually clicked into (Scan History row, or whatever loaded by default) — never {@link
   * overview}, which is strictly the latest/active scan's own aggregate, used only for the collapsed
   * header badge and for picking which scan {@link syncPolling} targets. When the selected scan IS
   * the overview's scan (the common case: {@link loadOverview} loads it by default, and it's the
   * only scan a fresh page load ever selects), {@link overview} already carries real, scan-wide
   * totals from {@code GET .../scan-overview} and is used directly. There is no per-scan aggregate
   * endpoint for a scan other than the latest, so once the user picks a different (older) scan from
   * Scan History, this necessarily falls back to counting {@link findings} — only the currently
   * loaded findings page, not the full set.
   */
  public get selectedScanSeverityCounts(): {
    criticalCount: number;
    highCount: number;
    mediumCount: number;
    lowCount: number;
    unknownCount: number;
  } {
    if (this.overview && this.selectedScan?.id === this.overview.scanId) {
      return {
        criticalCount: this.overview.criticalCount ?? 0,
        highCount: this.overview.highCount ?? 0,
        mediumCount: this.overview.mediumCount ?? 0,
        lowCount: this.overview.lowCount ?? 0,
        unknownCount: this.overview.unknownCount ?? 0,
      };
    }

    return {
      criticalCount: this.countBySeverity(Severity.Critical),
      highCount: this.countBySeverity(Severity.High),
      mediumCount: this.countBySeverity(Severity.Medium),
      lowCount: this.countBySeverity(Severity.Low),
      unknownCount: this.countBySeverity(Severity.Unknown),
    };
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.loadScans();
  }

  public selectScan(scanId: string): void {
    this.loadScanDetail(scanId);
  }

  public loadFindingsPage(pageNum: number): void {
    this.findingsPageNum = pageNum;

    if (this.selectedScan?.id) {
      this.loadFindings(this.selectedScan.id);
    }
  }

  public toggleFindingsSort(): void {
    this.findingsSortDirection = this.findingsSortDirection === 'ASC' ? 'DESC' : 'ASC';
    this.findingsPageNum = 0;

    if (this.selectedScan?.id) {
      this.loadFindings(this.selectedScan.id);
    }
  }

  public triggerScan(): void {
    this.triggering = true;
    this.vulnerabilityScanControllerService
      .triggerVulnerabilityScan(this.artifactName, this.artifactVersion, this.repoName)
      .pipe(
        finalize(() => {
          this.triggering = false;
        }),
      )
      .subscribe({
        next: () => {
          this.toastService.show('Vulnerability scan triggered', 'success');
          this.pageNum = 0;
          this.refresh();
        },
        error: () => {},
      });
  }

  public get canTrigger(): boolean {
    if (this.neverScanned || this.triggering) {
      return false;
    }

    return this.overview?.status !== ScanStatus.Pending && this.overview?.status !== ScanStatus.Running;
  }

  /**
   * {@code GET .../scan-overview} 404s ({@code artifactVersionNeverScanned}) when this artifact
   * version has no scan history yet, and the app's global HTTP error interceptor toasts every
   * caught error unconditionally — there is no per-request opt-out for it. Rather than add one just
   * for this, {@code listVulnerabilityScans} is checked first: it never 404s (an empty page is a
   * valid, non-error result), so the overview/detail calls — the only ones that can 404 for this
   * reason — are simply skipped once we already know there is nothing to show.
   */
  private refresh(): void {
    this.loading = true;
    this.overview = null;
    this.selectedScan = null;
    this.stopPolling();

    this.vulnerabilityScanControllerService
      .listVulnerabilityScans(this.artifactName, this.artifactVersion, { page: this.pageNum, size: PAGE_SIZE }, this.repoName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (response) => {
          this.scans = response.data?.content ?? [];
          this.totalPages = response.data?.page?.totalPages ?? 0;
          this.neverScanned = this.scans.length === 0 && this.pageNum === 0;

          if (!this.neverScanned) {
            this.loadOverview();
          }
        },
        error: () => {},
      });
  }

  private loadScans(): void {
    this.loading = true;

    this.vulnerabilityScanControllerService
      .listVulnerabilityScans(this.artifactName, this.artifactVersion, { page: this.pageNum, size: PAGE_SIZE }, this.repoName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (response) => {
          this.scans = response.data?.content ?? [];
          this.totalPages = response.data?.page?.totalPages ?? 0;
        },
        error: () => {},
      });
  }

  private loadOverview(): void {
    this.vulnerabilityScanControllerService
      .getScanOverview(this.artifactName, this.artifactVersion, this.repoName)
      .subscribe({
        next: (response) => {
          this.overview = response.data ?? null;

          if (this.overview?.scanId) {
            this.loadScanDetail(this.overview.scanId);
          }

          this.syncPolling();
        },
        error: () => {},
      });
  }

  private loadScanDetail(scanId: string): void {
    this.vulnerabilityScanControllerService.getVulnerabilityScan(scanId, this.repoName).subscribe({
      next: (response) => {
        this.selectedScan = response.data ?? null;
        this.findingsPageNum = 0;
        this.loadFindings(scanId);
      },
      error: () => {},
    });
  }

  private loadFindings(scanId: string): void {
    this.loadingFindings = true;

    const sort = [`severity,${this.findingsSortDirection}`];

    this.vulnerabilityScanControllerService
      .getVulnerabilityScanFindings(
        scanId,
        { page: this.findingsPageNum, size: FINDINGS_PAGE_SIZE, sort },
        this.repoName,
      )
      .pipe(
        finalize(() => {
          this.loadingFindings = false;
        }),
      )
      .subscribe({
        next: (response) => {
          this.findings = response.data?.content ?? [];
          this.findingsTotalPages = response.data?.page?.totalPages ?? 0;
          this.findingsTotalCount = response.data?.page?.totalElements ?? this.findings.length;
        },
        error: () => {},
      });
  }

  /**
   * Per-severity breakdown reflects only the currently loaded findings page (up to {@code
   * FINDINGS_PAGE_SIZE}), not the selected scan's full finding set — {@link findingsTotalCount}
   * alone stays exact (taken from the page's own {@code totalElements}).
   */
  private countBySeverity(severity: Severity): number {
    return this.findings.filter((finding) => finding.severity === severity).length;
  }

  /**
   * Patches whichever pieces of component state reference the scan a poll tick just fetched.
   * {@link overview} and {@link selectedScan} are independent — a poll tick for the latest/active
   * scan must never overwrite {@link selectedScan} while the user is looking at a different
   * (already-terminal) scan from Scan History, and conversely a history click must never redirect
   * what {@link syncPolling} is polling. Findings/overview-severity are only re-fetched once the
   * polled scan actually reaches a terminal state (see {@link refreshOnTerminalScan}) — not on every
   * PENDING/RUNNING tick, which would otherwise re-request the same not-yet-changed data every
   * {@link POLL_INTERVAL_MS}.
   */
  private applyRefreshedScan(detail: VulnerabilityScanDetail): void {
    this.patchScanInHistory(detail);

    const isOverviewScan = this.overview?.scanId === detail.id;
    const isSelectedScan = this.selectedScan?.id === detail.id;

    this.patchOverviewStatus(detail, isOverviewScan);
    this.patchSelectedScanStatus(detail, isSelectedScan);

    if (TERMINAL_STATUSES.includes(detail.status!)) {
      this.refreshOnTerminalScan(detail.id!, isSelectedScan, isOverviewScan);
    }
  }

  private patchScanInHistory(detail: VulnerabilityScanDetail): void {
    this.scans = this.scans.map((scan) =>
      scan.id === detail.id
        ? {
            ...scan,
            status: detail.status,
            startedAt: detail.startedAt,
            completedAt: detail.completedAt,
            errorMessage: detail.errorMessage,
            scannerVersion: detail.scannerVersion,
          }
        : scan,
    );
  }

  private patchOverviewStatus(detail: VulnerabilityScanDetail, isOverviewScan: boolean): void {
    if (isOverviewScan && this.overview) {
      this.overview = { ...this.overview, status: detail.status, scannedAt: detail.completedAt };
    }
  }

  private patchSelectedScanStatus(detail: VulnerabilityScanDetail, isSelectedScan: boolean): void {
    if (isSelectedScan) {
      this.selectedScan = detail;
    }
  }

  /**
   * Once (not per-tick): re-fetches findings for whichever scan is both selected and just finished
   * (so the expanded panel's findings table/severity counts pick up real results), and refreshes
   * {@link overview}'s own aggregate when the just-finished scan is the latest/active one (so the
   * collapsed header badge stops showing whatever stale counts it had before the scan completed).
   */
  private refreshOnTerminalScan(scanId: string, isSelectedScan: boolean, isOverviewScan: boolean): void {
    if (isSelectedScan) {
      this.findingsPageNum = 0;
      this.loadFindings(scanId);
    }

    if (isOverviewScan) {
      this.refreshOverviewSeverity();
    }
  }

  private refreshOverviewSeverity(): void {
    this.vulnerabilityScanControllerService
      .getScanOverview(this.artifactName, this.artifactVersion, this.repoName)
      .subscribe({
        next: (response) => {
          this.overview = response.data ?? this.overview;
        },
        error: () => {},
      });
  }

  /**
   * Only ever polls the single scan currently summarized at the top of the section — history rows
   * for other PENDING/RUNNING scans simply reflect whatever the last full {@link refresh} loaded,
   * consistent with this component being the only place in the app that polls (see {@link
   * pollUntilTerminal}); list/badge surfaces elsewhere never do. Independent of whatever the user has
   * selected from Scan History — see {@link applyRefreshedScan}.
   */
  private syncPolling(): void {
    const scanId = this.overview?.scanId;
    const isActive = this.overview?.status === ScanStatus.Pending || this.overview?.status === ScanStatus.Running;

    if (!scanId || !isActive) {
      return;
    }

    this.pollingSub = pollUntilTerminal(
      () => this.vulnerabilityScanControllerService.getVulnerabilityScan(scanId, this.repoName).pipe(map((response) => response.data!)),
      (detail) => TERMINAL_STATUSES.includes(detail.status!),
      POLL_INTERVAL_MS,
    ).subscribe((detail) => this.applyRefreshedScan(detail));
  }

  private stopPolling(): void {
    this.pollingSub?.unsubscribe();
    this.pollingSub = null;
  }
}
