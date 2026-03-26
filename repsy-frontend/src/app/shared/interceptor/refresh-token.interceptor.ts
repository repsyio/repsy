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
import { EMPTY, Observable, Subject, throwError } from 'rxjs';
import { catchError, switchMap, tap } from 'rxjs/operators';

import { AuthService } from '../../auth/pages/service/auth.service';

@Injectable()
export class RefreshTokenInterceptor implements HttpInterceptor {
  private _tokenRefreshInProgress = false;

  private readonly _refreshTokenSource = new Subject<string>();
  private readonly _tokenRefreshed$ = this._refreshTokenSource.asObservable();

  constructor(
    private readonly router: Router,
    private readonly authService: AuthService,
  ) {}
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    return next.handle(req).pipe(
      catchError((res: HttpErrorResponse) => {
        if (res && res.status && res.error && res.status === 403 && res.error.msgId === 'sessionExpired') {
          return this._refreshToken().pipe(
            switchMap((accessToken: string) => {
              return next.handle(
                req.clone({
                  setHeaders: { Authorization: `Bearer ${accessToken}` },
                }),
              );
            }),
          );
        }

        if (res && res.status && res.error && res.status === 403 && res.error.msgId === 'refreshTokenExpired') {
          this._logOut();
          return EMPTY;
        }
        return throwError(res);
      }),
    );
  }

  private _logOut(): void {
    this.authService.logOut();
    this.router.navigateByUrl('login');
  }

  private _refreshToken(): Observable<string> {
    if (this._tokenRefreshInProgress) {
      return new Observable<string>((observer) => {
        this._tokenRefreshed$.subscribe(
          (accessToken: string) => {
            observer.next(accessToken);
            observer.complete();
          },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (error: any) => {
            observer.error(error);
            observer.complete();
          },
        );
      });
    } else {
      this._tokenRefreshInProgress = true;

      return this.authService.refreshToken().pipe(
        tap((accessToken: string) => {
          this._tokenRefreshInProgress = false;
          this._refreshTokenSource.next(accessToken);
        }),
        catchError((error: HttpErrorResponse) => {
          this._tokenRefreshInProgress = false;
          this._refreshTokenSource.error(error);
          this._logOut();
          return throwError(error);
        }),
      );
    }
  }
}
