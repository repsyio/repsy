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
 * RPS-1308: `POST /api/auth/login` holds the password to its shape only (1-72 characters, no
 * complexity rule), so every existing account can log in and a wrong password of any strength gets
 * the same 401 `invalidCredentials`, never a 400 that would tell an anonymous caller the password
 * policy. The complexity rule stays where a password is set (create user, change password). That an
 * account whose password breaks the creation rule can log in cannot be seeded here (creation
 * enforces the rule): `AuthControllerIT` proves it against the database.
 *
 * `@cloud-skip` (RPS-1498): this is Repsy OS's `POST /api/auth/login`; Repsy Cloud has its own login.
 */
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

async function login(
  username: string,
  password: string,
): Promise<{ status: number; body: { msgId?: string; data?: unknown } }> {
  const res = await fetch(`${env.apiBaseUrl}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  return { status: res.status, body: (await res.json()) as { msgId?: string; data?: unknown } };
}

test(
  'a wrong password answers 401 invalidCredentials whatever its strength',
  { tag: ['@smoke', '@cloud-skip'] },
  async ({ seeder }) => {
    const user = await seeder.createUser();
    const unknown = seeder.reserveUsername();
    const wrongPasswords = [
      'a',
      'abc',
      'lowercase1',
      'UPPERCASE1',
      'NoDigitsHere',
      'has space',
      `${user.password}x`,
      'Aa1'.padEnd(72, 'x'),
    ];

    for (const password of wrongPasswords) {
      for (const username of [user.username, unknown]) {
        const answer = await login(username, password);

        expect(answer.status, `${username} / ${password}`).toBe(401);
        expect(answer.body.msgId, `${username} / ${password}`).toBe('invalidCredentials');
      }
    }
  },
);

test(
  'the right password still logs in, and a malformed form is still a 400',
  { tag: '@cloud-skip' },
  async ({ seeder }) => {
    const user = await seeder.createUser();

    const ok = await login(user.username, user.password);
    expect(ok.status).toBe(200);
    expect(ok.body.msgId).toBe('loginSucceeded');

    // Missing or empty password, one over 72 characters, and one over 72 bytes (37 two-byte characters).
    for (const password of ['', 'x'.repeat(73), 'é'.repeat(37)]) {
      const answer = await login(user.username, password);

      expect(answer.status, password).toBe(400);
    }
    const missing = await fetch(`${env.apiBaseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: user.username }),
    });
    expect(missing.status).toBe(400);
  },
);
