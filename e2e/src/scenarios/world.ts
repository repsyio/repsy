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
 * `World`: what a scenario turns into once seeded (plan section "Scenario model"). Kept in its own
 * module, separate from `fixtures.ts`, so a protocol adapter (`clients/maven.ts`) can import the
 * type and register its seed publisher without also pulling in `@playwright/test`'s fixture
 * machinery.
 */
import type { Scenario } from './types.js';

/**
 * The credential a scenario resolved to, in the shape every protocol's Basic-auth-based adapter
 * needs. `transport` is `undefined` for the `anonymous` credential: no `Authorization` header is
 * sent at all, which is a different thing from sending one with empty/wrong values.
 */
export interface MaterializedCredential {
  transport?: 'basic';
  username?: string;
  password?: string;
}

/** A protocol-appropriate package identity: for maven, `groupId:artifactId` plus a version. */
export interface Coordinates {
  /** For maven this is `groupId:artifactId`; other protocols (npm, cargo, ...) would use their own
   *  bare package name once their adapters exist. */
  packageName: string;
  /** Carries `-SNAPSHOT` when `scenario.versionType === 'snapshot'`. */
  version: string;
}

/** A ready-to-test scenario: a seeded repo, a materialised credential, and package coordinates. */
export interface World {
  scenario: Scenario;
  protocol: string;
  repoName: string;
  credential: MaterializedCredential;
  /** What this test's own `publish()` call targets; always unique to this one test. */
  publishTarget: Coordinates;
  /**
   * What this test's own `resolve()`/consume call targets. Equal to `publishTarget` unless the
   * scenario's own publish is expected to fail (see `fixtures.ts`'s `world` fixture): then this is
   * a *separate* coordinate an admin pre-published, so the scenario's own (doomed) attempt is a
   * genuine first deploy of a version that does not exist yet -- the case `maven-releases-off`/
   * `maven-snapshots-off` are about. A scenario with `reuseCoordinates` is the deliberate exception:
   * `consumeTarget === publishTarget`, and the pre-publish put the very coordinate the scenario's
   * own publish then redeploys (`no-override`/`override`, the `redeploy-*` and `snapshot-redeploy*`
   * scenarios).
   */
  consumeTarget: Coordinates;
  /**
   * What the pre-publish stored, when the scenario had one: lets a spec assert that a refused
   * redeploy left the pre-published content in place (`consumeTarget`'s content is still this).
   */
  seeded?: SeedResult;
}

/** What a seed publisher reports about the artifact it stored. */
export interface SeedResult {
  /** sha256 of the primary artifact file, when the adapter can tell (see `AdapterResult`). */
  contentSha256?: string;
}

/**
 * Publishes `world`'s package with an already-authorized (normally admin) credential, for a
 * scenario whose own credential cannot publish but is still expected to consume successfully, or
 * that redeploys a coordinate (`reuseCoordinates`) -- see `fixtures.ts`'s `world` fixture:
 * "pre-publish" scenarios. A protocol adapter registers its own
 * implementation via `registerSeedPublisher` when its module loads.
 */
export type SeedPublisher = (world: World) => Promise<SeedResult>;

const seedPublishers = new Map<string, SeedPublisher>();

/** Registers `publisher` as the way to pre-seed a package for `protocol`'s "pre-publish" scenarios. */
export function registerSeedPublisher(protocol: string, publisher: SeedPublisher): void {
  seedPublishers.set(protocol, publisher);
}

/** Looks up the seed publisher `protocol` registered, if its adapter module has been imported. */
export function seedPublisherFor(protocol: string): SeedPublisher | undefined {
  return seedPublishers.get(protocol);
}
