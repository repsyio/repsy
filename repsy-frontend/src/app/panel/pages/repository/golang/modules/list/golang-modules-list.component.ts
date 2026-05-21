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
import { GoModuleListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { Sort } from '../../../../../shared/dto/sort';
import { GolangConfigComponent } from '../../config/golang-config.component';
import { GolangService } from '../../service/golang.service';

@Component({
  selector: 'app-golang-modules-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    EmptyListComponent,
    GolangConfigComponent,
    PaginationComponent,
    SearchboxComponent,
    SortSelectorComponent,
    DropdownComponent,
    TooltipComponent,
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './golang-modules-list.component.html',
})
export class GolangModulesListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public pagedData: PagedData<GoModuleListItem>;
  public activeRepo: RepoPermissionInfo;
  public sortOption: Sort = { name: 'Newest', column: 'id', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'id', type: 'DESC' },
    { name: 'Oldest', column: 'id', type: 'ASC' },
  ];

  public modules: GoModuleListItem[];

  public readonly baseUrl: string;
  public readonly username: string;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly golangService: GolangService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<GoModuleListItem>();
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.golangService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchModules();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchModules();
  }

  public refreshPage(): void {
    this.fetchModules();
  }

  public search(modulePath: string) {
    this.pageNum = 0;
    this.searchText = modulePath;
    this.fetchModules();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchModules();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deleteModule(mod: GoModuleListItem) {
    this.dangerModalService.show('Delete Module', 'Delete', () => {
      this.golangService
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

    const fetch = this.searchText
      ? this.golangService.searchModules(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      : this.golangService.fetchModules(this.sortOption, this.pageNum, this.pageSize);

    fetch
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

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
