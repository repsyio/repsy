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

import { AuthService } from '../../auth/pages/services/auth.service';
import { ToastService } from '../../panel/shared/components/toast/toast.service';
import { ERROR_CODES } from '../constants/error-codes';
import { RefreshTokenInterceptor } from './refresh-token.interceptor';

const SESSION_EXPIRED = { status: 401, statusText: 'Unauthorized' };

describe('RefreshTokenInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router> & { url: string };
  let toastService: jasmine.SpyObj<ToastService>;
  let session: boolean;
  let refreshes: Subject<string>[];

  beforeEach(() => {
    refreshes = [];
    session = true;
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['refreshToken', 'logOut', 'isAuthenticated']);
    authService.isAuthenticated.and.callFake(() => session);
    authService.logOut.and.callFake(() => (session = false));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    authService.refreshToken.and.callFake(() => {
      const refresh = new Subject<string>();
      refreshes.push(refresh);
      return refresh;
    });
    router = Object.assign(jasmine.createSpyObj<Router>('Router', ['navigateByUrl']), { url: '/' });

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        { provide: HTTP_INTERCEPTORS, useClass: RefreshTokenInterceptor, multi: true },
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
        { provide: ToastService, useValue: toastService },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function expireSession(url: string): void {
    httpTesting.expectOne(url).flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
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
    const failure = new HttpErrorResponse({ status: 401, error: { code: ERROR_CODES.REFRESH_TOKEN_EXPIRED } });
    refreshes[0].error(failure);

    expect(errors).toEqual([failure, failure]);
    expect(authService.logOut).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
    expect(toastService.show).toHaveBeenCalledOnceWith('Session expired, please log in again.', 'error');
  });

  it('starts a clean refresh after a failed one, so concurrent requests are retried with the new token', () => {
    const errors: unknown[] = [];
    http.get('/a').subscribe({ error: (e) => errors.push(e) });
    http.get('/b').subscribe({ error: (e) => errors.push(e) });
    expireSession('/a');
    expireSession('/b');
    refreshes[0].error(new HttpErrorResponse({ status: 401, error: { code: ERROR_CODES.REFRESH_TOKEN_EXPIRED } }));
    expect(errors.length).toBe(2);

    // The user signs in again, and two requests expire together.
    session = true;
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

  // RPS-1621: a write is refused with 401 before the server does anything, so the one retry cannot apply it twice.
  describe('a write refused with sessionExpired', () => {
    const BODY = { name: 'my-repo', type: 'MAVEN' };

    function write(method: 'post' | 'put' | 'patch' | 'delete', results: unknown[], errors: unknown[] = []): void {
      const call = method === 'delete' ? http.delete('/api/repos/x') : http[method]('/api/repos/x', BODY);
      call.subscribe({ next: (r) => results.push(r), error: (e) => errors.push(e) });
    }

    ['post', 'put', 'patch', 'delete'].forEach((method) => {
      it(`${method.toUpperCase()} is replayed once, with its body and the new token, after one refresh`, () => {
        const results: unknown[] = [];
        write(method as 'post', results);

        const refused = httpTesting.expectOne('/api/repos/x');
        expect(refused.request.method).toBe(method.toUpperCase());
        refused.flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
        expect(authService.refreshToken).toHaveBeenCalledTimes(1);
        refreshes[0].next('token-1');
        refreshes[0].complete();

        const retried = httpTesting.expectOne('/api/repos/x');
        expect(retried.request.method).toBe(method.toUpperCase());
        expect(retried.request.body).toEqual(method === 'delete' ? null : BODY);
        expect(retried.request.headers.get('Authorization')).toBe('Bearer token-1');
        retried.flush({ done: true });

        expect(results).toEqual([{ done: true }]);
        expect(authService.refreshToken).toHaveBeenCalledTimes(1);
        expect(authService.logOut).not.toHaveBeenCalled();
      });
    });

    it('is not sent again when the refresh fails: the session ends and the write was never applied', () => {
      const results: unknown[] = [];
      const errors: unknown[] = [];
      router.url = '/my-repo/settings';
      write('post', results, errors);

      httpTesting.expectOne('/api/repos/x').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
      refreshes[0].error(new HttpErrorResponse({ status: 401, error: { code: ERROR_CODES.REFRESH_TOKEN_EXPIRED } }));

      httpTesting.expectNone('/api/repos/x');
      expect(results).toEqual([]);
      expect(errors.length).toBe(1);
      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Session expired, please log in again.', 'error');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login?returnUrl=%2Fmy-repo%2Fsettings');
    });

    it('is not sent a third time when the retry is refused too: it logs out and completes quietly', () => {
      const results: unknown[] = [];
      const errors: unknown[] = [];
      write('put', results, errors);

      httpTesting.expectOne('/api/repos/x').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
      refreshes[0].next('token-1');
      refreshes[0].complete();
      httpTesting.expectOne('/api/repos/x').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);

      httpTesting.expectNone('/api/repos/x');
      expect(authService.refreshToken).toHaveBeenCalledTimes(1);
      expect(results).toEqual([]);
      expect(errors).toEqual([]);
      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Session invalid, please log in again.', 'error');
    });

    it('completes quietly, without a replay, when another tab ended the session meanwhile', () => {
      const results: unknown[] = [];
      const errors: unknown[] = [];
      let completed = false;
      http.post('/api/repos/x', BODY).subscribe({
        next: (r) => results.push(r),
        error: (e) => errors.push(e),
        complete: () => (completed = true),
      });

      httpTesting.expectOne('/api/repos/x').flush({ code: ERROR_CODES.SESSION_EXPIRED }, SESSION_EXPIRED);
      // AuthService.refreshToken() completes empty when the storage says the session is gone.
      refreshes[0].complete();

      httpTesting.expectNone('/api/repos/x');
      expect(completed).toBeTrue();
      expect(results).toEqual([]);
      expect(errors).toEqual([]);
      expect(authService.logOut).not.toHaveBeenCalled();
    });
  });

  // RPS-1279: the rule per 401 code is documented on RefreshTokenInterceptor.
  describe('a 401 on an ordinary call', () => {
    const REFUSED = { status: 401, statusText: 'Unauthorized' };
    let completed: boolean;
    let errors: unknown[];

    beforeEach(() => {
      completed = false;
      errors = [];
    });

    function call(url = '/a'): void {
      http.get(url).subscribe({ error: (e) => errors.push(e), complete: () => (completed = true) });
    }

    function refuse(msgId: string | null, url = '/a'): void {
      httpTesting.expectOne(url).flush(msgId ? { code: msgId } : null, REFUSED);
    }

    function expectLoggedOut(message: string): void {
      expect(authService.refreshToken).not.toHaveBeenCalled();
      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith(message, 'error');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
      expect(completed).toBeTrue();
      expect(errors).toEqual([]);
    }

    it('sessionExpired refreshes once and retries once', () => {
      const results: unknown[] = [];
      http.get('/a').subscribe((r) => results.push(r));

      refuse(ERROR_CODES.SESSION_EXPIRED);
      refreshes[0].next('token-1');
      refreshes[0].complete();

      expectRetriedWith('/a', 'token-1');
      expect(results).toEqual([{ ok: '/a' }]);
      expect(authService.refreshToken).toHaveBeenCalledTimes(1);
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('sessionExpired that is refused again after the refresh logs out instead of looping', () => {
      call();

      refuse(ERROR_CODES.SESSION_EXPIRED);
      refreshes[0].next('token-1');
      refreshes[0].complete();
      httpTesting.expectOne('/a').flush({ code: ERROR_CODES.SESSION_EXPIRED }, REFUSED);

      expect(authService.refreshToken).toHaveBeenCalledTimes(1);
      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Session invalid, please log in again.', 'error');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
      expect(completed).toBeTrue();
    });

    it('accessNotAllowed (bad signature) logs out without a refresh', () => {
      call();
      refuse(ERROR_CODES.ACCESS_NOT_ALLOWED);
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('loginRequired (account gone, credentials missing or invalid) logs out without a refresh', () => {
      call();
      refuse(ERROR_CODES.LOGIN_REQUIRED);
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('refreshTokenExpired logs out without a refresh', () => {
      call();
      refuse(ERROR_CODES.REFRESH_TOKEN_EXPIRED);
      expectLoggedOut('Session expired, please log in again.');
    });

    it('an unknown code logs out without a refresh', () => {
      call();
      refuse('somethingNew');
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('a 401 without a body logs out without a refresh', () => {
      call();
      refuse(null);
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('logs out once when several calls are refused together', () => {
      call('/a');
      call('/b');

      refuse(ERROR_CODES.ACCESS_NOT_ALLOWED, '/a');
      refuse(ERROR_CODES.ACCESS_NOT_ALLOWED, '/b');

      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledTimes(1);
      expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    });

    it('passes a 401 on quietly when there is no session any more', () => {
      session = false;
      call();
      refuse(ERROR_CODES.ACCESS_NOT_ALLOWED);

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('a 403 accessDenied (signed in, not allowed) leaves the session alone: no refresh, no logout (RPS-1284)', () => {
      call();
      httpTesting.expectOne('/a').flush({ code: ERROR_CODES.ACCESS_DENIED }, { status: 403, statusText: 'Forbidden' });

      expect(errors.length).toBe(1);
      expect(authService.refreshToken).not.toHaveBeenCalled();
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('leaves the invalidCredentials of a login attempt to the login form', () => {
      call('/api/auth/login?x=1');
      refuse(ERROR_CODES.INVALID_CREDENTIALS, '/api/auth/login?x=1');

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    [ERROR_CODES.REFRESH_TOKEN_EXPIRED, ERROR_CODES.SESSION_EXPIRED, ERROR_CODES.ACCESS_NOT_ALLOWED].forEach(
      (msgId) => {
        it(`${msgId} on the refresh call itself logs out and never refreshes again`, () => {
          call('/api/auth/tokens/refresh');
          refuse(msgId, '/api/auth/tokens/refresh');

          expect(authService.refreshToken).not.toHaveBeenCalled();
          expect(authService.logOut).toHaveBeenCalledTimes(1);
          expect(toastService.show).toHaveBeenCalledOnceWith('Session expired, please log in again.', 'error');
          expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
          expect(completed).toBeTrue();
        });
      },
    );

    it('leaves a failed retry that is not a 401 to its caller', () => {
      call();
      refuse(ERROR_CODES.SESSION_EXPIRED);
      refreshes[0].next('token-1');
      refreshes[0].complete();
      httpTesting.expectOne('/a').flush(null, { status: 500, statusText: 'Server Error' });

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
    });
  });
});
