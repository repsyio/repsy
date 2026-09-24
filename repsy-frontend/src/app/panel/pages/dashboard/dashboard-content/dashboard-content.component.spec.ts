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

import { ProfileInfo, ProtocolRepoControllerService, RepoListInfo, RepoType } from '../../../../../generated/api';
import { ProfileService } from '../../profile/service/profile.service';
import { UsageService } from '../service/usage.service';
import { DashboardContentComponent } from './dashboard-content.component';

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

function repo(name: string, createdAt: string): RepoListInfo {
  return { name, createdAt } as RepoListInfo;
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
  let repoService: jasmine.SpyObj<ProtocolRepoControllerService>;
  let usageService: jasmine.SpyObj<UsageService>;
  let profileService: jasmine.SpyObj<ProfileService>;
  let cdRef: jasmine.SpyObj<ChangeDetectorRef>;
  let counts: Partial<Record<string, number | undefined>>;
  let infos: Partial<Record<string, RepoListInfo[]>>;

  beforeEach(() => {
    counts = {};
    infos = {};
    repoService = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getCount',
      'getInfo',
      'getUsage',
    ]);
    repoService.getCount.and.callFake(((type: RepoType) => of({ data: counts[type] })) as never);
    repoService.getInfo.and.callFake(((type: RepoType) => of({ data: infos[type] })) as never);
    repoService.getUsage.and.callFake(((name: string) =>
      of({ data: { diskUsed: { value: name.length * 100 } } })) as never);
    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getTotalUsage']);
    usageService.getTotalUsage.and.returnValue(of({ reposCount: 3 }) as never);
    profileService = jasmine.createSpyObj<ProfileService>('ProfileService', ['get']);
    profileService.get.and.returnValue(of({ role: 'ADMIN' } as ProfileInfo));
    cdRef = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);
  });

  function create(): DashboardContentComponent {
    return new DashboardContentComponent(repoService, usageService, profileService, cdRef);
  }

  it('shows the total usage', () => {
    const component = create();

    expect(component.usage as unknown).toEqual({ reposCount: 3 });
  });

  describe('the role of the user', () => {
    it('lets an admin see the repository counts of every type and create repositories', () => {
      counts = { MAVEN: 1, NPM: 2, PYPI: 3, DOCKER: 4, CARGO: 5, GOLANG: 6, HELM: 7, NUGET: 8, RUBY: 9 };

      const component = create();

      expect(component.isAdmin).toBeTrue();
      expect(repoService.getCount.calls.allArgs().map((args) => args[0])).toEqual(
        jasmine.arrayWithExactContents(ALL_TYPES),
      );
      expect(component.mavenRepoCount).toBe(1);
      expect(component.npmRegistryCount).toBe(2);
      expect(component.pypiRepoCount).toBe(3);
      expect(component.dockerRepoCount).toBe(4);
      expect(component.cargoRepoCount).toBe(5);
      expect(component.golangRepoCount).toBe(6);
      expect(component.helmRepoCount).toBe(7);
      expect(component.nugetRepoCount).toBe(8);
      expect(component.rubyRepoCount).toBe(9);
    });

    it('counts a type the response has no number for as zero', () => {
      counts = { MAVEN: undefined };

      const component = create();

      expect(component.mavenRepoCount).toBe(0);
    });

    it('does not fetch any count for a user who is not an admin', () => {
      profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));

      const component = create();

      expect(component.isAdmin).toBeFalse();
      expect(repoService.getCount).not.toHaveBeenCalled();
      expect(component.mavenRepoCount).toBe(0);
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
    it('asks for the repositories of every type, whatever the role', () => {
      profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));

      create();

      expect(repoService.getInfo.calls.allArgs().map((args) => args[0])).toEqual(ALL_TYPES);
    });

    it('lists them with their type and disk usage, newest first', () => {
      infos = {
        MAVEN: [repo('old-maven', '2026-01-01T00:00:00Z')],
        NPM: [repo('new-npm', '2026-03-01T00:00:00Z'), repo('mid-npm', '2026-02-01T00:00:00Z')],
      };

      const component = create();

      expect(component.repoListInfos.map((r) => r.name)).toEqual(['new-npm', 'mid-npm', 'old-maven']);
      expect(component.repoListInfos.map((r) => r.type)).toEqual([RepoType.Npm, RepoType.Npm, RepoType.Maven]);
      expect(component.repoListInfos.map((r) => r.diskUsage)).toEqual([700, 700, 900]);
      expect(repoService.getUsage).toHaveBeenCalledWith(
        'new-npm',
        jasmine.anything(),
        jasmine.anything(),
        jasmine.anything(),
      );
    });

    it('keeps only the six newest of all types together', () => {
      ALL_TYPES.forEach((type, index) => {
        infos[type] = [repo(`repo-${index}`, `2026-01-0${index + 1}T00:00:00Z`)];
      });

      const component = create();

      expect(component.repoListInfos.map((r) => r.name)).toEqual([
        'repo-8',
        'repo-7',
        'repo-6',
        'repo-5',
        'repo-4',
        'repo-3',
      ]);
    });

    it('handles a type that cannot be listed: no unhandled error, the other types still show', async () => {
      infos = { NPM: [repo('an-npm', '2026-02-01T00:00:00Z')] };
      repoService.getInfo.and.callFake(((type: RepoType) =>
        type === RepoType.Maven ? throwError(() => new Error('boom')) : of({ data: infos[type] })) as never);
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });

    it('keeps a repository whose usage cannot be fetched, with an unknown disk usage', async () => {
      infos = {
        MAVEN: [repo('bad-usage', '2026-03-01T00:00:00Z'), repo('good-maven', '2026-01-01T00:00:00Z')],
        NPM: [repo('an-npm', '2026-02-01T00:00:00Z')],
      };
      repoService.getUsage.and.callFake(((name: string) =>
        name === 'bad-usage'
          ? throwError(() => new Error('boom'))
          : of({ data: { diskUsed: { value: name.length * 100 } } })) as never);
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['bad-usage', 'an-npm', 'good-maven']);
      expect(component.repoListInfos.map((r) => r.type)).toEqual([RepoType.Maven, RepoType.Npm, RepoType.Maven]);
      expect(component.repoListInfos.map((r) => r.diskUsage)).toEqual([undefined, 600, 1000]);
    });

    it('keeps a repository whose usage response has no disk usage', () => {
      infos = { MAVEN: [repo('no-usage', '2026-03-01T00:00:00Z')] };
      repoService.getUsage.and.returnValue(of({ data: {} }) as never);

      const component = create();

      expect(component.repoListInfos.map((r) => r.name)).toEqual(['no-usage']);
      expect(component.repoListInfos[0].diskUsage).toBeUndefined();
    });

    it('is empty when there are no repositories at all', () => {
      const component = create();

      expect(component.repoListInfos).toEqual([]);
      expect(repoService.getUsage).not.toHaveBeenCalled();
    });
  });

  describe('a failing source leaves the rest of the dashboard working, with no unhandled error', () => {
    it('total usage', async () => {
      usageService.getTotalUsage.and.returnValue(throwError(() => new Error('boom')));
      counts = { MAVEN: 4 };
      infos = { NPM: [repo('an-npm', '2026-02-01T00:00:00Z')] };
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.usage as unknown).toEqual({});
      expect(component.isAdmin).toBeTrue();
      expect(component.mavenRepoCount).toBe(4);
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });

    it('the profile: the user is not an admin, no count is fetched, the rest still shows', async () => {
      profileService.get.and.returnValue(throwError(() => new Error('boom')));
      infos = { NPM: [repo('an-npm', '2026-02-01T00:00:00Z')] };
      let component!: DashboardContentComponent;

      const unhandled = await collectUnhandledErrors(() => (component = create()));

      expect(unhandled).toEqual([]);
      expect(component.isAdmin).toBeFalse();
      expect(repoService.getCount).not.toHaveBeenCalled();
      expect(component.usage as unknown).toEqual({ reposCount: 3 });
      expect(component.repoListInfos.map((r) => r.name)).toEqual(['an-npm']);
    });

    ALL_TYPES.forEach((failing) => {
      it(`the ${failing} repository count: it stays zero and the other counts are still shown`, async () => {
        counts = { MAVEN: 1, NPM: 2, PYPI: 3, DOCKER: 4, CARGO: 5, GOLANG: 6, HELM: 7, NUGET: 8, RUBY: 9 };
        repoService.getCount.and.callFake(((type: RepoType) =>
          type === failing ? throwError(() => new Error('boom')) : of({ data: counts[type] })) as never);
        let component!: DashboardContentComponent;

        const unhandled = await collectUnhandledErrors(() => (component = create()));

        const shown: Record<RepoType, number> = {
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
        expect(unhandled).toEqual([]);
        ALL_TYPES.forEach((type) => expect(shown[type]).toBe(type === failing ? 0 : counts[type]!));
      });
    });
  });

  it('asks Angular to check the view again as data arrives', () => {
    create();

    expect(cdRef.markForCheck).toHaveBeenCalled();
  });
});
