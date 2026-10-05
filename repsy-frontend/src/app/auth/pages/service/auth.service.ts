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

import { isPlatformBrowser } from '@angular/common';
import { HttpContext } from '@angular/common/http';
import { DestroyRef, Inject, inject, Injectable, PLATFORM_ID } from '@angular/core';
import {
  BehaviorSubject,
  defer,
  distinctUntilChanged,
  EMPTY,
  firstValueFrom,
  from,
  map,
  mergeMap,
  Observable,
  of,
  Subject,
  throwError,
} from 'rxjs';

import { LoginForm, LoginInfo } from '../../../../generated/api';
import { AuthApi } from '../../../../generated/api/api/auth.api';
import { SILENT_ERROR } from '../../../shared/interceptor/error-handler.interceptor';

const USERNAME_KEY = 'username';
const TOKEN_KEY = 'token';
const REFRESH_TOKEN_KEY = 'refresh-token';
const SESSION_KEYS = [USERNAME_KEY, TOKEN_KEY, REFRESH_TOKEN_KEY];

/** Name of the Web Lock that lets one tab at a time spend the (single-use) refresh token. */
const REFRESH_LOCK = 'repsy-refresh';

/** Name of the BroadcastChannel a tab announces its rotated (or freshly logged-in) session pair on. */
const SESSION_CHANNEL_NAME = 'repsy-session';

/** What `_update` announces on {@link SESSION_CHANNEL_NAME}: the pair it just stored. */
interface SessionBroadcastMessage {
  readonly type: 'session';
  readonly username: string;
  readonly token: string;
  readonly refreshToken: string;
}

/**
 * How long `_refreshOnce` waits, once it holds the Web Lock and still sees the token it is about to
 * spend, for another tab's BroadcastChannel hand-over of a pair that tab may already be rotating
 * (RPS-1672). Firefox replicates `localStorage` between tabs asynchronously (a probe found 17 of 20
 * first reads right after another tab released the Web Lock still returned the OLD value there, none in
 * Chromium or WebKit), so the plain `localStorage` re-read `_syncFromStorage` does can lose that race.
 * The BroadcastChannel message carries the rotated pair directly instead of relying on that replication
 * timing, and {@link onBroadcast} resolves the wait the moment it arrives; this bound only matters when
 * nothing was actually racing, so it is generous rather than tight.
 */
const HANDOVER_WAIT_MS = 150;

/**
 * The same mutual exclusion where `navigator.locks` does not exist (it needs a secure context, and a
 * self-hosted Repsy is often served over plain HTTP): a `localStorage` entry `<tab id>:<expiry>`, taken
 * by writing it and checking after a short settle time that no other tab wrote over it. Not as strict as
 * a Web Lock, but two tabs that hit their expiry together cannot both get through it in practice, and
 * whoever gets through re-reads the session first (see `_refreshOnce`), so a tab that loses the race
 * adopts the winner's tokens. An entry left by a closed tab expires, and a wait is bounded: the refresh
 * then goes ahead unlocked rather than never.
 */
const REFRESH_LOCK_KEY = 'repsy-refresh-lock';
const LOCK_TTL_MS = 15_000;
const LOCK_SETTLE_MS = 40;
const LOCK_POLL_MS = 50;
const LOCK_MAX_WAIT_MS = 20_000;

