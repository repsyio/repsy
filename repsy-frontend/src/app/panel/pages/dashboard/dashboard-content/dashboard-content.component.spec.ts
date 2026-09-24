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

import { ChangeDetectorRef } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';

import { ProfileInfo, RepoCollectionControllerService, RepoListInfo, RepoType } from '../../../../../generated/api';
import { ProfileService } from '../../profile/service/profile.service';
import { UsageService } from '../service/usage.service';
import { DashboardContentComponent, RECENT_REPOSITORY_COUNT } from './dashboard-content.component';

const ALL_TYPES = [
  RepoType.Maven,
  RepoType.Npm,
  RepoType.Pypi,
  RepoType.Docker,
  RepoType.Cargo,
  RepoType.Golang,
  RepoType.Helm,
  RepoType.Nuget,
  RepoType.Ruby,
];

const COUNTS: Record<string, number> = {
  MAVEN: 1,
  NPM: 2,
  PYPI: 3,
  DOCKER: 4,
  CARGO: 5,
  GOLANG: 6,
  HELM: 7,
  NUGET: 8,
  RUBY: 9,
};

function repo(name: string, type: RepoType, diskUsage = 0): RepoListInfo {
  return { name, type, diskUsage, createdAt: '2026-01-01T00:00:00Z' } as RepoListInfo;
}

// RxJS reports an unhandled subscriber error asynchronously, on window, and Jasmine fails the spec on it.
// Runs the body, waits a tick, and returns what reached window instead.
async function collectUnhandledErrors(body: () => void): Promise<unknown[]> {
  const unhandled: unknown[] = [];
  const onError = (event: ErrorEvent): void => {
    unhandled.push(event.error);
    event.preventDefault();
  };
  window.addEventListener('error', onError);
  try {
    body();
    await new Promise<void>((resolve) => setTimeout(resolve));
  } finally {
    window.removeEventListener('error', onError);
  }
  return unhandled;
}

