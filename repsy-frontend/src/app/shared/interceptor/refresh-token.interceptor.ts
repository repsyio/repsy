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

import { HttpErrorResponse, HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable, throwError } from 'rxjs';
import { catchError, finalize, share, switchMap } from 'rxjs/operators';

import { AuthService } from '../../auth/pages/service/auth.service';
import { ToastService } from '../../panel/shared/components/toast/toast.service';

const LOGIN_PATH = '/api/auth/login';
const REFRESH_PATH = '/api/auth/tokens/refresh';

const SESSION_EXPIRED_MESSAGE = 'Session expired, please log in again.';
const SESSION_INVALID_MESSAGE = 'Session invalid, please log in again.';

/**
 * Every 401 an authenticated call gets ends in a defined state (RPS-1279). The backend answers a
 * 401 with one of these `msgId`s, and the rule per case is:
 *
 * - Login call (`invalidCredentials`): not a session problem, the login form shows it.
 * - `sessionExpired` (expired access token, or a token version the account has moved past): the
 *   only refreshable case. Swap the tokens through ONE shared refresh, then retry the request ONCE.
 *   The retry is not intercepted again, and a 401 on it logs out, so this can never loop.
 * - Any 401 on the refresh call itself (`refreshTokenExpired`: expired, unknown, already used or
 *   revoked), or a refresh that fails otherwise: the session cannot be renewed, log out.
 * - `unAuthorized`: NOT a session problem, passed to the caller as it is. The backend answers it for
 *   "logged in, but no permission for this resource" (a USER asking for the usage of a repository they
 *   cannot see: the dashboard does that nine times) and also when the account behind a valid token is
 *   gone; the two cannot be told apart by msgId. Logging out on it would sign every USER out of their
 *   own dashboard. The removed-account case still ends within one access-token lifetime (30 minutes):
 *   the token expires, `sessionExpired` triggers the refresh, and the refresh is refused.
 * - Every other 401 (`accessNotAllowed`: bad signature, wrong token type or realm, e.g. a tampered
 *   token or a backend restarted with a new signing key; `refreshTokenExpired` on an ordinary call; a
 *   401 with no or an unknown body): the token can never become valid, and a refresh token signed by
 *   the same key would not either, so log out without trying.
 *
 * "Log out" clears the session, shows a toast once and goes to /login. A 401 that arrives when no
 * session exists any more (a request that was in flight during the logout) is passed on quietly.
 */
@Injectable()
export class RefreshTokenInterceptor implements HttpInterceptor {
  // The refresh currently in flight, shared by every request that hit sessionExpired meanwhile.
  // It is cleared once the refresh settles, so the next refresh always starts from a clean state.
  private _refresh$: Observable<string> | null = null;

  constructor(
    private readonly router: Router,
    private readonly authService: AuthService,
    private readonly toastService: ToastService,
  ) {}

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    return next.handle(req).pipe(
      catchError((res: HttpErrorResponse) => {
        if (res?.status !== 401 || RefreshTokenInterceptor._isPath(req, LOGIN_PATH)) {
          return throwError(() => res);
        }
        if (!this.authService.isAuthenticated()) {
          return throwError(() => res);
        }

        if (RefreshTokenInterceptor._isPath(req, REFRESH_PATH)) {
          return this._logOut(SESSION_EXPIRED_MESSAGE);
        }

        if (res.error?.msgId === 'sessionExpired') {
          return this._refreshToken().pipe(
            switchMap((accessToken: string) =>
              next.handle(req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } })).pipe(
                // Fresh token, still refused: nothing left to try.
                catchError((retried: HttpErrorResponse) =>
                  retried?.status === 401 ? this._logOut(SESSION_INVALID_MESSAGE) : throwError(() => retried),
                ),
              ),
            ),
          );
        }

        if (res.error?.msgId === 'unAuthorized') {
          return throwError(() => res);
        }

        return this._logOut(
          res.error?.msgId === 'refreshTokenExpired' ? SESSION_EXPIRED_MESSAGE : SESSION_INVALID_MESSAGE,
        );
      }),
    );
  }

  private static _isPath(req: HttpRequest<unknown>, path: string): boolean {
    return req.url.split('?')[0].endsWith(path);
  }

  // Ends the session and completes the request silently: the user is on their way to /login.
  private _logOut(message: string): Observable<never> {
    // A concurrent request may have logged out already, once is enough.
    if (this.authService.isAuthenticated()) {
      this.authService.logOut();
      this.toastService.show(message, 'error');
      this.router.navigateByUrl('/login');
    }
    return EMPTY;
  }

  private _refreshToken(): Observable<string> {
    if (!this._refresh$) {
      this._refresh$ = this.authService.refreshToken().pipe(
        catchError((error: HttpErrorResponse) => {
          this._logOut(SESSION_EXPIRED_MESSAGE);
          return throwError(() => error);
        }),
        finalize(() => (this._refresh$ = null)),
        share(),
      );
    }
    return this._refresh$;
  }
}
