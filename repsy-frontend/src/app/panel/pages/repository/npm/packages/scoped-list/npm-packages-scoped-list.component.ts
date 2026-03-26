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

import { environment } from '../../../../../../../environments/environment';
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
import { NpmConfigComponent } from '../../config/npm-config.component';
import { PackageListItem } from '../../dto/package-list-item';
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
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './npm-packages-scoped-list.component.html',
})
export class NpmPackagesScopeFilterComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public baseUrl: string;
  public error: string;
  public scopeName: string;
  public pageNum = 0;
  public pageSize = 10;
  public pagedData: PagedData<PackageListItem>;
  public packages: PackageListItem[];
  public activeRegistry: RepoPermissionInfo;
  public searchText = '';

  public sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };
  public sortOptions: Sort[] = [
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
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.pagedData = new PagedData<PackageListItem>();
    this.activeRegistry = new RepoPermissionInfo();

    this.registryChanges$ = this.npmService.registryChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = registry;
        this.scopeName = this.route.snapshot.paramMap.get('scope');
        this.fetchPackages();
      }
    });
  }

  public ngOnDestroy(): void {
    this.registryChanges$.unsubscribe();
  }

  public refreshPage(): void {
    this.fetchPackages();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchPackages();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchPackages();
  }

  public search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchPackages();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchPackages(): void {
    this.loading = true;
    if (this.scopeName !== '~') {
      this.npmService
        .fetchRegistryPackagesFilterByScopeLikeName(
          this.searchText,
          this.sortOption,
          this.scopeName,
          this.pageNum,
          this.pageSize,
        )
        .then((pagedData: PagedData<PackageListItem>) => {
          this.pagedData.page = pagedData.page;
          this.packages = pagedData.content;
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });

      return;
    }

    this.npmService
      .fetchRegistryPackagesFilterByNoScopeLikeName(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .then((pagedData: PagedData<PackageListItem>) => {
        this.pagedData.page = pagedData.page;
        this.packages = pagedData.content;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deletePackage(pck: PackageListItem) {
    this.dangerModalService.show('Delete Package', 'Delete', () => {
      this.loading = true;
      this.npmService
        .deletePackage(pck.name, pck.scope)
        .then(() => {
          if (this.packages.length - 1 === 0) {
            this.router.navigateByUrl(`/${this.activeRegistry.repoName}`).then(() => {
              this.toastService.show('Package deleted successfully', 'success');
            });
          } else {
            this.refreshPage();
            this.toastService.show('Package deleted successfully', 'success');
          }
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });
    });
  }

  public get canManage(): boolean {
    return this.activeRegistry?.canManage ?? false;
  }
}
