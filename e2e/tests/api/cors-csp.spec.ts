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
 * RPS-1480: the browser-facing headers at the real edge (`CorsGlobalConfiguration`,
 * `SecurityHeadersFilter`), which MockMvc-level tests bypass.
 *
 *  - CSP is a property of the single-page app: sent on the api port for every non-`/api/` path (the
 *    SPA's own routes, `/index.html`) and never on `/api/**` (JSON) or on the protocol port.
 *  - CORS with `APP_ALLOWED_ORIGINS` unset, the default and what this stack runs: the panel API answers
 *    a preflight from any origin, with credentials (README.md, the `APP_ALLOWED_ORIGINS` row: "Unset
 *    keeps today's behaviour: any origin is allowed"). The restricted-origin half of the contract
 *    needs a stack that sets the variable (README.md "API suite"), so it is not asserted here.
 *
 * Deliberately not asserted: what CORS does on the protocol port, and the absence of any other
 * security header (nosniff, Referrer-Policy, HSTS). Those are open product questions, not contracts.
 */
import { apiUrl, edgeRequest, repoUrl } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const FOREIGN_ORIGIN = 'http://evil.e2e.test';
const SPA_PATHS = ['/', '/some/deep/route', '/index.html'];

test.describe('Content-Security-Policy', { tag: ['@smoke'] }, () => {
  for (const path of SPA_PATHS) {
    test(`the SPA answer for ${path} carries the built-in policy`, async () => {
      const res = await edgeRequest(apiUrl(path));
      const csp = res.headers.get('content-security-policy');

      expect(res.status).toBe(200);
      expect(csp).toContain("default-src 'self'");
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("frame-ancestors 'none'");
      expect(csp).toContain("base-uri 'self'");
    });
  }

  test('connect-src is self only while APP_ALLOWED_ORIGINS is unset', async () => {
    test.skip(env.target === 'remote', 'the remote instance own APP_ALLOWED_ORIGINS');

    const csp = (await edgeRequest(apiUrl('/'))).headers.get('content-security-policy');

    expect(csp).toMatch(/(^|; )connect-src 'self'(;|$)/);
  });

  test('a panel API answer has no policy, on success and on error', async () => {
    const anonymous = await edgeRequest(apiUrl('/api/profile'));
    const login = await edgeRequest(apiUrl('/api/auth/login'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: env.adminUsername, password: env.adminPassword }),
    });

    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get('content-security-policy')).toBeNull();
    expect(login.status).toBe(200);
    expect(login.headers.get('content-security-policy')).toBeNull();
  });

  test('nothing on the protocol port has one', async () => {
    for (const path of ['/', '/v2/', '/api/users', '/no-such-repo/g/a/1.0/a-1.0.pom']) {
      const res = await edgeRequest(repoUrl(path));

      expect(res.headers.get('content-security-policy'), path).toBeNull();
    }
  });
});

test.describe('CORS on the panel API with APP_ALLOWED_ORIGINS unset', { tag: ['@smoke'] }, () => {
  test.skip(env.target === 'remote', 'the remote instance own APP_ALLOWED_ORIGINS');

  test('a preflight from a foreign origin is answered with that origin and credentials', async () => {
    const res = await edgeRequest(apiUrl('/api/repos'), {
      method: 'OPTIONS',
      headers: {
        Origin: FOREIGN_ORIGIN,
        'Access-Control-Request-Method': 'GET',
        'Access-Control-Request-Headers': 'authorization',
      },
    });

    expect(res.status).toBe(200);
    expect(res.headers.get('access-control-allow-origin')).toBe(FOREIGN_ORIGIN);
    expect(res.headers.get('access-control-allow-credentials')).toBe('true');
    expect(res.headers.get('access-control-allow-methods')).toBe('GET');
    expect(res.headers.get('access-control-allow-headers')).toBe('authorization');
  });

  test('a request without an Origin gets no CORS header', async () => {
    const res = await edgeRequest(apiUrl('/api/profile'));

    expect(res.headers.get('access-control-allow-origin')).toBeNull();
    expect(res.headers.get('access-control-allow-credentials')).toBeNull();
  });
});
