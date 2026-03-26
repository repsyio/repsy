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
import { ActivatedRoute, RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { EllipsisPipe } from '../../../../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { PaginationComponent } from '../../../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../../../shared/components/searchbox/searchbox.component';
import { SortSelectorComponent } from '../../../../../shared/components/sort-selector/sort-selector.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { Sort } from '../../../../../shared/dto/sort';
import { DockerConfigComponent } from '../../config/docker-config.component';
import { getRepoDomain } from '../../docker-repo-util';
import { ManifestListItem } from '../../dto/manifest-list-item';
import { DockerService } from '../../service/docker.service';

@Component({
  selector: 'app-docker-manifest-list',
  standalone: true,
  imports: [
    CommonModule,
    DockerConfigComponent,
    RouterLink,
    EmptyListComponent,
    SearchboxComponent,
    SortSelectorComponent,
    TooltipComponent,
    EllipsisPipe,
    PaginationComponent,
    CopyClipboardComponent,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './docker-images-manifest-list.component.html',
})
export class DockerImagesManifestListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public installText: string;
  public imageName: string;
  public tagName: string;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public pagedData: PagedData<ManifestListItem>;
  public activeRepo: RepoPermissionInfo;
  public manifests: ManifestListItem[];

  public sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
  ];

  public readonly baseUrl: string;
  public readonly username: string;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly dockerService: DockerService,
    private readonly authService: AuthService,
    private readonly toastService: ToastService,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<ManifestListItem>();
    this.activeRepo = new RepoPermissionInfo();
    this.repositoryChanges$ = this.dockerService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.imageName = this.route.snapshot.paramMap.get('image');
        this.tagName = this.route.snapshot.paramMap.get('tag');
        this.installText = `docker pull ${getRepoDomain()}/${this.activeRepo.repoName}/${this.imageName}:${this.tagName}`;
        this.fetchManifests();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchManifests();
  }

  public refreshPage(): void {
    this.fetchManifests();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchManifests();
  }

  public search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchManifests();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchManifests(): void {
    this.loading = true;
    this.dockerService
      .fetchManifestsLikeName(
        this.searchText,
        this.sortOption,
        this.imageName,
        this.tagName,
        this.pageNum,
        this.pageSize,
      )
      .then((pagedData: PagedData<ManifestListItem>) => {
        this.pagedData.page = pagedData.page;
        this.manifests = pagedData.content;
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
