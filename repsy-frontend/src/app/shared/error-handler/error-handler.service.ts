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

@Injectable({
  providedIn: 'root',
})
export class ErrorHandlerService {
  constructor(private readonly router: Router) {}

  public handle(res: HttpErrorResponse): string | null {
    if (!res || !res.status) {
      return 'Service unavailable';
    }

    if (
      res.status === 403 &&
      'msgId' in res.error &&
      (res.error.msgId === 'sessionExpired' || res.error.msgId === 'refreshTokenExpired')
    ) {
      localStorage.clear();
      this.router.navigateByUrl('/');
    } else {
      console.error(res.error);
    }

    let errorText = 'Error Occurred';

    if (Object.prototype.hasOwnProperty.call(res.error, 'text')) {
      errorText = res.error.text;
    }

    return errorText;
  }
}
