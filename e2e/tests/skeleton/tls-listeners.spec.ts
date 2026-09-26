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
 * RPS-1474: Repsy's own HTTPS listeners (README.md "HTTPS / SSL": 8443 next to the panel API, 9443 next to
 * the package protocols), on the stack that `./run.sh local up --tls` starts (README.md "TLS stack"). What
 * this pins, all of it observed on that stack:
 *
 * - the http listeners keep answering next to the https ones, and each connector speaks only its own
 *   protocol;
 * - the certificate the overlay generates is what a client is given: a chain to the throwaway CA with
 *   `localhost`, `repsy` and `127.0.0.1` as its names, and a client without that CA refuses it (the
 *   control that makes every other runner's success over TLS mean something);
 * - which scheme the public URLs carry. Cargo's `config.json` (`dl`, `api`) and the Docker token realm are
 *   built from the request, so they follow the listener the client reached Repsy on (https over TLS,
 *   http over plain). npm's `dist.tarball` and the NuGet service index take the configured
 *   `REPO_BASE_URL` when there is one, and the stack has one (the https URL of the repo listener), so they
 *   name it on BOTH listeners. That is the documented precedence (README.md "npm tarball URLs", "NuGet
 *   resource URLs"), pinned here so a change to it is seen.
 *
 * Skipped without the overlay (`REPSY_E2E_TLS=1`, see src/stack-overlays.ts), and by the runners of a
 * default stack, which have no TLS listener to reach.
 */
import net from 'node:net';
import tls from 'node:tls';
import fs from 'node:fs/promises';

import { RepoType } from '../../src/api/panel-api.js';
import { edgeRequest } from '../../src/clients/edge-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import { optedIn } from '../../src/stack-overlays.js';

test.skip(!optedIn('tls'), 'needs the TLS overlay: REPSY_E2E_TLS=1 ./run.sh local up --tls');

const tlsApi = new URL(env.apiBaseUrl);
const tlsRepo = new URL(env.repoBaseUrl);
const plainApi = new URL(env.plainApiBaseUrl);
const plainRepo = new URL(env.plainRepoBaseUrl);

/** What a client that follows a URL from an answer of `base` must be given back, sans path. */
const originOf = (base: URL): string => base.origin;

test.describe('the https listeners next to the http ones', { tag: ['@tls', '@smoke'] }, () => {
  test('the four URLs are two https and two http listeners', () => {
    expect(tlsApi.protocol).toBe('https:');
    expect(tlsRepo.protocol).toBe('https:');
    expect(plainApi.protocol).toBe('http:');
    expect(plainRepo.protocol).toBe('http:');
    expect(new Set([tlsApi.port, tlsRepo.port, plainApi.port, plainRepo.port]).size).toBe(4);
  });

  test('the panel API answers on both the https and the http port', async () => {
    const secure = await edgeRequest(`${originOf(tlsApi)}/`);
    const plain = await edgeRequest(`${originOf(plainApi)}/`);
    expect(secure.status).toBe(200);
    expect(plain.status).toBe(200);
    expect(secure.text).toBe(plain.text);
  });

  test('the repository protocols answer on both the https and the http port', async () => {
    const secure = await edgeRequest(`${originOf(tlsRepo)}/v2/`);
    const plain = await edgeRequest(`${originOf(plainRepo)}/v2/`);
    expect(secure.status).toBe(401);
    expect(plain.status).toBe(401);
  });

  test('an admin login works over both ports and the tokens are interchangeable', async () => {
    const login = (base: URL): Promise<Response> =>
      fetch(`${originOf(base)}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: env.adminUsername, password: env.adminPassword }),
      });
    const bearerOf = async (res: Response): Promise<string> =>
      ((await res.json()) as { data: { token: string } }).data.token;

    const overTls = await login(tlsApi);
    const overPlain = await login(plainApi);
    expect(overTls.status).toBe(200);
    expect(overPlain.status).toBe(200);

    const tlsToken = await bearerOf(overTls);
    const onPlain = await fetch(`${originOf(plainApi)}/api/repos/counts`, {
      headers: { Authorization: `Bearer ${tlsToken}` },
    });
    expect(onPlain.status, 'a token issued over https is accepted on the http port').toBe(200);
  });

  test('a connector speaks only its own protocol', async () => {
    const plainOnTls = await edgeRequest(`http://${tlsRepo.host}/v2/`).then(
      (res) => res.status,
      (error: unknown) => `refused (${String(error)})`,
    );
    const tlsOnPlain = await edgeRequest(`https://${plainRepo.host}/v2/`).then(
      (res) => res.status,
      (error: unknown) => `refused (${String(error)})`,
    );
    // Tomcat's own answer to a plaintext request on a TLS port; the TLS handshake against a plain port
    // never completes.
    expect(plainOnTls, 'plain http to the https port').toBe(400);
    expect(tlsOnPlain, 'tls to the http port').toEqual(expect.stringContaining('refused'));
  });
});

test.describe('the certificate', { tag: ['@tls', '@smoke'] }, () => {
  const caFile = process.env.REPSY_E2E_TLS_CA_FILE ?? '';

  /** The peer certificate of the repo listener as seen by a client that trusts only the overlay's CA. */
  async function peer(
    host: string,
  ): Promise<{ authorized: boolean; altNames: string; issuer: string }> {
    const ca = await fs.readFile(caFile);
    return new Promise((resolve, reject) => {
      const socket = tls.connect(
        {
          host: '127.0.0.1',
          port: Number(tlsRepo.port),
          // An IP is no server name (RFC 6066); the certificate is checked against it all the same.
          ...(net.isIP(host) ? {} : { servername: host }),
          ca,
        },
        () => {
          const cert = socket.getPeerCertificate();
          resolve({
            authorized: socket.authorized,
            altNames: cert.subjectaltname ?? '',
            issuer: String(cert.issuer?.CN ?? ''),
          });
          socket.end();
        },
      );
      socket.on('error', reject);
    });
  }

  test('it chains to the throwaway CA and names localhost, the service and 127.0.0.1', async () => {
    test.skip(!caFile, 'REPSY_E2E_TLS_CA_FILE is set by run.sh for a TLS stack');
    const seen = await peer('localhost');
    expect(seen.authorized).toBe(true);
    expect(seen.issuer).toBe('Repsy e2e CA');
    expect(seen.altNames).toContain('DNS:localhost');
    expect(seen.altNames).toContain('DNS:repsy');
    expect(seen.altNames).toContain('IP Address:127.0.0.1');
    // The IP form of the name is one the certificate covers as well.
    expect((await peer('127.0.0.1')).authorized).toBe(true);
  });

  test('a client that does not trust the CA refuses it (the control of every TLS run)', async () => {
    test.skip(!caFile, 'REPSY_E2E_TLS_CA_FILE is set by run.sh for a TLS stack');
    const { work } = await isolatedWorkDir('tls-untrusted');
    const script = `fetch(process.argv[1]).then((r) => console.log('status ' + r.status), (e) => console.log('error ' + (e.cause?.code ?? e.message)))`;
    const url = `${originOf(tlsRepo)}/v2/`;
    // NODE_EXTRA_CA_CERTS is read when Node starts, so the child's own environment decides.
    const withoutCa = await run(process.execPath, ['-e', script, url], {
      cwd: work,
      env: { PATH: process.env.PATH },
      label: 'tls-untrusted-without-ca',
    });
    expect(withoutCa.stdout.trim()).toMatch(
      /^error (SELF_SIGNED_CERT_IN_CHAIN|UNABLE_TO_VERIFY_LEAF_SIGNATURE|UNABLE_TO_GET_ISSUER_CERT)/,
    );
    const withCa = await run(process.execPath, ['-e', script, url], {
      cwd: work,
      env: { PATH: process.env.PATH, NODE_EXTRA_CA_CERTS: caFile },
      label: 'tls-untrusted-with-ca',
    });
    expect(withCa.stdout.trim()).toBe('status 401');
  });
});

test.describe('the scheme of the public URLs', { tag: ['@tls', '@smoke'] }, () => {
  test('Cargo config.json dl and api follow the listener', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: false });
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${originOf(base)}/${repo.name}/config.json`);
      expect(res.status).toBe(200);
      expect(res.json).toMatchObject({
        dl: `${originOf(base)}/${repo.name}/api/v1/crates/{crate}/{version}/download`,
        api: `${originOf(base)}/${repo.name}`,
      });
    }
  });

  test('the Docker token realm follows the listener', async () => {
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${originOf(base)}/v2/`);
      expect(res.status).toBe(401);
      expect(res.headers.get('www-authenticate')).toContain(`realm="${originOf(base)}/v2/token"`);
    }
  });

  test('npm dist.tarball is the configured REPO_BASE_URL on both listeners', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: false });
    // Unscoped: this case is about the scheme of the tarball URL, not about how a scope is spelled.
    const pkg = await seedPackage(repo, seeder, { scoped: false });
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${originOf(base)}/${repo.name}/${pkg.name}`);
      expect(res.status).toBe(200);
      const versions = (res.json as { versions: Record<string, { dist: { tarball: string } }> })
        .versions;
      expect(versions[pkg.version]?.dist.tarball).toBe(
        `${originOf(tlsRepo)}/${repo.name}/${pkg.name}/-/${pkg.name}-${pkg.version}.tgz`,
      );
    }
  });

  test('the NuGet service index names the configured REPO_BASE_URL on both listeners', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: false });
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${originOf(base)}/${repo.name}/v3/index.json`);
      expect(res.status).toBe(200);
      const resources = (res.json as { resources: Array<{ '@id': string }> }).resources;
      expect(resources.length).toBeGreaterThan(0);
      for (const resource of resources) {
        expect(resource['@id']).toMatch(new RegExp(`^${originOf(tlsRepo)}/${repo.name}/`));
      }
    }
  });

  test('the panel snippets are given the https repo URL', async () => {
    const res = await edgeRequest(`${originOf(tlsApi)}/assets/static-env.js`);
    expect(res.status).toBe(200);
    expect(res.text).toContain(originOf(tlsRepo));
  });
});
