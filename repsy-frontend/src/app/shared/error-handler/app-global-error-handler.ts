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

import { ErrorHandler, Injectable } from '@angular/core';
import { Router } from '@angular/router';

import { ErrorForm } from './error-form';

@Injectable()
export class AppGlobalErrorHandler implements ErrorHandler {
  private readonly MAX_MESSAGE_LENGTH = 9_999;
  private readonly MAX_STACK_LENGTH = 49_999;
  private readonly MAX_URL_LENGTH = 249;

  public constructor(private readonly router: Router) {}

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public handleError(error: any): void {
    const errorForm = new ErrorForm();
    errorForm.message = error?.message?.substring(0, this.MAX_MESSAGE_LENGTH) ?? 'unknown error';
    errorForm.stack = error?.stack?.substring(0, this.MAX_STACK_LENGTH) ?? 'N/A';
    errorForm.url = this.router?.url?.substring(0, this.MAX_URL_LENGTH) ?? 'N/A';
    //console.error('Global error handler caught an error:', errorForm);
  }
}
