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
import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import {
  ProtocolRepoControllerService,
  RepoCollectionControllerService,
  RepoListInfo,
  RepoSecuritySummary,
  RepoType as ApiRepoType,
} from '../../../../generated/api';
import { DangerModalService } from '../../shared/components/modals/danger-modal/danger-modal.service';
import { RepositoryCreateModalComponent } from '../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { ToastService } from '../../shared/components/toast/toast.service';
import { RepoType } from '../../shared/dto/repo/repo-type';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';
import { REPO_LIST_SORT, RepositoryComponent, SEARCH_DEBOUNCE_MS } from './repository.component';

const SCANNING: Record<string, RepoSecuritySummary> = {
  'my-repo': { scanned: false, unscannedInProgressCount: 1 } as RepoSecuritySummary,
};

function repo(name: string, type: ApiRepoType = ApiRepoType.Maven): RepoListInfo {
  return { name, type, privateRepo: true, diskUsage: 1234, createdAt: '2026-01-01T00:00:00Z' };
}

function page(content: RepoListInfo[], totalPages = 1, number = 0) {
  return { data: { content, page: { number, size: 10, totalElements: content.length, totalPages } } };
}

/** One `listRepos` call: what it was asked for, and the subject the test answers it with. */
interface ListCall {
  type: ApiRepoType | undefined;
  q: string | undefined;
  page: number;
  size: number;
  sort: string[];
  answer: Subject<unknown>;
}

