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
 * RPS-1480, RPS-1514: the browser-facing headers at the real edge (`CorsGlobalConfiguration`,
 * `SecurityHeadersFilter`), which MockMvc-level tests bypass.
 *
 *  - CSP is a property of the single-page app: sent on the api port for every non-`/api/` path (the
 *    SPA's own routes, `/index.html`) and never on `/api/**` (JSON) or on the protocol port.
 *  - `X-Content-Type-Options: nosniff` is on every response of both ports (the protocol port serves
 *    user-uploaded bytes); `Referrer-Policy` and `X-Frame-Options` on every response of the api port
 *    (`/api/**` too) and not on the protocol port.
 *  - HSTS is opt-in (`APP_HSTS_MAX_AGE`, off by default): never sent over plain http. Over https it
 *    is asserted by the TLS overlay's own spec (`tests/skeleton/tls-listeners.spec.ts`), where the
 *    overlay sets the variable.
 *  - CORS with `APP_ALLOWED_ORIGINS` unset, the default and what this stack runs (RPS-1590): the panel API
 *    is same-origin only, so it sends NO CORS header for any origin and does not answer a preflight
 *    with CORS headers (it used to reflect any origin with credentials). It still serves the request:
 *    the browser, not the server, refuses a cross-origin page the response. The repository port sends
 *    no CORS header at all, whatever the origin.
 *  - CORS with `APP_ALLOWED_ORIGINS` set (`@cors`, the `--cors` overlay, README.md "CORS leg"): exactly
 *    the listed origins are reflected, with credentials, on the panel API only; any other origin is
 *    refused with 403; the CSP `connect-src` names them; the repository port still sends nothing.
 */
import { apiUrl, edgeRequest, type EdgeResponse, repoUrl } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { optedIn } from '../../src/stack-overlays.js';

const FOREIGN_ORIGIN = 'http://evil.e2e.test';
// The two origins docker-compose.stack-cors.yml sets APP_ALLOWED_ORIGINS to.
const ALLOWED_ORIGINS = ['http://allowed.e2e.test', 'http://second-allowed.e2e.test'];
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
    test.skip(optedIn('cors'), 'the cors overlay sets APP_ALLOWED_ORIGINS, see the @cors describe');

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

test.describe(
  'X-Content-Type-Options, Referrer-Policy, X-Frame-Options',
  { tag: ['@smoke'] },
  () => {
    const API_PATHS = ['/', '/some/deep/route', '/api/profile', '/api/no-such-endpoint'];
    const REPO_PATHS = ['/', '/v2/', '/api/users', '/no-such-repo/g/a/1.0/a-1.0.pom'];

    for (const path of API_PATHS) {
      test(`the panel port sends all three on ${path}`, async () => {
        const res = await edgeRequest(apiUrl(path));

        expect(res.headers.get('x-content-type-options')).toBe('nosniff');
        expect(res.headers.get('referrer-policy')).toBe('strict-origin-when-cross-origin');
        expect(res.headers.get('x-frame-options')).toBe('DENY');
      });
    }

    test('the panel port sends them on an error and on a preflight too', async () => {
      const preflight = await edgeRequest(apiUrl('/api/repos'), {
        method: 'OPTIONS',
        headers: { Origin: FOREIGN_ORIGIN, 'Access-Control-Request-Method': 'GET' },
      });
      const denied = await edgeRequest(apiUrl('/api/users'));

      expect(denied.status).toBe(401);
      for (const res of [denied, preflight]) {
        expect(res.headers.get('x-content-type-options')).toBe('nosniff');
        expect(res.headers.get('referrer-policy')).toBe('strict-origin-when-cross-origin');
        expect(res.headers.get('x-frame-options')).toBe('DENY');
      }
    });

    for (const path of REPO_PATHS) {
      test(`the protocol port sends nosniff and nothing panel-only on ${path}`, async () => {
        const res = await edgeRequest(repoUrl(path));

        expect(res.headers.get('x-content-type-options')).toBe('nosniff');
        expect(res.headers.get('referrer-policy')).toBeNull();
        expect(res.headers.get('x-frame-options')).toBeNull();
      });
    }
  },
);

test.describe('Strict-Transport-Security is opt-in', { tag: ['@smoke'] }, () => {
  test('is never sent over plain http, on either port', async () => {
    // plain*BaseUrl is http on every stack, also the TLS one, whose https listeners do send it
    for (const url of [env.plainApiBaseUrl, env.plainRepoBaseUrl]) {
      const res = await edgeRequest(`${url}/`);

      expect(res.headers.get('strict-transport-security'), url).toBeNull();
    }
  });
});

function expectNoCorsHeaders(res: EdgeResponse, label: string): void {
  for (const name of [
    'access-control-allow-origin',
    'access-control-allow-credentials',
    'access-control-allow-methods',
    'access-control-allow-headers',
  ]) {
    expect(res.headers.get(name), `${label}: ${name}`).toBeNull();
  }
}

test.describe(
  'CORS on the panel API with APP_ALLOWED_ORIGINS unset (same-origin only, RPS-1590)',
  { tag: ['@smoke'] },
  () => {
    test.skip(env.target === 'remote', 'the remote instance own APP_ALLOWED_ORIGINS');
    test.skip(optedIn('cors'), 'the cors overlay sets APP_ALLOWED_ORIGINS, see the @cors describe');

    test('a preflight from a foreign origin is not answered with CORS headers', async () => {
      const res = await edgeRequest(apiUrl('/api/repos'), {
        method: 'OPTIONS',
        headers: {
          Origin: FOREIGN_ORIGIN,
          'Access-Control-Request-Method': 'GET',
          'Access-Control-Request-Headers': 'authorization',
        },
      });

      expectNoCorsHeaders(res, 'preflight');
    });

    test('a cross-origin request is still served, without any CORS header', async () => {
      const anonymous = await edgeRequest(apiUrl('/api/profile'), {
        headers: { Origin: FOREIGN_ORIGIN },
      });
      const login = await edgeRequest(apiUrl('/api/auth/login'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Origin: FOREIGN_ORIGIN },
        body: JSON.stringify({ username: env.adminUsername, password: env.adminPassword }),
      });

      // no 403 "Invalid CORS request": with nothing configured the server does not judge the origin
      expect(anonymous.status).toBe(401);
      expect(login.status).toBe(200);
      expectNoCorsHeaders(anonymous, 'anonymous');
      expectNoCorsHeaders(login, 'login');
    });

    test('a request without an Origin gets no CORS header', async () => {
      expectNoCorsHeaders(await edgeRequest(apiUrl('/api/profile')), 'no Origin');
    });

    test('the protocol port sends no CORS header, for a preflight or a plain request', async () => {
      for (const path of ['/v2/', '/', '/api/repos']) {
        const preflight = await edgeRequest(repoUrl(path), {
          method: 'OPTIONS',
          headers: {
            Origin: FOREIGN_ORIGIN,
            'Access-Control-Request-Method': 'GET',
            'Access-Control-Request-Headers': 'authorization',
          },
        });
        const plain = await edgeRequest(repoUrl(path), { headers: { Origin: FOREIGN_ORIGIN } });

        expectNoCorsHeaders(preflight, `${path} preflight`);
        expectNoCorsHeaders(plain, `${path} plain`);
      }
    });
  },
);

