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
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Injectable()
export class ErrorInterceptor implements HttpInterceptor {
  private readonly UNAUTHORIZED_STATUS = 401;
  private _error: HttpErrorResponse;

  constructor(private readonly router: Router) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    return next.handle(request).pipe(
      catchError((error: HttpErrorResponse) => {
        // You should, may switch-case here to handle different error codes
        if (error.status === this.UNAUTHORIZED_STATUS) {
          this.handleUnauthorizedError(error);
        }

        if (error.error) {
          return throwError(() => new Error(error.error.text));
        }

        return throwError(() => new Error(error.message));
      }),
    );
  }

  private handleUnauthorizedError(error: HttpErrorResponse) {
    this._error = error;
    console.error('Unauthorized access - redirecting to login');
    localStorage.clear();
    this.router.navigate(['/login']).then(() => window.location.reload());
  }
}
