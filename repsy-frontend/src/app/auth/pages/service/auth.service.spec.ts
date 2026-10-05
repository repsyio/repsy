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

import { HttpContext, HttpErrorResponse } from '@angular/common/http';
import { PLATFORM_ID } from '@angular/core';
import { fakeAsync, flushMicrotasks, TestBed, tick } from '@angular/core/testing';
import { EMPTY, firstValueFrom, from, of, throwError } from 'rxjs';

import { AuthApi, LoginInfo, RestResponseLoginInfo } from '../../../../generated/api';
import { SILENT_ERROR } from '../../../shared/interceptor/error-handler.interceptor';
import { AuthService } from './auth.service';

/** The refresh call must carry SILENT_ERROR, so `errorHandlerInterceptor` never toasts it (RPS-1754). */
const SILENT_REFRESH_OPTIONS = {
  asymmetricMatch: (options: { context?: HttpContext } | undefined) => options?.context?.get(SILENT_ERROR) === true,
  jasmineToString: () => '<request options with SILENT_ERROR set>',
};

const STORAGE_KEYS = ['username', 'token', 'refresh-token'];

const SESSION: LoginInfo = { username: 'alice', token: 'access-1', refreshToken: 'refresh-1' };

function response(data: LoginInfo): RestResponseLoginInfo {
  return { data };
}

