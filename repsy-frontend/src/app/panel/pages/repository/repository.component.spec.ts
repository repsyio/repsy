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
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { ProtocolRepoControllerService, RepoSecuritySummary } from '../../../../generated/api';
import { DangerModalService } from '../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../shared/components/toast/toast.service';
import { RepoType } from '../../shared/dto/repo/repo-type';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';
import { RepositoryComponent } from './repository.component';

const SCANNING: Record<string, RepoSecuritySummary> = {
  'my-repo': { scanned: false, unscannedInProgressCount: 1 } as RepoSecuritySummary,
};

describe('RepositoryComponent security summary', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let watched: Subject<Record<string, RepoSecuritySummary>>;

  function create(): RepositoryComponent {
    return new RepositoryComponent(
      repoApi,
      securityService,
      { get: () => of({ role: 'ADMIN' }) } as unknown as ProfileService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      new DangerModalService(),
    );
  }

  beforeEach(() => {
    watched = new Subject();
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.returnValue(of({ data: [{ name: 'my-repo' }] }) as never);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(watched);
  });

  it('watches the summary of the listed repositories and shows every emitted summary', () => {
    const component = create();
    component.filterRepos(RepoType.MAVEN);

    expect(securityService.watchSecuritySummary).toHaveBeenCalledWith(['my-repo']);
    watched.next(SCANNING);
    expect(component.securitySummary).toEqual(SCANNING);
    watched.next({ 'my-repo': { scanned: true } as RepoSecuritySummary });
    expect(component.securitySummary['my-repo'].scanned).toBeTrue();

    component.ngOnDestroy();
  });

  it('stops watching when the component is destroyed', () => {
    const component = create();
    expect(watched.observed).toBeTrue();

    component.ngOnDestroy();

    expect(watched.observed).toBeFalse();
  });

  it('replaces the watch when the list is loaded again', () => {
    const component = create();
    const second = new Subject<Record<string, RepoSecuritySummary>>();
    securityService.watchSecuritySummary.and.returnValue(second);

    component.filterRepos(RepoType.MAVEN);

    expect(watched.observed).toBeFalse();
    expect(second.observed).toBeTrue();

    component.ngOnDestroy();
  });

  it('does not watch when there are no repositories', () => {
    repoApi.getInfo.and.returnValue(of({ data: [] }) as never);
    securityService.watchSecuritySummary.calls.reset();

    const component = create();

    expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();
    component.ngOnDestroy();
  });
});

