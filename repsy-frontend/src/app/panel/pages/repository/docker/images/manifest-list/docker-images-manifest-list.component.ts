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
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { ManifestListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { Sort } from '../../../../../shared/dto/sort';
import { versionLoadError } from '../../../../../shared/util/version-load-error.util';
import { DockerConfigComponent } from '../../config/docker-config.component';
import { getRepoDomain } from '../../docker-repo-util';
import { DockerService } from '../../service/docker.service';

@Component({
  selector: 'app-docker-images-manifest-list',
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
  loading = true;
  showConfig = false;
  installText: string;
  imageName: string;
  tagName: string;
  pageNum = 0;
  pageSize = 10;
  searchText = '';
  error: string;
  pagedData: PagedData<ManifestListItem>;
  activeRepo: RepoPermissionInfo;
  manifests: ManifestListItem[];

  sortOption: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
  sortOptions: Sort[] = [
    { name: 'Newest', column: 'createdAt', type: 'DESC' },
    { name: 'Oldest', column: 'createdAt', type: 'ASC' },
  ];

  readonly baseUrl: string;
  readonly username: string;
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
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.dockerService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.imageName = this.route.snapshot.paramMap.get('imageName');
        this.tagName = this.route.snapshot.paramMap.get('tagName');
        this.installText = `docker pull ${getRepoDomain()}/${this.activeRepo.repoName}/${this.imageName}:${this.tagName}`;
        this.fetchManifests();
      }
    });
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchManifests();
  }

  refreshPage(): void {
    this.fetchManifests();
  }

  sort(option: Sort) {
    this.sortOption = option;
    this.fetchManifests();
  }

  search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchManifests();
  }

  openConfig(open: boolean) {
    this.showConfig = open;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchManifests(): void {
    this.loading = true;
    this.error = null;
    this.dockerService
      .searchManifests(this.searchText, this.sortOption, this.imageName, this.tagName, this.pageNum, this.pageSize)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (pagedData: PagedData<ManifestListItem>) => {
          this.pagedData.page = pagedData.page;
          this.manifests = pagedData.content;
        },
        // A tag that does not exist (a mistyped link, a digest in the place of a tag, a tag deleted meanwhile) is a 404:
        // the page says so, it does not pass for an empty tag (RPS-1627).
        error: (err: unknown) => {
          this.manifests = undefined;
          this.error = versionLoadError(err, this.tagName);
        },
      });
  }

  get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