describe('RepositoryComponent', () => {
  let calls: ListCall[];
  let repoApi: jasmine.SpyObj<RepoCollectionControllerService>;
  let protocolApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toast: jasmine.SpyObj<ToastService>;
  let dangerModal: DangerModalService;
  let profile: { get: jasmine.Spy };
  let watched: Subject<Record<string, RepoSecuritySummary>>;
  let created: RepositoryComponent[];
  let fixtures: ComponentFixture<RepositoryComponent>[];

  function create(): RepositoryComponent {
    const component = new RepositoryComponent(
      repoApi,
      protocolApi,
      securityService,
      profile as unknown as ProfileService,
      toast,
      dangerModal,
    );
    created.push(component);
    return component;
  }

  const last = (): ListCall => calls[calls.length - 1];
  const names = (component: RepositoryComponent): string[] => component.paginatedRepos.map((r) => r.name);

  /** Answers the newest call with a page. */
  function answer(content: RepoListInfo[], totalPages = 1, number = 0): void {
    // The call is fixed first: an answer can start the next call (an emptied page asks for the one before).
    const call = last();
    call.answer.next(page(content, totalPages, number));
    call.answer.complete();
  }

  beforeEach(() => {
    calls = [];
    created = [];
    fixtures = [];
    watched = new Subject();
    repoApi = jasmine.createSpyObj<RepoCollectionControllerService>('RepoCollectionControllerService', ['listRepos']);
    // Positional, exactly like the generated client: (type, q, page, size, sort). q and sort are strings, so a
    // swapped order would still compile; recording them by position makes the specs fail on it.
    repoApi.listRepos.and.callFake(((
      type: ApiRepoType | undefined,
      q: string | undefined,
      pageIndex: number,
      size: number,
      sort: string[],
    ) => {
      const answerSubject = new Subject<unknown>();
      calls.push({ type, q, page: pageIndex, size, sort, answer: answerSubject });
      return answerSubject;
    }) as never);
    protocolApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['deleteRepo']);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(watched);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModal = new DangerModalService();
    profile = { get: jasmine.createSpy('get').and.returnValue(of({ role: 'ADMIN' })) };
    window.history.replaceState(null, '');
  });

  afterEach(() => {
    fixtures.forEach((fixture) => fixture.destroy());
    created.forEach((component) => component.ngOnDestroy());
    window.history.replaceState(null, '');
  });

  describe('the request', () => {
    it('is ONE listRepos call per load, in the generated argument order, newest first, ten rows', () => {
      create();

      expect(repoApi.listRepos).toHaveBeenCalledTimes(1);
      expect(repoApi.listRepos).toHaveBeenCalledWith(undefined, undefined, 0, 10, ['createdAt,desc']);
      expect(REPO_LIST_SORT).toBe('createdAt,desc');
    });

    it('sends the chosen type upper-case as the type argument, not the search', () => {
      const component = create();

      component.filterRepos(RepoType.NPM);

      expect(repoApi.listRepos).toHaveBeenCalledTimes(2);
      expect(repoApi.listRepos.calls.mostRecent().args).toEqual([
        ApiRepoType.Npm,
        undefined,
        0,
        10,
        ['createdAt,desc'],
      ]);
    });

    it('starts with the type the dashboard card put into the router state', () => {
      window.history.replaceState({ repoType: 'docker' }, '');

      const component = create();

      expect(component.repoOption).toBe(RepoType.DOCKER);
      expect(last().type).toBe(ApiRepoType.Docker);
    });

    it('ignores a router state that is not a repository type', () => {
      window.history.replaceState({ repoType: 'bogus' }, '');

      const component = create();

      expect(component.repoOption).toBe(RepoType.ALL);
      expect(last().type).toBeUndefined();
    });
  });

  describe('the rows', () => {
    it('show the server page, with the type lower-case for the icons, and the page count from its metadata', () => {
      const component = create();
      expect(component.loading).toBeTrue();

      answer([repo('a-maven'), repo('an-npm', ApiRepoType.Npm)], 3);

      expect(component.loading).toBeFalse();
      expect(component.error).toBe('');
      expect(component.paginatedRepos).toEqual([
        { name: 'a-maven', privateRepo: true, repoType: 'maven', createdAt: '2026-01-01T00:00:00Z', diskUsage: 1234 },
        { name: 'an-npm', privateRepo: true, repoType: 'npm', createdAt: '2026-01-01T00:00:00Z', diskUsage: 1234 },
      ]);
      expect(component.totalPages).toBe(3);
    });

    it('are empty, with no page, when the server has none', () => {
      const component = create();

      answer([], 0);

      expect(component.paginatedRepos).toEqual([]);
      expect(component.totalPages).toBe(0);
      expect(component.error).toBe('');
    });

    it('keep the spinner until the answer is in', () => {
      const component = create();

      expect(component.loading).toBeTrue();
      expect(component.paginatedRepos).toEqual([]);
    });
  });

  describe('a failed load', () => {
    it('shows the error state, no rows, no security watch and no partial state', () => {
      const component = create();

      last().answer.error(new Error('boom'));

      expect(component.loading).toBeFalse();
      expect(component.error).not.toBe('');
      expect(component.paginatedRepos).toEqual([]);
      expect(component.totalPages).toBe(0);
      expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();
    });

    it('is retried by refresh, which clears the error and lists what now loads', () => {
      const component = create();
      last().answer.error(new Error('boom'));

      component.refreshPage();
      expect(component.error).toBe('');
      expect(component.loading).toBeTrue();
      answer([repo('a-maven')]);

      expect(component.error).toBe('');
      expect(component.loading).toBeFalse();
      expect(names(component)).toEqual(['a-maven']);
    });

    it('turns a successful list into the error state when a refresh fails', () => {
      const component = create();
      answer([repo('a-maven')]);

      component.refreshPage();
      last().answer.error(new Error('boom'));

      expect(component.error).not.toBe('');
      expect(component.paginatedRepos).toEqual([]);
    });

    it('also shows the error state when a search or a page change fails', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')], 2);

      component.loadPage(1);
      last().answer.error(new Error('boom'));

      expect(component.error).not.toBe('');
    }));

    it('has no partial-failure warning any more', () => {
      const component = create();

      expect((component as unknown as { warning?: string }).warning).toBeUndefined();
    });
  });

  describe('paging', () => {
    it('loadPage asks for that page of the same type and search, and keeps the rows until it answers', () => {
      const component = create();
      answer([repo('a-maven')], 3);

      component.loadPage(2);

      expect(last()).toEqual(jasmine.objectContaining({ type: undefined, q: undefined, page: 2, size: 10 }));
      expect(component.pageNum).toBe(2);
      expect(component.loading).toBeFalse();
      expect(names(component)).toEqual(['a-maven']);

      answer([repo('third')], 3, 2);
      expect(names(component)).toEqual(['third']);
    });

    it('keeps the type and the search of the list on a later page', fakeAsync(() => {
      const component = create();
      component.filterRepos(RepoType.NPM);
      answer([repo('web-app', ApiRepoType.Npm)], 3);
      component.search('web');
      tick(SEARCH_DEBOUNCE_MS);
      answer([repo('web-app', ApiRepoType.Npm)], 3);

      component.loadPage(1);

      expect(last()).toEqual(jasmine.objectContaining({ type: ApiRepoType.Npm, q: 'web', page: 1 }));
    }));

    it('shows the last page that is left when the page asked for has no rows any more', () => {
      const component = create();
      answer([repo('a-maven')], 3);

      component.loadPage(2);
      answer([], 2, 2);

      expect(calls.length).toBe(3);
      expect(last().page).toBe(1);
      expect(component.pageNum).toBe(1);
      answer([repo('second')], 2, 1);
      expect(names(component)).toEqual(['second']);
    });

    it('goes back to the first page on refresh', () => {
      const component = create();
      answer([repo('a-maven')], 3);
      component.loadPage(2);

      component.refreshPage();

      expect(component.pageNum).toBe(0);
      expect(last().page).toBe(0);
    });
  });

  describe('searching', () => {
    it('sends the typed text once the typing pauses, as the q argument, from the first page', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')], 3);
      component.loadPage(2);
      answer([repo('third')], 3, 2);
      calls.length = 0;

      component.search('m');
      component.search('ma');
      component.search('mav');
      tick(SEARCH_DEBOUNCE_MS - 1);
      expect(calls.length).toBe(0);
      tick(1);

      expect(calls.length).toBe(1);
      expect(repoApi.listRepos.calls.mostRecent().args).toEqual([undefined, 'mav', 0, 10, ['createdAt,desc']]);
      expect(component.pageNum).toBe(0);
      expect(component.searchQuery).toBe('mav');
    }));

    it('cancels the request of the previous search', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')]);

      component.search('one');
      tick(SEARCH_DEBOUNCE_MS);
      const first = last();
      expect(first.answer.observed).toBeTrue();

      component.search('two');
      tick(SEARCH_DEBOUNCE_MS);

      expect(first.answer.observed).toBeFalse();
      expect(last().q).toBe('two');
      expect(last().answer.observed).toBeTrue();
    }));

    it('never lists the rows of a search that was superseded, even when its answer arrives late', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')]);
      component.search('one');
      tick(SEARCH_DEBOUNCE_MS);
      const first = last();
      component.search('two');
      tick(SEARCH_DEBOUNCE_MS);

      first.answer.next(page([repo('stale-one')]));
      last().answer.next(page([repo('fresh-two')]));

      expect(names(component)).toEqual(['fresh-two']);
    }));

    it('shows the empty list, not the error, for a search that matches nothing', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')]);

      component.search('zzz');
      tick(SEARCH_DEBOUNCE_MS);
      answer([], 0);

      expect(component.paginatedRepos).toEqual([]);
      expect(component.error).toBe('');
      expect(component.loading).toBeFalse();
    }));

    it('is dropped when a refresh or a type change empties the box before the typing pause is over', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')]);
      calls.length = 0;

      component.search('lost');
      component.refreshPage();
      tick(SEARCH_DEBOUNCE_MS);

      expect(calls.length).toBe(1);
      expect(calls[0].q).toBeUndefined();
      expect(component.searchQuery).toBe('');

      component.search('lost-too');
      component.filterRepos(RepoType.NPM);
      tick(SEARCH_DEBOUNCE_MS);

      expect(calls.length).toBe(2);
      expect(calls[1].q).toBeUndefined();
    }));

    it('is emptied by refresh and by a type change, together with the query sent', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')]);
      component.search('maven');
      tick(SEARCH_DEBOUNCE_MS);
      answer([repo('a-maven')]);

      component.refreshPage();
      expect(component.searchQuery).toBe('');
      expect(last().q).toBeUndefined();
      answer([repo('a-maven')]);

      component.search('maven');
      tick(SEARCH_DEBOUNCE_MS);
      answer([repo('a-maven')]);
      component.filterRepos(RepoType.NPM);
      expect(component.searchQuery).toBe('');
      expect(last().q).toBeUndefined();
      expect(last().type).toBe(ApiRepoType.Npm);
      expect(last().page).toBe(0);
    }));
  });

  describe('a type change', () => {
    it('goes back to the first page and shows the spinner with no rows', () => {
      const component = create();
      answer([repo('a-maven')], 3);
      component.loadPage(2);

      component.filterRepos(RepoType.NPM);

      expect(last().page).toBe(0);
      expect(component.pageNum).toBe(0);
      expect(component.loading).toBeTrue();
      expect(component.paginatedRepos).toEqual([]);
    });

    it('supersedes a running load: the old answer is never listed', () => {
      const component = create();
      const first = last();

      component.filterRepos(RepoType.NPM);
      expect(first.answer.observed).toBeFalse();
      first.answer.next(page([repo('old-maven')]));
      answer([repo('web-app', ApiRepoType.Npm)]);

      expect(names(component)).toEqual(['web-app']);
      expect(component.loading).toBeFalse();
    });
  });

  describe('the security summary', () => {
    it('is watched ONCE after each list load, for the names of the page only', () => {
      const component = create();
      expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();

      answer([repo('my-repo'), repo('other')], 5);

      expect(securityService.watchSecuritySummary).toHaveBeenCalledOnceWith(['my-repo', 'other']);
      expect(component.securitySummary).toEqual({});
    });

    it('shows every emitted summary, as the poll goes on', () => {
      const component = create();
      answer([repo('my-repo')]);

      watched.next(SCANNING);
      expect(component.securitySummary).toEqual(SCANNING);
      watched.next({ 'my-repo': { scanned: true } as RepoSecuritySummary });
      expect(component.securitySummary['my-repo'].scanned).toBeTrue();
    });

    it('stops when a page or search change starts, and restarts with the new page names', () => {
      const component = create();
      answer([repo('my-repo')], 2);
      expect(watched.observed).toBeTrue();

      component.loadPage(1);
      expect(watched.observed).toBeFalse();

      const second = new Subject<Record<string, RepoSecuritySummary>>();
      securityService.watchSecuritySummary.and.returnValue(second);
      answer([repo('page-two')], 2, 1);

      expect(securityService.watchSecuritySummary).toHaveBeenCalledTimes(2);
      expect(securityService.watchSecuritySummary.calls.mostRecent().args).toEqual([['page-two']]);
      expect(second.observed).toBeTrue();
    });

    it('stops on refresh and does not restart when the list is empty or failed', () => {
      const component = create();
      answer([repo('my-repo')]);

      component.refreshPage();
      expect(watched.observed).toBeFalse();
      answer([]);
      expect(securityService.watchSecuritySummary).toHaveBeenCalledTimes(1);

      component.refreshPage();
      last().answer.error(new Error('boom'));
      expect(securityService.watchSecuritySummary).toHaveBeenCalledTimes(1);
    });

    it('stops when the component is destroyed', () => {
      const component = create();
      answer([repo('my-repo')]);
      expect(watched.observed).toBeTrue();

      component.ngOnDestroy();

      expect(watched.observed).toBeFalse();
    });

    it('does not fail the list when the summary cannot be loaded', () => {
      securityService.watchSecuritySummary.and.returnValue(throwError(() => new Error('boom')));
      const component = create();

      answer([repo('my-repo')]);

      expect(component.error).toBe('');
      expect(names(component)).toEqual(['my-repo']);
      expect(component.securitySummary).toEqual({});
    });
  });

  describe('overlapping loads', () => {
    it('cancel the request of the previous load and never list its rows', () => {
      const component = create();
      const first = last();

      component.refreshPage();
      expect(first.answer.observed).toBeFalse();
      first.answer.next(page([repo('old')]));
      answer([repo('new')]);

      expect(names(component)).toEqual(['new']);
      expect(securityService.watchSecuritySummary).toHaveBeenCalledOnceWith(['new']);
    });

    it('never show the failure of the old load in the state of the new one', () => {
      const component = create();
      const first = last();

      component.refreshPage();
      first.answer.error(new Error('boom'));
      expect(component.error).toBe('');
      expect(component.loading).toBeTrue();
      answer([repo('new')]);

      expect(component.error).toBe('');
      expect(names(component)).toEqual(['new']);
    });

    it('cancel the running request when the component is destroyed', () => {
      const component = create();

      component.ngOnDestroy();

      expect(last().answer.observed).toBeFalse();
    });
  });

  describe('the delete flow', () => {
    it('reloads the list from the first page, without the search, after the delete, and toasts', fakeAsync(() => {
      const component = create();
      answer([repo('a-maven')], 3);
      component.search('maven');
      tick(SEARCH_DEBOUNCE_MS);
      answer([repo('a-maven')], 3);
      protocolApi.deleteRepo.and.returnValue(of(undefined) as never);
      calls.length = 0;

      component.deleteRepository({ name: 'a-maven' } as never);
      dangerModal.call();

      expect(protocolApi.deleteRepo).toHaveBeenCalledOnceWith('a-maven');
      expect(calls.length).toBe(1);
      expect(last().q).toBeUndefined();
      expect(last().page).toBe(0);
      expect(toast.show).toHaveBeenCalledWith('Repository deleted successfully', 'success');
      expect(component.operationLock).toBeFalse();
    }));

    it('does not reload or toast when the delete fails, and lets go of the lock', () => {
      const component = create();
      answer([repo('a-maven')]);
      protocolApi.deleteRepo.and.returnValue(throwError(() => new Error('boom')) as never);
      calls.length = 0;

      component.deleteRepository({ name: 'a-maven' } as never);
      dangerModal.call();

      expect(calls.length).toBe(0);
      expect(toast.show).not.toHaveBeenCalled();
      expect(component.operationLock).toBeFalse();
    });

    it('is refused for a user who is not an admin', () => {
      profile.get.and.returnValue(of({ role: 'USER' }));
      const component = create();

      component.deleteRepository({ name: 'a-maven' } as never);

      expect(toast.show).toHaveBeenCalledWith('You do not have permission to delete repositories', 'error');
      expect(protocolApi.deleteRepo).not.toHaveBeenCalled();
    });
  });

  describe('the role of the user', () => {
    it('makes an admin an admin', () => {
      expect(create().isAdmin).toBeTrue();
    });

    it('treats the user as a non-admin when the profile cannot be loaded, without an unhandled error', () => {
      profile.get.and.returnValue(throwError(() => new Error('boom')));

      expect(create().isAdmin).toBeFalse();
    });
  });

  describe('the rendered page', () => {
    function render() {
      TestBed.configureTestingModule({
        imports: [RepositoryComponent],
        providers: [
          provideRouter([]),
          { provide: RepoCollectionControllerService, useValue: repoApi },
          { provide: ProtocolRepoControllerService, useValue: protocolApi },
          { provide: SecurityService, useValue: securityService },
          { provide: ProfileService, useValue: profile },
          { provide: ToastService, useValue: toast },
        ],
      });
      const fixture = TestBed.createComponent(RepositoryComponent);
      created.push(fixture.componentInstance);
      // The fixture's element stays in the document until it is destroyed, and a layout spec that runs
      // later (row-link.spec) reads positions off the page.
      fixtures.push(fixture);
      fixture.detectChanges();
      return fixture;
    }

    it('shows the rows, and the pager from the server page count', () => {
      const fixture = render();

      answer([repo('a-maven')], 4);
      fixture.detectChanges();

      const root: HTMLElement = fixture.nativeElement;
      expect(root.querySelector('[data-testid="repo-row-a-maven"]')).not.toBeNull();
      expect(root.querySelector('app-pagination')).not.toBeNull();
      expect(root.querySelector('[data-testid="repo-warning"]')).toBeNull();
    });

    it('shows repo-error, not the empty list, when the list request fails', () => {
      const fixture = render();

      last().answer.error(new Error('boom'));
      fixture.detectChanges();

      const root: HTMLElement = fixture.nativeElement;
      expect(root.querySelector('[data-testid="repo-error"]')).not.toBeNull();
      expect(root.querySelector('app-empty-list')).toBeNull();
    });

    it('empties the search box on the refresh button and on a type change, together with the query', fakeAsync(() => {
      const fixture = render();
      answer([repo('a-maven')]);
      fixture.detectChanges();
      const box = (): HTMLInputElement => fixture.nativeElement.querySelector('[data-testid="repo-search"] input');
      const type = (text: string) => {
        box().value = text;
        box().dispatchEvent(new Event('input'));
        fixture.detectChanges();
        tick(SEARCH_DEBOUNCE_MS);
      };

      type('maven');
      expect(box().value).toBe('maven');
      expect(last().q).toBe('maven');
      answer([repo('a-maven')]);

      fixture.nativeElement.querySelector('[data-testid="repo-refresh"]').click();
      fixture.detectChanges();
      expect(box().value).toBe('');
      answer([repo('a-maven')]);

      type('maven');
      answer([repo('a-maven')]);
      fixture.componentInstance.filterRepos(RepoType.NPM);
      fixture.detectChanges();
      expect(box().value).toBe('');
    }));

    it('reloads the list when the create modal reports a created repository', () => {
      const fixture = render();
      answer([repo('a-maven')]);
      const before = calls.length;
      const modal = fixture.debugElement.query(By.directive(RepositoryCreateModalComponent));

      modal.componentInstance.created.emit(repo('brand-new'));

      expect(calls.length).toBe(before + 1);
      expect(last().page).toBe(0);
    });
  });
});
