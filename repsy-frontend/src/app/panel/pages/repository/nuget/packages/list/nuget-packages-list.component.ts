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
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { NuGetPackageListItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../../../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { PackageSecurityBadgeComponent } from '../../../../../shared/components/package-security-badge/package-security-badge.component';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { SecurityService } from '../../../../security/service/security.service';
import { NugetConfigComponent } from '../../config/nuget-config.component';
import { NugetService } from '../../service/nuget.service';

@Component({
  selector: 'app-nuget-packages-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    SpinnerComponent,
    NugetConfigComponent,
    DropdownComponent,
    TooltipComponent,
    EllipsisPipe,
    EmptyListComponent,
    NgOptimizedImage,
    PackageSecurityBadgeComponent,
  ],
  templateUrl: './nuget-packages-list.component.html',
})
export class NugetPackagesListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public packages: NuGetPackageListItem[] = [];
  public pagedData = new PagedData<NuGetPackageListItem>();
  public activeRepo: RepoPermissionInfo;
  public securitySummary: Record<string, VersionSecuritySummary> = {};
  public readonly baseUrl: string;
  public readonly username: string;

  public sortOption: Sort = { name: 'Name (A-Z)', column: 'packageId', type: 'ASC' };
  public sortOptions: Sort[] = [
    { name: 'Name (A-Z)', column: 'packageId', type: 'ASC' },
    { name: 'Name (Z-A)', column: 'packageId', type: 'DESC' },
  ];

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly nugetService: NugetService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.nugetService.repoChanges.subscribe((repo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchPackages();
        this.fetchSecuritySummary();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchPackages();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchPackages();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchPackages();
  }

  public refreshPage(): void {
    this.fetchPackages();
  }

  public deletePackage(pkg: NuGetPackageListItem): void {
    this.dangerModalService.show('Delete Package', 'Delete', () => {
      this.loading = true;
      this.nugetService
        .deletePackage(pkg.packageId)
        .then(() => {
          this.refreshPage();
          this.toastService.show('Package deleted successfully', 'success');
        })
        // The error interceptor has already shown the failure to the user.
        .catch(() => undefined)
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private fetchPackages(): void {
    this.loading = true;
    this.nugetService
      .fetchRepositoryPackages(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .then((pagedData) => {
        this.pagedData.page = pagedData.page;
        this.packages = pagedData.content;
        this.error = null;
      })
      // The error interceptor has already toasted the failure; keep the message for the page.
      .catch((err: HttpErrorResponse) => {
        this.error = err.error?.text ?? 'Error Occurred';
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  public get totalPages(): number {
    return this.pagedData?.page?.totalPages ?? 0;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public packageRoute(pkg: NuGetPackageListItem): string {
    return `/${this.activeRepo.repoName}/${pkg.packageId}`;
  }

  private fetchSecuritySummary(): void {
    this.securityService.getArtifactSecuritySummary(this.activeRepo.repoName).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      error: () => {},
    });
  }
}
