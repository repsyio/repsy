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

import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';

import { AuthService } from '../pages/service/auth.service';
import { AuthRedirectGuard } from './auth-redirect.guard';

describe('AuthRedirectGuard', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;

  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let guard: AuthRedirectGuard;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    router.navigateByUrl.and.resolveTo(true);
    guard = new AuthRedirectGuard(router, authService);
  });

  const activations: [string, () => unknown][] = [
    ['canActivate', () => guard.canActivate(route, state)],
    ['canActivateChild', () => guard.canActivateChild(route, state)],
  ];

  activations.forEach(([name, activate]) => {
    describe(name, () => {
      it('lets an unauthenticated user through to the login page', () => {
        authService.isAuthenticated.and.returnValue(false);

        expect(activate()).toBeTrue();
        expect(router.navigateByUrl).not.toHaveBeenCalled();
      });

      it('sends an already authenticated user to the root and blocks the route', async () => {
        authService.isAuthenticated.and.returnValue(true);
        router.navigateByUrl.and.resolveTo(false);

        expect(await activate()).toBeFalse();
        expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/');
      });
    });
  });
});
