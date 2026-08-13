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
import { scanStatusLabel } from '../../util/scan-status-label.util';
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
const ACTIVE_STATUSES: ScanStatus[] = [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running];

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
  public readonly statusLabel = scanStatusLabel;

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



    this.fragmentChanges$ = this.route.fragment.subscribe((fragment) => {
      if (fragment === SECURITY_FRAGMENT) {
        this.expanded = true;
      }
    });
  }

  public ngOnInit(): void {
    this.isSupported$ = this.securityScanSupportService.isSupported(this.repoType);









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

    return !ACTIVE_STATUSES.includes(this.overview?.status ?? ScanStatus.Completed);
  }


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


  private countBySeverity(severity: Severity): number {
    return this.findings.filter((finding) => finding.severity === severity).length;
  }


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


  private syncPolling(): void {
    const scanId = this.overview?.scanId;
    const isActive = ACTIVE_STATUSES.includes(this.overview?.status ?? ScanStatus.Completed);

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
