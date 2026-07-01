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
import { GemListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { RubyConfigComponent } from '../../config/ruby-config.component';
import { RubyService } from '../../service/ruby.service';

@Component({
  selector: 'app-ruby-gems-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    SpinnerComponent,
    RubyConfigComponent,
    DropdownComponent,
    TooltipComponent,
    EllipsisPipe,
    EmptyListComponent,
    NgOptimizedImage,
  ],
  templateUrl: './ruby-gems-list.component.html',
})
export class RubyGemsListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public gems: GemListItem[] = [];
  public pagedData = new PagedData<GemListItem>();
  public activeRepo: RepoPermissionInfo;

  public sortOption: Sort = { name: 'Newest', column: 'updatedAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'updatedAt', type: 'DESC' },
    { name: 'Oldest', column: 'updatedAt', type: 'ASC' },
    { name: 'Name (A-Z)', column: 'name', type: 'ASC' },
    { name: 'Name (Z-A)', column: 'name', type: 'DESC' },
  ];
  public readonly baseUrl: string;
  public readonly username: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly rubyService: RubyService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.rubyService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchGems();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchGems();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchGems();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchGems();
  }

  public refreshPage(): void {
    this.fetchGems();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public deleteGem(gem: GemListItem): void {
    this.dangerModalService.show('Delete Gem', 'Delete', () => {
      this.loading = true;
      this.rubyService
        .deleteGem(gem.name)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Gem deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  private fetchGems(): void {
    this.loading = true;
    this.rubyService
      .searchGems(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<GemListItem>) => {
          this.pagedData.page = pagedData.page;
          this.gems = pagedData.content;
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
}
