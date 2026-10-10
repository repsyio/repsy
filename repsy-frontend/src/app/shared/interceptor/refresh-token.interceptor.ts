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
import { loginUrlReturningTo } from '../../auth/util/return-url';
import { ToastService } from '../../panel/shared/components/toast/toast.service';
import { ERROR_CODES } from '../constants/error-codes';
import { problemCode } from '../error-handler/problem.util';

const LOGIN_PATH = '/api/auth/login';
const REFRESH_PATH = '/api/auth/tokens/refresh';

const SESSION_EXPIRED_MESSAGE = 'Session expired, please log in again.';
const SESSION_INVALID_MESSAGE = 'Session invalid, please log in again.';

/**
 * Every 401 an authenticated call gets ends in a defined state (RPS-1279). The backend answers a
 * 401 with one of these problem `code`s, and the rule per case is:
 *
 * - Login call (`invalidCredentials`): not a session problem, the login form shows it.
 * - `sessionExpired` (expired access token, or a token version the account has moved past): the
 *   only refreshable case. Swap the tokens through ONE shared refresh, then retry the request ONCE.
 *   The retry is not intercepted again, and a 401 on it logs out, so this can never loop.
 * - Any 401 on the refresh call itself (`refreshTokenExpired`: expired, unknown, already used or
 *   revoked), or a refresh that fails otherwise: the session cannot be renewed, log out.
 * - `loginRequired` (the account behind a valid token is gone, or credentials are missing or invalid;
 *   the panel's own id, the wire protocols answer `unAuthorized` for the same cases, RPS-1352):
 *   a lost session like the rest, so it logs out. A signed-in caller who merely lacks the permission
 *   for an operation is NOT a 401 any more but a 403 `accessDenied` (RPS-1284), which this interceptor
 *   never touches: the session is fine, the caller shows the refusal and the user stays logged in.
 * - Every other 401 (`accessNotAllowed`: bad signature, wrong token type or realm, e.g. a tampered
 *   token or a backend restarted with a new signing key; `refreshTokenExpired` on an ordinary call; a
 *   401 with no or an unknown body): the token can never become valid, and a refresh token signed by
 *   the same key would not either, so log out without trying.
 *
 * "Log out" clears the session, shows a toast once and goes to /login, remembering the page the user was
 * on (`returnUrl`, RPS-1621), so a new login returns there. A write (POST/PUT/PATCH/DELETE) is no
 * different from a read here: the 401 means the server refused it before doing anything, so the one
 * retry after a refresh cannot apply it twice, and when the refresh or the retry fails it was never
 * applied at all. A 401 that arrives when no session exists any more (a request that was in flight
 * during the logout) is passed on quietly.
 *
 * With several tabs (RPS-1621) the refresh goes through `AuthService.refreshToken()`, which adopts what
 * another tab has already refreshed instead of spending the same single-use token twice.
 */
@Injectable()
export class RefreshTokenInterceptor implements HttpInterceptor {
  // The refresh currently in flight, shared by every request that hit sessionExpired meanwhile.
  // It is cleared once the refresh settles, so the next refresh always starts from a clean state.
  private refresh$: Observable<string> | null = null;

  constructor(
    private readonly router: Router,
    private readonly authService: AuthService,
    private readonly toastService: ToastService,
  ) {}

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    return next.handle(req).pipe(
      catchError((res: HttpErrorResponse) => {
        if (res?.status !== 401 || RefreshTokenInterceptor.isPath(req, LOGIN_PATH)) {
          return throwError(() => res);
        }
        if (!this.authService.isAuthenticated()) {
          return throwError(() => res);
        }

        if (RefreshTokenInterceptor.isPath(req, REFRESH_PATH)) {
          return this.signOut(SESSION_EXPIRED_MESSAGE);
        }

        if (problemCode(res) === ERROR_CODES.SESSION_EXPIRED) {
          return this.refreshSession().pipe(
            switchMap((accessToken: string) =>
              next.handle(req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } })).pipe(
                // Fresh token, still refused: nothing left to try.
                catchError((retried: HttpErrorResponse) =>
                  retried?.status === 401 ? this.signOut(SESSION_INVALID_MESSAGE) : throwError(() => retried),
                ),
              ),
            ),
          );
        }

        return this.signOut(
          problemCode(res) === ERROR_CODES.REFRESH_TOKEN_EXPIRED ? SESSION_EXPIRED_MESSAGE : SESSION_INVALID_MESSAGE,
        );
      }),
    );
  }

  private static isPath(req: HttpRequest<unknown>, path: string): boolean {
    return req.url.split('?')[0].endsWith(path);
  }

  // Ends the session and completes the request silently: the user is on their way to /login.
  private signOut(message: string): Observable<never> {
    // A concurrent request may have logged out already, once is enough.
    if (this.authService.isAuthenticated()) {
      this.authService.logOut();
      this.toastService.show(message, 'error');
      this.router.navigateByUrl(loginUrlReturningTo(this.router.url));
    }
    return EMPTY;
  }

  private refreshSession(): Observable<string> {
    if (!this.refresh$) {
      this.refresh$ = this.authService.refreshToken().pipe(
        catchError((error: HttpErrorResponse) => {
          this.signOut(SESSION_EXPIRED_MESSAGE);
          return throwError(() => error);
        }),
        finalize(() => (this.refresh$ = null)),
        share(),
      );
    }
    return this.refresh$;
  }
}
