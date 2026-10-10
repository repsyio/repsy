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
import { PypiPackageListItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/services/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { PackageSecurityBadgeComponent } from '../../../../../shared/components/package-security-badge/package-security-badge.component';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../../shared/dtos/paged-data';
import { Sort } from '../../../../../shared/dtos/sort';
import { SecurityService } from '../../../../security/services/security.service';
import { PypiConfigComponent } from '../../config/pypi-config.component';
import { PypiService } from '../../services/pypi.service';

@Component({
  selector: 'app-pypi-packages-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    EmptyListComponent,
    PypiConfigComponent,
    PaginationComponent,
    SearchboxComponent,
    SortSelectorComponent,
    DropdownComponent,
    TooltipComponent,
    NgOptimizedImage,
    SpinnerComponent,
    PackageSecurityBadgeComponent,
  ],
  templateUrl: './pypi-packages-list.component.html',
})
export class PypiPackagesListComponent implements OnDestroy {
  loading = true;
  showConfig = false;
  pageNum = 0;
  pageSize = 10;
  searchText = '';
  error: string;
  pagedData: PagedData<PypiPackageListItem>;
  activeRepo: RepoPermissionInfo;
  securitySummary: Record<string, VersionSecuritySummary> = {};

  packages: PypiPackageListItem[];
  sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };

  sortOptions: Sort[] = [
    { name: 'Newest', column: 'updatedAt', type: 'DESC' },
    { name: 'Oldest', column: 'updatedAt', type: 'ASC' },
  ];

  readonly baseUrl: string;
  readonly username: string;
  private readonly repositoryChanges$: Subscription;
  private securitySummarySubscription?: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly pypiService: PypiService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<PypiPackageListItem>();
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.pypiService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchPackages();
        this.fetchSecuritySummary();
      }
    });
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
    this.securitySummarySubscription?.unsubscribe();
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchPackages();
  }

  refreshPage(): void {
    this.fetchPackages();
  }

  search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchPackages();
  }

  sort(option: Sort) {
    this.sortOption = option;
    this.fetchPackages();
  }

  openConfig(open: boolean) {
    this.showConfig = open;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  deletePackage(pck: PypiPackageListItem) {
    this.dangerModalService.show('Delete Package', 'Delete', () => {
      this.loading = true;
      this.pypiService
        .deletePackage(pck.name)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Package deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  private fetchPackages(): void {
    this.loading = true;

    this.pypiService
      .fetchRepositoryPackagesLikeName(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<PypiPackageListItem>) => {
          this.pagedData.page = pagedData.page;
          this.packages = pagedData.content;
        },
        error: () => {},
      });
  }

  get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  packageRoute(pkg: PypiPackageListItem): string {
    return `/${this.activeRepo.repoName}/${pkg.name}`;
  }

  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();
    this.securitySummarySubscription = this.securityService
      .watchArtifactSecuritySummary(this.activeRepo.repoName)
      .subscribe({
        next: (summary) => {
          this.securitySummary = summary;
        },
        error: () => {},
      });
  }
}
