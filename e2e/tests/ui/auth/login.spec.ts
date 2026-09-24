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

/**
 * AUTH-01 .. AUTH-04 and AUTH-11: the login page. UI login is exercised ONLY here: every other UI
 * suite gets its session from the API through the `adminPage`/`userPage` fixtures.
 *
 * No test changes the admin user or its password: the admin only types its own credentials (AUTH-01),
 * and every negative login uses a user seeded for the test or a name that does not exist.
 */
import type { Page } from '@playwright/test';

import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { LoginPage } from '../../../src/ui/pages/login.js';
import { countLoginRequests, LoginValidation } from '../../../src/ui/pages/login-validation.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { optedIn } from '../../../src/ui/session.js';
import { JWT_SHAPE, NO_SESSION, storedSession } from './stored-session.js';

const WRONG_CREDENTIALS_TOAST = 'Username or password is incorrect.';

/** Submits the (already filled) form until the server answers 429; the attempt that did, or 0 if none did. */
async function submitUntilThrottled(page: Page, login: LoginPage, max: number): Promise<number> {
  for (let attempt = 1; attempt <= max; attempt += 1) {
    const answer = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/auth/login' &&
        response.request().method() === 'POST',
    );
    await login.submit.click();
    if ((await answer).status() === 429) {
      return attempt;
    }
    // The form is disabled while a request runs; wait for it before submitting again.
    await expect(login.submit).toBeEnabled();
  }
  return 0;
}

test.describe('AUTH-01 valid login', () => {
  test('the admin lands on the dashboard with the session stored', async ({ page }) => {
    const login = new LoginPage(page);
    const dashboard = new DashboardPage(page);

    await login.goto();
    await login.login(env.adminUsername, env.adminPassword);

    await expect(page).toHaveURL('/');
    await dashboard.expectLoaded();
    await expect(dashboard.welcomeUsername).toContainText(env.adminUsername);
    // The chrome of a logged-in page is there, the login form is gone.
    await expect(new Shell(page).sidebar.root).toBeVisible();
    await expect(login.form).toHaveCount(0);

    const session = await storedSession(page);
    expect(session.username).toBe(env.adminUsername);
    expect(session.token).toMatch(JWT_SHAPE);
    expect(session.refreshToken).toMatch(JWT_SHAPE);
    // Two different tokens: an access token must never double as the refresh token.
    expect(session.refreshToken).not.toBe(session.token);
  });
});

test.describe('AUTH-02 wrong credentials', () => {
  test(
    'a wrong password toasts the error and stays on /login',
    { tag: ['@smoke'] },
    async ({ page, seededUser }) => {
      const login = new LoginPage(page);
      const shell = new Shell(page);

      await login.goto();
      // Conforms to the form rules (so the form submits) but is not the user's password.
      await login.login(seededUser.username, `${seededUser.password}x`);

      // Right after the action: a toast lives 3 s.
      await shell.toasts.expectError(WRONG_CREDENTIALS_TOAST);
      await expect(page).toHaveURL(/\/login$/);
      // The form is usable again (it is disabled while the request runs) and nothing was stored.
      await expect(login.submit).toBeEnabled();
      await expect(login.username).toHaveValue(seededUser.username);
      expect(await storedSession(page)).toEqual(NO_SESSION);
    },
  );

  test('an unknown username gets the same answer as a wrong password', async ({
    page,
    seeder,
    seededUser,
  }) => {
    const login = new LoginPage(page);
    const shell = new Shell(page);
    // A name in the run's namespace that is never created: reserved, not adopted.
    const unknown = seeder.reserveUsername();
    expect(unknown).not.toBe(seededUser.username);

    await login.goto();
    await login.login(unknown, seededUser.password);

    await shell.toasts.expectError(WRONG_CREDENTIALS_TOAST);
    await expect(page).toHaveURL(/\/login$/);
    expect(await storedSession(page)).toEqual(NO_SESSION);
  });
});

