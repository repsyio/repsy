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
  GemVersionListItem,
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
import { SecurityService } from '../../../../security/service/security.service';
import { RubyConfigComponent } from '../../config/ruby-config.component';
import { RubyService } from '../../service/ruby.service';

@Component({
  selector: 'app-ruby-gems-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SpinnerComponent,
    RubyConfigComponent,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    DropdownComponent,
    TooltipComponent,
    EmptyListComponent,
    NgOptimizedImage,
    VersionSecurityBadgeComponent,
  ],
  templateUrl: './ruby-gems-version-list.component.html',
})
export class RubyGemsVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public error: string;
  public gemName: string;
  public searchText = '';
  public versions: GemVersionListItem[] = [];
  public pagedData = new PagedData<GemVersionListItem>();
  public activeRepo: RepoPermissionInfo;
  public readonly baseUrl: string;
  public readonly username: string;
  public securitySummary: Record<string, VersionSecuritySummary> = {};

  public sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
  ];

  private readonly repositoryChanges$: Subscription;

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  constructor(
    private readonly route: ActivatedRoute,
    private readonly authService: AuthService,
    private readonly rubyService: RubyService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.rubyService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.gemName = this.route.snapshot.paramMap.get('gem');
        this.fetchVersions();
        this.fetchSecuritySummary();
      }
    });
  }

  public search(version: string): void {
    this.pageNum = 0;
    this.searchText = version;
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

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deleteVersion(version: GemVersionListItem): void {
    const isLastVersion = this.pagedData.page.totalElements === 1;
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      const deleteAction = isLastVersion
        ? this.rubyService.deleteGem(this.gemName)
        : this.rubyService.deleteGemVersion(this.gemName, version.version, version.platform);
      deleteAction
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

  private fetchVersions(): void {
    this.loading = true;
    this.rubyService
      .fetchGemVersions(this.gemName, this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData) => {
          this.pagedData.page = pagedData.page;
          this.versions = pagedData.content;
          this.error = null;
        },
        error: () => {},
      });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  private fetchSecuritySummary(): void {
    this.securityService.getVersionSecuritySummary(this.activeRepo.repoName, this.gemName).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      error: () => {},
    });
  }
}
