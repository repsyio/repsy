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

/**
 * Session plumbing for the panel UI suite: how a test gets logged in to the SPA without typing the
 * login form, and the guards that keep a UI test from locking the harness out of its own admin.
 *
 * The SPA's session is three `localStorage` keys, `username`, `token` and `refresh-token`
 * (`AuthService`; Repsy Cloud's panel also keeps an `email`): the names come from
 * `target.ui.sessionStorageKeys` (RPS-1638), never a literal here. It counts as authenticated iff
 * both tokens exist. Refresh tokens are SINGLE-USE
 * with family revocation on reuse (`RefreshTokenService.consume` in the backend), which shapes
 * everything here: never share one token pair between tests or contexts.
 */
import type { BrowserContext, Page, Request, Route } from '@playwright/test';

import { loginPanel } from '../api/backend-registry.js';
import { env } from '../env.js';
import { target, type UiSessionStorageKeys } from '../target.js';
import { errorBody, fulfillJson, type ErrorResponse } from './stub-responses.js';

export interface UiSession {
  username: string;
  token: string;
  refreshToken: string;
  /** Only where the panel keeps one (`target.ui.sessionStorageKeys.email`, Repsy Cloud); the login answer's, when it has it. */
  email?: string;
}

/** The login form's username rule (3-150 chars); `login.component.ts`. */
export const LOGIN_USERNAME_PATTERN = /^[a-zA-Z0-9@_\-.]+$/;
const USERNAME_LENGTH = { min: 3, max: 150 } as const;
/** The login form's password rule (RPS-1308): typed, at most 72 characters; `login.component.ts`. */
const PASSWORD_LENGTH = { min: 1, max: 72 } as const;

/** sessionStorage flag that makes `seedSession()` a one-shot per tab. */
const SEEDED_FLAG = 'repsy-e2e-session-seeded';

/**
 * A fresh API login, i.e. a NEW refresh-token family. Call it once per test/context and never share
 * the result: the first page that refreshes consumes the refresh token, and a second use revokes the
 * whole family and logs the other holder out mid-test.
 */
export async function loginSession(username: string, password: string): Promise<UiSession> {
  const info = await loginPanel(username, password);
  if (!info.username || !info.token || !info.refreshToken) {
    throw new Error(`Login as "${username}" did not return a username, token and refresh token`);
  }
  const session: UiSession = {
    username: info.username,
    token: info.token,
    refreshToken: info.refreshToken,
  };
  // The OS login answer has no email; a backend module of Repsy Cloud may return one (RPS-1639).
  const email = (info as { email?: unknown }).email;
  if (typeof email === 'string' && email) {
    session.email = email;
  }
  return session;
}

/**
 * Makes every page of `context` start logged in as `session`, by writing the keys of
 * `target.ui.sessionStorageKeys` into `localStorage` before the SPA boots (no `storageState` file: tokens are per run, and the SPA's
 * refresh flow rewrites them).
 *
 * The write happens ONCE per tab, guarded by a `sessionStorage` flag, and only on `origin`. That is
 * what keeps the other tests possible: after a logout a reload must stay logged out, and a token the
 * SPA has refreshed since must not be overwritten by the stale one on the next navigation. A NEW
 * tab (a `window.open` popup) has fresh sessionStorage and is seeded again with the original tokens.
 */
export async function seedSession(
  context: BrowserContext,
  origin: string,
  session: UiSession,
): Promise<void> {
  const keys = target.ui.sessionStorageKeys;
  await context.addInitScript(
    ({ origin: expectedOrigin, session: s, flag, keys: k }) => {
      // about:blank, other origins and third-party frames must not be touched.
      if (window.location.origin !== expectedOrigin) {
        return;
      }
      try {
        if (window.sessionStorage.getItem(flag)) {
          return;
        }
        window.localStorage.setItem(k.username, s.username);
        window.localStorage.setItem(k.token, s.token);
        window.localStorage.setItem(k.refreshToken, s.refreshToken);
        if (k.email && s.email) {
          window.localStorage.setItem(k.email, s.email);
        }
        window.sessionStorage.setItem(flag, '1');
      } catch {
        // Storage is unavailable (e.g. a sandboxed frame): the test then fails on its own assertions.
      }
    },
    { origin, session, flag: SEEDED_FLAG, keys },
  );
}

