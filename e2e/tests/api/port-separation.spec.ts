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
 * RPS-1480: the two ports serve different things (README.md "API suite"). The panel API (`/api/**`)
 * lives on the api port (8080) only, the wire protocols (`/v2/`, a Maven path, ...) on the main port
 * (9090) only. Raw `fetch` against both base URLs of the target, never a literal port, so the spec
 * holds on a parallel stack (`REPSY_E2E_PORT_OFFSET`).
 *
 * What the api port does with a wire-protocol path is NOT a 404: the single-page app's deep-link
 * fallback (`SpaController`) answers every dot-free path that is not `/api`, `/assets` or the favicon
 * with `index.html`. So "not served" on 8080 means "answered by the SPA, not by the protocol handler":
 * `text/html`, no registry header, no challenge, no package bytes.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { adminBearer, apiUrl, edgeRequest, repoUrl } from '../../src/clients/edge-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { seedPackage } from '../../src/seed/packages.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

test.describe('the panel API is not served on the protocol port', { tag: ['@smoke'] }, () => {
  test('an anonymous GET /api/users is a 404 unknownPath', async () => {
    const res = await edgeRequest(repoUrl('/api/users'));

    expect(res.status).toBe(404);
    expect(res.json).toMatchObject({ msgId: 'unknownPath', type: 'ERROR' });
  });

  test('an admin GET /api/users is a 404 too, and lists no user', async () => {
    const token = await adminBearer();

    const res = await edgeRequest(repoUrl('/api/users'), {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.status).toBe(404);
    expect(res.json).toMatchObject({ msgId: 'unknownPath', type: 'ERROR' });
    expect(res.text).not.toContain('"content"');
  });

  test('POST /api/auth/login is a 404 and mints no token', async () => {
    const res = await edgeRequest(repoUrl('/api/auth/login'), {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ username: env.adminUsername, password: env.adminPassword }),
    });

    expect(res.status).toBe(404);
    expect(res.json).toMatchObject({ msgId: 'unknownPath', type: 'ERROR' });
    expect(res.text).not.toContain('token');
  });

  test('control: the same admin GET /api/users on the api port is a 200 list', async () => {
    const token = await adminBearer();

    const res = await edgeRequest(apiUrl('/api/users'), {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.status).toBe(200);
    expect(res.json).toMatchObject({ type: 'SUCCESS', data: { content: expect.any(Array) } });
  });
});

test.describe('the wire protocols are not served on the api port', { tag: ['@smoke'] }, () => {
  test('GET /v2/ is the SPA on the api port, the registry ping on the protocol port', async () => {
    const onApi = await edgeRequest(apiUrl('/v2/'));
    const onRepo = await edgeRequest(repoUrl('/v2/'));

    expect(onApi.status).toBe(200);
    expect(onApi.headers.get('content-type')).toContain('text/html');
    expect(onApi.text).toContain('<app-root>');
    expect(onApi.headers.get('docker-distribution-api-version')).toBeNull();
    expect(onApi.headers.get('www-authenticate')).toBeNull();
    // Control: 9090 speaks the registry protocol (a Bearer challenge for an anonymous ping).
    expect(onRepo.status).toBe(401);
    expect(onRepo.headers.get('www-authenticate')).toMatch(/^Bearer realm=/);
    expect(onRepo.headers.get('content-type')).not.toContain('text/html');
  });

  test('a seeded Maven pom is served on the protocol port, and never on the api port', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: false });
    const pkg = await seedPackage(repo, seeder);
    const [group, artifact] = pkg.name.split(':');
    const path = `/${repoPath(repo.name)}/${group.replaceAll('.', '/')}/${artifact}/${pkg.version}/${artifact}-${pkg.version}.pom`;

    const onRepo = await edgeRequest(repoUrl(path));
    const onApi = await edgeRequest(apiUrl(path));

    expect(onRepo.status).toBe(200);
    expect(onRepo.text).toContain(`<artifactId>${artifact}</artifactId>`);
    expect(onApi.status).toBe(200);
    expect(onApi.headers.get('content-type')).toContain('text/html');
    expect(onApi.text).toContain('<app-root>');
    expect(onApi.text).not.toContain(`<artifactId>${artifact}</artifactId>`);
    expect(onApi.headers.get('www-authenticate')).toBeNull();
  });
});
