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
import { MavenConfigComponent } from '../../config/maven-config.component';
import { ArtifactVersionListItem } from '../../dto/artifact-version-list-item';
import { MavenService } from '../../service/maven.service';

@Component({
  selector: 'app-maven-artifacts-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    EmptyListComponent,
    MavenConfigComponent,
    SearchboxComponent,
    SortSelectorComponent,
    TooltipComponent,
    DropdownComponent,
    PaginationComponent,
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './maven-artifacts-version-list.component.html',
})
export class MavenArtifactsVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public baseUrl: string;
  public username: string;
  public pageNum = 0;
  public pageSize = 10;
  public pagedData: PagedData<ArtifactVersionListItem>;
  public activeRepo: RepoPermissionInfo;
  public versions: ArtifactVersionListItem[];
  public searchText = '';
  public error: string;
  public groupName: string;
  public artifactName: string;
  public sortOption: Sort = { name: 'Newest', column: 'versionName', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'versionName', type: 'DESC' },
    { name: 'Oldest', column: 'versionName', type: 'ASC' },
  ];

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly mavenService: MavenService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<ArtifactVersionListItem>();
    this.activeRepo = new RepoPermissionInfo();
    this.groupName = this.route.snapshot.paramMap.get('group');
    this.artifactName = this.route.snapshot.paramMap.get('artifact');
    this.repositoryChanges$ = this.mavenService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.fetchArtifactVersions();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchArtifactVersions();
  }

  public refreshPage(): void {
    this.fetchArtifactVersions();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchArtifactVersions();
  }

  public search(groupName: string) {
    this.pageNum = 0;
    this.searchText = groupName;
    this.fetchArtifactVersions();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public deleteVersion(version: ArtifactVersionListItem) {
    const versionCount = this.versions.length;

    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.mavenService
        .deleteVersion(this.groupName, this.artifactName, version.versionName)
        .then(() => {
          if (versionCount - 1 === 0) {
            this.router.navigateByUrl(`/${this.activeRepo.repoName}`).then(() => {
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

  private fetchArtifactVersions(): void {
    this.loading = true;
    this.mavenService
      .getArtifactVersionsLikeVersion(
        this.searchText,
        this.sortOption,
        this.groupName,
        this.artifactName,
        this.pageNum,
        this.pageSize,
      )
      .then((pagedData: PagedData<ArtifactVersionListItem>) => {
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