describe('RepositoryComponent load outcome', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let profile: { get: jasmine.Spy };
  let failing: Set<string>;
  let created: RepositoryComponent[];

  function create(): RepositoryComponent {
    const component = new RepositoryComponent(
      repoApi,
      securityService,
      profile as unknown as ProfileService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      new DangerModalService(),
    );
    created.push(component);
    return component;
  }

  beforeEach(() => {
    failing = new Set();
    created = [];
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.callFake(((type: string) =>
      failing.has(type)
        ? throwError(() => new Error('boom'))
        : of({ data: type === 'MAVEN' ? [{ name: 'a-maven', createdAt: '2026-01-01T00:00:00Z' }] : [] })) as never);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(new Subject());
    profile = { get: jasmine.createSpy('get').and.returnValue(of({ role: 'ADMIN' })) };
  });

  afterEach(() => created.forEach((component) => component.ngOnDestroy()));

  it('shows neither an error nor a warning when no type fails', () => {
    const component = create();

    expect(component.loading).toBeFalse();
    expect(component.error).toBe('');
    expect(component.warning).toBe('');
    expect(component.repositories.map((r) => r.name)).toEqual(['a-maven']);
  });

  it('shows the error state, no rows and no warning when every type fails', () => {
    repoApi.getInfo.and.returnValue(throwError(() => new Error('boom')) as never);

    const component = create();

    expect(component.loading).toBeFalse();
    expect(component.error).not.toBe('');
    expect(component.warning).toBe('');
    expect(component.repositories).toEqual([]);
    expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();
  });

  it('shows the error state when the only requested type fails', () => {
    failing.add('NPM');
    const component = create();

    component.filterRepos(RepoType.NPM);

    expect(component.error).not.toBe('');
    expect(component.warning).toBe('');
  });

  it('keeps the loaded types and warns about the failed one when only one type fails', () => {
    failing.add('NPM');

    const component = create();

    expect(component.loading).toBeFalse();
    expect(component.error).toBe('');
    expect(component.warning).toContain('npm');
    expect(component.warning).not.toContain('maven');
    expect(component.repositories.map((r) => r.name)).toEqual(['a-maven']);
    expect(component.paginatedRepos.map((r) => r.name)).toEqual(['a-maven']);
    expect(securityService.watchSecuritySummary).toHaveBeenCalledWith(['a-maven']);
  });

  it('warns, and shows an empty list, when one type fails and the others have no repositories', () => {
    failing.add('MAVEN');

    const component = create();

    expect(component.error).toBe('');
    expect(component.warning).toContain('maven');
    expect(component.repositories).toEqual([]);
  });

  it('keeps the spinner until an answer has rows or the last one is in, so a failing load never shows the empty state first', () => {
    const answers: Record<string, Subject<unknown>> = {};
    repoApi.getInfo.and.callFake(((type: string) => (answers[type] = new Subject())) as never);

    const component = create();
    expect(component.loading).toBeTrue();

    Object.entries(answers)
      .slice(0, -1)
      .forEach(([, subject]) => subject.error(new Error('boom')));
    expect(component.loading).toBeTrue();
    expect(component.error).toBe('');

    Object.values(answers).at(-1)!.error(new Error('boom'));
    expect(component.loading).toBeFalse();
    expect(component.error).not.toBe('');
  });

  it('lists the rows of a type as soon as it answers with some, while the others are still loading', () => {
    const answers: Record<string, Subject<unknown>> = {};
    repoApi.getInfo.and.callFake(((type: string) => (answers[type] = new Subject())) as never);

    const component = create();
    answers['MAVEN'].next({ data: [{ name: 'a-maven', createdAt: '2026-01-01T00:00:00Z' }] });
    answers['MAVEN'].complete();

    expect(component.loading).toBeFalse();
    expect(component.paginatedRepos.map((r) => r.name)).toEqual(['a-maven']);
  });

  it('retries after a failure: refresh clears the error and lists what now loads', () => {
    failing = new Set(['MAVEN', 'NPM', 'PYPI', 'DOCKER', 'CARGO', 'GOLANG', 'HELM', 'NUGET', 'RUBY']);
    const component = create();
    expect(component.error).not.toBe('');

    failing.clear();
    component.refreshPage();

    expect(component.error).toBe('');
    expect(component.warning).toBe('');
    expect(component.loading).toBeFalse();
    expect(component.repositories.map((r) => r.name)).toEqual(['a-maven']);
  });

  it('clears the warning on refresh when the failed type answers again', () => {
    failing.add('NPM');
    const component = create();
    expect(component.warning).not.toBe('');

    failing.clear();
    component.refreshPage();

    expect(component.warning).toBe('');
  });

  it('turns a successful load into an error state when a refresh fails', () => {
    const component = create();
    expect(component.error).toBe('');

    repoApi.getInfo.and.returnValue(throwError(() => new Error('boom')) as never);
    component.refreshPage();

    expect(component.error).not.toBe('');
    expect(component.paginatedRepos).toEqual([]);
  });

  it('treats the user as a non-admin when the profile cannot be loaded, without an unhandled error', () => {
    profile.get.and.returnValue(throwError(() => new Error('boom')));

    const component = create();

    expect(component.isAdmin).toBeFalse();
  });
});

