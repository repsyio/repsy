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
import { Component, ElementRef, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { NpmPackageListItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
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
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { restoreListFocus } from '../../../../../shared/util/list-focus-restore.util';
import {
  readListPageParam,
  readListQueryParam,
  updateListQueryParams,
} from '../../../../../shared/util/list-query-params.util';
import { SecurityService } from '../../../../security/service/security.service';
import { NpmConfigComponent } from '../../config/npm-config.component';
import { NpmService } from '../../service/npm.service';

@Component({
  selector: 'app-npm-packages-list',
  standalone: true,
  imports: [
    CommonModule,
    NpmConfigComponent,
    RouterLink,
    DropdownComponent,
    PaginationComponent,
    SearchboxComponent,
    EmptyListComponent,
    SortSelectorComponent,
    TooltipComponent,
    NgOptimizedImage,
    SpinnerComponent,
    PackageSecurityBadgeComponent,
  ],
  templateUrl: './npm-packages-list.component.html',
})
export class NpmPackagesListComponent implements OnDestroy {
  loading = true;
  showConfig = false;
  baseUrl: string;
  searchText: string;
  error: string;
  pageNum = 0;
  pageSize = 10;
  pagedData: PagedData<NpmPackageListItem>;
  activeRegistry: RepoPermissionInfo;
  packages: NpmPackageListItem[];
  securitySummary: Record<string, VersionSecuritySummary> = {};

  sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };
  sortOptions: Sort[] = [
    { name: 'Newest', column: 'updatedAt', type: 'DESC' },
    { name: 'Oldest', column: 'updatedAt', type: 'ASC' },
  ];

  readonly username: string;
  private readonly registryChanges$: Subscription;
  private securitySummarySubscription?: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly npmService: NpmService,
    private readonly authService: AuthService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly securityService: SecurityService,
    private readonly elementRef: ElementRef<HTMLElement>,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.pagedData = new PagedData<NpmPackageListItem>();
    this.activeRegistry = {} as RepoPermissionInfo;
    this.username = this.authService.username;
    this.registryChanges$ = this.npmService.repoChanges$.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = Object.assign({}, registry);

        // RPS-1668: the search, sort and page live in the URL, so a reload or a Back navigation
        // restores exactly what was left instead of an unfiltered, first page.
        this.searchText = readListQueryParam(this.route, 'q') ?? '';
        const sortParam = readListQueryParam(this.route, 'sort');
        this.sortOption = this.sortOptions.find((option) => option.name === sortParam) ?? this.sortOptions[0];
        this.pageNum = readListPageParam(this.route, 'page', 0);

        this.fetchPackages();
        this.fetchSecuritySummary();
      }
    });
  }

  ngOnDestroy(): void {
    this.registryChanges$.unsubscribe();
    this.securitySummarySubscription?.unsubscribe();
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchPackages();
  }

  /** `previouslyFocused` lets a caller (a delete, whose opener is about to vanish) name the control that
   *  had the focus before it starts asking (RPS-1669), instead of the moment this reload actually goes
   *  out; a plain refresh (the toolbar button) needs no fallback and leaves it out. */
  refreshPage(previouslyFocused: Element | null = null): void {
    this.fetchPackages(previouslyFocused);
  }

  sort(option: Sort) {
    this.sortOption = option;
    this.fetchPackages();
  }

  search(scopeName: string) {
    if (scopeName.startsWith('@')) {
      scopeName = scopeName.substring(1);
    }

    this.pageNum = 0;
    this.searchText = scopeName;
    this.fetchPackages();
  }

  openConfig(open: boolean) {
    this.showConfig = open;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  /** Keeps the URL's `q`, `sort` and `page` query params in step with what is about to be fetched (RPS-1668). */
  private syncUrl(): void {
    updateListQueryParams(this.router, this.route, {
      q: this.searchText || null,
      sort: this.sortOption.name === this.sortOptions[0].name ? null : this.sortOption.name,
      page: this.pageNum || null,
    });
  }

  private fetchPackages(previouslyFocused: Element | null = null): void {
    this.syncUrl();
    this.loading = true;
    this.npmService
      .searchPackages(this.searchText === '' ? null : this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<NpmPackageListItem>) => {
          this.pagedData.page = pagedData.page;
          this.packages = pagedData.content;
          // RPS-1669: the pager stays mounted through the reload, so a page change keeps its focus by
          // itself; this only fires when the control that had it (a deleted row's menu) is really gone.
          restoreListFocus(previouslyFocused, this.elementRef.nativeElement);
        },
        error: () => {},
      });
  }

  deletePackage(pck: NpmPackageListItem) {
    // Captured now (RPS-1669), not when the reload actually fires: by then the danger modal has already
    // closed and, since its own opener (this row's menu item) is gone, given up on restoring the focus.
    const previouslyFocused = document.activeElement;
    this.dangerModalService.show('Delete Package', 'Delete', () => {
      this.loading = true;
      this.npmService
        .deletePackage(pck.name, pck.scope)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage(previouslyFocused);
            this.toastService.show('Package deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  get canManage(): boolean {
    return this.activeRegistry?.canManage ?? false;
  }

  packageSecurityKey(pkg: NpmPackageListItem): string {
    return pkg.scope ? `@${pkg.scope}/${pkg.name}` : pkg.name;
  }

  packageRoute(pkg: NpmPackageListItem): string {
    return `/${this.activeRegistry.repoName}/${pkg.scope ? pkg.scope : '~'}/${pkg.name}`;
  }

  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();
    this.securitySummarySubscription = this.securityService
      .watchArtifactSecuritySummary(this.activeRegistry.repoName)
      .subscribe({
        next: (summary) => {
          this.securitySummary = summary;
        },
        error: () => {},
      });
  }
}
