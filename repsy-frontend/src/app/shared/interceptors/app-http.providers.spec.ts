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

import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { ToastService } from '../../panel/shared/components/toast/toast.service';
import { ERROR_CODES } from '../constants/error-codes';
import { provideAppHttpClient } from './app-http-providers';

const REFRESH_URL_END = '/api/auth/tokens/refresh';
const SESSION_EXPIRED = { status: 401, statusText: 'Unauthorized' };
const SESSION_EXPIRED_TOAST = 'Session expired, please log in again.';

// RPS-1754: the real chain (headers, RefreshTokenInterceptor, errorHandlerInterceptor) with the real
// AuthService, so the refresh call is a real HttpClient POST that passes the chain too. The refresh
// runs under a Web Lock inside AuthService, so its request appears a moment after the 401.
describe('panel HTTP pipeline (provideAppHttpClient)', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('username', 'jane');
    localStorage.setItem('token', 'old-access');
    localStorage.setItem('refresh-token', 'old-refresh');
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = Object.assign(jasmine.createSpyObj<Router>('Router', ['navigateByUrl']), { url: '/' });
    TestBed.configureTestingModule({
      providers: [
        ...provideAppHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
    localStorage.clear();
  });

  async function nextRefresh() {
    for (let attempt = 0; attempt < 100; attempt++) {
      const [refresh] = httpTesting.match((r) => r.url.endsWith(REFRESH_URL_END));
      if (refresh) {
        return refresh;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error('the refresh call was never sent');
  }

  /** Lets the failure cascade (a rejected refresh promise, then its rethrow) run to the end. */
  const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

  it('401 sessionExpired, refresh ok, retry ok: no toast, and the retry carries the new token', async () => {
    http.get('/api/things').subscribe({ error: () => undefined });

    httpTesting.expectOne('/api/things').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
    (await nextRefresh()).flush({ username: 'jane', token: 'new-access', refreshToken: 'new-refresh' });
    await settle();
    const retry = httpTesting.expectOne('/api/things');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new-access');
    retry.flush({ ok: true });

    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('the refresh call fails with 503: exactly one toast, "Session expired" (error interceptor innermost)', async () => {
    http.get('/api/things').subscribe({ error: () => undefined });
    http.get('/api/other').subscribe({ error: () => undefined });

    httpTesting.expectOne('/api/things').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
    httpTesting.expectOne('/api/other').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
    (await nextRefresh()).flush({ detail: 'busy' }, { status: 503, statusText: 'Service Unavailable' });
    await settle();

    expect(toastService.show.calls.allArgs()).toEqual([[SESSION_EXPIRED_TOAST, 'error']]);
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    httpTesting.expectOne((r) => r.url.endsWith('/api/auth/logout')).flush({});
  });

  it('the refresh call fails with status 0: exactly one toast, "Session expired"', async () => {
    http.get('/api/things').subscribe({ error: () => undefined });

    httpTesting.expectOne('/api/things').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
    (await nextRefresh()).error(new ProgressEvent('error'));
    await settle();

    expect(toastService.show.calls.allArgs()).toEqual([[SESSION_EXPIRED_TOAST, 'error']]);
    httpTesting.expectOne((r) => r.url.endsWith('/api/auth/logout')).flush({});
  });

  it('the retry after a good refresh answers 500: exactly one "Server error" toast', async () => {
    http.get('/api/things').subscribe({ error: () => undefined });

    httpTesting.expectOne('/api/things').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
    (await nextRefresh()).flush({ username: 'jane', token: 'new-access', refreshToken: 'new-refresh' });
    await settle();
    httpTesting.expectOne('/api/things').flush({ detail: 'detail' }, { status: 500, statusText: 'Error' });

    expect(toastService.show.calls.allArgs()).toEqual([['Server error', 'error']]);
  });

  it('a plain 500 on an ordinary GET: exactly one "Server error" toast', () => {
    http.get('/api/things').subscribe({ error: () => undefined });

    httpTesting.expectOne('/api/things').flush({ detail: 'detail' }, { status: 500, statusText: 'Error' });

    expect(toastService.show.calls.allArgs()).toEqual([['Server error', 'error']]);
  });
});
