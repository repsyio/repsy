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

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';

import { ERROR_CODES } from '../constants/error-codes';
import { problemOf } from './problem.utils';

@Injectable({
  providedIn: 'root',
})
export class ErrorHandlerService {
  constructor(private readonly router: Router) {}

  handle(res: HttpErrorResponse): string | null {
    if (!res || !res.status) {
      return 'Service unavailable';
    }

    const problem = problemOf(res);

    if (
      res.status === 401 &&
      (problem?.code === ERROR_CODES.SESSION_EXPIRED || problem?.code === ERROR_CODES.REFRESH_TOKEN_EXPIRED)
    ) {
      localStorage.clear();
      this.router.navigateByUrl('/');
    } else {
      console.error(res.error);
    }

    return problem?.detail ?? 'Error Occurred';
  }
}