describe('RepositoryComponent search and paging', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let component: RepositoryComponent;

  const names = (repos: { name: string }[]): string[] => repos.map((r) => r.name);

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.callFake(((type: string) =>
      of({
        data:
          type === 'MAVEN'
            ? Array.from({ length: 12 }, (_, i) => ({
                name: `lib-${i + 1}`,
                createdAt: new Date(2026, 0, i + 1).toISOString(),
              }))
            : type === 'NPM'
              ? [{ name: 'web-app', createdAt: '2026-02-01T00:00:00Z' }]
              : [],
      })) as never);
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(new Subject());
    component = new RepositoryComponent(
      repoApi,
      securityService,
      { get: () => of({ role: 'ADMIN' }) } as unknown as ProfileService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      new DangerModalService(),
    );
  });

  afterEach(() => component.ngOnDestroy());

  it('lists the newest ten first and the rest on page two', () => {
    expect(component.getTotalPages()).toBe(2);
    expect(component.paginatedRepos.length).toBe(10);

    component.pageNum = 1;
    component.loadPage(1);

    expect(names(component.paginatedRepos)).toEqual(['lib-3', 'lib-2', 'lib-1']);
  });

  it('starts a new search on the first page, so page one is shown and marked as current', () => {
    component.pageNum = 1;
    component.loadPage(1);

    component.search('lib-1');

    expect(component.pageNum).toBe(0);
    expect(component.searchQuery).toBe('lib-1');
    expect(names(component.paginatedRepos)).toEqual(['lib-12', 'lib-11', 'lib-10', 'lib-1']);
  });

  it('starts on the first page again when the search is widened after being narrowed on page two', () => {
    component.pageNum = 1;
    component.loadPage(1);
    component.search('lib-1');

    component.search('lib');

    expect(component.pageNum).toBe(0);
    expect(component.paginatedRepos.length).toBe(10);
  });

  it('empties the search on refresh, and the list is the unfiltered one', () => {
    component.search('web');
    expect(names(component.filteredRepos)).toEqual(['web-app']);

    component.refreshPage();

    expect(component.searchQuery).toBe('');
    expect(component.filteredRepos.length).toBe(13);
  });

  it('empties the search and goes back to the first page when the type changes', () => {
    component.search('lib');
    component.pageNum = 1;
    component.loadPage(1);

    component.filterRepos(RepoType.NPM);

    expect(component.searchQuery).toBe('');
    expect(component.pageNum).toBe(0);
    expect(names(component.paginatedRepos)).toEqual(['web-app']);
  });

  it('goes back to the first page on refresh', () => {
    component.pageNum = 1;
    component.loadPage(1);

    component.refreshPage();

    expect(component.pageNum).toBe(0);
    expect(component.paginatedRepos.length).toBe(10);
  });
});

