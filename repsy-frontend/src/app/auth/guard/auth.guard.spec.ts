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

import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';

import { AuthService } from '../pages/service/auth.service';
import { AuthGuard } from './auth.guard';

describe('AuthGuard', () => {
  const route = {} as ActivatedRouteSnapshot;
  const stateOf = (url: string) => ({ url }) as RouterStateSnapshot;

  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let guard: AuthGuard;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']);
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.callFake((commands, extras) => ({ commands, extras }) as unknown as UrlTree);
    guard = new AuthGuard(router, authService);
  });

  const activations: [string, (url: string) => unknown][] = [
    ['canActivate', (url) => guard.canActivate(route, stateOf(url))],
    ['canActivateChild', (url) => guard.canActivateChild(route, stateOf(url))],
  ];

  activations.forEach(([name, activate]) => {
    describe(name, () => {
      it('lets an authenticated user through', () => {
        authService.isAuthenticated.and.returnValue(true);

        expect(activate('/repositories')).toBeTrue();
        expect(router.createUrlTree).not.toHaveBeenCalled();
      });

      it('redirects an unauthenticated user to the root, remembering the requested URL (RPS-1278)', () => {
        authService.isAuthenticated.and.returnValue(false);

        const result = activate('/my-repo/packages?tab=1');

        expect(router.createUrlTree).toHaveBeenCalledOnceWith(['/'], {
          queryParams: { returnUrl: '/my-repo/packages?tab=1' },
        });
        expect(result).toEqual(jasmine.objectContaining({ commands: ['/'] }));
      });

      it('does not remember a URL that is not a safe in-app path', () => {
        authService.isAuthenticated.and.returnValue(false);

        activate('//evil.example');

        expect(router.createUrlTree).toHaveBeenCalledOnceWith(['/'], {});
      });
    });
  });
});
