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

import { HTTP_INTERCEPTORS, HttpClient, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../auth/pages/service/auth.service';
import { HttpHeadersInterceptor } from './http-headers.interceptor';

describe('HttpHeadersInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated'], {
      accessToken: 'access-1',
    });

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        { provide: HTTP_INTERCEPTORS, useClass: HttpHeadersInterceptor, multi: true },
        { provide: AuthService, useValue: authService },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('adds the bearer token when the user is authenticated', () => {
    authService.isAuthenticated.and.returnValue(true);

    http.get('/api').subscribe();

    const request = httpTesting.expectOne('/api').request;
    expect(request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(request.headers.get('Cache-Control')).toBe('no-cache');
  });

  it('sends no Authorization header when the user is not authenticated', () => {
    authService.isAuthenticated.and.returnValue(false);

    http.get('/api').subscribe();

    const request = httpTesting.expectOne('/api').request;
    expect(request.headers.has('Authorization')).toBeFalse();
    expect(request.headers.get('Cache-Control')).toBe('no-cache');
  });

  it('keeps the headers the caller already set', () => {
    authService.isAuthenticated.and.returnValue(true);

    http.get('/api', { headers: { 'X-Custom': 'yes' } }).subscribe();

    expect(httpTesting.expectOne('/api').request.headers.get('X-Custom')).toBe('yes');
  });

  it('overrides an Authorization header set by the caller for an authenticated user', () => {
    authService.isAuthenticated.and.returnValue(true);

    http.get('/api', { headers: { Authorization: 'Bearer stale' } }).subscribe();

    expect(httpTesting.expectOne('/api').request.headers.get('Authorization')).toBe('Bearer access-1');
  });
});
