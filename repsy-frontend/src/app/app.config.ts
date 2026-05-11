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

import { HTTP_INTERCEPTORS, HttpClient, provideHttpClient, withInterceptors, withInterceptorsFromDi } from '@angular/common/http';
import { ApplicationConfig, ErrorHandler, importProvidersFrom } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { PreloadAllModules, PreloadingStrategy, provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHighlightOptions } from 'ngx-highlightjs';
import { provideMarkdown } from 'ngx-markdown';

import { environment } from '../environments/environment';
import { BASE_PATH } from '../generated/api';
import { routes } from './app.routes';
import { errorHandlerInterceptor } from './core/interceptors/error-handler.interceptor';
import { AppGlobalErrorHandler } from './shared/error-handler/app-global-error-handler';
import { ACCESS_TOKEN_INITIALIZER } from './shared/initializer/access-token.initializer';
import { HttpHeadersInterceptor } from './shared/interceptor/http-headers.interceptor';
import { RefreshTokenInterceptor } from './shared/interceptor/refresh-token.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    importProvidersFrom(BrowserModule),
    provideRouter(
      routes,
      withInMemoryScrolling({
        anchorScrolling: 'enabled',
        scrollPositionRestoration: 'enabled',
      }),
    ),
    provideMarkdown({ loader: HttpClient }),
    provideHttpClient(withInterceptors([errorHandlerInterceptor]), withInterceptorsFromDi()),
    {
      provide: HTTP_INTERCEPTORS,
      useClass: HttpHeadersInterceptor,
      multi: true,
    },
    {
      provide: HTTP_INTERCEPTORS,
      useClass: RefreshTokenInterceptor,
      multi: true,
    },
    {
      provide: ErrorHandler,
      useClass: AppGlobalErrorHandler,
    },
    {
      provide: PreloadingStrategy,
      useClass: PreloadAllModules,
    },
    { provide: BASE_PATH, useValue: environment.apiBaseUrl },
    ACCESS_TOKEN_INITIALIZER,
    provideHighlightOptions({
      fullLibraryLoader: () => import('highlight.js'),
      lineNumbersLoader: () => import('ngx-highlightjs/line-numbers'),
    }),
  ],
};
