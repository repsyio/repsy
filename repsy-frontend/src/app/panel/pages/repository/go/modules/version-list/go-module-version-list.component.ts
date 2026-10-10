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
import {
  GoModuleVersionListItem,
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
import { emptiesList, pageAfterDelete } from '../../../../../shared/util/list-page-after-delete.util';
import { SecurityService } from '../../../../security/service/security.service';
import { GoConfigComponent } from '../../config/go-config.component';
import { GoService } from '../../service/go.service';

@Component({
  selector: 'app-go-module-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    GoConfigComponent,
    DropdownComponent,
    EmptyListComponent,
    PaginationComponent,
    SearchboxComponent,
    SortSelectorComponent,
    TooltipComponent,
    NgOptimizedImage,
    SpinnerComponent,
    VersionSecurityBadgeComponent,
  ],
  templateUrl: './go-module-version-list.component.html',
})
export class GoModuleVersionListComponent implements OnDestroy {
  loading = true;
  showConfig = false;
  pageNum = 0;
  pageSize = 10;
  searchText = '';
  error: string;
  modulePath: string;
  pagedData: PagedData<GoModuleVersionListItem>;
  versions: GoModuleVersionListItem[];
  activeRepo: RepoPermissionInfo;
  securitySummary: Record<string, VersionSecuritySummary> = {};
  sortOption: Sort = { name: 'Newest', column: 'id', type: 'DESC' };
  sortOptions: Sort[] = [
    { name: 'Newest', column: 'id', type: 'DESC' },
    { name: 'Oldest', column: 'id', type: 'ASC' },
  ];
  readonly baseUrl: string;
  readonly username: string;

  private readonly repositoryChanges$: Subscription;
  private securitySummarySubscription?: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly goService: GoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly authService: AuthService,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<GoModuleVersionListItem>();
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.goService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.modulePath = this.route.snapshot.queryParamMap.get('modulePath');
        if (!this.modulePath) {
          this.router.navigate(['/' + this.activeRepo.repoName]);
          return;
        }
        this.fetchVersions();
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
    this.fetchVersions();
  }

  refreshPage(): void {
    this.fetchVersions();
  }

  search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchVersions();
  }

  sort(option: Sort): void {
    this.sortOption = option;
    this.fetchVersions();
  }

  openConfig(open: boolean): void {
    this.showConfig = open;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  deleteVersion(version: GoModuleVersionListItem): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.goService
        .deleteModuleVersion(this.modulePath, version.version)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            if (emptiesList(this.versions.length, this.pageNum, this.searchText)) {
              this.router.navigateByUrl('/' + this.activeRepo.repoName).then(() => {
                this.toastService.show('Version deleted successfully', 'success');
              });
            } else {
              this.pageNum = pageAfterDelete(this.versions.length, this.pageNum);
              this.refreshPage();
              this.toastService.show('Version deleted successfully', 'success');
            }
          },
          error: () => {},
        });
    });
  }

  private fetchVersions(): void {
    this.loading = true;
    this.goService
      .fetchModuleVersions(this.modulePath, this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<GoModuleVersionListItem>) => {
          this.pagedData.page = pagedData.page;
          this.versions = pagedData.content;
        },
        error: () => {},
      });
  }

  get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();
    this.securitySummarySubscription = this.securityService
      .watchVersionSecuritySummary(this.activeRepo.repoName, this.modulePath)
      .subscribe({
        next: (summary) => {
          this.securitySummary = summary;
        },
        error: () => {},
      });
  }
}
