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
 * The Playwright fixtures every spec uses: an admin-authenticated `PanelBackend`, a per-test `Seeder`
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

import { createPanelBackend } from '../api/backend-registry.js';
import { isUnsupportedPanelOperation, type PanelBackend, RepoType } from '../api/panel-backend.js';
import { ownerCredential } from '../clients/raw-http.js';
import { env } from '../env.js';
import { perTestRunId } from '../seed/run-id.js';
import { Seeder } from '../seed/seeder.js';
import { target, type TargetCapabilities } from '../target.js';
import type { ProtocolAdapter } from './adapter.js';
import type { CredentialKind, Scenario } from './types.js';
import { expectationFor } from './types.js';
import type { Coordinates, MaterializedCredential, World } from './world.js';

export type { Coordinates, World, MaterializedCredential } from './world.js';

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

/**
 * Why `kind` cannot be materialised on a target with these `capabilities`, or `undefined` when it can
 * (RPS-1498). Decided from the capabilities alone, so a scenario is skipped before it seeds a repo.
 */
export function unsupportedCredentialReason(
  kind: CredentialKind,
  capabilities: TargetCapabilities,
): string | undefined {
  if (
    kind === 'user-password' &&
    !capabilities.supportsUserRole &&
    !capabilities.supportsRepoUsers
  ) {
    return 'the target has neither user roles nor repo users, so it has no user credential to seed';
  }
  if (kind === 'token-expired' && capabilities.expiredTokenStrategy === 'unsupported') {
    return 'the target has no way to seed an expired deploy token (expiredTokenStrategy: unsupported)';
  }
  return undefined;
}

/** The result of `tryMaterializeCredentialKind`: the credential, or why the test has to be skipped. */
export type MaterializeResult =
  | { credential: MaterializedCredential; skipReason?: undefined }
  | { skipReason: string; credential?: undefined };

/**
 * Turns a `CredentialKind` into a credential that exists, or says why it cannot on this target,
 * without skipping anything itself (`materializeCredentialKind` does that, and this stays testable
 * outside a running test). `user-password` and `token-expired` go through the backend
 * (`PanelBackend.seedUserCredential`/`seedExpiredTokenCredential`) because what they are differs by
 * product; an `UnsupportedPanelOperation` from any backend call becomes a skip reason too.
 */
export async function tryMaterializeCredentialKind(
  seeder: Seeder,
  kind: CredentialKind,
  repoName: string,
  repoType: RepoType,
  capabilities: TargetCapabilities = target,
): Promise<MaterializeResult> {
  const reason = unsupportedCredentialReason(kind, capabilities);
  if (reason !== undefined) {
    return { skipReason: `${kind}: ${reason}` };
  }
  try {
    return { credential: await seedCredential(seeder, kind, repoName, repoType) };
  } catch (err) {
    if (isUnsupportedPanelOperation(err)) {
      return { skipReason: `${kind}: ${err.message}` };
    }
    throw err;
  }
}

/**
 * Turns a `CredentialKind` into a credential that exists (a seeded user, a token of `repoName`...),
 * for a caller that has no `Scenario`: the manage matrix (`manage-matrix.ts`, RPS-1475) runs one
 * operation with each credential of one repo. A credential the target cannot seed skips the test
 * (`test.skip` with the reason, RPS-1498).
 */
export async function materializeCredentialKind(
  seeder: Seeder,
  kind: CredentialKind,
  repoName: string,
  repoType: RepoType,
  capabilities: TargetCapabilities = target,
): Promise<MaterializedCredential> {
  const result = await tryMaterializeCredentialKind(seeder, kind, repoName, repoType, capabilities);
  if (result.credential === undefined) {
    test.skip(true, result.skipReason);
    // `test.skip` throws to end the test; this is for the type checker only.
    throw new Error(result.skipReason);
  }
  return result.credential;
}

async function seedCredential(
  seeder: Seeder,
  kind: CredentialKind,
  repoName: string,
  repoType: RepoType,
): Promise<MaterializedCredential> {
  switch (kind) {
    case 'admin-password':
      return ownerCredential();

    case 'user-password':
      return seeder.backend.seedUserCredential({ seeder, repoName, repoType });

    case 'token-rw': {
      const token = await seeder.createToken(repoName, { readOnly: false });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-ro': {
      const token = await seeder.createToken(repoName, { readOnly: true });
      return { transport: 'basic', username: token.username, password: token.token, kind: 'token' };
    }

    case 'token-expired':
      return seeder.backend.seedExpiredTokenCredential({ seeder, repoName, repoType });

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
  panelApi: PanelBackend;
  seeder: Seeder;
  world: (scenario: Scenario, adapter: ProtocolAdapter) => Promise<World>;
}

export interface FixtureOptions {
  /**
   * The capabilities `world` decides with (which credentials to skip, which settings a repo type
   * takes): the target of the run. An option so a spec can stand a fake target in
   * (`test.use({ targetCapabilities })`, `tests/skeleton/cloud-target.spec.ts`).
   */
  targetCapabilities: TargetCapabilities;
}

let testSeqByWorker = 0;

export const test = base.extend<Fixtures & FixtureOptions>({
  targetCapabilities: [target, { option: true }],

  // eslint-disable-next-line no-empty-pattern
  panelApi: async ({}, use) => {
    const api = await createPanelBackend();
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

  world: async ({ seeder, panelApi, targetCapabilities }, use) => {
    const factory = async (scenario: Scenario, adapter: ProtocolAdapter): Promise<World> => {
      const repoType = repoTypeForProtocol(adapter.protocol);

      // Decided before anything is seeded: a scenario the target cannot run leaves no repo behind.
      const skipReason = unsupportedCredentialReason(scenario.credential, targetCapabilities);
      test.skip(skipReason !== undefined, skipReason);

      const repo = await seeder.createRepo(repoType, { privateRepo: scenario.repo.privateRepo });

      const credential = await materializeCredentialKind(
        seeder,
        scenario.credential,
        repo.name,
        repoType,
        targetCapabilities,
      );

      const versionType = scenario.versionType ?? 'release';
      const packageName = adapter.packageName(seeder.runId, scenario);
      const expectation = expectationFor(scenario, adapter.protocol, panelApi.expectByTarget);

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
          credential: ownerCredential(),
          publishTarget: seedTarget,
        });
        world = { ...world, seeded };
      }

      await seeder.setSettings(repo.name, {
        privateRepo: scenario.repo.privateRepo,
        allowOverride: scenario.repo.allowOverride ?? true,
        // RPS-1210: only Maven and NuGet consult releases/snapshots; the settings PUT refuses them
        // (400 releasesSnapshotsUnsupported) for every other repo type.
        ...(targetCapabilities.supportsVersionAllowanceSettings(repoType)
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

export { expect } from '@playwright/test';
