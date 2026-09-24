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
 * (`AuthService`); it counts as authenticated iff both tokens exist. Refresh tokens are SINGLE-USE
 * with family revocation on reuse (`RefreshTokenService.consume` in the backend), which shapes
 * everything here: never share one token pair between tests or contexts.
 */
import type { BrowserContext, Page } from '@playwright/test';

import { PanelApi } from '../api/panel-api.js';
import { env } from '../env.js';

export interface UiSession {
  username: string;
  token: string;
  refreshToken: string;
}

/** The login form's username rule (3-150 chars); `login.component.ts`. */
export const LOGIN_USERNAME_PATTERN = /^[a-zA-Z0-9@_\-.]+$/;
/** The login form's password rule (6-50 chars); `login.component.ts`. */
export const LOGIN_PASSWORD_PATTERN = /^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/;

const USERNAME_LENGTH = { min: 3, max: 150 } as const;
const PASSWORD_LENGTH = { min: 6, max: 50 } as const;

/** sessionStorage flag that makes `seedSession()` a one-shot per tab. */
const SEEDED_FLAG = 'repsy-e2e-session-seeded';

/**
 * A fresh API login, i.e. a NEW refresh-token family. Call it once per test/context and never share
 * the result: the first page that refreshes consumes the refresh token, and a second use revokes the
 * whole family and logs the other holder out mid-test.
 */
export async function loginSession(username: string, password: string): Promise<UiSession> {
  const info = await new PanelApi(env.apiBaseUrl).login(username, password);
  if (!info.username || !info.token || !info.refreshToken) {
    throw new Error(`Login as "${username}" did not return a username, token and refresh token`);
  }
  return { username: info.username, token: info.token, refreshToken: info.refreshToken };
}

/**
 * Makes every page of `context` start logged in as `session`, by writing the three keys into
 * `localStorage` before the SPA boots (no `storageState` file: tokens are per run, and the SPA's
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
  await context.addInitScript(
    ({ origin: expectedOrigin, session: s, flag }) => {
      // about:blank, other origins and third-party frames must not be touched.
      if (window.location.origin !== expectedOrigin) {
        return;
      }
      try {
        if (window.sessionStorage.getItem(flag)) {
          return;
        }
        window.localStorage.setItem('username', s.username);
        window.localStorage.setItem('token', s.token);
        window.localStorage.setItem('refresh-token', s.refreshToken);
        window.sessionStorage.setItem(flag, '1');
      } catch {
        // Storage is unavailable (e.g. a sandboxed frame): the test then fails on its own assertions.
      }
    },
    { origin, session, flag: SEEDED_FLAG },
  );
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
  return page.evaluate(() => window.localStorage.getItem('username'));
}

/** `assertNotAdmin()` for whoever `page` is logged in as. For page-object methods that change credentials. */
export async function assertPageNotAdmin(page: Page): Promise<void> {
  assertNotAdmin(await currentUsername(page));
}

/**
 * Throws a clear error unless the admin credentials can be typed into the panel's login form. The
 * backend refuses to boot with a password that fails the complexity part of the rule but does not
 * check the length, and a `remote` target may run with anything, so UI login (impossible with a
 * non-conforming password, the form refuses to submit) is checked here.
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
    !LOGIN_PASSWORD_PATTERN.test(env.adminPassword) ||
    env.adminPassword.length < PASSWORD_LENGTH.min ||
    env.adminPassword.length > PASSWORD_LENGTH.max
  ) {
    problems.push(
      `REPSY_ADMIN_PASSWORD must be ${PASSWORD_LENGTH.min}-${PASSWORD_LENGTH.max} chars with a ` +
        'lower-case letter, an upper-case letter and a digit, and no whitespace',
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
 * Whether an opt-in suite was requested through `REPSY_UI_OPT_IN` (a comma list such as
 * `throttle,scanner`; `docker-compose.runners.yml` forwards it to the ui runner). Opt-in specs skip
 * themselves instead of being filtered out in the config: `test.skip(!optedIn('throttle'), ...)`.
 */
export function optedIn(name: string): boolean {
  return (process.env.REPSY_UI_OPT_IN ?? '')
    .split(',')
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry.length > 0)
    .includes(name.toLowerCase());
}