// Needs the `--cors` overlay (docker-compose.stack-cors.yml sets APP_ALLOWED_ORIGINS to ALLOWED_ORIGINS):
// ./run.sh local up --cors, then REPSY_E2E_CORS=1 ./run.sh test --protocol api --grep @cors
test.describe('CORS on the panel API with APP_ALLOWED_ORIGINS set', { tag: ['@cors'] }, () => {
  test.skip(env.target === 'remote', 'the remote instance own APP_ALLOWED_ORIGINS');
  test.skip(
    !optedIn('cors'),
    'opt-in: needs the cors overlay; ./run.sh local up --cors, then REPSY_E2E_CORS=1 ./run.sh test --protocol api --grep @cors',
  );

  for (const origin of ALLOWED_ORIGINS) {
    test(`a preflight from ${origin} is answered with that origin and credentials`, async () => {
      const res = await edgeRequest(apiUrl('/api/repos'), {
        method: 'OPTIONS',
        headers: {
          Origin: origin,
          'Access-Control-Request-Method': 'GET',
          'Access-Control-Request-Headers': 'authorization',
        },
      });

      expect(res.status).toBe(200);
      expect(res.headers.get('access-control-allow-origin')).toBe(origin);
      expect(res.headers.get('access-control-allow-credentials')).toBe('true');
      expect(res.headers.get('access-control-allow-methods')).toBe('GET');
      expect(res.headers.get('access-control-allow-headers')).toBe('authorization');
    });

    test(`a request from ${origin} gets that origin back with credentials`, async () => {
      const res = await edgeRequest(apiUrl('/api/profile'), { headers: { Origin: origin } });

      expect(res.status).toBe(401);
      expect(res.headers.get('access-control-allow-origin')).toBe(origin);
      expect(res.headers.get('access-control-allow-credentials')).toBe('true');
    });
  }

  test('an origin that is not listed is refused, for a preflight and for a request', async () => {
    const preflight = await edgeRequest(apiUrl('/api/repos'), {
      method: 'OPTIONS',
      headers: { Origin: FOREIGN_ORIGIN, 'Access-Control-Request-Method': 'GET' },
    });
    const plain = await edgeRequest(apiUrl('/api/profile'), {
      headers: { Origin: FOREIGN_ORIGIN },
    });

    for (const res of [preflight, plain]) {
      expect(res.status).toBe(403);
      expectNoCorsHeaders(res, 'foreign origin');
    }
  });

  test('a request without an Origin gets no CORS header', async () => {
    expectNoCorsHeaders(await edgeRequest(apiUrl('/api/profile')), 'no Origin');
  });

  test('the protocol port sends no CORS header even for a listed origin', async () => {
    for (const path of ['/v2/', '/']) {
      const preflight = await edgeRequest(repoUrl(path), {
        method: 'OPTIONS',
        headers: { Origin: ALLOWED_ORIGINS[0], 'Access-Control-Request-Method': 'GET' },
      });
      const plain = await edgeRequest(repoUrl(path), { headers: { Origin: ALLOWED_ORIGINS[0] } });

      expectNoCorsHeaders(preflight, `${path} preflight`);
      expectNoCorsHeaders(plain, `${path} plain`);
    }
  });

  test('the CSP connect-src names the listed origins', async () => {
    const csp = (await edgeRequest(apiUrl('/'))).headers.get('content-security-policy');

    expect(csp).toContain(`connect-src 'self' ${ALLOWED_ORIGINS.join(' ')}`);
  });
});
