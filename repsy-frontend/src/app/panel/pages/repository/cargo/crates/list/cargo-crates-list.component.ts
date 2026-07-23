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
import { CrateListItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../../../../shared/components/ellipsis/ellipsis.pipe';
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
import { SecurityService } from '../../../../security/service/security.service';
import { CargoConfigComponent } from '../../config/cargo-config.component';
import { CargoService } from '../../service/cargo.service';

@Component({
  selector: 'app-cargo-crates-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    SpinnerComponent,
    CargoConfigComponent,
    DropdownComponent,
    TooltipComponent,
    EllipsisPipe,
    EmptyListComponent,
    NgOptimizedImage,
    PackageSecurityBadgeComponent,
  ],
  templateUrl: './cargo-crates-list.component.html',
})
export class CargoCratesListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public crates: CrateListItem[] = [];
  public pagedData = new PagedData<CrateListItem>();
  public activeRepo: RepoPermissionInfo;
  public securitySummary: Record<string, VersionSecuritySummary> = {};

  public sortOption: Sort = { name: 'Newest', column: 'maxVersion', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'maxVersion', type: 'DESC' },
    { name: 'Oldest', column: 'maxVersion', type: 'ASC' },
    { name: 'Name (A-Z)', column: 'name', type: 'ASC' },
    { name: 'Name (Z-A)', column: 'name', type: 'DESC' },
  ];
  public readonly baseUrl: string;
  public readonly username: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly securityService: SecurityService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.cargoService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchCrates();
        this.fetchSecuritySummary();
      }
    });
  }
  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchCrates();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchCrates();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchCrates();
  }

  public refreshPage(): void {
    this.fetchCrates();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public deleteCrate(crate: CrateListItem): void {
    this.dangerModalService.show('Delete Crate', 'Delete', () => {
      this.loading = true;
      this.cargoService
        .deleteCrate(crate.name)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Crate deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  private fetchCrates(): void {
    this.loading = true;
    this.cargoService
      .searchCrates(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<CrateListItem>) => {
          this.pagedData.page = pagedData.page;
          this.crates = pagedData.content;
          this.error = null;
        },
        error: () => {},
      });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public packageRoute(crate: CrateListItem): string {
    return `/${this.activeRepo.repoName}/${crate.name}`;
  }

  private fetchSecuritySummary(): void {
    this.securityService.getArtifactSecuritySummary(this.activeRepo.repoName).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      error: () => {},
    });
  }
}
