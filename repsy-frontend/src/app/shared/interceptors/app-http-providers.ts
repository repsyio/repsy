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

import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptors, withInterceptorsFromDi } from '@angular/common/http';
import { EnvironmentProviders, Provider } from '@angular/core';

import { errorHandlerInterceptor } from './error-handler.interceptor';
import { HttpHeadersInterceptor } from './http-headers.interceptor';
import { RefreshTokenInterceptor } from './refresh-token.interceptor';

/**
 * The panel's HTTP pipeline. The first feature passed to `provideHttpClient` is the outermost
 * interceptor, so the order is: headers, then the 401 refresh/logout, then `errorHandlerInterceptor`
 * next to the backend.
 *
 * The error toaster sits innermost on purpose (RPS-1754, as in Repsy Cloud's panel, RPS-1742).
 * Innermost it sees each raw backend response exactly once, including the retry
 * `RefreshTokenInterceptor` sends, and never the error `RefreshTokenInterceptor` rethrows after a
 * failed refresh. Outermost, a refresh call that fails with a 5xx or status 0 toasted three times (the
 * refresh request, "Session expired", then every request that waited for it).
 */
export function provideAppHttpClient(): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(withInterceptorsFromDi(), withInterceptors([errorHandlerInterceptor])),
    { provide: HTTP_INTERCEPTORS, useClass: HttpHeadersInterceptor, multi: true },
    { provide: HTTP_INTERCEPTORS, useClass: RefreshTokenInterceptor, multi: true },
  ];
}
