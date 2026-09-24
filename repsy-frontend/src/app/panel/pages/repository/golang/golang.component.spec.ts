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

import { Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepoContext, RepoLookupService } from '../repo-entry/repo-lookup.service';
import { GolangComponent } from './golang.component';
import { GolangService } from './service/golang.service';

describe('GolangComponent', () => {
  const PERMISSION: RepoPermissionInfo = {
    repoName: 'go-repo',
    canRead: true,
    canWrite: false,
    canManage: false,
    private: false,
  };

  let currentRepo: BehaviorSubject<RepoContext | null>;
  let golangService: jasmine.SpyObj<GolangService>;
  let router: jasmine.SpyObj<Router>;
  let component: GolangComponent;

  function build(current: RepoContext | null, authenticated = true): void {
    currentRepo = new BehaviorSubject<RepoContext | null>(current);
    const lookup = { currentRepo$: currentRepo.asObservable(), currentRepo: current } as unknown as RepoLookupService;
    golangService = jasmine.createSpyObj<GolangService>('GolangService', ['getRepository']);
    golangService.getRepository.and.returnValue(of(PERMISSION));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    component = new GolangComponent(
      lookup,
      golangService,
      { isAuthenticated: () => authenticated } as AuthService,
      router,
    );
  }

  afterEach(() => component.ngOnDestroy());

  // RPS-1302: a second, direct load made the permissions load twice and every child list with it.
  it('loads the permissions of an already selected repository once', () => {
    build({ repoName: 'go-repo', repoType: 'golang' });

    component.ngOnInit();

    expect(golangService.getRepository).toHaveBeenCalledOnceWith('go-repo');
    expect(component.activeRepo).toEqual(PERMISSION);
    expect(component.loading).toBeFalse();
  });

  it('loads the permissions when the repository is selected later, once each time', () => {
    build(null);
    component.ngOnInit();
    expect(golangService.getRepository).not.toHaveBeenCalled();

    currentRepo.next({ repoName: 'go-repo', repoType: 'golang' });

    expect(golangService.getRepository).toHaveBeenCalledOnceWith('go-repo');
  });

  it('ignores a repository of another type', () => {
    build({ repoName: 'npm-repo', repoType: 'npm' });

    component.ngOnInit();

    expect(golangService.getRepository).not.toHaveBeenCalled();
  });

  it('leaves for the not-found page when a private repository is opened without a login', () => {
    build({ repoName: 'go-repo', repoType: 'golang' }, false);
    golangService.getRepository.and.returnValue(of({ ...PERMISSION, private: true }));

    component.ngOnInit();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/not-found'], {
      queryParams: { message: "Repository 'go-repo' not found" },
    });
  });
});