describe('DashboardContentComponent', () => {
  let repoService: jasmine.SpyObj<RepoCollectionControllerService>;
  let usageService: jasmine.SpyObj<UsageService>;
  let profileService: jasmine.SpyObj<ProfileService>;
  let cdRef: jasmine.SpyObj<ChangeDetectorRef>;
  let counts: Record<string, number> | undefined;
  let recent: RepoListInfo[];

  beforeEach(() => {
    counts = { ...COUNTS };
    recent = [];
    repoService = jasmine.createSpyObj<RepoCollectionControllerService>('RepoCollectionControllerService', [
      'getRepoCounts',
      'listRepos',
    ]);
    repoService.getRepoCounts.and.callFake((() => of({ data: counts })) as never);
    repoService.listRepos.and.callFake((() => of({ data: { content: recent, page: {} } })) as never);
    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getTotalUsage']);
    usageService.getTotalUsage.and.returnValue(of({ reposCount: 3 }) as never);
    profileService = jasmine.createSpyObj<ProfileService>('ProfileService', ['get']);
    profileService.get.and.returnValue(of({ role: 'ADMIN' } as ProfileInfo));
    cdRef = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);
  });

  function create(): DashboardContentComponent {
    return new DashboardContentComponent(repoService, usageService, profileService, cdRef);
  }

  function shownCounts(component: DashboardContentComponent): Record<string, number> {
    return {
      MAVEN: component.mavenRepoCount,
      NPM: component.npmRegistryCount,
      PYPI: component.pypiRepoCount,
      DOCKER: component.dockerRepoCount,
      CARGO: component.cargoRepoCount,
      GOLANG: component.golangRepoCount,
      HELM: component.helmRepoCount,
      NUGET: component.nugetRepoCount,
      RUBY: component.rubyRepoCount,
    };
  }

  it('shows the total usage', () => {
    const component = create();

    expect(component.usage as unknown).toEqual({ reposCount: 3 });
  });

  describe('the repository counts', () => {
    it('come from ONE request for every type', () => {
      const component = create();

      expect(repoService.getRepoCounts).toHaveBeenCalledTimes(1);
      expect(shownCounts(component)).toEqual(COUNTS);
    });

    it('are shown to a user who is not an admin, the same as to an admin', () => {
      profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));

      const component = create();

      expect(component.isAdmin).toBeFalse();
      expect(repoService.getRepoCounts).toHaveBeenCalledTimes(1);
      expect(shownCounts(component)).toEqual(COUNTS);
    });

    it('are requested even before the profile has arrived, and when it fails', () => {
      profileService.get.and.returnValue(new Subject<ProfileInfo>());

      const component = create();

      expect(repoService.getRepoCounts).toHaveBeenCalledTimes(1);
      expect(shownCounts(component)).toEqual(COUNTS);
    });

    it('count a type the response has no number for as zero', () => {
      counts = { MAVEN: 4 };

      const component = create();

      expect(shownCounts(component)).toEqual({
        MAVEN: 4,
        NPM: 0,
        PYPI: 0,
        DOCKER: 0,
        CARGO: 0,
        GOLANG: 0,
        HELM: 0,
        NUGET: 0,
        RUBY: 0,
      });
    });

    it('stay zero when the response has no data', () => {
      counts = undefined;

      const component = create();

      expect(Object.values(shownCounts(component))).toEqual(new Array(9).fill(0));
    });
  });

  describe('the role of the user', () => {
    it('lets an admin create repositories', () => {
      const component = create();

      expect(component.isAdmin).toBeTrue();
    });

    it('opens the create-repository modal only for an admin', () => {
      const admin = create();
      admin.openCreateRepo();
      expect(admin.createRepoModal).toBeTrue();

      profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));
      const user = create();
      user.openCreateRepo();
      expect(user.createRepoModal).toBeUndefined();
    });

    it('is not an admin until the profile has arrived', () => {
      profileService.get.and.returnValue(new Subject<ProfileInfo>());

      const component = create();
      component.openCreateRepo();

      expect(component.isAdmin).toBeFalse();
      expect(component.createRepoModal).toBeUndefined();
    });
  });

  describe('the recent repositories', () => {
    it('come from ONE request, with the positional arguments in the generated order, whatever the role', () => {
      profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));

      create();

      // listRepos(type, q, page, size, sort): q and sort are strings, so a swapped order would still compile.
      expect(repoService.listRepos).toHaveBeenCalledOnceWith(undefined, undefined, 0, RECENT_REPOSITORY_COUNT, [
        'createdAt,desc',
      ]);
      expect(RECENT_REPOSITORY_COUNT).toBe(6);
    });

    it('are listed as the server answers them, with their type and disk usage', () => {
      recent = [repo('new-npm', RepoType.Npm, 700), repo('old-maven', RepoType.Maven, 900)];

      const component = create();

      expect(component.repoListInfos.map((r) => r.name)).toEqual(['new-npm', 'old-maven']);
      expect(component.repoListInfos.map((r) => r.type)).toEqual([RepoType.Npm, RepoType.Maven]);
      expect(component.repoListInfos.map((r) => r.diskUsage)).toEqual([700, 900]);
    });

    it('are empty when there are no repositories at all', () => {
      const component = create();

      expect(component.repoListInfos).toEqual([]);
    });

    it('are empty when the response has no data', () => {
      repoService.listRepos.and.returnValue(of({}) as never);

      const component = create();

      expect(component.repoListInfos).toEqual([]);
    });

    it('handle a failing list: no unhandled error, the rest of the dashboard still shows', async () => {
      repoService.listRepos.and.returnValue(throwError(() => new Error('boom')));
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.repoListInfos).toEqual([]);
      expect(shownCounts(component)).toEqual(COUNTS);
    });
  });

  describe('a failing source leaves the rest of the dashboard working, with no unhandled error', () => {
    it('total usage', async () => {
      usageService.getTotalUsage.and.returnValue(throwError(() => new Error('boom')));
      recent = [repo('an-npm', RepoType.Npm)];
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.usage as unknown).toEqual({});
      expect(component.isAdmin).toBeTrue();
      expect(component.mavenRepoCount).toBe(1);
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });

    it('the profile: the user is not an admin, the counts and the list still show', async () => {
      profileService.get.and.returnValue(throwError(() => new Error('boom')));
      recent = [repo('an-npm', RepoType.Npm)];
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.isAdmin).toBeFalse();
      expect(shownCounts(component)).toEqual(COUNTS);
      expect(component.usage as unknown).toEqual({ reposCount: 3 });
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });

    it('the counts: they stay zero, the list still shows', async () => {
      repoService.getRepoCounts.and.returnValue(throwError(() => new Error('boom')));
      recent = [repo('an-npm', RepoType.Npm)];
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(Object.values(shownCounts(component))).toEqual(new Array(ALL_TYPES.length).fill(0));
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });
  });

  it('asks Angular to check the view again as data arrives', () => {
    create();

    expect(cdRef.markForCheck).toHaveBeenCalled();
  });

  // The dashboard lives inside an OnPush component: whichever answer arrives LAST must mark the view, or the
  // page keeps showing the state before it.
  describe('marks the view for a check after each answer, whichever arrives last', () => {
    let usage: Subject<unknown>;
    let counted: Subject<unknown>;
    let listed: Subject<unknown>;

    beforeEach(() => {
      usage = new Subject();
      counted = new Subject();
      listed = new Subject();
      usageService.getTotalUsage.and.returnValue(usage as never);
      repoService.getRepoCounts.and.returnValue(counted as never);
      repoService.listRepos.and.returnValue(listed as never);
      create();
      cdRef.markForCheck.calls.reset();
    });

    it('the total usage', () => {
      usage.next({ reposCount: 3 });

      expect(cdRef.markForCheck).toHaveBeenCalledTimes(1);
    });

    it('the counts', () => {
      counted.next({ data: COUNTS });

      expect(cdRef.markForCheck).toHaveBeenCalledTimes(1);
    });

    it('the recent repositories', () => {
      listed.next({ data: { content: [] } });

      expect(cdRef.markForCheck).toHaveBeenCalledTimes(1);
    });
  });
});