/** What the SPA keeps of a session in `localStorage`, read from the keys of `target.ui.sessionStorageKeys`. */
export interface StoredSessionValues {
  username: string | null;
  token: string | null;
  refreshToken: string | null;
}

/** The session keys as they are in the page's `localStorage` right now (`null` = absent). */
export function readStoredSession(page: Page): Promise<StoredSessionValues> {
  return page.evaluate((k: UiSessionStorageKeys) => {
    return {
      username: window.localStorage.getItem(k.username),
      token: window.localStorage.getItem(k.token),
      refreshToken: window.localStorage.getItem(k.refreshToken),
    };
  }, target.ui.sessionStorageKeys);
}

/**
 * Overwrites one value of the page's stored session (a tampered token, a stale refresh token), under
 * the key `target.ui.sessionStorageKeys` names for it. The SPA reads `localStorage` once at boot.
 */
export async function setStoredSessionValue(
  page: Page,
  which: 'username' | 'token' | 'refreshToken',
  value: string,
): Promise<void> {
  await page.evaluate(({ key, v }) => window.localStorage.setItem(key, v), {
    key: target.ui.sessionStorageKeys[which],
    v: value,
  });
}

/** The panel's refresh call (`POST`). The SPA sends it with the stored refresh token and stores the rotated pair. */
export const REFRESH_PATH = '/api/auth/tokens/refresh';

/** Whether `response` answers the refresh call. */
export function isRefreshResponse(response: { url(): string }): boolean {
  return new URL(response.url()).pathname === REFRESH_PATH;
}

/**
 * From now on the page's API calls carrying `Bearer <token>` are answered with the 401
 * `sessionExpired` the backend gives an expired access token (the one answer that makes the SPA refresh
 * and retry). An access token lives 30 minutes and is not configurable, so a test cannot wait for an
 * expiry, nor forge one the backend calls expired: the 401 is the one thing stubbed. Calls with any
 * other token (the one a refresh hands out) and the `/api/auth/` calls themselves (the refresh) go to
 * the real backend, so the refresh, the token rotation and a refused refresh token are real.
 *
 * `methods` limits the stub to those HTTP methods (default: every method), `times` to that many
 * refused calls (default: every call); `refused` is filled with the refused requests, in order.
 */
export async function expireAccessToken(
  page: Page,
  token: string,
  options: { methods?: string[]; times?: number; refused?: Request[] } = {},
): Promise<void> {
  let left = options.times ?? Number.POSITIVE_INFINITY;
  await page.route(/\/api\/(?!auth\/)/, async (route) => {
    const request = route.request();
    const matches =
      request.headers()['authorization'] === `Bearer ${token}` &&
      (!options.methods || options.methods.includes(request.method())) &&
      left > 0;
    if (!matches) {
      await route.continue();
      return;
    }
    left -= 1;
    options.refused?.push(request);
    await answerSessionExpired(route);
  });
}

/** What the backend answers (401) for an access token past its lifetime: the panel then tries a refresh. */
export function answerSessionExpired(route: Route): Promise<void> {
  return fulfillJson<ErrorResponse>(
    route,
    401,
    errorBody({ status: 401, code: 'sessionExpired', detail: 'Session expired.' }),
  );
}

/** What the backend answers (401) for a refresh token past its lifetime (30 days, so not waitable). */
export function answerRefreshTokenExpired(route: Route): Promise<void> {
  return fulfillJson<ErrorResponse>(
    route,
    401,
    errorBody({
      msgId: 'refreshTokenExpired',
      data: 'refreshTokenExpired',
      text: 'Refresh token expired.',
    }),
  );
}

/** Counts the refresh calls `page` sends from now on (the request, whatever the answer). */
export function countRefreshCalls(page: Page): { readonly count: number } {
  const counter = { count: 0 };
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === REFRESH_PATH) {
      counter.count += 1;
    }
  });
  return counter;
}

