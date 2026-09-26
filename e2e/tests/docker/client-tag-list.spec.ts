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
 * What the real clients do when they ask a Repsy Docker repo for a tag list or a catalog (RPS-1478
 * part B; the raw pins are RA1/RA2 in `registry-api.spec.ts`). Repsy has no `tags/list` and no
 * `_catalog` route: both answer `404 NAME_UNKNOWN / unknownPath` for every caller, so each client
 * fails with that answer instead of listing anything. RPS-1489 is the backend story that adds
 * `tags/list`; the day it lands the `tags` cells of this file flip on purpose (the same day as RA1).
 *
 * Probed live, admin credential, private repo with one pushed image:
 *  - `crane ls` / `crane catalog`: exit 1, `NAME_UNKNOWN: unknownPath`.
 *  - `skopeo list-tags`: exit 1, "fetching tags list: name unknown: unknownPath". A plain
 *    `skopeo inspect <tag>` (without `--no-tags`) fails the same way, because it lists the repository's
 *    tags too: the one place a listing gap breaks a command that has nothing to do with listing.
 *  - `regctl tag ls` / `regctl repo ls`: exit 1, "request failed: not found [http 404]" with the same
 *    envelope.
 */
import path from 'node:path';

import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import { newDockerRepo } from '../../src/clients/docker-client-tests.js';
import { openSession, secretsOf } from '../../src/clients/docker-copy-adapter.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { adminCredential, imageRef, registryHost } from '../../src/clients/docker-raw.js';
import { regctlClient } from '../../src/clients/docker-regctl.js';
import { skopeoClient } from '../../src/clients/docker-skopeo.js';
import { skopeoTlsFlags } from '../../src/clients/docker-tls.js';
import { env } from '../../src/env.js';
import { isolatedWorkDir, run, type RunResult } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

/** crane's own plain-HTTP switch (`docker.ts`): needed only for a remote plain-HTTP host. */
const CRANE_INSECURE = env.insecureRegistry ? ['--insecure'] : [];
const GAP = 'no tags/list or _catalog route (RPS-1489)';

function expectNoListing(result: RunResult, what: string): void {
  expect(result.exitCode, `${what} cannot list: ${GAP}\n${result.stderr}`).not.toBe(0);
  expect(result.stderr.toLowerCase(), `${what}: the registry's own answer (${GAP})`).toContain(
    'unknownpath',
  );
}

test(
  'docker > crane ls/catalog, skopeo list-tags/inspect and regctl tag ls/repo ls fail on the missing tags/list and _catalog (RPS-1489)',
  { tag: ['@skopeo', '@regctl'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-listing`;
    const ref = imageRef(repoName, image, 'v1');
    const admin = adminCredential();

    const skopeo = await openSession(skopeoClient, admin, `docker-listing-skopeo-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(skopeo.work, 'image'), marker: 'listing' });
    const pushed = await skopeo.run(skopeoClient.pushArgs(built, ref), 'listing-push');
    expect(pushed.exitCode, `skopeo copy: ${pushed.stderr}`).toBe(0);

    // skopeo
    expectNoListing(
      await skopeo.run(
        [
          'list-tags',
          ...skopeoTlsFlags('both'),
          `docker://${imageRef(repoName, image, 'v1').replace(/:v1$/, '')}`,
        ],
        'listing-skopeo-list-tags',
      ),
      'skopeo list-tags',
    );
    expectNoListing(
      await skopeo.run(
        ['inspect', ...skopeoTlsFlags('both'), `docker://${ref}`],
        'listing-skopeo-inspect',
      ),
      'skopeo inspect (without --no-tags)',
    );

    // regctl
    const regctl = await openSession(regctlClient, admin, `docker-listing-regctl-${seeder.runId}`);
    expectNoListing(
      await regctl.run(['tag', 'ls', ref.replace(/:v1$/, '')], 'listing-regctl-tag-ls'),
      'regctl tag ls',
    );
    expectNoListing(
      await regctl.run(['repo', 'ls', registryHost()], 'listing-regctl-repo-ls'),
      'regctl repo ls',
    );

    // crane
    const { home, work } = await isolatedWorkDir(`docker-listing-crane-${seeder.runId}`);
    await renderDockerConfig(home, admin);
    const craneRun = (args: string[]) =>
      run('crane', [...args, ...CRANE_INSECURE], {
        cwd: work,
        env: craneEnv(home),
        timeoutMs: 60_000,
        redact: secretsOf(admin),
        label: 'listing-crane',
      });
    expectNoListing(await craneRun(['ls', ref.replace(/:v1$/, '')]), 'crane ls');
    expectNoListing(await craneRun(['catalog', registryHost()]), 'crane catalog');
  },
);
