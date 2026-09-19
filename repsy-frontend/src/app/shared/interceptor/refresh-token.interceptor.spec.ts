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

import {
  HTTP_INTERCEPTORS,
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptorsFromDi,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';

import { AuthService } from '../../auth/pages/service/auth.service';
import { RefreshTokenInterceptor } from './refresh-token.interceptor';

const SESSION_EXPIRED = { status: 401, statusText: 'Unauthorized' };

describe('RefreshTokenInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let refreshes: Subject<string>[];

  beforeEach(() => {
    refreshes = [];
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['refreshToken', 'logOut']);
    authService.refreshToken.and.callFake(() => {
      const refresh = new Subject<string>();
      refreshes.push(refresh);
      return refresh;
    });
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        { provide: HTTP_INTERCEPTORS, useClass: RefreshTokenInterceptor, multi: true },
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function expireSession(url: string): void {
    httpTesting.expectOne(url).flush({ msgId: 'sessionExpired' }, SESSION_EXPIRED);
  }

  function expectRetriedWith(url: string, accessToken: string): void {
    const retried = httpTesting.expectOne(url);
    expect(retried.request.headers.get('Authorization')).toBe(`Bearer ${accessToken}`);
    retried.flush({ ok: url });
  }

  it('retries concurrent sessionExpired requests with the token from a single refresh', () => {
    const results: unknown[] = [];
    http.get('/a').subscribe((r) => results.push(r));
    http.get('/b').subscribe((r) => results.push(r));

    expireSession('/a');
    expireSession('/b');
    expect(authService.refreshToken).toHaveBeenCalledTimes(1);

    refreshes[0].next('token-1');
    refreshes[0].complete();

    expectRetriedWith('/a', 'token-1');
    expectRetriedWith('/b', 'token-1');
    expect(results).toEqual([{ ok: '/a' }, { ok: '/b' }]);
    expect(authService.logOut).not.toHaveBeenCalled();
  });

  it('fails the waiting requests and logs out when the refresh fails', () => {
    const errors: HttpErrorResponse[] = [];
    http.get('/a').subscribe({ error: (e) => errors.push(e) });
    http.get('/b').subscribe({ error: (e) => errors.push(e) });

    expireSession('/a');
    expireSession('/b');
    const failure = new HttpErrorResponse({ status: 401, error: { msgId: 'refreshTokenExpired' } });
    refreshes[0].error(failure);

    expect(errors).toEqual([failure, failure]);
    expect(authService.logOut).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('login');
  });

  it('starts a clean refresh after a failed one, so concurrent requests are retried with the new token', () => {
    const errors: unknown[] = [];
    http.get('/a').subscribe({ error: (e) => errors.push(e) });
    http.get('/b').subscribe({ error: (e) => errors.push(e) });
    expireSession('/a');
    expireSession('/b');
    refreshes[0].error(new HttpErrorResponse({ status: 401, error: { msgId: 'refreshTokenExpired' } }));
    expect(errors.length).toBe(2);

    // The user signs in again, and two requests expire together.
    const results: unknown[] = [];
    http.get('/c').subscribe({ next: (r) => results.push(r), error: (e) => errors.push(e) });
    http.get('/d').subscribe({ next: (r) => results.push(r), error: (e) => errors.push(e) });
    expireSession('/c');
    expireSession('/d');

    expect(errors.length).toBe(2);
    expect(authService.refreshToken).toHaveBeenCalledTimes(2);

    refreshes[1].next('token-2');
    refreshes[1].complete();

    expectRetriedWith('/c', 'token-2');
    expectRetriedWith('/d', 'token-2');
    expect(results).toEqual([{ ok: '/c' }, { ok: '/d' }]);
    expect(errors.length).toBe(2);
    expect(authService.logOut).toHaveBeenCalledTimes(1);
  });
});
