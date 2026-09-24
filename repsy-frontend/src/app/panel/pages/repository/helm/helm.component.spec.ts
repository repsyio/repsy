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
import { HelmComponent } from './helm.component';
import { HelmService } from './service/helm.service';

describe('HelmComponent', () => {
  const PERMISSION: RepoPermissionInfo = {
    repoName: 'helm-repo',
    canRead: true,
    canWrite: false,
    canManage: false,
    private: false,
  };

  let currentRepo: BehaviorSubject<RepoContext | null>;
  let helmService: jasmine.SpyObj<HelmService>;
  let router: jasmine.SpyObj<Router>;
  let component: HelmComponent;

  function build(current: RepoContext | null, authenticated = true): void {
    currentRepo = new BehaviorSubject<RepoContext | null>(current);
    const lookup = { currentRepo$: currentRepo.asObservable(), currentRepo: current } as unknown as RepoLookupService;
    helmService = jasmine.createSpyObj<HelmService>('HelmService', ['getRepository']);
    helmService.getRepository.and.returnValue(of(PERMISSION));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    component = new HelmComponent(lookup, helmService, { isAuthenticated: () => authenticated } as AuthService, router);
  }

  afterEach(() => component.ngOnDestroy());

  // RPS-1302: a second, direct load made the permissions load twice and every child list with it.
  it('loads the permissions of an already selected repository once', () => {
    build({ repoName: 'helm-repo', repoType: 'helm' });

    component.ngOnInit();

    expect(helmService.getRepository).toHaveBeenCalledOnceWith('helm-repo');
    expect(component.permissions).toEqual(PERMISSION);
    expect(component.loading).toBeFalse();
  });

  it('loads the permissions when the repository is selected later, once each time', () => {
    build(null);
    component.ngOnInit();
    expect(helmService.getRepository).not.toHaveBeenCalled();

    currentRepo.next({ repoName: 'helm-repo', repoType: 'helm' });

    expect(helmService.getRepository).toHaveBeenCalledOnceWith('helm-repo');
  });

  it('ignores a repository of another type', () => {
    build({ repoName: 'npm-repo', repoType: 'npm' });

    component.ngOnInit();

    expect(helmService.getRepository).not.toHaveBeenCalled();
  });

  it('leaves for the not-found page when a private repository is opened without a login', () => {
    build({ repoName: 'helm-repo', repoType: 'helm' }, false);
    helmService.getRepository.and.returnValue(of({ ...PERMISSION, private: true }));

    component.ngOnInit();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/not-found']);
  });
});
