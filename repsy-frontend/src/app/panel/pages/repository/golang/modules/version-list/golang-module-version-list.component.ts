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
import { GolangConfigComponent } from '../../config/golang-config.component';
import { ModuleVersionListItem } from '../../dto/module-version-list-item';
import { GolangService } from '../../service/golang.service';

@Component({
  selector: 'app-golang-module-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    GolangConfigComponent,
    DropdownComponent,
    EmptyListComponent,
    PaginationComponent,
    SearchboxComponent,
    SortSelectorComponent,
    TooltipComponent,
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './golang-module-version-list.component.html',
})
export class GolangModuleVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public modulePath: string;
  public pagedData: PagedData<ModuleVersionListItem>;
  public versions: ModuleVersionListItem[];
  public activeRepo: RepoPermissionInfo;
  public sortOption: Sort = { name: 'Newest', column: 'id', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'id', type: 'DESC' },
    { name: 'Oldest', column: 'id', type: 'ASC' },
  ];
  public readonly baseUrl: string;
  public readonly username: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly golangService: GolangService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly authService: AuthService,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<ModuleVersionListItem>();
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.golangService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.modulePath = this.route.snapshot.queryParamMap.get('modulePath');
        if (!this.modulePath) {
          this.router.navigate(['/' + this.activeRepo.repoName]);
          return;
        }
        this.fetchVersions();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchVersions();
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public search(text: string): void {
    this.pageNum = 0;
    this.searchText = text;
    this.fetchVersions();
  }

  public sort(option: Sort): void {
    this.sortOption = option;
    this.fetchVersions();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deleteVersion(version: ModuleVersionListItem): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.golangService
        .deleteModuleVersion(this.modulePath, version.version)
        .then(() => {
          if (this.versions.length - 1 === 0) {
            this.router.navigateByUrl('/' + this.activeRepo.repoName).then(() => {
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
    this.golangService
      .fetchModuleVersions(this.modulePath, this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .then((pagedData: PagedData<ModuleVersionListItem>) => {
        this.pagedData.page = pagedData.page;
        this.versions = pagedData.content;
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
