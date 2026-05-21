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
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { ArtifactListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { MavenConfigComponent } from '../../config/maven-config.component';
import { MavenService } from '../../service/maven.service';

@Component({
  selector: 'app-maven-artifact',
  standalone: true,
  imports: [
    CommonModule,
    MavenConfigComponent,
    EmptyListComponent,
    SearchboxComponent,
    SortSelectorComponent,
    PaginationComponent,
    TooltipComponent,
    DropdownComponent,
    RouterLink,
    EllipsisPipe,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './maven-artifacts-list.component.html',
})
export class MavenArtifactsListComponent implements OnDestroy {
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
  public groupName: string;
  public sortOption: Sort = { name: 'Newest', column: 'groupName', type: 'DESC' };
  public sortOptions: Sort[] = [
    { name: 'Newest', column: 'groupName', type: 'DESC' },
    { name: 'Oldest', column: 'groupName', type: 'ASC' },
  ];

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly authService: AuthService,
    private readonly mavenService: MavenService,
    private readonly route: ActivatedRoute,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.username = this.authService.username;
    this.pagedData = new PagedData<ArtifactListItem>();
    this.activeRepo = {} as RepoPermissionInfo;
    this.groupName = this.route.snapshot.paramMap.get('group');
    this.repositoryChanges$ = this.mavenService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.fetchGroupArtifacts();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchGroupArtifacts();
  }

  public refreshPage(): void {
    this.fetchGroupArtifacts();
  }

  public sort(option: Sort) {
    this.sortOption = option;
    this.fetchGroupArtifacts();
  }

  public search(groupName: string) {
    this.pageNum = 0;
    this.searchText = groupName;
    this.fetchGroupArtifacts();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  public imageContain(image: string): boolean {
    const images: string[] = ['jar', 'war', 'maven-plugin', 'pom', 'aar'];
    return images.includes(image);
  }

  public deleteArtifact(artifact: ArtifactListItem) {
    this.dangerModalService.show('Delete Artifact', 'Delete', () => {
      this.loading = true;
      this.mavenService
        .deleteArtifact(artifact.groupName, artifact.artifactName)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            if (this.artifacts.length === 1) {
              this.router.navigateByUrl(`/${this.activeRepo.repoName}`).then(() => {
                this.toastService.show('Artifact deleted successfully', 'success');
              });
            } else {
              this.refreshPage();
              this.toastService.show('Artifact deleted successfully', 'success');
            }
          },
          error: () => {},
        });
    });
  }

  private fetchGroupArtifacts(): void {
    this.loading = true;
    this.mavenService
      .searchArtifacts(this.groupName, this.searchText, this.sortOption, this.pageNum, this.pageSize)
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

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
