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
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import moment from 'moment';
import { finalize, map } from 'rxjs/operators';

import {
  ProtocolRepoControllerService,
  RepoSecuritySummary,
  RepoType as ApiRepoType,
} from '../../../../generated/api';
import { SpinnerComponent } from '../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../shared/components/modals/danger-modal/danger-modal.service';
import { RepositoryCreateModalComponent } from '../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { PaginationComponent } from '../../shared/components/pagination/pagination.component';
import { RepoSecurityBadgeComponent } from '../../shared/components/repo-security-badge/repo-security-badge.component';
import { SearchboxComponent } from '../../shared/components/searchbox/searchbox.component';
import { SelectorComponent } from '../../shared/components/selector/selector.component';
import { ToastService } from '../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../shared/components/tooltip/tooltip.component';
import { RepoListItem } from '../../shared/dto/repo/repo-list-item';
import { RepoType } from '../../shared/dto/repo/repo-type';
import { ByteFormatter } from '../../shared/util/byte-formatter';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';

@Component({
  selector: 'app-repository',
  standalone: true,
  imports: [
    CommonModule,
    NgOptimizedImage,
    EmptyListComponent,
    SearchboxComponent,
    SelectorComponent,
    RouterLink,
    DropdownComponent,
    PaginationComponent,
    RepositoryCreateModalComponent,
    TooltipComponent,
    EllipsisPipe,
    SpinnerComponent,
    RepoSecurityBadgeComponent,
  ],
  templateUrl: './repository.component.html',
})
export class RepositoryComponent {
  public pageNum = 0;
  public pageSize = 10;
  public repositories: RepoListItem[] = [];
  public filteredRepos: RepoListItem[] = [];
  public paginatedRepos: RepoListItem[] = [];
  public createRepoModal: boolean;
  public repoOption = RepoType.ALL;
  public repoOptions = [
    RepoType.ALL,
    RepoType.DOCKER,
    RepoType.MAVEN,
    RepoType.NPM,
    RepoType.PYPI,
    RepoType.CARGO,
    RepoType.GOLANG,
    RepoType.HELM,
    RepoType.NUGET,
    RepoType.RUBY,
  ];
  public loading = true;
  public operationLock = false;
  public username: string;
  public error: string;
  public isAdmin = false;
  public securitySummary: Record<string, RepoSecuritySummary> = {};

  private pendingRepoFetches = 0;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly securityService: SecurityService,
    private readonly profileFacadeService: ProfileService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    const state = window.history.state;

    if (state && state.repoType) {
      this.repoOption = state.repoType as RepoType;
    }

    this.loadUserRole();
    this.filterRepos(this.repoOption);
  }

  public loadPage(pageNum: number): void {
    const startIndex = pageNum * this.pageSize;
    const endIndex = startIndex + this.pageSize;

    this.paginatedRepos = this.filteredRepos
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(startIndex, endIndex);
  }

  public search(repoName: string) {
    this.filteredRepos = this.repositories.filter((repo) => repo.name.toLowerCase().includes(repoName.toLowerCase()));

    this.loadPage(0);
  }

  public refreshPage(): void {
    this.filterRepos(this.repoOption);
  }

  public getTotalPages(): number {
    return Math.ceil(this.filteredRepos.length / this.pageSize);
  }

  public formatBytes(bytes: number, decimals = 2): string {
    return ByteFormatter.formatBytes(bytes, decimals);
  }

  public filterRepos(option: string) {
    this.loading = true;
    this.repositories = [];
    this.filteredRepos = [];
    this.securitySummary = {};

    this.loadAllRepos(option);
  }

  public deleteRepository(repo: RepoListItem) {
    if (!this.isAdmin) {
      this.toastService.show('You do not have permission to delete repositories', 'error');
      return;
    }
    this.dangerModalService.show('Delete Repository', 'Delete', () => {
      this.operationLock = true;
      this.protocolRepoControllerService
        .deleteRepo(repo.name)
        .pipe(
          finalize(() => {
            this.operationLock = false;
          }),
          map(() => undefined),
        )
        .subscribe({
          next: () => {
            this.refreshPage();
            this.toastService.show('Repository deleted successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  public openCreateRepoModal() {
    this.createRepoModal = true;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchAllRepositories(): void {
    this.fetchRepositories(RepoType.MAVEN);
    this.fetchRepositories(RepoType.NPM);
    this.fetchRepositories(RepoType.PYPI);
    this.fetchRepositories(RepoType.DOCKER);
    this.fetchRepositories(RepoType.CARGO);
    this.fetchRepositories(RepoType.GOLANG);
    this.fetchRepositories(RepoType.HELM);
    this.fetchRepositories(RepoType.NUGET);
    this.fetchRepositories(RepoType.RUBY);
  }

  private fetchRepositories(repoType: RepoType): void {
    this.loading = true;
    this.pendingRepoFetches++;
    this.protocolRepoControllerService
      .getInfo(repoType.toUpperCase() as ApiRepoType)
      .pipe(
        finalize(() => {
          this.loading = false;
          this.pendingRepoFetches--;
          if (this.pendingRepoFetches === 0) {
            this.fetchSecuritySummary();
          }
        }),
        map((r) => r.data as unknown as RepoListItem[]),
      )
      .subscribe({
        next: (repos: RepoListItem[]) => {
          const temp = (repos ?? []).map((repo: RepoListItem) => {
            repo.repoType = repoType;
            return repo;
          });

          this.repositories.push(...temp);
          this.filteredRepos.push(...temp);
          this.loadPage(0);
        },
        error: () => {},
      });
  }




  private fetchSecuritySummary(): void {
    const repoNames = this.repositories.map((repo) => repo.name);
    if (repoNames.length === 0) {
      return;
    }

    this.securityService.getSecuritySummary(repoNames).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      error: () => {},
    });
  }

  private loadAllRepos(option: string) {
    switch (option) {
      case RepoType.ALL:
        this.fetchAllRepositories();
        break;
      default:
        this.fetchRepositories(option as RepoType);
        break;
    }
  }

  private loadUserRole(): void {
    this.profileFacadeService.get().subscribe((profile) => {
      this.isAdmin = profile.role === 'ADMIN';
    });
  }
}
