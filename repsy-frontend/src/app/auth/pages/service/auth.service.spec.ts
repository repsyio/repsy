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
import { PLATFORM_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';

import { AuthControllerService, LoginInfo, RestResponseLoginInfo } from '../../../../generated/api';
import { AuthService } from './auth.service';

const STORAGE_KEYS = ['username', 'token', 'refresh-token'];

const SESSION: LoginInfo = { username: 'alice', token: 'access-1', refreshToken: 'refresh-1' };

function response(data: LoginInfo): RestResponseLoginInfo {
  return { data };
}

describe('AuthService', () => {
  let login: jasmine.Spy;
  let refreshToken: jasmine.Spy;

  function createService(platform = 'browser'): AuthService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthControllerService, useValue: { login, refreshToken } },
        { provide: PLATFORM_ID, useValue: platform },
      ],
    });
    return TestBed.inject(AuthService);
  }

  function clearStorage(): void {
    STORAGE_KEYS.forEach((key) => localStorage.removeItem(key));
  }

  function seedStorage(session: Partial<LoginInfo>): void {
    if (session.username) {
      localStorage.setItem('username', session.username);
    }
    if (session.token) {
      localStorage.setItem('token', session.token);
    }
    if (session.refreshToken) {
      localStorage.setItem('refresh-token', session.refreshToken);
    }
  }

  beforeEach(() => {
    clearStorage();
    login = jasmine.createSpy('login');
    refreshToken = jasmine.createSpy('refreshToken');
  });

  afterEach(clearStorage);

  describe('isAuthenticated$ (RPS-1278)', () => {
    it('emits the session state, then every change of it once', () => {
      const service = createService();
      const states: boolean[] = [];
      service.isAuthenticated$.subscribe((state) => states.push(state));

      service.updateLoginInfo(SESSION);
      service.updateLoginInfo({ ...SESSION, token: 'access-2', refreshToken: 'refresh-2' });
      service.logOut();
      service.logOut();

      expect(states).toEqual([false, true, false]);
    });

    it('starts true when a stored session is restored', () => {
      seedStorage(SESSION);
      const service = createService();
      const states: boolean[] = [];
      service.isAuthenticated$.subscribe((state) => states.push(state));

      expect(states).toEqual([true]);
    });
  });

  describe('restoring a session', () => {
    it('starts signed out with nothing stored', () => {
      const service = createService();

      expect(service.isAuthenticated()).toBeFalse();
      expect(service.username).toBeNull();
      expect(service.accessToken).toBeNull();
    });

    it('restores the username and tokens from local storage', () => {
      seedStorage(SESSION);

      const service = createService();

      expect(service.isAuthenticated()).toBeTrue();
      expect(service.username).toBe('alice');
      expect(service.accessToken).toBe('access-1');
    });

    it('is not authenticated when only one of the two tokens is stored', () => {
      seedStorage({ username: 'alice', token: 'access-1' });
      expect(createService().isAuthenticated()).toBeFalse();

      clearStorage();
      seedStorage({ username: 'alice', refreshToken: 'refresh-1' });
      expect(createService().isAuthenticated()).toBeFalse();
    });

    it('ignores local storage when not running in a browser', () => {
      seedStorage(SESSION);

      const service = createService('server');

      expect(service.isAuthenticated()).toBeFalse();
      expect(service.accessToken).toBeUndefined();
    });
  });

  describe('logIn', () => {
    it('sends the form, keeps the session in memory and persists it', async () => {
      login.and.returnValue(of(response(SESSION)));
      const service = createService();
      const form = { username: 'alice', password: 'Passw0rd' };

      await firstValueFrom(service.logIn(form));

      expect(login).toHaveBeenCalledOnceWith(form);
      expect(service.isAuthenticated()).toBeTrue();
      expect(service.username).toBe('alice');
      expect(service.accessToken).toBe('access-1');
      expect(localStorage.getItem('username')).toBe('alice');
      expect(localStorage.getItem('token')).toBe('access-1');
      expect(localStorage.getItem('refresh-token')).toBe('refresh-1');
    });

    it('does not touch the session when the login fails', async () => {
      const failure = new HttpErrorResponse({ status: 401 });
      login.and.returnValue(throwError(() => failure));
      const service = createService();

      await expectAsync(firstValueFrom(service.logIn({ username: 'alice', password: 'wrong' }))).toBeRejectedWith(
        failure,
      );

      expect(service.isAuthenticated()).toBeFalse();
      expect(localStorage.getItem('token')).toBeNull();
    });

    it('does not persist to local storage when not running in a browser', async () => {
      login.and.returnValue(of(response(SESSION)));
      const service = createService('server');

      await firstValueFrom(service.logIn({ username: 'alice', password: 'Passw0rd' }));

      expect(service.isAuthenticated()).toBeTrue();
      expect(localStorage.getItem('token')).toBeNull();
    });
  });

  describe('refreshToken', () => {
    it('fails without calling the API when there is no refresh token', async () => {
      const service = createService();

      await expectAsync(firstValueFrom(service.refreshToken())).toBeRejectedWithError('No refresh token presents.');
      expect(refreshToken).not.toHaveBeenCalled();
    });

    it('exchanges the refresh token, stores the rotated session and returns the new access token', async () => {
      seedStorage(SESSION);
      const rotated: LoginInfo = { username: 'alice', token: 'access-2', refreshToken: 'refresh-2' };
      refreshToken.and.returnValue(of(response(rotated)));
      const service = createService();

      const accessToken = await firstValueFrom(service.refreshToken());

      expect(refreshToken).toHaveBeenCalledOnceWith({ refreshToken: 'refresh-1' });
      expect(accessToken).toBe('access-2');
      expect(service.accessToken).toBe('access-2');
      expect(localStorage.getItem('token')).toBe('access-2');
      expect(localStorage.getItem('refresh-token')).toBe('refresh-2');
    });

    it('keeps the current session when the refresh fails', async () => {
      seedStorage(SESSION);
      const failure = new HttpErrorResponse({ status: 401 });
      refreshToken.and.returnValue(throwError(() => failure));
      const service = createService();

      await expectAsync(firstValueFrom(service.refreshToken())).toBeRejectedWith(failure);

      expect(service.accessToken).toBe('access-1');
      expect(localStorage.getItem('refresh-token')).toBe('refresh-1');
    });
  });

  describe('updateLoginInfo', () => {
    it('replaces the session in memory and in local storage', () => {
      const service = createService();

      service.updateLoginInfo({ username: 'bob', token: 'access-9', refreshToken: 'refresh-9' });

      expect(service.username).toBe('bob');
      expect(service.accessToken).toBe('access-9');
      expect(service.isAuthenticated()).toBeTrue();
      expect(localStorage.getItem('username')).toBe('bob');
      expect(localStorage.getItem('token')).toBe('access-9');
      expect(localStorage.getItem('refresh-token')).toBe('refresh-9');
    });
  });

  describe('logOut', () => {
    it('clears the session in memory and in local storage', () => {
      seedStorage(SESSION);
      const service = createService();

      service.logOut();

      expect(service.isAuthenticated()).toBeFalse();
      expect(service.username).toBeNull();
      expect(service.accessToken).toBeNull();
      STORAGE_KEYS.forEach((key) => expect(localStorage.getItem(key)).withContext(key).toBeNull());
    });

    it('leaves unrelated local storage entries alone', () => {
      localStorage.setItem('theme', 'dark');
      seedStorage(SESSION);
      const service = createService();

      service.logOut();

      expect(localStorage.getItem('theme')).toBe('dark');
      localStorage.removeItem('theme');
    });
  });
});
