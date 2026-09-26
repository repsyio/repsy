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
 * RPS-1289: the REAL `npm unpublish` against Repsy OS. The client sends `GET /pkg?write=true`, then
 * `PUT /pkg/-rev/<rev>` with the packument minus the version, `GET /pkg?write=true` again and
 * `DELETE /pkg/-/pkg-<version>.tgz/-rev/<rev>`; the only version of a package goes with a single
 * `DELETE /pkg/-rev/<rev>`. The PUT used to answer 404 `itemNotFound` (the package name was taken to
 * be `pkg/-rev/<rev>`), which `npm unpublish` treats as "already unpublished": it exited 0 and
 * changed nothing. So every test here asserts the registry's state after the client, not only its
 * exit code. A scoped name goes over the wire as `@scope%2fpkg`.
 *
 * Versions are published by the real `npm publish` (`npm.seedPublish`), and read back with raw GETs
 * of the packument and of each tarball's canonical path (`npm-raw.ts`).
 */
import { RepoType } from '../../src/api/panel-api.js';
import * as npm from '../../src/clients/npm.js';
import { npmAdapter } from '../../src/clients/npm.js';
import {
  adminCredential,
  parsePackument,
  rawGetPackument,
  rawGetTarballCanonical,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { World } from '../../src/scenarios/world.js';

const SCENARIO: Scenario = {
  id: 'unpublish',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

function worldFor(
  repoName: string,
  packageName: string,
  version: string,
  credential: MaterializedCredential = adminCredential(),
): World {
  const target = { packageName, version };
  return {
    scenario: SCENARIO,
    protocol: 'npm',
    repoName,
    credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

/** The versions of the package the registry serves, and the version `latest` points at. */
async function served(repoName: string, packageName: string) {
  const res = await rawGetPackument(repoName, adminCredential(), packageName);
  if (res.status === 404) {
    return { status: 404, versions: [] as string[], latest: undefined };
  }
  expect(res.status, `GET packument of ${packageName}`).toBe(200);
  const packument = parsePackument(res.body);
  return {
    status: 200,
    versions: Object.keys(packument.versions).sort(),
    latest: (JSON.parse(res.body.toString('utf8')) as { 'dist-tags'?: Record<string, string> })[
      'dist-tags'
    ]?.latest,
  };
}

async function tarballStatus(repoName: string, packageName: string, version: string) {
  return (await rawGetTarballCanonical(repoName, adminCredential(), packageName, version)).status;
}

test.describe('npm unpublish (real client, RPS-1289)', () => {
  const oneVersionCases = [
    {
      title: 'unpublishes one version of an unscoped package',
      tags: ['@smoke'],
      packageName: (runId: string) => `e2e-${runId}-unpublish`,
    },
    {
      title: 'unpublishes one version of a scoped package',
      tags: [],
      packageName: (runId: string) => `@e2e-${runId}/unpublish`,
    },
  ];

  for (const { title, tags, packageName: nameFor } of oneVersionCases) {
    test(title, { tag: tags }, async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = nameFor(seeder.runId);
      const kept = npmAdapter.version('release');
      const removed = npmAdapter.version('release');

      for (const version of [kept, removed]) {
        await npm.seedPublish(worldFor(repo.name, packageName, version));
      }
      expect((await served(repo.name, packageName)).versions).toEqual([kept, removed].sort());

      const run = await npm.unpublish(
        worldFor(repo.name, packageName, removed),
        `${packageName}@${removed}`,
      );
      expect(run.exitCode, `npm unpublish: ${run.command}\n${run.stderr}`).toBe(0);

      // The registry really lost the version and its tarball, and kept the other one.
      const after = await served(repo.name, packageName);
      expect(after.versions, 'the packument no longer lists the unpublished version').toEqual([
        kept,
      ]);
      expect(after.latest, 'latest moved to the remaining version').toBe(kept);
      expect(await tarballStatus(repo.name, packageName, removed)).toBe(404);
      expect(await tarballStatus(repo.name, packageName, kept)).toBe(200);

      // ... and what is left still installs.
      const resolved = await npm.resolve(worldFor(repo.name, packageName, kept));
      expect(resolved.clientExitCode, `npm install: ${resolved.command}`).toBe(0);
    });
  }

  test(
    'unpublishing the only version deletes the package',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = `@e2e-${seeder.runId}/only-version`;
      const version = npmAdapter.version('release');
      await npm.seedPublish(worldFor(repo.name, packageName, version));

      // npm itself refuses to delete the last version of a package without --force.
      const run = await npm.unpublish(
        worldFor(repo.name, packageName, version),
        `${packageName}@${version}`,
        { force: true },
      );
      expect(run.exitCode, `npm unpublish: ${run.command}\n${run.stderr}`).toBe(0);

      expect((await served(repo.name, packageName)).status, 'the package is gone').toBe(404);
      expect(await tarballStatus(repo.name, packageName, version)).toBe(404);
    },
  );

  test(
    'unpublishing a whole package with --force deletes every version',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = `e2e-${seeder.runId}-whole`;
      const versions = [npmAdapter.version('release'), npmAdapter.version('release')];
      for (const version of versions) {
        await npm.seedPublish(worldFor(repo.name, packageName, version));
      }

      const run = await npm.unpublish(worldFor(repo.name, packageName, versions[0]!), packageName, {
        force: true,
      });
      expect(run.exitCode, `npm unpublish: ${run.command}\n${run.stderr}`).toBe(0);

      expect((await served(repo.name, packageName)).status, 'the package is gone').toBe(404);
      for (const version of versions) {
        expect(await tarballStatus(repo.name, packageName, version)).toBe(404);
      }
    },
  );

  test(
    'a read-write deploy token cannot unpublish: removing a version needs MANAGE (RPS-1424)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
      const packageName = `e2e-${seeder.runId}-token`;
      const kept = npmAdapter.version('release');
      const other = npmAdapter.version('release');
      for (const version of [kept, other]) {
        await npm.seedPublish(worldFor(repo.name, packageName, version));
      }

      // A read-write token publishes, deprecates and re-tags (WRITE), but never removes files.
      const token = await seeder.createToken(repo.name, { readOnly: false });
      const credential: MaterializedCredential = {
        transport: 'basic',
        username: token.username,
        password: token.token,
        kind: 'token',
      };

      const run = await npm.unpublish(
        worldFor(repo.name, packageName, other, credential),
        `${packageName}@${other}`,
      );
      expect(run.exitCode, `npm unpublish: ${run.command}\n${run.stderr}`).not.toBe(0);

      expect((await served(repo.name, packageName)).versions, 'nothing was removed').toEqual(
        [kept, other].sort(),
      );
      expect(await tarballStatus(repo.name, packageName, other)).toBe(200);
    },
  );
});
