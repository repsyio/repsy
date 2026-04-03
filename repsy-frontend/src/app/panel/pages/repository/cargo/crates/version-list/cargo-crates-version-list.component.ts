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
import { PagedData } from '../../../../../shared/dto/paged-data';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { Sort } from '../../../../../shared/dto/sort';
import { CargoConfigComponent } from '../../config/cargo-config.component';
import { CrateInfo } from '../../dto/crate-info';
import { CrateVersionListItem } from '../../dto/crate-version-list-item';
import { CargoService } from '../../service/cargo.service';

@Component({
  selector: 'app-cargo-crates-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SpinnerComponent,
    CargoConfigComponent,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    DropdownComponent,
    TooltipComponent,
    EmptyListComponent,
    NgOptimizedImage,
  ],
  templateUrl: './cargo-crates-version-list.component.html',
})
export class CargoCratesVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public error: string;
  public packageName: string;
  public searchText = '';
  public crate: CrateInfo;
  public versions: CrateVersionListItem[] = [];
  public pagedData = new PagedData<CrateVersionListItem>();
  public activeRepo: RepoPermissionInfo;
  public readonly baseUrl: string;
  public readonly username: string;
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
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = new RepoPermissionInfo();
    this.repositoryChanges$ = this.cargoService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.packageName = this.route.snapshot.paramMap.get('crate');
        this.fetchVersions();
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

  public deleteVersion(version: CrateVersionListItem): void {
    const isLastVersion = this.pagedData.page.totalElements === 1;
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      const deleteAction = isLastVersion
        ? this.cargoService.deleteCrate(this.packageName)
        : this.cargoService.deleteCrateVersion(this.packageName, version.version);
      deleteAction
        .then(() => {
          this.toastService.show('Version deleted successfully', 'success');
          if (isLastVersion) {
            this.router.navigate(['..'], { relativeTo: this.route });
          } else {
            this.fetchVersions();
          }
        })
        .catch((err: string) => {
          this.loading = false;
          this.toastService.show(err, 'error');
        });
    });
  }

  private fetchVersions(): void {
    const crateName = this.packageName;

    this.loading = true;
    this.cargoService
      .fetchCrate(crateName)
      .then((crate) => {
        this.crate = crate;
        return this.cargoService.fetchCrateVersions(
          crateName,
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