// RPS-1265: the sentences are the shared credential ones (`credential-messages.ts`). RPS-1308: the login
// password is checked for its shape only (typed, at most 72 characters). The complexity rule is for a
// password that is SET: an account from before the policy, or an admin bootstrapped from a short
// ADMIN_INITIAL_PASSWORD, must be able to type its own password, and a wrong one is the server's 401.
test.describe('AUTH-03 client-side validation', () => {
  const VALID_PASSWORD = 'Valid-Pass1';
  const passwordOfLength = (length: number) => `Aa1${'x'.repeat(length - 3)}`;

  test.describe('username', () => {
    const cases = [
      { name: 'empty', value: '', error: 'required' },
      { name: 'too short (2 chars)', value: 'ab', error: 'minlength' },
      { name: 'with a space', value: 'bad user', error: 'pattern' },
      { name: 'with a forbidden character', value: 'bad!user', error: 'pattern' },
      { name: 'with a non-ASCII letter', value: 'jürgen', error: 'pattern' },
      { name: 'too long (151 chars)', value: 'a'.repeat(151), error: 'maxlength' },
    ] as const;

    for (const { name, value, error } of cases) {
      test(`${name} shows the ${error} message and keeps submit disabled`, async ({ page }) => {
        const login = new LoginPage(page);
        const validation = new LoginValidation(login);

        await login.goto();
        await validation.enter('password', VALID_PASSWORD);
        await validation.enter('username', value);

        await validation.expectOnlyError('username', error);
        await validation.expectNoError('password');
        await expect(login.submit).toBeDisabled();
      });
    }

    test('the whole allowed alphabet and both length limits are accepted', async ({ page }) => {
      const login = new LoginPage(page);
      const validation = new LoginValidation(login);

      await login.goto();
      await validation.enter('password', VALID_PASSWORD);
      for (const value of ['abc', 'A_b-c.d@e', '0123456789', 'a'.repeat(150)]) {
        await validation.enter('username', value);
        await validation.expectNoError('username');
        await expect(login.submit).toBeEnabled();
      }
    });
  });

  test.describe('password', () => {
    const cases = [
      { name: 'empty', value: '', error: 'required' },
      { name: 'too long (73 chars)', value: passwordOfLength(73), error: 'maxlength' },
    ] as const;

    for (const { name, value, error } of cases) {
      test(`${name} shows the ${error} message and keeps submit disabled`, async ({ page }) => {
        const login = new LoginPage(page);
        const validation = new LoginValidation(login);

        await login.goto();
        await validation.enter('username', 'valid.user');
        await validation.enter('password', value);

        await validation.expectOnlyError('password', error);
        await validation.expectNoError('username');
        await expect(login.submit).toBeDisabled();
      });
    }

    // What the create/change forms refuse and the login form must accept (RPS-1308).
    const weak = [
      { name: 'a single character', value: 'a' },
      { name: 'too short for a new password', value: 'abc' },
      { name: 'without an upper-case letter', value: 'abcdef12' },
      { name: 'without a lower-case letter', value: 'ABCDEF12' },
      { name: 'without a digit', value: 'Abcdefgh' },
      { name: 'with a space', value: 'Abc 1234' },
      { name: 'longer than a new password (60 chars)', value: passwordOfLength(60) },
      { name: 'the longest one, 72 chars', value: passwordOfLength(72) },
    ] as const;

    for (const { name, value } of weak) {
      test(`${name} is accepted: no inline message, submit enabled`, async ({ page }) => {
        const login = new LoginPage(page);
        const validation = new LoginValidation(login);

        await login.goto();
        await validation.enter('username', 'valid.user');
        await validation.enter('password', value);

        await validation.expectNoError('password');
        await expect(login.submit).toBeEnabled();
      });
    }

    test('a wrong weak password gets the invalid-credentials toast from the server, not an inline message', async ({
      page,
      seededUser,
    }) => {
      const login = new LoginPage(page);
      const validation = new LoginValidation(login);
      const shell = new Shell(page);

      await login.goto();
      await validation.enter('username', seededUser.username);
      await validation.enter('password', 'abc');
      await validation.expectNoError('password');
      await expect(login.submit).toBeEnabled();
      await login.submit.click();

      await shell.toasts.expectError(WRONG_CREDENTIALS_TOAST);
      await validation.expectNoError('password');
      await expect(page).toHaveURL(/\/login$/);
      expect(await storedSession(page)).toEqual(NO_SESSION);
    });
  });

  test('errors appear only after a field is touched, and clear when the value is fixed', async ({
    page,
  }) => {
    const login = new LoginPage(page);
    const validation = new LoginValidation(login);

    await login.goto();
    await expect(login.submit).toBeDisabled();
    await validation.expectNoError('username');
    await validation.expectNoError('password');

    // Typing without leaving the field shows nothing yet ...
    await login.username.fill('ab');
    await validation.expectNoError('username');
    // ... leaving it does, and fixing the value removes the message again.
    await login.username.blur();
    await validation.expectOnlyError('username', 'minlength');
    await login.username.fill('abc');
    await validation.expectNoError('username');
  });

  test('pressing Enter on an invalid form sends nothing to the server', async ({ page }) => {
    const login = new LoginPage(page);
    const validation = new LoginValidation(login);
    const loginRequests = countLoginRequests(page);

    await login.goto();
    await login.username.fill('valid.user');
    await validation.enter('password', '');
    await validation.expectOnlyError('password', 'required');
    await login.password.press('Enter');

    await expect(login.submit).toBeDisabled();
    await expect(login.form).toBeVisible();
    // A bounded settle, not a sleep: the request (if any) would be sent synchronously on submit.
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
    expect(loginRequests()).toBe(0);
  });
});

