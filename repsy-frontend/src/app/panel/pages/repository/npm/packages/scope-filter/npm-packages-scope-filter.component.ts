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
import { NpmPackageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { restoreListFocus } from '../../../../../shared/util/list-focus-restore.util';
import { emptiesList, pageAfterDelete } from '../../../../../shared/util/list-page-after-delete.util';
import {
  readListPageParam,
  readListQueryParam,
  updateListQueryParams,
} from '../../../../../shared/util/list-query-params.util';
import { NpmConfigComponent } from '../../config/npm-config.component';
import { NpmService } from '../../service/npm.service';

@Component({
  selector: 'app-npm-packages-scope-filter',
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
  ],
  templateUrl: './npm-packages-scope-filter.component.html',
})
export class NpmPackagesScopeFilterComponent implements OnDestroy {
  loading = true;
  showConfig = false;
  baseUrl: string;
  error: string;
  scopeName: string;
  pageNum = 0;
  pageSize = 10;
  pagedData: PagedData<NpmPackageListItem>;
  packages: NpmPackageListItem[];
  activeRegistry: RepoPermissionInfo;
  searchText = '';

  sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };
  sortOptions: Sort[] = [
    { name: 'Newest', column: 'updatedAt', type: 'DESC' },
    { name: 'Oldest', column: 'updatedAt', type: 'ASC' },
  ];

  private readonly registryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly npmService: NpmService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
    private readonly elementRef: ElementRef<HTMLElement>,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.pagedData = new PagedData<NpmPackageListItem>();
    this.activeRegistry = {} as RepoPermissionInfo;

    this.registryChanges$ = this.npmService.repoChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = registry;
        this.scopeName = this.route.snapshot.paramMap.get('scope');

        // RPS-1668: the search, sort and page live in the URL, so a reload or a Back navigation
        // restores exactly what was left instead of an unfiltered, first page.
        this.searchText = readListQueryParam(this.route, 'q') ?? '';
        const sortParam = readListQueryParam(this.route, 'sort');
        this.sortOption = this.sortOptions.find((option) => option.name === sortParam) ?? this.sortOptions[0];
        this.pageNum = readListPageParam(this.route, 'page', 0);

        this.fetchPackages();
      }
    });
  }

  ngOnDestroy(): void {
    this.registryChanges$.unsubscribe();
  }

  /** See `NpmPackagesListComponent.refreshPage` (RPS-1669): the caller can name the control that had
   *  the focus before it started asking, for when this reload's own opener will not survive it. */
  refreshPage(previouslyFocused: Element | null = null): void {
    this.fetchPackages(previouslyFocused);
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchPackages();
  }

  sort(option: Sort) {
    this.sortOption = option;
    this.fetchPackages();
  }

  search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
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
    const call =
      this.scopeName !== '~'
        ? this.npmService.searchScopedPackages(
            this.scopeName,
            this.searchText,
            this.sortOption,
            this.pageNum,
            this.pageSize,
          )
        : this.npmService.searchUnscopedPackages(this.searchText, this.sortOption, this.pageNum, this.pageSize);
    call
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<NpmPackageListItem>) => {
          this.pagedData.page = pagedData.page;
          this.packages = pagedData.content;
          restoreListFocus(previouslyFocused, this.elementRef.nativeElement);
        },
        error: () => {},
      });
  }

  deletePackage(pck: NpmPackageListItem) {
    // Captured now (RPS-1669): by the time the reload actually fires, the danger modal has already
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
            if (emptiesList(this.packages.length, this.pageNum, this.searchText)) {
              this.router.navigateByUrl(`/${this.activeRegistry.repoName}`).then(() => {
                this.toastService.show('Package deleted successfully', 'success');
              });
            } else {
              this.pageNum = pageAfterDelete(this.packages.length, this.pageNum);
              this.refreshPage(previouslyFocused);
              this.toastService.show('Package deleted successfully', 'success');
            }
          },
          error: () => {},
        });
    });
  }

  get canManage(): boolean {
    return this.activeRegistry?.canManage ?? false;
  }
}
