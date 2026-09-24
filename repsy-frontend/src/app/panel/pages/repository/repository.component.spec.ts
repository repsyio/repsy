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
