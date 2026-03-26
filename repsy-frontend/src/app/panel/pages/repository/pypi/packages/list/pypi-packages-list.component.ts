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

import { environment } from '../../../../../../../environments/environment';
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
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { Sort } from '../../../../../shared/dto/sort';
import { PypiConfigComponent } from '../../config/pypi-config.component';
import { PackageListItem } from '../../dto/package-list-item';
import { PypiService } from '../../service/pypi.service';

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
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './pypi-packages-list.component.html',
})
export class PypiPackagesListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public pagedData: PagedData<PackageListItem>;
  public activeRepo: RepoPermissionInfo;

  public packages: PackageListItem[];
  public sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };

  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'updatedAt', type: 'DESC' },
    { name: 'Oldest', column: 'updatedAt', type: 'ASC' },
  ];

  public readonly baseUrl: string;
  public readonly username: string;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly pypiService: PypiService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<PackageListItem>();
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.pypiService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.fetchPackages();
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

  public refreshPage(): void {
    this.fetchPackages();
  }

  public search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchPackages();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchPackages();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deletePackage(pck: PackageListItem) {
    this.dangerModalService.show('Delete Package', 'Delete', () => {
      this.loading = true;
      this.pypiService
        .deletePackage(pck.name)
        .then(() => {
          this.refreshPage();
          this.toastService.show('Package deleted successfully', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private fetchPackages(): void {
    this.loading = true;

    this.pypiService
      .fetchRepositoryPackagesLikeName(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .then((pagedData: PagedData<PackageListItem>) => {
        this.pagedData.page = pagedData.page;
        this.packages = pagedData.content;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
