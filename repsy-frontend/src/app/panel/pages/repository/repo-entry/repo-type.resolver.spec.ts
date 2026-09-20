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
import { ActivatedRouteSnapshot, convertToParamMap, Router, RouterStateSnapshot } from '@angular/router';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';

import { RepoLookupService } from './repo-lookup.service';
import { repoTypeResolver } from './repo-type.resolver';

describe('repoTypeResolver', () => {
  let repoLookupService: jasmine.SpyObj<RepoLookupService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    repoLookupService = jasmine.createSpyObj<RepoLookupService>('RepoLookupService', ['getRepoType']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    TestBed.configureTestingModule({
      providers: [
        { provide: RepoLookupService, useValue: repoLookupService },
        { provide: Router, useValue: router },
      ],
    });
  });

  function resolve(params: Record<string, string>) {
    const route = { paramMap: convertToParamMap(params) } as ActivatedRouteSnapshot;
    const result = TestBed.runInInjectionContext(() => repoTypeResolver(route, {} as RouterStateSnapshot));
    return firstValueFrom(result as Observable<unknown>);
  }

  it('resolves the repo name together with its type', async () => {
    repoLookupService.getRepoType.and.returnValue(of('npm'));

    expect(await resolve({ repoName: 'acme-npm' })).toEqual({ repoName: 'acme-npm', repoType: 'npm' });
    expect(repoLookupService.getRepoType).toHaveBeenCalledOnceWith('acme-npm');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('goes to not-found with a message when the route has no repo name', async () => {
    expect(await resolve({})).toBeNull();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/not-found'], { state: { message: 'Invalid repository path' } });
    expect(repoLookupService.getRepoType).not.toHaveBeenCalled();
  });

  it('goes to not-found when the repo cannot be looked up', async () => {
    repoLookupService.getRepoType.and.returnValue(throwError(() => new Error('404')));

    expect(await resolve({ repoName: 'missing' })).toBeNull();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/not-found']);
  });
});
