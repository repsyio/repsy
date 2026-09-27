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
 * RPS-1651: the URLs Repsy writes into its answers when a real reverse proxy is in front of it, on the stack
 * `./run.sh local up --proxy` starts (README.md "Reverse proxy stack"): nginx terminating TLS, forwarding to
 * Repsy's plain HTTP ports with `X-Forwarded-Proto/-Host/-Port`, in the form of the main README's example.
 * `tests/api/forwarded-headers.spec.ts` proves the same with the headers written by hand; this is the same
 * behaviour through a proxy that sets them itself, and the scheme, host and port of every URL below are the
 * proxy's (https, `localhost`, the proxy's port), not Repsy's (http, its own port).
 *
 * - the panel and the protocol port answer through the proxy, each on its own public origin;
 * - the Docker token realm, the Cargo `config.json` (`dl`, `api`) and the PyPI simple page's file links are
 *   built from the request and follow the forwarded headers; each has the direct answer as the control;
 * - HSTS (`APP_HSTS_MAX_AGE`, set by the overlay) goes out on a request the proxy forwarded as https and not
 *   on the same one made straight to Repsy's plain port (README.md "Security headers");
 * - RPS-1515, pinned: a proxy that puts the port INSIDE `X-Forwarded-Host` and sends no `X-Forwarded-Port`
 *   (nginx with `proxy_set_header X-Forwarded-Host $http_host`, the third proxy listener) gets a public URL
 *   without the port, so a client that follows it reaches the wrong port.
 *
 * Skipped without the overlay (`REPSY_E2E_PROXY=1`).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { apiUrl, edgeRequest, repoUrl as repoPortUrl } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { repoPath, repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import { optedIn } from '../../src/stack-overlays.js';

test.skip(
  !optedIn('proxy'),
  'needs the proxy overlay: REPSY_E2E_PROXY=1 ./run.sh local up --proxy',
);

const publicRepo = new URL(env.repoBaseUrl);
const publicApi = new URL(env.apiBaseUrl);
const directRepo = new URL(env.plainRepoBaseUrl);
const directApi = new URL(env.plainApiBaseUrl);
/** The proxy's third listener (`X-Forwarded-Host` with its port, no `X-Forwarded-Port`), from run.sh. */
const hostPortUrl = process.env.REPSY_E2E_PROXY_HOSTPORT_URL;

test.describe('Repsy behind a reverse proxy', { tag: ['@proxy'] }, () => {
  test('the panel and the protocol port are the proxy origins, not Repsy own', async () => {
    expect(publicApi.protocol).toBe('https:');
    expect(publicRepo.protocol).toBe('https:');
    expect(new Set([publicApi.port, publicRepo.port, directApi.port, directRepo.port]).size).toBe(
      4,
    );

    const panel = await edgeRequest(`${publicApi.origin}/`);
    expect(panel.status).toBe(200);
    expect(panel.headers.get('server')).toMatch(/nginx/i);
    const repo = await edgeRequest(repoPortUrl('/v2/'));
    expect(repo.status).toBe(401);
    expect(repo.headers.get('server')).toMatch(/nginx/i);
    // Repsy answers the same on its own port, which is what the proxy forwards to.
    const direct = await edgeRequest(`${directRepo.origin}/v2/`);
    expect(direct.status).toBe(401);
    expect(direct.headers.get('server')).toBeNull();
  });

  test('the Docker token realm is the proxy URL, and the direct one is Repsy own (the control)', async () => {
    const proxied = await edgeRequest(repoPortUrl('/v2/'));
    const direct = await edgeRequest(`${directRepo.origin}/v2/`);

    expect(proxied.headers.get('www-authenticate')).toContain(
      `realm="${publicRepo.origin}/v2/token"`,
    );
    expect(direct.headers.get('www-authenticate')).toContain(
      `realm="${directRepo.origin}/v2/token"`,
    );
  });

  test('the Cargo config.json names the proxy URL in dl and api', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: false });

    const proxied = await edgeRequest(repoUrl(repo.name, 'config.json'));
    const direct = await edgeRequest(`${directRepo.origin}/${repoPath(repo.name)}/config.json`);

    expect(proxied.status).toBe(200);
    expect(proxied.json).toMatchObject({
      dl: expect.stringContaining(`${publicRepo.origin}/${repoPath(repo.name)}/`),
      api: `${publicRepo.origin}/${repoPath(repo.name)}`,
    });
    expect(direct.json).toMatchObject({ api: `${directRepo.origin}/${repoPath(repo.name)}` });
  });

  test('the PyPI simple page links files on the proxy URL', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: false });
    const pkg = await seedPackage(repo, seeder);
    const page = `/${repoPath(repo.name)}/simple/${pkg.name}/`;

    const proxied = await edgeRequest(repoPortUrl(page));
    const direct = await edgeRequest(`${directRepo.origin}${page}`);

    expect(proxied.status).toBe(200);
    expect(proxied.text).toContain(`href="${publicRepo.origin}/${repoPath(repo.name)}/`);
    expect(proxied.text).not.toContain(directRepo.origin);
    expect(direct.text).toContain(`href="${directRepo.origin}/${repoPath(repo.name)}/`);
  });

  test('HSTS goes out on the request the proxy forwarded as https, not on the direct http one', async () => {
    const proxied = await edgeRequest(apiUrl('/'));
    const direct = await edgeRequest(`${directApi.origin}/`);

    expect(proxied.headers.get('strict-transport-security')).toBe('max-age=31536000');
    expect(direct.headers.get('strict-transport-security')).toBeNull();
  });

  // RPS-1515. `X-Forwarded-Host: localhost:<port>`, no X-Forwarded-Port: the realm is expected to keep the port (a
  // client follows this URL). It loses it today, so the test is expected to fail; when it is fixed the pin turns red
  // ("Expected to fail, but passed") and is deleted.
  test('the Docker token realm keeps a port that arrives inside X-Forwarded-Host (RPS-1515)', async () => {
    test.fail(
      true,
      'RPS-1515: X-Forwarded-Host with its own port and no X-Forwarded-Port loses the port',
    );
    expect(hostPortUrl, 'run.sh gives the runner REPSY_E2E_PROXY_HOSTPORT_URL').toBeTruthy();

    const res = await edgeRequest(`${hostPortUrl}/v2/`);

    expect(res.headers.get('www-authenticate')).toContain(`realm="${hostPortUrl}/v2/token"`);
  });
});
