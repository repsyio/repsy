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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { HelmChartVersionItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { VersionSecurityBadgeComponent } from '../../../../../shared/components/version-security-badge/version-security-badge.component';
import { Sort } from '../../../../../shared/dto/sort';
import { SecurityService } from '../../../../security/service/security.service';
import { HelmConfigComponent } from '../../config/helm-config.component';
import { HelmService } from '../../service/helm.service';

@Component({
  selector: 'app-helm-charts-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SpinnerComponent,
    HelmConfigComponent,
    SearchboxComponent,
    SortSelectorComponent,
    DropdownComponent,
    TooltipComponent,
    EmptyListComponent,
    NgOptimizedImage,
    VersionSecurityBadgeComponent,
  ],
  templateUrl: './helm-charts-version-list.component.html',
})
export class HelmChartsVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public error: string;
  public chartName: string;
  public versions: HelmChartVersionItem[] = [];
  public searchText = '';
  public sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
  ];
  public activeRepo: RepoPermissionInfo = {} as RepoPermissionInfo;
  public readonly baseUrl: string;
  public readonly username: string;
  public securitySummary: Record<string, VersionSecuritySummary> = {};

  private allVersions: HelmChartVersionItem[] = [];
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly authService: AuthService,
    private readonly helmService: HelmService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.repositoryChanges$ = this.helmService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.chartName = this.route.snapshot.paramMap.get('name');
        this.fetchVersions();
        this.fetchSecuritySummary();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public search(text: string): void {
    this.searchText = text;
    this.applyFilterAndSort();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.applyFilterAndSort();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public timeAgo(date: string): string {
    return moment(date).fromNow();
  }

  public deleteVersion(version: HelmChartVersionItem): void {
    const isLastVersion = this.versions.length === 1;
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.helmService
        .deleteChart(this.chartName, version.version)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.toastService.show('Version deleted successfully', 'success');
            if (isLastVersion) {
              this.router.navigate(['..'], { relativeTo: this.route });
            } else {
              this.fetchVersions();
            }
          },
          error: () => {},
        });
    });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  private fetchVersions(): void {
    this.loading = true;
    this.helmService
      .getChartVersions(this.chartName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (versions: HelmChartVersionItem[]) => {
          this.allVersions = versions;
          this.applyFilterAndSort();
          this.error = null;
        },
        error: (err: string) => this.toastService.show(err, 'error'),
      });
  }

  private fetchSecuritySummary(): void {
    this.securityService.getVersionSecuritySummary(this.activeRepo.repoName, this.chartName).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      error: () => {},
    });
  }

  private applyFilterAndSort(): void {
    const text = this.searchText.toLowerCase();
    const filtered = text
      ? this.allVersions.filter((v) => v.version.toLowerCase().includes(text))
      : [...this.allVersions];
    this.versions = filtered.sort((a, b) => {
      const diff = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
      return this.sortOption.type === 'DESC' ? -diff : diff;
    });
  }
}