/**
 * Makes `navigator.locks` (Web Locks) undefined (unless `remove` is false, so a table of variants needs no branch) in every page of `context` that loads from now on, as it
 * is on any plain-HTTP origin other than localhost (Web Locks need a secure context, and `localhost`
 * is one, so the suite's own stack has them): what a self-hosted install served over HTTP looks like.
 */
export async function withoutWebLocks(context: BrowserContext, remove = true): Promise<void> {
  if (!remove) {
    return;
  }
  await context.addInitScript(() => {
    Object.defineProperty(window.navigator, 'locks', { value: undefined, configurable: true });
  });
}

/**
 * Holds every refresh answer of `page` back by `ms` (the real backend still answers it, and rotates
 * the token, at once). Makes two tabs' refreshes overlap for certain, which is what a race needs.
 */
export async function delayRefreshAnswers(page: Page, ms: number): Promise<void> {
  await page.route(`**${REFRESH_PATH}`, async (route) => {
    const response = await route.fetch();
    await new Promise((resolve) => setTimeout(resolve, ms));
    await route.fulfill({ response });
  });
}

/**
 * Throws if `username` is the harness admin (case-insensitive). Call it at every action that changes
 * credentials or removes an account (change password/username, delete account, reset a user's
 * password, delete a user): tokens are per run, but the admin's password/account is what the stack,
 * every runner and every later test log in with, so changing it breaks the whole run.
 */
export function assertNotAdmin(username: string | null | undefined): void {
  if (username && username.toLowerCase() === env.adminUsername.toLowerCase()) {
    throw new Error(
      `Refusing to change credentials of the harness admin "${env.adminUsername}": use a user ` +
        'seeded for this test (the `userPage`/`seededUser` fixtures), never `adminPage`.',
    );
  }
}

/** The username the SPA is currently logged in as (`localStorage.username`), or null. */
export async function currentUsername(page: Page): Promise<string | null> {
  return (await readStoredSession(page)).username;
}

/** `assertNotAdmin()` for whoever `page` is logged in as. For page-object methods that change credentials. */
export async function assertPageNotAdmin(page: Page): Promise<void> {
  assertNotAdmin(await currentUsername(page));
}

/**
 * Throws a clear error unless the admin credentials can be typed into the panel's login form. The
 * form takes any username of its alphabet and any password of 1-72 characters (RPS-1308), and a
 * `remote` target may run with anything, so UI login (impossible when the form refuses to submit)
 * is checked here.
 */
export function assertAdminCredentialsUsableInUi(): void {
  const problems: string[] = [];
  if (
    !LOGIN_USERNAME_PATTERN.test(env.adminUsername) ||
    env.adminUsername.length < USERNAME_LENGTH.min ||
    env.adminUsername.length > USERNAME_LENGTH.max
  ) {
    problems.push(
      `REPSY_ADMIN_USERNAME "${env.adminUsername}" must be ${USERNAME_LENGTH.min}-${USERNAME_LENGTH.max} ` +
        'chars from [a-zA-Z0-9@_.-]',
    );
  }
  if (
    env.adminPassword.length < PASSWORD_LENGTH.min ||
    env.adminPassword.length > PASSWORD_LENGTH.max
  ) {
    problems.push(
      `REPSY_ADMIN_PASSWORD must be ${PASSWORD_LENGTH.min}-${PASSWORD_LENGTH.max} chars`,
    );
  }
  if (problems.length > 0) {
    throw new Error(
      `${problems.join('; ')}, or the panel's login form cannot submit it ` +
        '(repsy-frontend login.component.ts), so no UI test can log in. Change ADMIN_INITIAL_PASSWORD ' +
        'on the stack and REPSY_ADMIN_PASSWORD in e2e/.env to a conforming value.',
    );
  }
}

/**
 * Whether an opt-in suite was requested (`REPSY_E2E_OPT_IN`, or the UI suite's older
 * `REPSY_UI_OPT_IN`): see `src/stack-overlays.ts`, where it moved so that runners other than `ui` share
 * it. Re-exported so the UI suite's imports stay as they were.
 */
export { optedIn } from '../stack-overlays.js';
