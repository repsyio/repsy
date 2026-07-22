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

import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { Router } from '@angular/router';
import { Chart, registerables } from 'chart.js';
import { finalize } from 'rxjs';

import {
  PagedModelVulnerabilityScanInfo,
  RepoType,
  ScanStatus,
  SecurityScansSummary,
  Severity,
  VulnerabilityScanInfo,
} from '../../../../generated/api';
import { SpinnerComponent } from '../../../shared/components/spinner/spinner.component';
import { PaginationComponent } from '../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../shared/components/searchbox/searchbox.component';
import { SelectorComponent } from '../../shared/components/selector/selector.component';
import { SeverityBadgeComponent } from '../../shared/components/severity-badge/severity-badge.component';
import { ToastService } from '../../shared/components/toast/toast.service';
import { SecurityScanSupportService } from '../../shared/service/security-scan-support.service';
import { buildArtifactDetailRoute } from '../../shared/util/security-detail-route.util';
import { SecurityService } from './service/security.service';

Chart.register(...registerables);

const ALL_OPTION = 'ALL';
const SEVERITY_CHART_COLORS = ['#ed7688', '#ffec7d', '#7de2ff', '#9eff87', '#9fa0a0'];
const SEVERITY_CHART_LABELS = ['Critical', 'High', 'Medium', 'Low', 'Unknown'];

@Component({
  selector: 'app-security',
  standalone: true,
  imports: [
    CommonModule,
    SpinnerComponent,
    PaginationComponent,
    SearchboxComponent,
    SelectorComponent,
    SeverityBadgeComponent,
  ],
  templateUrl: './security.component.html',
})
export class SecurityComponent implements OnInit, OnDestroy {
  public loading = true;
  public scans: VulnerabilityScanInfo[] = [];
  public pagedData: PagedModelVulnerabilityScanInfo = { page: { totalPages: 0 } };
  public pageNum = 0;
  public pageSize = 10;
  public repoNameSearch = '';

  @ViewChild('summaryChartCanvas') public summaryChartCanvasRef?: ElementRef<HTMLCanvasElement>;
  public loadingSummary = true;
  public scansSummary: SecurityScansSummary | null = null;
  private summaryChart?: Chart;

  public readonly severityOptions: string[] = [ALL_OPTION, 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];
  public repoTypeOptions: string[] = [ALL_OPTION];
  public severityOption = ALL_OPTION;
  public repoTypeOption = ALL_OPTION;

  protected readonly ScanStatus = ScanStatus;

  constructor(
    private readonly securityService: SecurityService,
    private readonly toastService: ToastService,
    private readonly router: Router,
    private readonly securityScanSupportService: SecurityScanSupportService,
  ) {}

  public ngOnInit(): void {
    this.fetchScans();
    this.fetchScansSummary();
    this.fetchSupportedRepoTypes();
  }

  public ngOnDestroy(): void {
    this.summaryChart?.destroy();
  }

  public get hasSummaryFindings(): boolean {
    return (this.scansSummary?.totalCount ?? 0) > 0;
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchScans();
  }

  public search(repoName: string): void {
    this.repoNameSearch = repoName;
    this.pageNum = 0;
    this.fetchScans();
  }

  public filterBySeverity(option: string): void {
    this.severityOption = option;
    this.pageNum = 0;
    this.fetchScans();
  }

  public filterByRepoType(option: string): void {
    this.repoTypeOption = option;
    this.pageNum = 0;
    this.fetchScans();
  }

  /**
   * A full reset, matching {@code UserManagementComponent.refreshPage}: clears every active filter
   * (not just re-fetching with whatever's currently selected) — the severity-distribution chart is
   * deliberately left untouched, since it's already filter-independent (see {@link
   * fetchScansSummary}) and has no reason to change here.
   */
  public refreshPage(): void {
    this.pageNum = 0;
    this.repoNameSearch = '';
    this.severityOption = ALL_OPTION;
    this.repoTypeOption = ALL_OPTION;
    this.fetchScans();
  }

  public isScanClickable(scan: VulnerabilityScanInfo): boolean {
    return this.buildDetailRoute(scan) !== null;
  }

  public openScan(scan: VulnerabilityScanInfo): void {
    const route = this.buildDetailRoute(scan);
    if (!route) {
      return;
    }

    if (route.queryParams) {
      this.router.navigate([route.path], { queryParams: route.queryParams, fragment: 'security' });
    } else {
      this.router.navigateByUrl(`${route.path}#security`);
    }
  }

  private fetchSupportedRepoTypes(): void {
    this.securityScanSupportService.getSupportedRepoTypes().subscribe((supported) => {
      this.repoTypeOptions = [ALL_OPTION, ...Array.from(supported).sort()];
    });
  }

  private fetchScansSummary(): void {
    this.loadingSummary = true;

    // Deliberately called with no repoType/repoName filters — this chart always shows the
    // system-wide severity distribution, independent of the scans list's active filters, so it
    // isn't mistaken for a live summary of the (possibly filtered) table below it.
    this.securityService
      .getScansSummary()
      .pipe(finalize(() => (this.loadingSummary = false)))
      .subscribe({
        next: (summary) => {
          this.scansSummary = summary;
          setTimeout(() => this.renderSummaryChart());
        },
        error: () => {},
      });
  }

  private renderSummaryChart(): void {
    const canvas = this.summaryChartCanvasRef?.nativeElement;
    const ctx = canvas?.getContext('2d');

    if (!ctx || !this.scansSummary) {
      return;
    }

    this.summaryChart?.destroy();

    this.summaryChart = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: SEVERITY_CHART_LABELS,
        datasets: [
          {
            data: [
              this.scansSummary.criticalCount ?? 0,
              this.scansSummary.highCount ?? 0,
              this.scansSummary.mediumCount ?? 0,
              this.scansSummary.lowCount ?? 0,
              this.scansSummary.unknownCount ?? 0,
            ],
            backgroundColor: SEVERITY_CHART_COLORS,
            borderWidth: 0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { color: '#9FA0A0', font: { size: 11 }, boxWidth: 12 },
          },
        },
      },
    });
  }

  private fetchScans(): void {
    this.loading = true;

    const severity = this.severityOption === ALL_OPTION ? undefined : (this.severityOption as Severity);
    const repoType = this.repoTypeOption === ALL_OPTION ? undefined : (this.repoTypeOption as RepoType);

    this.securityService
      .listScans(severity, repoType, this.repoNameSearch || undefined, this.pageNum, this.pageSize)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (pagedModel) => {
          this.pagedData = pagedModel;
          this.scans = pagedModel.content ?? [];
        },
        // Every other status is already toasted by the global error interceptor — 401 is the one
        // status that interceptor deliberately skips (see error-handler.interceptor.ts), so without
        // this the page would just sit there loading forever with no feedback whenever the route
        // guard doesn't catch a since-revoked admin role (e.g. the role changes while this page is
        // already open and the user changes a filter, triggering a new request past the guard).
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.toastService.show('You do not have permission to view this page', 'error');
            this.router.navigateByUrl('/');
          }
        },
      });
  }

  private buildDetailRoute(scan: VulnerabilityScanInfo): { path: string; queryParams?: Record<string, string> } | null {
    return buildArtifactDetailRoute(scan.repoType, scan.repoName, scan.artifactName, scan.artifactVersion);
  }
}
