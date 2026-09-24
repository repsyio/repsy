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

import { Injectable } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivate,
  CanActivateChild,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { Observable } from 'rxjs';

import { AuthService } from '../../auth/pages/service/auth.service';
import { RETURN_URL_PARAM, safeReturnUrl } from '../util/return-url';

@Injectable({
  providedIn: 'root',
})
export class AuthGuard implements CanActivate, CanActivateChild {
  public constructor(
    private readonly router: Router,
    private readonly authService: AuthService,
  ) {}

  public canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
    return this.authService.isAuthenticated() ? true : this._toLogin(state);
  }

  public canActivateChild(
    childRoute: ActivatedRouteSnapshot,
    state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
    return this.authService.isAuthenticated() ? true : this._toLogin(state);
  }

  /**
   * "/" renders the login form in place, and remembers the requested URL so that the login can
   * return the visitor there (RPS-1278). The redirect is a UrlTree, so the router replaces the
   * blocked navigation instead of racing a second one.
   */
  private _toLogin(state: RouterStateSnapshot): UrlTree {
    const returnUrl = safeReturnUrl(state.url);
    return this.router.createUrlTree(
      ['/'],
      returnUrl && returnUrl !== '/' ? { queryParams: { [RETURN_URL_PARAM]: returnUrl } } : {},
    );
  }
}
