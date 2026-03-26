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
import { PackageDistributionTagMapListItem } from '../../dto/package-distribution-tag-map-list-item';
import { PackageVersionListItem } from '../../dto/package-version-list-item';
import { NpmService } from '../../service/npm.service';

@Component({
  selector: 'app-npm-packages-version-list',
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
  templateUrl: './npm-packages-version-list.component.html',
})
export class NpmPackagesVersionListComponent implements OnDestroy {
  public baseUrl: string;
  public error: string;
  public scopeName: string;
  public packageName: string;
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public pagedData: PagedData<PackageVersionListItem>;
  public versions: PackageVersionListItem[];
  public tags: PackageDistributionTagMapListItem[];
  public activeRegistry: RepoPermissionInfo;
  public searchText = '';

  public sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
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
    this.pagedData = new PagedData<PackageVersionListItem>();
    this.activeRegistry = new RepoPermissionInfo();
    this.registryChanges$ = this.npmService.registryChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = Object.assign(new RepoPermissionInfo(), registry);
        this.scopeName = this.route.snapshot.paramMap.get('scope');
        this.packageName = this.route.snapshot.paramMap.get('package');

        if (this.scopeName == '~') {
          this.scopeName = null;
        }

        this.fetchVersions();
      }
    });
  }

  public ngOnDestroy(): void {
    this.registryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchVersions();
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchVersions();
  }

  public search(versionName: string) {
    if (versionName.startsWith('v')) {
      versionName = versionName.substring(1);
    }

    this.pageNum = 0;
    this.searchText = versionName;
    this.fetchVersions();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deleteVersion(version: PackageVersionListItem) {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;

      this.npmService
        .deletePackageVersion(this.packageName, this.scopeName, version.version)
        .then(() => {
          if (this.versions.length - 1 === 0) {
            this.router.navigateByUrl(`/${this.activeRegistry.repoName}`).then(() => {
              this.toastService.show('Version deleted successfully', 'success');
            });
          } else {
            this.refreshPage();
            this.toastService.show('Version deleted successfully', 'success');
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

  private fetchVersions(): void {
    this.loading = true;
    this.npmService
      .fetchPackageVersionsLikeVersion(
        this.packageName,
        this.scopeName,
        this.searchText,
        this.sortOption,
        this.pageNum,
        this.pageSize,
      )
      .then((pagedData: PagedData<PackageVersionListItem>) => {
        this.pagedData.page = pagedData.page;
        this.versions = pagedData.content;
        this.fetchPackageTags();
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  private fetchPackageTags() {
    this.npmService
      .fetchPackageTags(this.packageName, this.scopeName)
      .then((tags: PackageDistributionTagMapListItem[]) => {
        this.tags = tags;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      });
  }

  public get canManage(): boolean {
    return this.activeRegistry?.canManage ?? false;
  }
}
