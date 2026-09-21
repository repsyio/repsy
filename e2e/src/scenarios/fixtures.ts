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
 * The Playwright fixtures every spec uses: an admin-authenticated `PanelApi`, a per-test `Seeder`
 * that cleans up after itself, and `world` — the factory that turns a `Scenario` into a ready-to-test
 * `World` (plan section "Scenario model"). `world` is a function-valued fixture (`world(scenario,
 * protocol)`), not a single resolved value, because one spec calls it once per scenario in its own
 * loop (see `tests/maven/publish-consume.spec.ts`).
 */
import { test as base } from '@playwright/test';

import { PanelApi, RepoType } from '../api/panel-api.js';
import { env } from '../env.js';
import { perTestRunId } from '../seed/run-id.js';
import { Seeder } from '../seed/seeder.js';
import type { Scenario } from './types.js';
import {
  type Coordinates,
  type MaterializedCredential,
  seedPublisherFor,
  type World,
} from './world.js';

export type { Coordinates, World, MaterializedCredential } from './world.js';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

const ADMIN_CREDENTIAL: MaterializedCredential = {
  transport: 'basic',
  username: env.adminUsername,
  password: env.adminPassword,
};

/** Lower-case runner/service name -> the RepoType its repos are created as. */
const REPO_TYPE_BY_PROTOCOL: Record<string, RepoType> = {
  maven: RepoType.MAVEN,
  npm: RepoType.NPM,
  pypi: RepoType.PYPI,
  docker: RepoType.DOCKER,
  cargo: RepoType.CARGO,
  nuget: RepoType.NUGET,
  golang: RepoType.GOLANG,
  helm: RepoType.HELM,
  ruby: RepoType.RUBY,
};

function repoTypeForProtocol(protocol: string): RepoType {
  const repoType = REPO_TYPE_BY_PROTOCOL[protocol];
  if (!repoType) {
    throw new Error(`world(): unknown protocol "${protocol}"`);
  }
  return repoType;
}

/** `password-admin`, `no-override`, ... -> `password-admin`, `no-override` (already slug-safe). */
function slugify(id: string): string {
  return id.replace(/[^a-z0-9-]/gi, '-').toLowerCase();
}

let versionSeq = 0;

/** A version unique to this test process, in `versionType`'s form. Not a coordinate by itself. */
function uniqueVersion(versionType: 'release' | 'snapshot'): string {
  versionSeq += 1;
  const base = `0.0.${Date.now()}${versionSeq}`;
  return versionType === 'snapshot' ? `${base}-SNAPSHOT` : base;
}

