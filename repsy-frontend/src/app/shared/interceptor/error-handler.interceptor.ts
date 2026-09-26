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

import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { ToastService } from '../../panel/shared/components/toast/toast.service';

/**
 * Set on a request whose failure the caller handles itself (a background load that degrades to an
 * "unknown" value), so the interceptor does not toast it. The error still reaches the caller.
 */
export const SILENT_ERROR = new HttpContextToken<boolean>(() => false);

export const errorHandlerInterceptor: HttpInterceptorFn = (req, next) => {
  const toastService = inject(ToastService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // 401 is a session problem, handled by RefreshTokenInterceptor and the login form. A 403
      // (signed in, not allowed: `accessDenied`) is a plain error and gets its toast below; it never
      // ends the session (RPS-1284).
      if (error.status === 401 || req.context.get(SILENT_ERROR)) {
        return throwError(() => error);
      }

      const message = error.error?.text;
      let displayMessage: string;

      if (error.status === 0) {
        displayMessage = 'Connection error';
      } else if (error.status === 403) {
        displayMessage = message || 'Access denied';
      } else if (error.status === 503 && message) {
        // The backend answers 503 for "try again shortly" (`resourceBusy`, `scanExecutorSaturated`)
        // and its `text` says so in plain words, so it is shown; any other 5xx stays generic
        // (RPS-1355).
        displayMessage = message;
      } else if (error.status >= 500) {
        displayMessage = 'Server error';
      } else if (error.status >= 400) {
        displayMessage = message || 'An error occurred';
      } else {
        displayMessage = message || 'An error occurred';
      }

      toastService.show(displayMessage, 'error');
      return throwError(() => error);
    }),
  );
};
