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
import { ToastService } from '../../panel/shared/components/toast/toast.service';
import { RefreshTokenInterceptor } from './refresh-token.interceptor';

const SESSION_EXPIRED = { status: 401, statusText: 'Unauthorized' };

describe('RefreshTokenInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
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
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);

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
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
    expect(toastService.show).toHaveBeenCalledOnceWith('Session expired, please log in again.', 'error');
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

  // RPS-1279: the rule per 401 msgId is documented on RefreshTokenInterceptor.
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
      httpTesting.expectOne(url).flush(msgId ? { msgId } : null, REFUSED);
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

      refuse('sessionExpired');
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

      refuse('sessionExpired');
      refreshes[0].next('token-1');
      refreshes[0].complete();
      httpTesting.expectOne('/a').flush({ msgId: 'sessionExpired' }, REFUSED);

      expect(authService.refreshToken).toHaveBeenCalledTimes(1);
      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Session invalid, please log in again.', 'error');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
      expect(completed).toBeTrue();
    });

    it('accessNotAllowed (bad signature) logs out without a refresh', () => {
      call();
      refuse('accessNotAllowed');
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('unAuthorized (account gone, credentials missing or invalid) logs out without a refresh', () => {
      call();
      refuse('unAuthorized');
      expectLoggedOut('Session invalid, please log in again.');
    });

    it('refreshTokenExpired logs out without a refresh', () => {
      call();
      refuse('refreshTokenExpired');
      expectLoggedOut('Session expired, please log in again.');
    });

    it('an unknown msgId logs out without a refresh', () => {
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

      refuse('accessNotAllowed', '/a');
      refuse('accessNotAllowed', '/b');

      expect(authService.logOut).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledTimes(1);
      expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    });

    it('passes a 401 on quietly when there is no session any more', () => {
      session = false;
      call();
      refuse('accessNotAllowed');

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('a 403 accessDenied (signed in, not allowed) leaves the session alone: no refresh, no logout (RPS-1284)', () => {
      call();
      httpTesting.expectOne('/a').flush({ msgId: 'accessDenied' }, { status: 403, statusText: 'Forbidden' });

      expect(errors.length).toBe(1);
      expect(authService.refreshToken).not.toHaveBeenCalled();
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('leaves the invalidCredentials of a login attempt to the login form', () => {
      call('/api/auth/login?x=1');
      refuse('invalidCredentials', '/api/auth/login?x=1');

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    ['refreshTokenExpired', 'sessionExpired', 'accessNotAllowed'].forEach((msgId) => {
      it(`${msgId} on the refresh call itself logs out and never refreshes again`, () => {
        call('/api/auth/tokens/refresh');
        refuse(msgId, '/api/auth/tokens/refresh');

        expect(authService.refreshToken).not.toHaveBeenCalled();
        expect(authService.logOut).toHaveBeenCalledTimes(1);
        expect(toastService.show).toHaveBeenCalledOnceWith('Session expired, please log in again.', 'error');
        expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
        expect(completed).toBeTrue();
      });
    });

    it('leaves a failed retry that is not a 401 to its caller', () => {
      call();
      refuse('sessionExpired');
      refreshes[0].next('token-1');
      refreshes[0].complete();
      httpTesting.expectOne('/a').flush(null, { status: 500, statusText: 'Server Error' });

      expect(errors.length).toBe(1);
      expect(authService.logOut).not.toHaveBeenCalled();
    });
  });
});
