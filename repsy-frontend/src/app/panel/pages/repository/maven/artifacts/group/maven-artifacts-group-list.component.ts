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
import { ArtifactListItem, MavenGroupSummary, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { Sort } from '../../../../../shared/dto/sort';
import { MavenConfigComponent } from '../../config/maven-config.component';
import { MavenService } from '../../service/maven.service';

function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** The dialog's message: the group, and what deleting it removes (all of it, when the counts are not known). */
export function groupDeleteWarning(groupName: string, summary?: MavenGroupSummary): string {
  const what = summary
    ? `${plural(summary.artifactCount, 'artifact')} and ${plural(summary.versionCount, 'version')}`
    : 'all of its artifacts and versions';
  return `The whole group ${groupName} will be deleted, not just this artifact: ${what}. This cannot be undone.`;
}

@Component({
  selector: 'app-maven-group-list',
  standalone: true,
  imports: [
    CommonModule,
    EmptyListComponent,
    SearchboxComponent,
    SortSelectorComponent,
    MavenConfigComponent,
    RouterLink,
    DropdownComponent,
    PaginationComponent,
    TooltipComponent,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './maven-artifacts-group-list.component.html',
})
export class MavenArtifactsGroupListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public baseUrl: string;
  public username: string;
  public pageNum = 0;
  public pageSize = 10;
  public pagedData: PagedData<ArtifactListItem>;
  public activeRepo: RepoPermissionInfo;
  public artifacts: ArtifactListItem[];
  public searchText = '';
  public error: string;
  public sortOption: Sort = { name: 'Newest', column: 'artifactName', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'artifactName', type: 'DESC' },
    { name: 'Oldest', column: 'artifactName', type: 'ASC' },
  ];

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly mavenService: MavenService,
    private readonly dangerModalService: DangerModalService,
    private readonly toastService: ToastService,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<ArtifactListItem>();
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.mavenService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchArtifacts();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchArtifacts();
  }

  public refreshPage(): void {
    this.fetchArtifacts();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchArtifacts();
  }

  public search(groupName: string) {
    this.pageNum = 0;
    this.searchText = groupName;
    this.fetchArtifacts();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  /**
   * Deleting from this list removes the whole GROUP, although a row is one artifact (RPS-1288): the
   * confirmation names the group and says how many artifacts and versions go with it.
   */
  public deleteGroup(artifact: ArtifactListItem) {
    const groupName = artifact.groupName;
    this.mavenService.getGroupSummary(groupName).subscribe({
      next: (summary) => this.confirmGroupDelete(groupName, groupDeleteWarning(groupName, summary)),
      // The counts are a courtesy: without them the dialog still names the group and what goes with it.
      error: () => this.confirmGroupDelete(groupName, groupDeleteWarning(groupName)),
    });
  }

  private confirmGroupDelete(groupName: string, warning: string) {
    this.dangerModalService.showWithMessage('Delete Group', 'Delete', warning, () => {
      this.loading = true;
      this.mavenService
        .deleteGroup(groupName)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Group deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  private fetchArtifacts(): void {
    this.loading = true;
    this.mavenService
      .searchGroups(this.searchText, this.sortOption, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<ArtifactListItem>) => {
          this.pagedData.page = pagedData.page;
          this.artifacts = pagedData.content;
        },
        error: () => {},
      });
  }
}