async function materializeCredential(
  seeder: Seeder,
  scenario: Scenario,
  repoName: string,
  repoType: RepoType,
): Promise<MaterializedCredential> {
  switch (scenario.credential) {
    case 'admin-password':
      return ADMIN_CREDENTIAL;

    case 'user-password': {
      const user = await seeder.createUser();
      return { transport: 'basic', username: user.username, password: user.password };
    }

    case 'token-rw': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'token-ro': {
      const token = await seeder.createToken(repoName, { readOnly: true });
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'token-expired': {
      const token = await seeder.createToken(repoName, {
        readOnly: false,
        expirationDate: new Date(Date.now() - ONE_DAY_MS),
      });
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'token-revoked': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      await seeder.revokeNow(repoName, token.id);
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'token-rotated-old': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      // The new value is discarded on purpose: this credential deliberately keeps using the old one.
      await seeder.rotateNow(repoName, token.id);
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'token-other-repo': {
      const otherRepo = await seeder.createRepo(repoType, { privateRepo: true });
      const token = await seeder.createToken(otherRepo.name, { readOnly: false });
      return { transport: 'basic', username: token.username, password: token.token };
    }

    case 'wrong-password':
      return {
        transport: 'basic',
        username: env.adminUsername,
        password: `${env.adminPassword}-wrong`,
      };

    case 'anonymous':
      return {};
  }
}

export interface Fixtures {
  panelApi: PanelApi;
  seeder: Seeder;
  world: (scenario: Scenario, protocol: string) => Promise<World>;
}

let testSeqByWorker = 0;

export const test = base.extend<Fixtures>({
  // eslint-disable-next-line no-empty-pattern
  panelApi: async ({}, use) => {
    const api = new PanelApi(env.apiBaseUrl);
    await api.login(env.adminUsername, env.adminPassword);
    await use(api);
  },

  seeder: async ({ panelApi }, use, testInfo) => {
    testSeqByWorker += 1;
    const runId = perTestRunId(env.runId, testInfo.parallelIndex, testSeqByWorker);
    const seeder = new Seeder(panelApi, runId);
    await use(seeder);
    await seeder.cleanup();
  },

  world: async ({ seeder }, use) => {
    const factory = async (scenario: Scenario, protocol: string): Promise<World> => {
      const repoType = repoTypeForProtocol(protocol);
      const repo = await seeder.createRepo(repoType, { privateRepo: scenario.repo.privateRepo });

      const credential = await materializeCredential(seeder, scenario, repo.name, repoType);

      const versionType = scenario.versionType ?? 'release';
      const packageName = `io.repsy.e2e.${seeder.runId}:${protocol}-${slugify(scenario.id)}`;
      const publishTarget: Coordinates = { packageName, version: uniqueVersion(versionType) };

      let world: World = {
        scenario,
        protocol,
        repoName: repo.name,
        credential,
        publishTarget,
        consumeTarget: publishTarget,
      };

      // A scenario whose own credential cannot publish but is still expected to consume
      // successfully (token-ro, anonymous-public, maven-releases-off/snapshots-off) needs something
      // already published first. A `reuseCoordinates` scenario is about a redeploy, so it always
      // pre-publishes: without an existing artifact its own publish would be an ordinary first
      // deploy and never exercise the rule it is meant to test (allowOverride, a switched-off kind).
      const needsPrePublish =
        scenario.reuseCoordinates === true ||
        (scenario.expect.consume === 'ok' && scenario.expect.publish !== 'ok');

      if (needsPrePublish) {
        const publish = seedPublisherFor(protocol);
        if (!publish) {
          throw new Error(
            `world(): scenario "${scenario.id}" needs a pre-publish, but no seed publisher is ` +
              `registered for protocol "${protocol}" (its clients/<protocol>.ts must be imported ` +
              'before the test runs, which registers it as a side effect of module load)',
          );
        }

        // A `reuseCoordinates` scenario pre-publishes the SAME coordinate its own publish targets
        // (that redeploy, allowed or refused, is what it tests). Every other pre-publish scenario
        // pre-publishes at a SEPARATE coordinate, so the scenario's own (doomed) publish lands on a
        // fresh, never-before-seen version. Since RPS-1174 a redeploy of an existing version is
        // judged by the releases/snapshots switches exactly like a first deploy, so the separate
        // coordinate no longer decides which rule refuses (the redeploy-* scenarios cover that
        // case); it keeps maven-releases-off/snapshots-off about a genuinely new version, and gives
        // their "nothing was stored" check a coordinate that must not exist at all.
        const seedTarget: Coordinates = scenario.reuseCoordinates
          ? publishTarget
          : { packageName, version: uniqueVersion(versionType) };

        world = { ...world, consumeTarget: seedTarget };

        // Pre-published with a repo that still has its default, permissive settings: releases and
        // snapshots off are a repo-wide write rule, not a permission check, so even admin could not
        // publish here once the scenario's real settings (applied right below) are in effect. The
        // artifact has to land in storage before that restriction exists.
        const seeded = await publish({
          ...world,
          credential: ADMIN_CREDENTIAL,
          publishTarget: seedTarget,
        });
        world = { ...world, seeded };
      }

      await seeder.setSettings(repo.name, {
        privateRepo: scenario.repo.privateRepo,
        allowOverride: scenario.repo.allowOverride ?? true,
        releases: scenario.repo.releases ?? true,
        snapshots: scenario.repo.snapshots ?? true,
      });

      return world;
    };

    await use(factory);
  },
});

export { expect } from '@playwright/test';