describe('RepositoryComponent overlapping loads', () => {
  interface Call {
    type: string;
    load: number;
    answer: Subject<unknown>;
  }

  let calls: Call[];
  let load: number;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let component: RepositoryComponent;

  /** The answers of the requests the `n`th load started (the constructor's is the first). */
  const answersOf = (n: number): Call[] => calls.filter((call) => call.load === n);
  const answerOf = (n: number, type: string): Subject<unknown> =>
    answersOf(n).find((call) => call.type === type)!.answer;
  const rows = (...names: string[]) => ({ data: names.map((name) => ({ name, createdAt: '2026-01-01T00:00:00Z' })) });
  const names = (repos: { name: string }[]): string[] => repos.map((r) => r.name);

  beforeEach(() => {
    calls = [];
    load = 1;
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.callFake(((type: string) => {
      const answer = new Subject<unknown>();
      calls.push({ type, load, answer });
      return answer;
    }) as never);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(new Subject());
    component = new RepositoryComponent(
      repoApi,
      securityService,
      { get: () => of({ role: 'ADMIN' }) } as unknown as ProfileService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      new DangerModalService(),
    );
  });

  afterEach(() => component.ngOnDestroy());

  function reload(): void {
    load++;
    component.refreshPage();
  }

  it('cancels the requests of the previous load when a new one starts', () => {
    expect(answersOf(1).length).toBe(9);
    expect(answersOf(1).every((call) => call.answer.observed)).toBeTrue();

    reload();

    expect(answersOf(1).some((call) => call.answer.observed)).toBeFalse();
    expect(answersOf(2).every((call) => call.answer.observed)).toBeTrue();
  });

  it('never lists a row of the old load, and does not list the rows of the new load twice', () => {
    reload(); // the create modal's (created) handler refreshes while a load is still running
    answerOf(1, 'MAVEN').next(rows('old-maven'));
    answerOf(1, 'MAVEN').complete();
    answerOf(2, 'MAVEN').next(rows('new-maven'));
    answerOf(2, 'MAVEN').complete();

    expect(names(component.repositories)).toEqual(['new-maven']);
    expect(names(component.filteredRepos)).toEqual(['new-maven']);
    expect(names(component.paginatedRepos)).toEqual(['new-maven']);
  });

  it('keeps the spinner of the new load while the old load winds down, and never leaves it stuck', () => {
    reload();
    answersOf(2)
      .slice(0, -1)
      .forEach((call) => call.answer.next({ data: [] }));
    answersOf(2)
      .slice(0, -1)
      .forEach((call) => call.answer.complete());

    expect(component.loading).toBeTrue();

    answersOf(2).at(-1)!.answer.next(rows('new-maven'));
    answersOf(2).at(-1)!.answer.complete();

    expect(component.loading).toBeFalse();
    expect(names(component.paginatedRepos)).toEqual(['new-maven']);
  });

  it('does not let the cancelled requests count towards the pending requests of the new load', () => {
    reload();

    // If the old requests had been counted, the new load would already be "finished" after four answers.
    answersOf(2)
      .slice(0, 4)
      .forEach((call) => call.answer.error(new Error('boom')));

    expect(component.loading).toBeTrue();
    expect(component.error).toBe('');
    expect(component.warning).toBe('');

    answersOf(2)
      .slice(4)
      .forEach((call) => call.answer.error(new Error('boom')));

    expect(component.loading).toBeFalse();
    expect(component.error).not.toBe('');
  });

  it('never shows the failures of the old load in the error or warning state of the new one', () => {
    answerOf(1, 'NPM').error(new Error('boom'));
    reload();
    answersOf(2).forEach((call) => {
      call.answer.next(call.type === 'MAVEN' ? rows('new-maven') : { data: [] });
      call.answer.complete();
    });

    expect(component.error).toBe('');
    expect(component.warning).toBe('');
    expect(component.loading).toBeFalse();
  });

  it('drops the error of the old load when the refresh that follows it succeeds', () => {
    answersOf(1).forEach((call) => call.answer.error(new Error('boom')));
    expect(component.error).not.toBe('');

    reload();
    answersOf(2).forEach((call) => {
      call.answer.next({ data: [] });
      call.answer.complete();
    });

    expect(component.error).toBe('');
  });

  it('lets a type change supersede a running load of all types', () => {
    load++;
    component.filterRepos(RepoType.NPM);
    answerOf(1, 'MAVEN').next(rows('old-maven'));
    answerOf(2, 'NPM').next(rows('web-app'));
    answerOf(2, 'NPM').complete();

    expect(answersOf(2).length).toBe(1);
    expect(names(component.paginatedRepos)).toEqual(['web-app']);
    expect(component.loading).toBeFalse();
  });

  it('watches the security summary of the last load only', () => {
    reload();
    answersOf(1).forEach((call) => call.answer.complete());
    expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();

    answersOf(2).forEach((call) => {
      call.answer.next(call.type === 'MAVEN' ? rows('new-maven') : { data: [] });
      call.answer.complete();
    });

    expect(securityService.watchSecuritySummary).toHaveBeenCalledOnceWith(['new-maven']);
  });

  it('cancels a running load when the component is destroyed, without starting the summary watch', () => {
    component.ngOnDestroy();

    expect(answersOf(1).some((call) => call.answer.observed)).toBeFalse();
    expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();
  });
});

describe('RepositoryComponent search box', () => {
  it('is emptied by the refresh button and by a type change, together with the query', () => {
    const repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.callFake(((type: string) =>
      of({ data: [{ name: `${type.toLowerCase()}-repo`, createdAt: '2026-01-01T00:00:00Z' }] })) as never);
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(new Subject());
    TestBed.configureTestingModule({
      imports: [RepositoryComponent],
      providers: [
        provideRouter([]),
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: SecurityService, useValue: securityService },
        { provide: ProfileService, useValue: { get: () => of({ role: 'ADMIN' }) } },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
    });
    const fixture = TestBed.createComponent(RepositoryComponent);
    fixture.detectChanges();
    const box = (): HTMLInputElement => fixture.nativeElement.querySelector('[data-testid="repo-search"] input');
    const type = (text: string) => {
      box().value = text;
      box().dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };

    type('maven');
    expect(box().value).toBe('maven');

    fixture.nativeElement.querySelector('[data-testid="repo-refresh"]').click();
    fixture.detectChanges();
    expect(box().value).toBe('');

    type('maven');
    fixture.componentInstance.filterRepos(RepoType.NPM);
    fixture.detectChanges();
    expect(box().value).toBe('');

    fixture.destroy();
  });
});