test.describe('AUTH-04 password visibility toggle', () => {
  test('the eye button switches the input between password and text', async ({ page }) => {
    const login = new LoginPage(page);
    const validation = new LoginValidation(login);

    await login.goto();
    await login.password.fill('Secret-Pass1');
    await expect(login.password).toHaveAttribute('type', 'password');

    await validation.togglePasswordVisibility();
    await expect(login.password).toHaveAttribute('type', 'text');
    // The typed value survives the switch.
    await expect(login.password).toHaveValue('Secret-Pass1');

    await validation.togglePasswordVisibility();
    await expect(login.password).toHaveAttribute('type', 'password');
    await expect(login.password).toHaveValue('Secret-Pass1');
  });

  test('the toggle never submits the form', async ({ page }) => {
    const login = new LoginPage(page);
    const validation = new LoginValidation(login);
    const loginRequests = countLoginRequests(page);

    await login.goto();
    await login.username.fill('valid.user');
    await login.password.fill('Secret-Pass1');
    await validation.togglePasswordVisibility();

    await expect(login.password).toHaveAttribute('type', 'text');
    await expect(login.form).toBeVisible();
    expect(loginRequests()).toBe(0);
  });
});

test.describe('AUTH-11 auth throttle', { tag: ['@throttle'] }, () => {
  // More failures than the smallest sensible AUTH_THROTTLE_MAX_FAILURES (the backend default is 20).
  const ATTEMPTS = 30;
  const THROTTLE_TOAST = 'Too many failed authentication attempts. Please try again later.';

  test('repeated bad logins are refused with the throttle toast', async ({ page, seededUser }) => {
    // eslint-disable-next-line playwright/no-skipped-test -- the opt-in switch of README "UI suite"
    test.skip(
      !optedIn('throttle'),
      'opt-in: needs a stack started with a low AUTH_THROTTLE_MAX_FAILURES; run with ' +
        'REPSY_UI_OPT_IN=throttle (see README, "Auth, guards and session")',
    );
    const login = new LoginPage(page);
    const shell = new Shell(page);

    await login.goto();
    await login.username.fill(seededUser.username);
    await login.password.fill(`${seededUser.password}x`);

    const throttledAt = await submitUntilThrottled(page, login, ATTEMPTS);

    expect(
      throttledAt,
      `no 429 after ${ATTEMPTS} bad logins: is AUTH_THROTTLE_MAX_FAILURES on this stack below ${ATTEMPTS}?`,
    ).toBeGreaterThan(0);
    await shell.toasts.expectError(THROTTLE_TOAST);
    await expect(page).toHaveURL(/\/login$/);
    expect(await storedSession(page)).toEqual(NO_SESSION);
  });
});
