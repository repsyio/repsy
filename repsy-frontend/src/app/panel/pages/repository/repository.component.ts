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
import { finalize, map } from 'rxjs/operators';

import { ProtocolRepoControllerService, RepoSecuritySummary, RepoType as ApiRepoType } from '../../../../generated/api';
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
export class RepositoryComponent implements OnDestroy {
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
  /** Set when every requested type failed to load: the page shows its error state instead of a list. */
  public error = '';
  /** Set when only some of the requested types failed: the loaded ones are listed, and this says which are missing. */
  public warning = '';
  public isAdmin = false;
  /** The text of the search box: it is emptied whenever the list is loaded again, so box and list agree. */
  public searchQuery = '';
  public securitySummary: Record<string, RepoSecuritySummary> = {};

  private pendingRepoFetches = 0;
  private requestedRepoFetches = 0;
  private failedRepoTypes: RepoType[] = [];
  private securitySummarySubscription?: Subscription;
  /**
   * Identifies the current load. A load supersedes the previous one: its requests are cancelled and,
   * because an unsubscribe still runs `finalize`, every callback also checks that its own load is
   * still the current one before it touches the list or the counters.
   */
  private loadGeneration = 0;
  private loadSubscription?: Subscription;

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

  public ngOnDestroy(): void {
    this.loadGeneration++;
    this.loadSubscription?.unsubscribe();
    this.securitySummarySubscription?.unsubscribe();
  }

  public loadPage(pageNum: number): void {
    const startIndex = pageNum * this.pageSize;
    const endIndex = startIndex + this.pageSize;

    this.paginatedRepos = this.filteredRepos
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(startIndex, endIndex);
  }

  public search(repoName: string) {
    this.searchQuery = repoName;
    this.filteredRepos = this.repositories.filter((repo) => repo.name.toLowerCase().includes(repoName.toLowerCase()));

    this.pageNum = 0;
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
    // The list is unfiltered again and starts on its first page: the search box and the page index follow.
    this.searchQuery = '';
    this.pageNum = 0;
    this.loading = true;
    this.error = '';
    this.warning = '';
    this.failedRepoTypes = [];
    this.repositories = [];
    this.filteredRepos = [];
    this.paginatedRepos = [];
    this.securitySummary = {};
    this.securitySummarySubscription?.unsubscribe();
    // The generation moves first: unsubscribing runs the old requests' `finalize`, which must see that
    // its load is no longer the current one.
    this.loadGeneration++;
    this.loadSubscription?.unsubscribe();
    this.loadSubscription = new Subscription();

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
          // The HTTP error interceptor already shows the failure and the repository stays listed;
          // the handler only keeps the failure from becoming an unhandled RxJS error.
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
    this.fetchRepositoryTypes([
      RepoType.MAVEN,
      RepoType.NPM,
      RepoType.PYPI,
      RepoType.DOCKER,
      RepoType.CARGO,
      RepoType.GOLANG,
      RepoType.HELM,
      RepoType.NUGET,
      RepoType.RUBY,
    ]);
  }

  // The number of requests is fixed before the first one is sent, so the outcome (all failed, some
  // failed, none failed) is decided only once every one of them has answered.
  private fetchRepositoryTypes(repoTypes: RepoType[]): void {
    this.requestedRepoFetches = repoTypes.length;
    this.pendingRepoFetches = repoTypes.length;
    const generation = this.loadGeneration;
    repoTypes.forEach((repoType) => this.fetchRepositories(repoType, generation));
  }

  private fetchRepositories(repoType: RepoType, generation: number): void {
    const subscription = this.protocolRepoControllerService
      .getInfo(repoType.toUpperCase() as ApiRepoType)
      .pipe(
        finalize(() => {
          if (generation !== this.loadGeneration) {
            return;
          }
          this.pendingRepoFetches--;
          if (this.pendingRepoFetches === 0) {
            this.loading = false;
            this.reportFailedFetches();
            this.fetchSecuritySummary();
          } else if (this.repositories.length > 0) {
            // Rows show as soon as one type has some; while nothing has arrived the spinner stays,
            // so a load that is going to fail never flashes the empty state first.
            this.loading = false;
          }
        }),
        map((r) => r.data as unknown as RepoListItem[]),
      )
      .subscribe({
        next: (repos: RepoListItem[]) => {
          if (generation !== this.loadGeneration) {
            return;
          }
          const temp = (repos ?? []).map((repo: RepoListItem) => {
            repo.repoType = repoType;
            return repo;
          });

          this.repositories.push(...temp);
          this.filteredRepos.push(...temp);
          this.loadPage(0);
        },
        // The HTTP error interceptor already shows the toast; the failure is kept for the page.
        error: () => {
          if (generation !== this.loadGeneration) {
            return;
          }
          this.failedRepoTypes.push(repoType);
        },
      });
    this.loadSubscription?.add(subscription);
  }

  private reportFailedFetches(): void {
    if (this.failedRepoTypes.length === 0) {
      return;
    }

    if (this.failedRepoTypes.length === this.requestedRepoFetches) {
      this.error = 'The repositories could not be loaded. Use the refresh button to try again.';
    } else {
      this.warning = `Some repositories could not be loaded (${this.failedRepoTypes.join(', ')}), so the list is incomplete. Use the refresh button to try again.`;
    }
  }

  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();

    const repoNames = this.repositories.map((repo) => repo.name);
    if (repoNames.length === 0) {
      return;
    }

    this.securitySummarySubscription = this.securityService.watchSecuritySummary(repoNames).subscribe({
      next: (summary) => {
        this.securitySummary = summary;
      },
      // The HTTP error interceptor already shows the failure; the repositories stay listed, only
      // without their security badges.
      error: () => {},
    });
  }

  private loadAllRepos(option: string) {
    switch (option) {
      case RepoType.ALL:
        this.fetchAllRepositories();
        break;
      default:
        this.fetchRepositoryTypes([option as RepoType]);
        break;
    }
  }

  private loadUserRole(): void {
    this.profileFacadeService.get().subscribe({
      next: (profile) => {
        this.isAdmin = profile.role === 'ADMIN';
      },
      // Without a profile the user is treated as a non-admin: no create button, no delete. The HTTP
      // error interceptor already shows the failure.
      error: () => {
        this.isAdmin = false;
      },
    });
  }
}
