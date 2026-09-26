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
 * adapter)`), not a single resolved value, because one spec calls it once per scenario in its own
 * loop (see `scenarios/loop.ts`'s `registerPublishConsumeLoop`).
 *
 * Step 3a (RPS-294) generalised this from a hardcoded maven `protocol` string to a `ProtocolAdapter`
 * object (`scenarios/adapter.ts`): coordinates come from `adapter.packageName`/`adapter.version`,
 * and a pre-publish runs through `adapter.seedPublish` instead of the old
 * `registerSeedPublisher`/`seedPublisherFor` registry.
 */
import { test as base } from '@playwright/test';

import { PanelApi, RepoType } from '../api/panel-api.js';
import { env } from '../env.js';
import { perTestRunId } from '../seed/run-id.js';
import { Seeder } from '../seed/seeder.js';
import type { ProtocolAdapter } from './adapter.js';
import type { CredentialKind, Scenario } from './types.js';
import { expectationFor } from './types.js';
import type { Coordinates, MaterializedCredential, World } from './world.js';

export type { Coordinates, World, MaterializedCredential } from './world.js';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

const ADMIN_CREDENTIAL: MaterializedCredential = {
  transport: 'basic',
  username: env.adminUsername,
  password: env.adminPassword,
  kind: 'password',
};

/** Lower-case runner/service name -> the RepoType its repos are created as. */
const REPO_TYPE_BY_PROTOCOL: Record<string, RepoType> = {
  maven: RepoType.MAVEN,
  // The Gradle client (RPS-133), one adapter per build file language: an ordinary Maven repo.
  'gradle-groovy': RepoType.MAVEN,
  'gradle-kotlin': RepoType.MAVEN,
  // A Gradle plugin published to and applied from a Maven repo (RPS-133), one adapter per language.
  'gradle-plugin-groovy': RepoType.MAVEN,
  'gradle-plugin-kotlin': RepoType.MAVEN,
  // The sbt client (RPS-134): publishes to and resolves from an ordinary Maven repo.
  sbt: RepoType.MAVEN,
  // The Apache Ivy client (RPS-135, an Ant build with ivy:publish and ivy:retrieve): an ordinary Maven repo.
  ivy: RepoType.MAVEN,
  npm: RepoType.NPM,
  pypi: RepoType.PYPI,
  docker: RepoType.DOCKER,
  cargo: RepoType.CARGO,
  nuget: RepoType.NUGET,
  golang: RepoType.GOLANG,
  helm: RepoType.HELM,
  // The classic (ChartMuseum-protocol) Helm adapter (step 4b): a SEPARATE protocol key so the
  // shared catalog is run once per Helm mode (`helm.ts`/`helm-classic.ts`'s file headers), but the
  // same underlying `RepoType.HELM` repo -- Repsy implements both protocols on one repo/port.
  'helm-classic': RepoType.HELM,
  ruby: RepoType.RUBY,
};

export function repoTypeForProtocol(protocol: string): RepoType {
  const repoType = REPO_TYPE_BY_PROTOCOL[protocol];
  if (!repoType) {
    throw new Error(`world(): unknown protocol "${protocol}"`);
  }
  return repoType;
}

async function materializeCredential(
  seeder: Seeder,
  scenario: Scenario,
  repoName: string,
  repoType: RepoType,
): Promise<MaterializedCredential> {
  return materializeCredentialKind(seeder, scenario.credential, repoName, repoType);
}

/**
 * Turns a `CredentialKind` into a credential that exists (a seeded user, a token of `repoName`...),
 * for a caller that has no `Scenario`: the manage matrix (`manage-matrix.ts`, RPS-1475) runs one
 * operation with each credential of one repo.
 */
export async function materializeCredentialKind(
  seeder: Seeder,
  kind: CredentialKind,
  repoName: string,
  repoType: RepoType,
): Promise<MaterializedCredential> {
  switch (kind) {
    case 'admin-password':
      return ADMIN_CREDENTIAL;

    case 'user-password': {
      const user = await seeder.createUser();
      return {
        transport: 'basic',
        username: user.username,
        password: user.password,
        kind: 'password',
      };
    }

    case 'token-rw': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-ro': {
      const token = await seeder.createToken(repoName, { readOnly: true });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-expired': {
      const token = await seeder.createToken(repoName, {
        readOnly: false,
        expirationDate: new Date(Date.now() - ONE_DAY_MS),
      });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-revoked': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      await seeder.revokeNow(repoName, token.id);
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-rotated-old': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      // The new value is discarded on purpose: this credential deliberately keeps using the old one.
      await seeder.rotateNow(repoName, token.id);
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-other-repo': {
      const otherRepo = await seeder.createRepo(repoType, { privateRepo: true });
      const token = await seeder.createToken(otherRepo.name, { readOnly: false });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'wrong-password':
      return {
        transport: 'basic',
        username: env.adminUsername,
        password: `${env.adminPassword}-wrong`,
        kind: 'password',
      };

    case 'anonymous':
      return {};
  }
}

export interface Fixtures {
  panelApi: PanelApi;
  seeder: Seeder;
  world: (scenario: Scenario, adapter: ProtocolAdapter) => Promise<World>;
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
    const factory = async (scenario: Scenario, adapter: ProtocolAdapter): Promise<World> => {
      const repoType = repoTypeForProtocol(adapter.protocol);
      const repo = await seeder.createRepo(repoType, { privateRepo: scenario.repo.privateRepo });

      const credential = await materializeCredential(seeder, scenario, repo.name, repoType);

      const versionType = scenario.versionType ?? 'release';
      const packageName = adapter.packageName(seeder.runId, scenario);
      const expectation = expectationFor(scenario, adapter.protocol);

      // A scenario whose own credential cannot publish but is still expected to consume
      // successfully (token-ro, anonymous-public, maven-releases-off/snapshots-off) needs something
      // already published first. A `reuseCoordinates` scenario is about a redeploy, so it always
      // pre-publishes: without an existing artifact its own publish would be an ordinary first
      // deploy and never exercise the rule it is meant to test (allowOverride, a switched-off kind).
      const needsPrePublish =
        scenario.reuseCoordinates === true ||
        (expectation.consume === 'ok' && expectation.publish !== 'ok');

      // Correction #2 (the plan): for a SEPARATE pre-publish coordinate, generate the SEED version
      // BEFORE the scenario's own version, not after -- otherwise the seed (used for consume-only
      // scenarios) never ends up numerically lower than the scenario's own doomed publish attempt,
      // and a real npm client refuses to publish a version lower than the highest one already
      // published without an explicit --tag. Assertion-neutral for maven (nothing there asserts the
      // relative order of the two coordinates).
      const seedVersionForSeparateCoordinate =
        needsPrePublish && !scenario.reuseCoordinates ? adapter.version(versionType) : undefined;
      const publishTarget: Coordinates = { packageName, version: adapter.version(versionType) };

      let world: World = {
        scenario,
        protocol: adapter.protocol,
        repoName: repo.name,
        credential,
        publishTarget,
        consumeTarget: publishTarget,
      };

      if (needsPrePublish) {
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
          : { packageName, version: seedVersionForSeparateCoordinate as string };

        world = { ...world, consumeTarget: seedTarget };

        // Pre-published with a repo that still has its default, permissive settings: releases and
        // snapshots off are a repo-wide write rule, not a permission check, so even admin could not
        // publish here once the scenario's real settings (applied right below) are in effect. The
        // artifact has to land in storage before that restriction exists.
        const seeded = await adapter.seedPublish({
          ...world,
          credential: ADMIN_CREDENTIAL,
          publishTarget: seedTarget,
        });
        world = { ...world, seeded };
      }

      await seeder.setSettings(repo.name, {
        privateRepo: scenario.repo.privateRepo,
        allowOverride: scenario.repo.allowOverride ?? true,
        // RPS-1210: only Maven and NuGet consult releases/snapshots; the settings PUT refuses them
        // (400 releasesSnapshotsUnsupported) for every other repo type.
        ...(supportsVersionAllowance(repoType)
          ? {
              releases: scenario.repo.releases ?? true,
              snapshots: scenario.repo.snapshots ?? true,
            }
          : {}),
      });

      return world;
    };

    await use(factory);
  },
});

/** The repo types whose publish path reads the `releases`/`snapshots` settings (RPS-1210). */
function supportsVersionAllowance(repoType: RepoType): boolean {
  return repoType === RepoType.MAVEN || repoType === RepoType.NUGET;
}

export { expect } from '@playwright/test';
