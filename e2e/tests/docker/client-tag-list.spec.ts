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
 * part B; the raw pins are RA1/RA2 in `registry-api.spec.ts`). RPS-1489 added `tags/list`, so `crane
 * ls`, `skopeo list-tags`, `skopeo inspect <tag>` (which lists the tags too, unless `--no-tags`),
 * `regctl tag ls` and `oras repo tags` list the tags of a pushed image, in lexical order. There is no
 * `_catalog` route: `crane catalog`, `regctl repo ls` and `oras repo ls` still fail with the router's
 * `404 NAME_UNKNOWN / unknownPath` (RA2), which stays pinned here until a story adds it.
 *
 * Probed live, admin credential, private repo with one image pushed under two tags.
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
const NO_CATALOG = 'no _catalog route';

function expectNoCatalog(result: RunResult, what: string): void {
  expect(
    result.exitCode,
    `${what} cannot enumerate the registry: ${NO_CATALOG}\n${result.stderr}`,
  ).not.toBe(0);
  expect(
    result.stderr.toLowerCase(),
    `${what}: the registry's own answer (${NO_CATALOG})`,
  ).toContain('unknownpath');
}

/** The non-empty lines of a client's stdout (`crane ls` and `regctl tag ls` print one tag per line). */
function linesOf(result: RunResult): string[] {
  return result.stdout
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

test(
  'docker > crane ls, skopeo list-tags/inspect and regctl tag ls list the tags; crane catalog and regctl repo ls still fail on the missing _catalog (RPS-1489)',
  { tag: ['@skopeo', '@regctl'] },
  async ({ seeder }) => {
    const repoName = await newDockerRepo(seeder);
    const image = `e2e-${seeder.runId}-listing`;
    const ref = imageRef(repoName, image, 'v1');
    const bare = ref.replace(/:v1$/, '');
    const admin = adminCredential();

    const skopeo = await openSession(skopeoClient, admin, `docker-listing-skopeo-${seeder.runId}`);
    const built = await buildImage({ dir: path.join(skopeo.work, 'image'), marker: 'listing' });
    for (const tag of ['v2', 'v1', 'v10']) {
      const pushed = await skopeo.run(
        skopeoClient.pushArgs(built, imageRef(repoName, image, tag)),
        `listing-push-${tag}`,
      );
      expect(pushed.exitCode, `skopeo copy ${tag}: ${pushed.stderr}`).toBe(0);
    }
    // Lexical order: `v10` sorts before `v2`.
    const expectedTags = ['v1', 'v10', 'v2'];

    // skopeo
    const listed = await skopeo.run(
      ['list-tags', ...skopeoTlsFlags('both'), `docker://${bare}`],
      'listing-skopeo-list-tags',
    );
    expect(listed.exitCode, `skopeo list-tags: ${listed.stderr}`).toBe(0);
    expect((JSON.parse(listed.stdout) as { Tags: string[] }).Tags, 'skopeo list-tags').toEqual(
      expectedTags,
    );
    const inspected = await skopeo.run(
      ['inspect', ...skopeoTlsFlags('both'), `docker://${ref}`],
      'listing-skopeo-inspect',
    );
    expect(inspected.exitCode, `skopeo inspect (without --no-tags): ${inspected.stderr}`).toBe(0);
    expect(
      (JSON.parse(inspected.stdout) as { RepoTags: string[] }).RepoTags,
      'skopeo inspect lists the repository tags too',
    ).toEqual(expectedTags);

    // regctl
    const regctl = await openSession(regctlClient, admin, `docker-listing-regctl-${seeder.runId}`);
    const regctlTags = await regctl.run(['tag', 'ls', bare], 'listing-regctl-tag-ls');
    expect(regctlTags.exitCode, `regctl tag ls: ${regctlTags.stderr}`).toBe(0);
    expect(linesOf(regctlTags), 'regctl tag ls').toEqual(expectedTags);
    expectNoCatalog(
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
    const craneTags = await craneRun(['ls', bare]);
    expect(craneTags.exitCode, `crane ls: ${craneTags.stderr}`).toBe(0);
    expect(linesOf(craneTags), 'crane ls').toEqual(expectedTags);
    expectNoCatalog(await craneRun(['catalog', registryHost()]), 'crane catalog');
  },
);
