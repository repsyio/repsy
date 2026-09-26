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
 * RPS-1474 part b: the URLs the remaining clients follow, on the stack that `./run.sh local up --tls` starts
 * (README.md "TLS stack"). `tls-listeners.spec.ts` covers npm, NuGet, Cargo and the Docker realm; this is what
 * else Repsy puts into an answer that a client then fetches, probed on 9443 and 9090:
 *
 * - PyPI's PEP 503 project page carries ABSOLUTE file links, built from the request, so pip and twine follow
 *   https over TLS (and http over the plain port) without any setting;
 * - Helm's classic `index.yaml` lists its chart `urls` and Maven's directory pages list their entries as
 *   RELATIVE references, and Ruby's compact index and Go's `@v/list` / `@latest` carry no URL at all, so
 *   `helm`, `mvn`, `gem` and `go` stay on the scheme of the URL the client was given. Pinned as observed: an
 *   absolute `http://` link here would send a TLS-only client to a port it may not reach.
 *
 * Skipped without the overlay (`REPSY_E2E_TLS=1`, see src/stack-overlays.ts).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { edgeRequest } from '../../src/clients/edge-raw.js';
import { env } from '../../src/env.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import { optedIn } from '../../src/stack-overlays.js';

test.skip(!optedIn('tls'), 'needs the TLS overlay: REPSY_E2E_TLS=1 ./run.sh local up --tls');

const tlsRepo = new URL(env.repoBaseUrl);
const plainRepo = new URL(env.plainRepoBaseUrl);

test.describe('the URLs of the other clients over TLS', { tag: ['@tls', '@smoke'] }, () => {
  test('the PyPI project page links the files on the listener it was asked on', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: false });
    const pkg = await seedPackage(repo, seeder);
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${base.origin}/${repoPath(repo.name)}/simple/${pkg.name}/`);
      expect(res.status).toBe(200);
      const hrefs = [...res.text.matchAll(/href="([^"]+)"/g)].map((m) => m[1] ?? '');
      expect(hrefs.length).toBeGreaterThan(0);
      for (const href of hrefs) {
        expect(href).toMatch(new RegExp(`^${base.origin}/${repoPath(repo.name)}/${pkg.name}/-/`));
      }
    }
  });

  test('the Helm classic index lists relative chart urls, whatever the listener', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: false });
    const chart = await seedPackage(repo, seeder, { variant: 'classic' });
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${base.origin}/${repoPath(repo.name)}/index.yaml`);
      expect(res.status).toBe(200);
      const urls = [...res.text.matchAll(/^\s*-\s+(\S+\.tgz)\s*$/gm)].map((m) => m[1]);
      expect(urls).toEqual([`charts/${chart.name}-${chart.version}.tgz`]);
      expect(res.text).not.toMatch(/https?:\/\//);
    }
  });

  test('a Maven directory page links its entries relatively', async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: false });
    await seedPackage(repo, seeder);
    for (const base of [tlsRepo, plainRepo]) {
      const res = await edgeRequest(`${base.origin}/${repoPath(repo.name)}/`);
      expect(res.status).toBe(200);
      const hrefs = [...res.text.matchAll(/href="([^"]+)"/g)].map((m) => m[1] ?? '');
      expect(hrefs.length).toBeGreaterThan(0);
      for (const href of hrefs) {
        expect(href).not.toMatch(/^[a-z]+:\/\//);
      }
    }
  });

  test('the Ruby compact index and the Go version list carry no absolute URL', async ({
    seeder,
  }) => {
    const gems = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const gem = await seedPackage(gems, seeder);
    const modules = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const goModule = await seedPackage(modules, seeder);
    for (const base of [tlsRepo, plainRepo]) {
      for (const url of [
        `${base.origin}/${repoPath(gems.name)}/versions`,
        `${base.origin}/${repoPath(gems.name)}/info/${gem.name}`,
        `${base.origin}/${repoPath(modules.name)}/${goModule.name}/@v/list`,
        `${base.origin}/${repoPath(modules.name)}/${goModule.name}/@latest`,
      ]) {
        const res = await edgeRequest(url);
        expect(res.status, url).toBe(200);
        expect(res.text, url).not.toMatch(/https?:\/\//);
      }
    }
  });
});
