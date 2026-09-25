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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import {
  NuGetDeletedItem,
  NuGetPackageInfo,
  NuGetVersionListItem,
  RepoPermissionInfo,
  VersionSecuritySummary,
} from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { VersionSecurityBadgeComponent } from '../../../../../shared/components/version-security-badge/version-security-badge.component';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { pageAfterDelete } from '../../../../../shared/util/list-page-after-delete.util';
import { SecurityService } from '../../../../security/service/security.service';
import { NugetConfigComponent } from '../../config/nuget-config.component';
import { NugetService } from '../../service/nuget.service';

@Component({
  selector: 'app-nuget-packages-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SpinnerComponent,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    DropdownComponent,
    EmptyListComponent,
    TooltipComponent,
    NugetConfigComponent,
    NgOptimizedImage,
    VersionSecurityBadgeComponent,
  ],
  templateUrl: './nuget-packages-version-list.component.html',
})
export class NugetPackagesVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public packageId: string;
  public pkg: NuGetPackageInfo;
  public versions: NuGetVersionListItem[] = [];
  public pagedData = new PagedData<NuGetVersionListItem>();
  public activeRepo: RepoPermissionInfo;
  public readonly baseUrl: string;
  public readonly username: string;
  public securitySummary: Record<string, VersionSecuritySummary> = {};
  public sortOption: Sort = { name: 'Newest', column: 'publishedAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'publishedAt', type: 'DESC' },
    { name: 'Oldest', column: 'publishedAt', type: 'ASC' },
  ];
  private readonly repositoryChanges$: Subscription;
  private securitySummarySubscription?: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly authService: AuthService,
    private readonly nugetService: NugetService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.nugetService.repoChanges.subscribe((repo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.packageId = this.route.snapshot.paramMap.get('packageId');
        this.fetchVersions();
        this.fetchSecuritySummary();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
    this.securitySummarySubscription?.unsubscribe();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchVersions();
  }

  public sort(option: Sort): void {
    this.pageNum = 0;
    this.sortOption = option;
    this.fetchVersions();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchVersions();
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public deleteVersion(version: NuGetVersionListItem): void {
    const isLastVersion = this.pagedData.page.totalElements === 1 && !this.searchText;
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      const action = isLastVersion
        ? this.nugetService.deletePackage(this.packageId)
        : this.nugetService.deletePackageVersion(this.packageId, version.version);
      action
        .then((deletedItem) => {
          this.toastService.show('Version deleted successfully', 'success');
          if (isLastVersion || deletedItem === NuGetDeletedItem.Package) {
            this.router.navigate(['..'], { relativeTo: this.route });
          } else {
            this.pageNum = pageAfterDelete(this.versions.length, this.pageNum);
            this.fetchVersions();
          }
        })
        // The error interceptor has already shown the failure to the user.
        .catch(() => undefined)
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private fetchVersions(): void {
    this.loading = true;
    this.nugetService
      .fetchPackage(this.packageId)
      .then((pkg) => {
        this.pkg = pkg;
        return this.nugetService.fetchPackageVersions(
          this.packageId,
          this.searchText,
          this.sortOption,
          this.pageNum,
          this.pageSize,
        );
      })
      .then((pagedData) => {
        this.pagedData.page = pagedData.page;
        this.versions = pagedData.content;
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

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();
    this.securitySummarySubscription = this.securityService
      .watchVersionSecuritySummary(this.activeRepo.repoName, this.packageId)
      .subscribe({
        next: (summary) => {
          this.securitySummary = summary;
        },
        error: () => {},
      });
  }
}