describe('AuthService', () => {
  let login: jasmine.Spy;
  let refreshToken: jasmine.Spy;
  let logout: jasmine.Spy;

  function createService(platform = 'browser'): AuthService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthApi, useValue: { login, refreshToken, logout } },
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
    logout = jasmine.createSpy('logout').and.returnValue(of({}));
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

      expect(refreshToken).toHaveBeenCalledOnceWith(
        { refreshToken: 'refresh-1' },
        'body',
        false,
        SILENT_REFRESH_OPTIONS,
      );
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

    // RPS-1622: a copied/leaked refresh token must not keep working after the user clicks Logout.
    it('revokes the refresh token family server-side, with the token it is about to discard', () => {
      seedStorage(SESSION);
      const service = createService();

      service.logOut();

      expect(logout).toHaveBeenCalledOnceWith({ refreshToken: 'refresh-1' });
    });

    it('does not call the backend when there is no refresh token to revoke', () => {
      const service = createService();

      service.logOut();

      expect(logout).not.toHaveBeenCalled();
    });

    it('clears the session synchronously, without waiting for the backend call to settle', () => {
      logout.and.returnValue(EMPTY);
      seedStorage(SESSION);
      const service = createService();

      service.logOut();

      expect(service.isAuthenticated()).toBeFalse();
      STORAGE_KEYS.forEach((key) => expect(localStorage.getItem(key)).withContext(key).toBeNull());
    });

    it('does not throw or leave an unhandled rejection when the backend call fails', () => {
      logout.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      seedStorage(SESSION);
      const service = createService();

      expect(() => service.logOut()).not.toThrow();
      expect(service.isAuthenticated()).toBeFalse();
    });
  });

  // RPS-1621: the tabs of one browser share the localStorage session, each with its own in-memory copy.
  describe('several tabs', () => {
    const ROTATED: LoginInfo = { username: 'alice', token: 'access-2', refreshToken: 'refresh-2' };
    const LOCKS = Object.getOwnPropertyDescriptor(navigator, 'locks');
    let lockRequests: string[];

    /** What another tab writes to the shared storage (a refresh, a login), then the event the browser sends us. */
    function otherTabWrites(session: LoginInfo): void {
      seedStorage(session);
      ['username', 'token', 'refresh-token'].forEach((key) => dispatchStorageEvent(key));
    }

    function dispatchStorageEvent(key: string | null, storageArea: Storage = localStorage): void {
      window.dispatchEvent(new StorageEvent('storage', { key, storageArea }));
    }

    /** A second tab: its own in-memory session over the same localStorage, and no event from writes made in this window. */
    function secondTab(): AuthService {
      return TestBed.runInInjectionContext(
        () => new AuthService({ login, refreshToken } as unknown as AuthApi, 'browser' as unknown as object),
      );
    }

    /** A Web Locks stand-in: one holder at a time, like `navigator.locks.request` with an exclusive lock. */
    function fakeLocks(): void {
      let queue: Promise<unknown> = Promise.resolve();
      Object.defineProperty(navigator, 'locks', {
        configurable: true,
        value: {
          request: (name: string, callback: () => Promise<unknown>) => {
            lockRequests.push(name);
            const run = queue.then(callback);
            queue = run.then(
              () => undefined,
              () => undefined,
            );
            return run;
          },
        },
      });
    }

    function noLocks(): void {
      Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
    }

    beforeEach(() => {
      lockRequests = [];
      fakeLocks();
    });

    afterEach(() => {
      if (LOCKS) {
        Object.defineProperty(navigator, 'locks', LOCKS);
      } else {
        delete (navigator as unknown as { locks?: unknown }).locks;
      }
    });

    describe('the storage event', () => {
      it('adopts the session another tab refreshed', () => {
        seedStorage(SESSION);
        const service = createService();
        const states: boolean[] = [];
        service.isAuthenticated$.subscribe((state) => states.push(state));

        otherTabWrites(ROTATED);

        expect(service.accessToken).toBe('access-2');
        expect(service.username).toBe('alice');
        expect(service.isAuthenticated()).toBeTrue();
        expect(states).toEqual([true]);
      });

      it('adopts a login made in another tab', () => {
        const service = createService();
        const states: boolean[] = [];
        service.isAuthenticated$.subscribe((state) => states.push(state));

        otherTabWrites(SESSION);

        expect(service.isAuthenticated()).toBeTrue();
        expect(service.accessToken).toBe('access-1');
        expect(states).toEqual([false, true]);
      });

      it('signs this tab out when another tab logged out, and says so once', () => {
        seedStorage(SESSION);
        const service = createService();
        const ended = jasmine.createSpy('sessionEndedElsewhere');
        service.sessionEndedElsewhere$.subscribe(ended);

        STORAGE_KEYS.forEach((key) => localStorage.removeItem(key));
        STORAGE_KEYS.forEach((key) => dispatchStorageEvent(key));

        expect(service.isAuthenticated()).toBeFalse();
        expect(service.accessToken).toBeNull();
        expect(ended).toHaveBeenCalledTimes(1);
      });

      it('signs this tab out on localStorage.clear() (a null key)', () => {
        seedStorage(SESSION);
        const service = createService();
        const ended = jasmine.createSpy('sessionEndedElsewhere');
        service.sessionEndedElsewhere$.subscribe(ended);

        localStorage.clear();
        dispatchStorageEvent(null);

        expect(service.isAuthenticated()).toBeFalse();
        expect(ended).toHaveBeenCalledTimes(1);
      });

      it('does not announce a logout this tab made itself, or one it never had a session for', () => {
        seedStorage(SESSION);
        const service = createService();
        const ended = jasmine.createSpy('sessionEndedElsewhere');
        service.sessionEndedElsewhere$.subscribe(ended);

        service.logOut();
        dispatchStorageEvent('token');
        dispatchStorageEvent(null);

        expect(ended).not.toHaveBeenCalled();
      });

      it('ignores other keys and other storage areas', () => {
        seedStorage(SESSION);
        const service = createService();
        localStorage.setItem('token', 'changed-behind-our-back');

        dispatchStorageEvent('theme');
        dispatchStorageEvent('token', sessionStorage);

        expect(service.accessToken).toBe('access-1');
      });

      it('stops listening once the service is destroyed', () => {
        seedStorage(SESSION);
        const service = createService();
        TestBed.resetTestingModule();

        otherTabWrites(ROTATED);

        expect(service.accessToken).toBe('access-1');
      });

      it('is not listened to when not running in a browser', () => {
        seedStorage(SESSION);
        const service = createService('server');

        otherTabWrites(ROTATED);

        expect(service.accessToken).toBeUndefined();
      });
    });

    describe('refreshing', () => {
      it('holds the refresh lock around the call', async () => {
        seedStorage(SESSION);
        refreshToken.and.callFake(() => {
          expect(lockRequests).toEqual(['repsy-refresh']);
          return of(response(ROTATED));
        });
        const service = createService();

        expect(await firstValueFrom(service.refreshToken())).toBe('access-2');

        expect(refreshToken).toHaveBeenCalledOnceWith(
          { refreshToken: 'refresh-1' },
          'body',
          false,
          SILENT_REFRESH_OPTIONS,
        );
      });

      it('adopts the pair another tab rotated meanwhile instead of spending the spent token again', async () => {
        seedStorage(SESSION);
        const service = createService();
        // Another tab refreshed, and its event has not reached this tab (or never will: a frozen tab).
        seedStorage(ROTATED);

        const accessToken = await firstValueFrom(service.refreshToken());

        expect(accessToken).toBe('access-2');
        expect(refreshToken).not.toHaveBeenCalled();
        expect(service.accessToken).toBe('access-2');
        expect(localStorage.getItem('refresh-token')).toBe('refresh-2');
      });

      it('refreshes with the stored token once the tab has adopted it', async () => {
        seedStorage(SESSION);
        const service = createService();
        otherTabWrites(ROTATED);
        refreshToken.and.returnValue(of(response({ ...ROTATED, token: 'access-3', refreshToken: 'refresh-3' })));

        expect(await firstValueFrom(service.refreshToken())).toBe('access-3');

        expect(refreshToken).toHaveBeenCalledOnceWith(
          { refreshToken: 'refresh-2' },
          'body',
          false,
          SILENT_REFRESH_OPTIONS,
        );
      });

      it('makes one call when two tabs find their access token expired together', async () => {
        seedStorage(SESSION);
        refreshToken.and.callFake(() => from(Promise.resolve(response(ROTATED))));
        const tabA = createService();
        const tabB = secondTab();

        const [a, b] = await Promise.all([firstValueFrom(tabA.refreshToken()), firstValueFrom(tabB.refreshToken())]);

        expect(refreshToken).toHaveBeenCalledOnceWith(
          { refreshToken: 'refresh-1' },
          'body',
          false,
          SILENT_REFRESH_OPTIONS,
        );
        expect([a, b]).toEqual(['access-2', 'access-2']);
        expect(tabA.accessToken).toBe('access-2');
        expect(tabB.accessToken).toBe('access-2');
        expect(localStorage.getItem('refresh-token')).toBe('refresh-2');
      });

      describe('without Web Locks (plain HTTP)', () => {
        const LOCK_KEY = 'repsy-refresh-lock';

        beforeEach(() => {
          noLocks();
          seedStorage(SESSION);
          refreshToken.and.callFake(() => from(Promise.resolve(response(ROTATED))));
        });

        afterEach(() => localStorage.removeItem(LOCK_KEY));

        /** Subscribes and returns what the refresh has emitted so far, for a test that drives the clock by hand. */
        function start(service: AuthService): { tokens: string[]; failures: unknown[] } {
          const seen = { tokens: [] as string[], failures: [] as unknown[] };
          service.refreshToken().subscribe({ next: (t) => seen.tokens.push(t), error: (e) => seen.failures.push(e) });
          return seen;
        }

        it('makes one call when two tabs find their access token expired together', fakeAsync(() => {
          const tabA = createService();
          const tabB = secondTab();

          const a = start(tabA);
          const b = start(tabB);
          tick(1000);

          expect(refreshToken).toHaveBeenCalledOnceWith(
            { refreshToken: 'refresh-1' },
            'body',
            false,
            SILENT_REFRESH_OPTIONS,
          );
          expect(a.tokens).toEqual(['access-2']);
          expect(b.tokens).toEqual(['access-2']);
          expect(tabB.accessToken).toBe('access-2');
          expect(localStorage.getItem('refresh-token')).toBe('refresh-2');
          expect(lockRequests).toEqual([]);
        }));

        it('takes the lock for the call and gives it back afterwards, also when the call fails', fakeAsync(() => {
          const failure = new HttpErrorResponse({ status: 500 });
          refreshToken.and.callFake(() => {
            expect(localStorage.getItem(LOCK_KEY)).toMatch(/^[0-9a-f]{16}:\d+$/);
            return throwError(() => failure);
          });
          const seen = start(createService());

          tick(1000);

          expect(seen.failures).toEqual([failure]);
          expect(localStorage.getItem(LOCK_KEY)).toBeNull();
        }));

        it('waits while another tab holds the lock, then refreshes', fakeAsync(() => {
          localStorage.setItem(LOCK_KEY, `someone-else:${Date.now() + 15_000}`);
          const seen = start(createService());

          tick(5000);
          expect(refreshToken).not.toHaveBeenCalled();
          localStorage.removeItem(LOCK_KEY);
          tick(1000);

          expect(refreshToken).toHaveBeenCalledTimes(1);
          expect(seen.tokens).toEqual(['access-2']);
        }));

        it('adopts what the holder stored instead of refreshing again', fakeAsync(() => {
          localStorage.setItem(LOCK_KEY, `someone-else:${Date.now() + 15_000}`);
          const service = createService();
          const seen = start(service);

          tick(1000);
          seedStorage(ROTATED);
          localStorage.removeItem(LOCK_KEY);
          tick(1000);

          expect(refreshToken).not.toHaveBeenCalled();
          expect(seen.tokens).toEqual(['access-2']);
          expect(service.accessToken).toBe('access-2');
        }));

        it('takes over a lock its holder left behind (a closed tab)', fakeAsync(() => {
          localStorage.setItem(LOCK_KEY, `someone-else:${Date.now() - 1}`);
          const seen = start(createService());

          tick(1000);

          expect(seen.tokens).toEqual(['access-2']);
          expect(localStorage.getItem(LOCK_KEY)).toBeNull();
        }));

        it('does not wait for ever on a lock that never frees: it refreshes after the bounded wait', fakeAsync(() => {
          const stuck = () => localStorage.setItem(LOCK_KEY, `someone-else:${Date.now() + 15_000}`);
          stuck();
          const seen = start(createService());

          for (let elapsed = 0; elapsed < 21_000; elapsed += 1000) {
            stuck();
            tick(1000);
          }
          flushMicrotasks();

          expect(seen.tokens).toEqual(['access-2']);
          // It never held the lock, so it does not remove somebody else's.
          expect(localStorage.getItem(LOCK_KEY)).toMatch(/^someone-else:/);
        }));

        it("a tab that lost the race for the lock adopts the winner's tokens", fakeAsync(() => {
          const service = createService();
          const seen = start(service);
          // Another tab wrote its claim over ours during the settle time, then refreshed.
          tick(10);
          localStorage.setItem(LOCK_KEY, `someone-else:${Date.now() + 15_000}`);
          tick(100);
          seedStorage(ROTATED);
          localStorage.removeItem(LOCK_KEY);
          tick(1000);

          expect(refreshToken).not.toHaveBeenCalled();
          expect(seen.tokens).toEqual(['access-2']);
        }));
      });

      it('completes without a value, and without a call, when another tab logged out meanwhile', async () => {
        seedStorage(SESSION);
        const service = createService();
        const ended = jasmine.createSpy('sessionEndedElsewhere');
        service.sessionEndedElsewhere$.subscribe(ended);
        STORAGE_KEYS.forEach((key) => localStorage.removeItem(key));

        const values: string[] = [];
        await new Promise<void>((resolve, reject) =>
          service.refreshToken().subscribe({ next: (v) => values.push(v), error: reject, complete: resolve }),
        );

        expect(values).toEqual([]);
        expect(refreshToken).not.toHaveBeenCalled();
        expect(service.isAuthenticated()).toBeFalse();
        expect(ended).toHaveBeenCalledTimes(1);
      });

      it('completes without a value, not with an error, when the refresh call itself completes empty', async () => {
        // RefreshTokenInterceptor ends a refused refresh call quietly (it logs the session out itself).
        seedStorage(SESSION);
        refreshToken.and.returnValue(EMPTY);
        const service = createService();
        const values: string[] = [];

        await new Promise<void>((resolve, reject) =>
          service.refreshToken().subscribe({ next: (v) => values.push(v), error: reject, complete: resolve }),
        );

        expect(values).toEqual([]);
        expect(refreshToken).toHaveBeenCalledTimes(1);
      });

      it('lets the next tab in when a refresh fails, and keeps the stored session', async () => {
        seedStorage(SESSION);
        const failure = new HttpErrorResponse({ status: 500 });
        refreshToken.and.returnValues(
          throwError(() => failure),
          of(response(ROTATED)),
        );
        const tabA = createService();
        const tabB = secondTab();

        const results = await Promise.allSettled([
          firstValueFrom(tabA.refreshToken()),
          firstValueFrom(tabB.refreshToken()),
        ]);

        expect(results[0]).toEqual({ status: 'rejected', reason: failure });
        expect(results[1]).toEqual({ status: 'fulfilled', value: 'access-2' });
        expect(refreshToken).toHaveBeenCalledTimes(2);
      });
    });

    // RPS-1672: Firefox replicates `localStorage` between tabs asynchronously, so a tab that just got the
    // Web Lock can still read its own spent token for a few milliseconds after another tab rotated it and
    // released the lock. `_syncFromStorage` cannot see that (it is a plain, synchronous read in this spec,
    // same as real Chromium/WebKit), so these drive the hand-over through a real BroadcastChannel instead,
    // the way the two engines actually differ.
    describe('the broadcast channel hand-over', () => {
      let announcer: BroadcastChannel;

      beforeEach(() => {
        announcer = new BroadcastChannel('repsy-session');
      });

      afterEach(() => announcer.close());

      it('adopts a pair another tab announces while still holding the spent token, without calling the API', async () => {
        seedStorage(SESSION);
        const service = createService();

        const refreshed = firstValueFrom(service.refreshToken());
        // Nothing was ever written to storage: only the announcement tells this tab about the rotation.
        announcer.postMessage({ type: 'session', username: 'alice', token: 'access-2', refreshToken: 'refresh-2' });

        expect(await refreshed).toBe('access-2');
        expect(refreshToken).not.toHaveBeenCalled();
        expect(service.accessToken).toBe('access-2');
        expect(service.username).toBe('alice');
      });

      it('still emits isAuthenticated$ once for an adopted announcement', async () => {
        seedStorage(SESSION);
        const service = createService();
        const states: boolean[] = [];
        service.isAuthenticated$.subscribe((state) => states.push(state));

        const refreshed = firstValueFrom(service.refreshToken());
        announcer.postMessage({ type: 'session', username: 'alice', token: 'access-2', refreshToken: 'refresh-2' });
        await refreshed;

        expect(states).toEqual([true]);
      });

      it('refreshes normally, once, when nothing is announced before the hand-over window elapses', async () => {
        seedStorage(SESSION);
        refreshToken.and.returnValue(of(response(ROTATED)));
        const service = createService();

        expect(await firstValueFrom(service.refreshToken())).toBe('access-2');

        expect(refreshToken).toHaveBeenCalledOnceWith(
          { refreshToken: 'refresh-1' },
          'body',
          false,
          SILENT_REFRESH_OPTIONS,
        );
      });

      it('ignores an announcement of a different shape', async () => {
        seedStorage(SESSION);
        refreshToken.and.returnValue(of(response(ROTATED)));
        const service = createService();

        announcer.postMessage({ type: 'something-else' });
        announcer.postMessage(null);

        expect(await firstValueFrom(service.refreshToken())).toBe('access-2');
        expect(refreshToken).toHaveBeenCalledOnceWith(
          { refreshToken: 'refresh-1' },
          'body',
          false,
          SILENT_REFRESH_OPTIONS,
        );
      });

      it('does not react to an announcement once the service is destroyed', async () => {
        seedStorage(SESSION);
        const service = createService();
        TestBed.resetTestingModule();

        announcer.postMessage({ type: 'session', username: 'eve', token: 'access-9', refreshToken: 'refresh-9' });
        await new Promise((resolve) => setTimeout(resolve, 50));

        expect(service.accessToken).toBe('access-1');
      });

      it('does not react to an announcement when not running in a browser', async () => {
        const service = createService('server');

        announcer.postMessage({ type: 'session', username: 'eve', token: 'access-9', refreshToken: 'refresh-9' });
        await new Promise((resolve) => setTimeout(resolve, 50));

        expect(service.isAuthenticated()).toBeFalse();
      });
    });
  });
});
