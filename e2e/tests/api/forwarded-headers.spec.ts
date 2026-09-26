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
 * RPS-1480: the public URL Repsy writes into its answers follows `X-Forwarded-Proto/-Host/-Port`
 * (`server.forward-headers-strategy: native`, Tomcat's RemoteIpValve, which trusts the private
 * addresses a docker bridge or a local proxy connects from). Three answers are built from the request
 * and so follow the headers: the Docker token realm of the `WWW-Authenticate` challenge, the PyPI
 * simple page's file links and the Cargo `config.json` (`dl`, `api`). Each is asserted with the headers
 * and, as a control, without them (the URL the client reached Repsy on).
 *
 * npm's `dist.tarball` and the NuGet service index are NOT covered here: the e2e stack sets
 * `REPO_BASE_URL`, which those two prefer to the request, so they ignore `X-Forwarded-*` on it. They
 * need a stack without `REPO_BASE_URL` (README.md "API suite").
 *
 * Skipped against a remote target: whether the forwarded headers are trusted depends on the
 * proxies that instance is configured to trust (Tomcat's `internalProxies`).
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  edgeRequest,
  FORWARDED_HEADERS,
  FORWARDED_ORIGIN,
  repoUrl as repoPortUrl,
} from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { repoPath, repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';

test.skip(env.target === 'remote', 'X-Forwarded-* trust is the remote instance own configuration');

test.describe('the public URL follows X-Forwarded-*', { tag: ['@smoke'] }, () => {
  test('the Docker token realm', async () => {
    const forwarded = await edgeRequest(repoPortUrl('/v2/'), { headers: FORWARDED_HEADERS });
    const direct = await edgeRequest(repoPortUrl('/v2/'));

    expect(forwarded.status).toBe(401);
    expect(forwarded.headers.get('www-authenticate')).toContain(
      `realm="${FORWARDED_ORIGIN}/v2/token"`,
    );
    expect(direct.headers.get('www-authenticate')).toContain(`realm="${env.repoBaseUrl}/v2/token"`);
  });

  test('the Docker realm takes the proto and host alone, without a port', async () => {
    const res = await edgeRequest(repoPortUrl('/v2/'), {
      headers: { 'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': 'pub.e2e.test' },
    });

    expect(res.headers.get('www-authenticate')).toContain('realm="https://pub.e2e.test/v2/token"');
  });

  test('the Cargo config.json dl and api', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: false });

    const forwarded = await edgeRequest(repoUrl(repo.name, 'config.json'), {
      headers: FORWARDED_HEADERS,
    });
    const direct = await edgeRequest(repoUrl(repo.name, 'config.json'));

    expect(forwarded.status).toBe(200);
    expect(forwarded.json).toMatchObject({
      dl: expect.stringContaining(`${FORWARDED_ORIGIN}/${repoPath(repo.name)}/`),
      api: `${FORWARDED_ORIGIN}/${repoPath(repo.name)}`,
    });
    expect(direct.json).toMatchObject({
      dl: expect.stringContaining(repoUrl(repo.name, '')),
      api: repoUrl(repo.name),
    });
  });

  test('the PyPI simple page file links', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: false });
    const pkg = await seedPackage(repo, seeder);
    const page = `/${repoPath(repo.name)}/simple/${pkg.name}/`;

    const forwarded = await edgeRequest(repoPortUrl(page), { headers: FORWARDED_HEADERS });
    const direct = await edgeRequest(repoPortUrl(page));

    expect(forwarded.status).toBe(200);
    expect(forwarded.text).toContain(`href="${FORWARDED_ORIGIN}/${repoPath(repo.name)}/`);
    expect(forwarded.text).not.toContain(env.repoBaseUrl);
    expect(direct.text).toContain(`href="${repoUrl(repo.name, '')}`);
    expect(direct.text).not.toContain(FORWARDED_ORIGIN);
  });
});