function randomTabId(): string {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * The panel session, kept in `localStorage` and in memory.
 *
 * Every tab of a browser shares the one `localStorage` session, but each keeps its own in-memory copy,
 * and a refresh token is single use: the backend revokes the whole token family when a spent one comes
 * back (`RefreshTokenService.consume`). Two tabs that each refreshed with their own copy therefore
 * logged each other out (RPS-1621). The tabs now behave as one session:
 *
 * - a change of the stored session made by another tab (a refresh, a login, a logout) is adopted at once
 *   (the `storage` event), and a tab whose session was ended by another tab announces it through
 *   {@link sessionEndedElsewhere$};
 * - a refresh takes a cross-tab lock (the Web Lock {@link REFRESH_LOCK}, or a `localStorage` one where the
 *   browser has no `navigator.locks`, see {@link REFRESH_LOCK_KEY}) and, holding it, first re-reads the
 *   storage: when another tab has rotated the tokens since the caller's copy was made, that tab's pair
 *   is adopted and no refresh call is made. On the Web Lock path that re-read is backed up by a
 *   BroadcastChannel hand-over ({@link SESSION_CHANNEL_NAME}, {@link HANDOVER_WAIT_MS}): the tab that
 *   rotates the pair announces it on the channel, so a tab that still sees its own spent token right
 *   after acquiring the lock gets a short, bounded chance to adopt the announced pair instead of reading
 *   a `localStorage` that has not caught up yet (RPS-1672, Firefox only).
 */
@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private _accessToken: string;
  private _refreshToken: string;
  private _username: string;
  private readonly isBrowser: boolean;
  private readonly _authenticated$ = new BehaviorSubject<boolean>(false);
  private readonly _sessionEndedElsewhere$ = new Subject<void>();
  private readonly tabId = randomTabId();
  private readonly channel: BroadcastChannel | null;
  /** Resolvers of a pending {@link _waitForHandover}, woken as soon as {@link onBroadcast} adopts a pair. */
  private readonly handoverWaiters = new Set<() => void>();

  /**
   * Whether a session exists, emitted again whenever that changes (login, logout). Views that
   * decide once from {@link isAuthenticated} go stale when the session changes under them, e.g.
   * `AuthRedirectComponent`, which shows the login form at "/" and must swap to the dashboard
   * after a login without a route change (RPS-1278).
   */
  public readonly isAuthenticated$: Observable<boolean> = this._authenticated$.pipe(distinctUntilChanged());

  /** Emits when another tab logged out (or otherwise removed the session) while this tab held one. */
  public readonly sessionEndedElsewhere$: Observable<void> = this._sessionEndedElsewhere$.asObservable();

  private readonly onStorage = (event: StorageEvent): void => {
    // A null key is `localStorage.clear()`. Other keys and sessionStorage are none of our business.
    if (event.storageArea === localStorage && (event.key === null || SESSION_KEYS.includes(event.key))) {
      this._syncFromStorage();
    }
  };

  /** Adopts the pair another tab announced (RPS-1672): see the class doc and {@link HANDOVER_WAIT_MS}. */
  private readonly onBroadcast = (event: MessageEvent<SessionBroadcastMessage>): void => {
    const message = event.data;
    if (!message || message.type !== 'session') {
      return;
    }
    this._username = message.username;
    this._accessToken = message.token;
    this._refreshToken = message.refreshToken;
    this._authenticated$.next(this.isAuthenticated());
    this.handoverWaiters.forEach((wake) => wake());
  };

  constructor(
    private readonly authApi: AuthApi,
    @Inject(PLATFORM_ID) platformId: object,
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
    this.channel =
      this.isBrowser && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(SESSION_CHANNEL_NAME) : null;
    if (this.isBrowser) {
      this._readStorage();
      window.addEventListener('storage', this.onStorage);
      inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', this.onStorage));
    }
    if (this.channel) {
      this.channel.addEventListener('message', this.onBroadcast);
      inject(DestroyRef).onDestroy(() => this.channel!.close());
    }
    this._authenticated$.next(this.isAuthenticated());
  }

  public get username(): string {
    return this._username;
  }

  public get accessToken(): string {
    return this._accessToken;
  }

  public isAuthenticated(): boolean {
    return !!(this._accessToken && this._refreshToken);
  }

  public logIn(form: LoginForm): Observable<void> {
    return this.authApi.login(form).pipe(
      map((r) => {
        this._update(r.username!, r.token!, r.refreshToken!);
      }),
    );
  }

  /**
   * Swaps the session's tokens through the refresh token, emitting the new access token. Completes
   * without a value when the session ended meanwhile, in another tab (nothing left to refresh; the tab is
   * on its way to the login form, see {@link sessionEndedElsewhere$}) or through a refused refresh call
   * (`RefreshTokenInterceptor` has logged out by then).
   */
  public refreshToken(): Observable<string> {
    return defer(() => {
      if (!this._refreshToken) {
        return throwError(() => new Error('No refresh token presents.'));
      }
      const spentBefore = this._refreshToken;
      return from(this._withRefreshLock(() => this._refreshOnce(spentBefore))).pipe(
        mergeMap((accessToken) => (accessToken ? of(accessToken) : EMPTY)),
      );
    });
  }

  public updateLoginInfo(loginInfo: LoginInfo): void {
    this._update(loginInfo.username!, loginInfo.token!, loginInfo.refreshToken!);
  }

  /**
   * Ends the session. The refresh token this tab held is sent to `POST /api/auth/logout`, which
   * revokes its whole family server-side (RPS-1622): a copy of it left in another tab's memory, or
   * leaked another way, can no longer be exchanged for a new pair. That call is best-effort and
   * fire-and-forget: local storage is cleared and every subscriber told at once, whether or not the
   * network call succeeds (offline, or the token was already gone), because a client-side sign-out
   * must not be able to fail or hang on a server round trip.
   */
  public logOut(): void {
    const refreshToken = this._refreshToken;

    this._username = null;
    this._accessToken = null;
    this._refreshToken = null;

    if (this.isBrowser) {
      SESSION_KEYS.forEach((key) => localStorage.removeItem(key));
    }
    this._authenticated$.next(this.isAuthenticated());

    if (refreshToken) {
      this.authApi.logout({ refreshToken }).subscribe({ error: () => undefined });
    }
  }

  private _update(username: string, accessToken: string, refreshToken: string): void {
    this._username = username;
    this._accessToken = accessToken;
    this._refreshToken = refreshToken;

    if (this.isBrowser) {
      localStorage.setItem(USERNAME_KEY, this._username);
      localStorage.setItem(TOKEN_KEY, this._accessToken);
      localStorage.setItem(REFRESH_TOKEN_KEY, this._refreshToken);
      this.channel?.postMessage({
        type: 'session',
        username: this._username,
        token: this._accessToken,
        refreshToken: this._refreshToken,
      } satisfies SessionBroadcastMessage);
    }
    this._authenticated$.next(this.isAuthenticated());
  }

  private _readStorage(): void {
    this._username = localStorage.getItem(USERNAME_KEY);
    this._accessToken = localStorage.getItem(TOKEN_KEY);
    this._refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  /** Adopts whatever the shared storage holds now, i.e. what the other tabs did to the session. */
  private _syncFromStorage(): void {
    const wasAuthenticated = this.isAuthenticated();
    this._readStorage();
    this._authenticated$.next(this.isAuthenticated());
    if (wasAuthenticated && !this.isAuthenticated()) {
      this._sessionEndedElsewhere$.next();
    }
  }

  /** Runs `task` while holding the cross-tab refresh lock: a Web Lock, else the `localStorage` one. */
  private _withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
    if (!this.isBrowser) {
      return task();
    }
    if (navigator.locks) {
      // The DOM typing of `request` does not flatten a promise-returning callback, though it does at runtime.
      return navigator.locks.request(REFRESH_LOCK, task) as unknown as Promise<T>;
    }
    return this._withStorageLock(task);
  }

  private async _withStorageLock<T>(task: () => Promise<T>): Promise<T> {
    const mine = `${this.tabId}:`;
    const giveUpAt = Date.now() + LOCK_MAX_WAIT_MS;
    let held = false;
    while (!held && Date.now() < giveUpAt) {
      const expiresAt = Number(localStorage.getItem(REFRESH_LOCK_KEY)?.split(':')[1]);
      if (!(expiresAt > Date.now())) {
        localStorage.setItem(REFRESH_LOCK_KEY, `${mine}${Date.now() + LOCK_TTL_MS}`);
        await sleep(LOCK_SETTLE_MS);
        held = !!localStorage.getItem(REFRESH_LOCK_KEY)?.startsWith(mine);
      }
      if (!held) {
        await sleep(LOCK_POLL_MS);
      }
    }
    try {
      return await task();
    } finally {
      if (held && localStorage.getItem(REFRESH_LOCK_KEY)?.startsWith(mine)) {
        localStorage.removeItem(REFRESH_LOCK_KEY);
      }
    }
  }

  /**
   * The refresh, under the lock. `spentBefore` is the refresh token this tab held when it found its
   * access token expired: when the storage holds another one by now, another tab has already rotated the
   * session, so its pair is adopted instead of spending a token that is not ours to spend any more.
   */
  private async _refreshOnce(spentBefore: string): Promise<string | null> {
    // Skipped once something has already moved this tab past `spentBefore` (a broadcast that arrived
    // while this tab was still queued for the lock, see `onBroadcast`): re-reading storage at that point
    // could only replace a value already known good with a `localStorage` that has not caught up yet
    // (RPS-1672, Firefox).
    if (this.isBrowser && this._refreshToken === spentBefore) {
      this._syncFromStorage();
    }
    // Only the Web Lock path needs the hand-over: the `localStorage` lock's own settle delay already
    // covers the plain-HTTP case (RPS-1672; see the class doc and `HANDOVER_WAIT_MS`).
    if (this.isBrowser && navigator.locks && this._refreshToken === spentBefore) {
      await this._waitForHandover(spentBefore);
    }
    if (!this._refreshToken) {
      return null;
    }
    if (this._refreshToken !== spentBefore) {
      return this._accessToken;
    }
    // The refresh call completes without an answer when `RefreshTokenInterceptor` logged the session out
    // for a refused refresh token: no token, like the session that ended in another tab.
    return firstValueFrom(
      this.authApi
        .refreshToken({ refreshToken: this._refreshToken }, 'body', false, {
          // Not toasted by `errorHandlerInterceptor` (RPS-1754): a failed refresh ends the session, and
          // `RefreshTokenInterceptor` shows the one "Session expired" toast for it.
          context: new HttpContext().set(SILENT_ERROR, true),
        })
        .pipe(
          map((r) => {
            this._update(r.username!, r.token!, r.refreshToken!);
            return r.token!;
          }),
        ),
      { defaultValue: null },
    );
  }

  /**
   * Waits, bounded by {@link HANDOVER_WAIT_MS}, for {@link onBroadcast} to adopt another tab's rotated
   * pair. Resolves at once if there is no channel (an old browser) or the token has already changed by
   * the time it is called (the broadcast, or a `storage` event, got here first).
   */
  private _waitForHandover(spentBefore: string): Promise<void> {
    if (!this.channel || this._refreshToken !== spentBefore) {
      return Promise.resolve();
    }
    return new Promise((resolve) => {
      const wake = (): void => {
        clearTimeout(timer);
        this.handoverWaiters.delete(wake);
        resolve();
      };
      const timer = setTimeout(wake, HANDOVER_WAIT_MS);
      this.handoverWaiters.add(wake);
    });
  }
}
