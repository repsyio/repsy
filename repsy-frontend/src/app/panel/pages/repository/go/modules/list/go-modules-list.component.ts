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
import { GoModuleListItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
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
import { GoConfigComponent } from '../../config/go-config.component';
import { GoService } from '../../services/go.service';

@Component({
  selector: 'app-go-modules-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    EmptyListComponent,
    GoConfigComponent,
    PaginationComponent,
    SearchboxComponent,
    SortSelectorComponent,
    DropdownComponent,
    TooltipComponent,
    NgOptimizedImage,
    SpinnerComponent,
    PackageSecurityBadgeComponent,
  ],
  templateUrl: './go-modules-list.component.html',
})
export class GoModulesListComponent implements OnDestroy {
  loading = true;
  showConfig = false;
  pageNum = 0;
  pageSize = 10;
  searchText = '';
  error: string;
  pagedData: PagedData<GoModuleListItem>;
  activeRepo: RepoPermissionInfo;
  securitySummary: Record<string, VersionSecuritySummary> = {};
  sortOption: Sort = { name: 'Newest', column: 'id', type: 'DESC' };
  sortOptions: Sort[] = [
    { name: 'Newest', column: 'id', type: 'DESC' },
    { name: 'Oldest', column: 'id', type: 'ASC' },
  ];

  modules: GoModuleListItem[];

  readonly baseUrl: string;
  readonly username: string;
  private readonly repositoryChanges$: Subscription;
  private securitySummarySubscription?: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly goService: GoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<GoModuleListItem>();
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.goService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchModules();
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
    this.fetchModules();
  }

  refreshPage(): void {
    this.fetchModules();
  }

  search(modulePath: string) {
    this.pageNum = 0;
    this.searchText = modulePath;
    this.fetchModules();
  }

  sort(option: Sort): void {
    this.sortOption = option;
    this.fetchModules();
  }

  openConfig(open: boolean) {
    this.showConfig = open;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  deleteModule(mod: GoModuleListItem) {
    this.dangerModalService.show('Delete Module', 'Delete', () => {
      this.goService
        .deleteModule(mod.modulePath)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Module deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  private fetchModules(): void {
    this.loading = true;

    this.goService
      .fetchModules(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<GoModuleListItem>) => {
          this.pagedData.page = pagedData.page;
          this.modules = pagedData.content;
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
      .watchArtifactSecuritySummary(this.activeRepo.repoName)
      .subscribe({
        next: (summary) => {
          this.securitySummary = summary;
        },
        error: () => {},
      });
  }
}
