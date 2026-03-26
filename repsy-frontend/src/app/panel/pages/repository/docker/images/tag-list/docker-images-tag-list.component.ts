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
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
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
import { DockerConfigComponent } from '../../config/docker-config.component';
import { getRepoDomain } from '../../docker-repo-util';
import { TagListItem } from '../../dto/tag-list-item';
import { DockerService } from '../../service/docker.service';

@Component({
  selector: 'app-docker-tag-list',
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
    DropdownComponent,
    PaginationComponent,
    NgOptimizedImage,
    SpinnerComponent,
    CopyClipboardComponent,
  ],
  templateUrl: './docker-images-tag-list.component.html',
})
export class DockerImagesTagListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public installText: string;
  public imageName: string;
  public pageNum = 0;
  public pageSize = 10;
  public searchText = '';
  public error: string;
  public pagedData: PagedData<TagListItem>;
  public activeRepo: RepoPermissionInfo;
  public tags: TagListItem[];

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
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<TagListItem>();
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.dockerService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.imageName = this.route.snapshot.paramMap.get('image');
        this.installText = `docker pull ${getRepoDomain()}/${this.activeRepo.repoName}/${this.imageName}`;
        this.fetchTags();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchTags();
  }

  public refreshPage(): void {
    this.fetchTags();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchTags();
  }

  public search(packageName: string) {
    this.pageNum = 0;
    this.searchText = packageName;
    this.fetchTags();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchTags(): void {
    this.loading = true;
    this.dockerService
      .fetchImageTagsLikeName(this.searchText, this.sortOption, this.imageName, this.pageNum, this.pageSize)
      .then((pagedData: PagedData<TagListItem>) => {
        this.pagedData.page = pagedData.page;
        this.tags = pagedData.content;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deleteTag(tag: TagListItem) {
    this.dangerModalService.show('Delete Tag', 'Delete', () => {
      this.loading = true;
      this.dockerService
        .deleteTag(this.imageName, tag.name)
        .then(() => {
          if (this.tags.length - 1 === 0) {
            this.router.navigate([`/${this.activeRepo.repoName}`]).then(() => {
              this.toastService.show('Tag deleted successfully', 'success');
            });
          } else {
            this.refreshPage();
            this.toastService.show('Tag deleted successfully', 'success');
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

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
