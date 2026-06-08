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
import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { HelmChartListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../../../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { HelmConfigComponent } from '../../config/helm-config.component';
import { HelmService } from '../../service/helm.service';

@Component({
  selector: 'app-helm-charts-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    SpinnerComponent,
    HelmConfigComponent,
    DropdownComponent,
    TooltipComponent,
    EllipsisPipe,
    EmptyListComponent,
    NgOptimizedImage,
  ],
  templateUrl: './helm-charts-list.component.html',
})
export class HelmChartsListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public charts: HelmChartListItem[] = [];
  public pagedData = new PagedData<HelmChartListItem>();
  public activeRepo: RepoPermissionInfo = {} as RepoPermissionInfo;

  public sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
    { name: 'Name (A-Z)', column: 'name', type: 'ASC' },
    { name: 'Name (Z-A)', column: 'name', type: 'DESC' },
  ];
  public readonly baseUrl: string;
  public readonly username: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly helmService: HelmService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.repositoryChanges$ = this.helmService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchCharts();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchCharts();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchCharts();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchCharts();
  }

  public refreshPage(): void {
    this.fetchCharts();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public deleteChart(chart: HelmChartListItem): void {
    this.dangerModalService.show('Delete Chart', 'Delete', () => {
      this.loading = true;
      this.helmService
        .deleteChart(chart.name, chart.latestVersion)
        .pipe(finalize(() => { this.loading = false; }))
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Chart deleted successfully', 'success');
          },
          error: (err: string) => this.toastService.show(err, 'error'),
        });
    });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  public timeAgo(date: string): string {
    return moment(date).fromNow();
  }

  private fetchCharts(): void {
    this.loading = true;
    this.helmService
      .searchCharts(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(finalize(() => { this.loading = false; }))
      .subscribe({
        next: (pagedData: PagedData<HelmChartListItem>) => {
          this.pagedData.page = pagedData.page;
          this.charts = pagedData.content;
          this.error = null;
        },
        error: (err: string) => {
          this.error = err;
          this.toastService.show(err, 'error');
        },
      });
  }
}
