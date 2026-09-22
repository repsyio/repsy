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
 * module, separate from `fixtures.ts`, so a protocol adapter (`clients/maven-adapter.ts`,
 * `clients/npm.ts`, ...) can import the type without also pulling in `@playwright/test`'s fixture
 * machinery. The seed-publisher registry that used to live here (`registerSeedPublisher`/
 * `seedPublisherFor`) is gone: a `ProtocolAdapter` (`scenarios/adapter.ts`) carries its own
 * `seedPublish` directly, so there is no module-load-order dependency on a protocol's client module
 * having been imported first.
 */
import type { Scenario } from './types.js';

/**
 * The credential a scenario resolved to, in the shape every protocol's Basic-auth-based adapter
 * needs. `transport` is `undefined` for the `anonymous` credential: no `Authorization` header is
 * sent at all, which is a different thing from sending one with empty/wrong values. `kind`
 * distinguishes a real user/admin password from a deploy token for a protocol whose raw client
 * speaks more than one auth scheme over the same "username + secret" shape (npm's `_authToken`
 * vs. `_auth`); it is left out for the `anonymous` credential, which has neither.
 */
export interface MaterializedCredential {
  transport?: 'basic';
  username?: string;
  password?: string;
  kind?: 'password' | 'token';
}

/** A protocol-appropriate package identity: for maven, `groupId:artifactId` plus a version. */
export interface Coordinates {
  /** For maven this is `groupId:artifactId`; other protocols use their own bare package name
   *  instead (npm: a possibly-scoped bare name, `name` or `@scope/name`, never a `groupId:...`
   *  pair). */
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
