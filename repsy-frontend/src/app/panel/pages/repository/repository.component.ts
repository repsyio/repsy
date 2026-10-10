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
import { Component, ElementRef, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import moment from 'moment';
import { of, Subject, Subscription, timer } from 'rxjs';
import { catchError, filter, finalize, map, switchMap, tap } from 'rxjs/operators';

import { PagedModelRepoListInfo, RepoListInfo, ReposApi, RepoSecuritySummary } from '../../../../generated/api';
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
import { restoreListFocus } from '../../shared/util/list-focus-restore.util';
import { readListPageParam, readListQueryParam, updateListQueryParams } from '../../shared/util/list-query-params.util';
import { toApiRepoType, toRouteSlug } from '../../shared/util/repo-api-type';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';

/** The list is sorted like the server sorts by default: the newest repository first. */
export const REPO_LIST_SORT = 'createdAt,desc';
/** How long the search box must be idle before the typed text is sent to the server. */
export const SEARCH_DEBOUNCE_MS = 250;

/** One request of the list: what the server is asked for, and whether the page shows its spinner meanwhile. */
interface ListRequest {
  option: string;
  q: string;
  page: number;
  /** True for a load that starts from nothing (first load, type change, refresh); a page or search change keeps the rows until the answer arrives. */
  spinner: boolean;
  /** Whatever had the keyboard focus when this request was dispatched (RPS-1669), or `null`/`body` for
   *  a mouse-driven one; the fallback below only reclaims the focus once its own control is really gone. */
  previouslyFocused?: Element | null;
}

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
  pageNum = 0;
  pageSize = 10;
  /** The rows of the page the server answered with. */
  paginatedRepos: RepoListItem[] = [];
  /** The total number of pages of the current type and search, from the server's page metadata. */
  totalPages = 0;
  createRepoModal: boolean;
  repoOption = RepoType.ALL;
  repoOptions = [
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
  loading = true;
  operationLock = false;
  username: string;
  /** Set when the list could not be loaded: the page shows its error state instead of a list. */
  error = '';
  isAdmin = false;
  /** The text of the search box: it is emptied whenever the list is loaded again, so box and list agree. */
  searchQuery = '';
  securitySummary: Record<string, RepoSecuritySummary> = {};

  /** The search the list currently shows; `searchQuery` runs ahead of it while the typing is debounced. */
  private appliedQuery = '';
  private securitySummarySubscription?: Subscription;
  private readonly requests = new Subject<ListRequest>();
  private readonly typedSearches = new Subject<string>();
  private readonly subscriptions = new Subscription();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly reposApi: ReposApi,
    private readonly securityService: SecurityService,
    private readonly profileFacadeService: ProfileService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly elementRef: ElementRef<HTMLElement>,
  ) {
    // A request supersedes the one before it: switchMap unsubscribes from it, which cancels it on the wire.
    this.subscriptions.add(
      this.requests
        .pipe(
          tap((request) => this.startLoading(request)),
          switchMap((request) =>
            this.reposApi
              .listRepos(toApiRepoType(request.option), request.q || undefined, request.page, this.pageSize, [
                REPO_LIST_SORT,
              ])
              .pipe(
                map((response) => ({ request, page: response })),
                // The HTTP error interceptor already shows the toast; the failure is kept for the page.
                catchError(() => of({ request, page: null })),
              ),
          ),
        )
        .subscribe(({ request, page }) => this.showResult(request, page)),
    );

    // The typed text is sent once the box has been idle; a reload that emptied the box meanwhile drops it.
    this.subscriptions.add(
      this.typedSearches
        .pipe(
          switchMap((text) => timer(SEARCH_DEBOUNCE_MS).pipe(map(() => text))),
          filter((text) => text === this.searchQuery),
        )
        .subscribe((text) => this.applySearch(text)),
    );

    this.loadUserRole();

    // RPS-1668: the type, search and page of the list live in the URL, so a reload or a Back
    // navigation restores exactly what was left instead of an unfiltered, first page. A dashboard
    // count card also lands here through `type` now (it used to set `history.state`, which a reload
    // does not carry).
    const typeParam = readListQueryParam(this.route, 'type');
    const initialType =
      typeParam !== null && this.repoOptions.includes(typeParam as RepoType) ? (typeParam as RepoType) : RepoType.ALL;
    const initialQuery = readListQueryParam(this.route, 'q') ?? '';
    const initialPage = readListPageParam(this.route, 'page', 0);

    this.repoOption = initialType;
    this.searchQuery = initialQuery;
    this.appliedQuery = initialQuery;
    this.pageNum = initialPage;
    this.dispatch({ option: initialType, q: initialQuery, page: initialPage, spinner: true });
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.securitySummarySubscription?.unsubscribe();
  }

  loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    // No `previouslyFocused` here: the pager stays mounted through a plain page change (its button keeps
    // the focus by itself, see the pagination component), so there is nothing to fall back to.
    this.dispatch({ option: this.repoOption, q: this.appliedQuery, page: pageNum, spinner: false });
  }

  /** Called on every keystroke; the request goes out when the typing pauses, and it starts from the first page. */
  search(repoName: string) {
    this.searchQuery = repoName;
    this.typedSearches.next(repoName);
  }

  /** `previouslyFocused` lets a caller (a delete, whose opener is about to vanish) name the control that
   *  had the focus before it starts asking (RPS-1669), instead of the moment this reload actually goes
   *  out; a plain refresh (the toolbar button, a modal's `created` event) needs no fallback and leaves it
   *  out. */
  refreshPage(previouslyFocused: Element | null = null): void {
    this.filterRepos(this.repoOption, previouslyFocused);
  }

  formatBytes(bytes: number, decimals = 2): string {
    return ByteFormatter.formatBytes(bytes, decimals);
  }

  filterRepos(option: string, previouslyFocused: Element | null = null) {
    // The list is unfiltered again and starts on its first page: the search box and the page index follow.
    this.repoOption = option as RepoType;
    this.searchQuery = '';
    this.appliedQuery = '';
    this.pageNum = 0;
    this.dispatch({ option, q: '', page: 0, spinner: true, previouslyFocused });
  }

  deleteRepository(repo: RepoListItem) {
    if (!this.isAdmin) {
      this.toastService.show('You do not have permission to delete repositories', 'error');
      return;
    }
    // Captured now (RPS-1669): by the time the reload actually fires, the danger modal has already
    // closed and, since its own opener (this row's menu item) is gone, given up on restoring the focus.
    const previouslyFocused = document.activeElement;
    this.dangerModalService.show('Delete Repository', 'Delete', () => {
      this.operationLock = true;
      this.reposApi
        .deleteRepo(repo.name)
        .pipe(
          finalize(() => {
            this.operationLock = false;
          }),
          map(() => undefined),
        )
        .subscribe({
          next: () => {
            this.refreshPage(previouslyFocused);
            this.toastService.show('Repository deleted successfully', 'success');
          },
          // The HTTP error interceptor already shows the failure and the repository stays listed;
          // the handler only keeps the failure from becoming an unhandled RxJS error.
          error: () => {},
        });
    });
  }

  openCreateRepoModal() {
    this.createRepoModal = true;
  }

  timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private applySearch(text: string): void {
    this.appliedQuery = text;
    this.pageNum = 0;
    this.dispatch({ option: this.repoOption, q: text, page: 0, spinner: false });
  }

  /**
   * Sends `request` and, with `replaceUrl: true`, keeps the URL's `type`, `q` and `page` query
   * params in step with it (RPS-1668) so the current history entry always reflects what the list is
   * actually showing.
   */
  private dispatch(request: ListRequest): void {
    updateListQueryParams(this.router, this.route, {
      type: request.option === RepoType.ALL ? null : request.option,
      q: request.q || null,
      page: request.page || null,
    });
    this.requests.next(request);
  }

  private startLoading(request: ListRequest): void {
    this.error = '';
    // The badges of the rows that are about to be replaced are not polled any longer.
    this.securitySummarySubscription?.unsubscribe();

    if (request.spinner) {
      this.loading = true;
      this.paginatedRepos = [];
      this.totalPages = 0;
      this.securitySummary = {};
    }
  }

  private showResult(request: ListRequest, page: PagedModelRepoListInfo | null | undefined): void {
    this.loading = false;

    if (page === null) {
      this.error = 'The repositories could not be loaded. Use the refresh button to try again.';
      this.paginatedRepos = [];
      this.totalPages = 0;
      this.securitySummary = {};
      return;
    }

    const content = page?.content ?? [];
    const totalPages = page?.page?.totalPages ?? 0;

    if (content.length === 0 && request.page > 0) {
      // The page is gone (repositories were deleted meanwhile): show the last one that is left.
      this.pageNum = Math.max(0, totalPages - 1);
      this.dispatch({ ...request, page: this.pageNum, spinner: false });
      return;
    }

    this.totalPages = totalPages;
    this.paginatedRepos = content.map((repo) => this.toListItem(repo));
    this.securitySummary = {};
    this.fetchSecuritySummary();
    // RPS-1669: a page change keeps its own focus (the pager stays mounted); this only does something
    // when the control that had it (a deleted row's menu) is really gone once the new rows are in.
    restoreListFocus(request.previouslyFocused, this.elementRef.nativeElement);
  }

  private toListItem(repo: RepoListInfo): RepoListItem {
    return {
      name: repo.name,
      privateRepo: repo.privateRepo ?? false,
      repoType: toRouteSlug(repo.type) ?? '',
      createdAt: repo.createdAt,
      diskUsage: repo.diskUsage ?? 0,
    };
  }

  /** Only the repositories of the page on screen are asked about; the summary is polled while a scan is unfinished. */
  private fetchSecuritySummary(): void {
    this.securitySummarySubscription?.unsubscribe();

    const repoNames = this.paginatedRepos.map((repo) => repo.name);
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
