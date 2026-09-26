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
 * The scenario model (plan section "Scenario model"): a scenario is data, not a test file. One
 * catalog (`catalog.ts`) is shared by every protocol; `scenariosFor(protocol)` filters to what that
 * protocol's adapter supports. `fixtures.ts` turns a scenario into a ready-to-test `World`, and a
 * protocol adapter (`clients/<protocol>.ts`) turns a `World` into a real client run.
 */

/** How the fixture materialises the credential a scenario names (see `fixtures.ts`). */
export type CredentialKind =
  | 'admin-password'
  | 'user-password'
  | 'token-rw'
  | 'token-ro'
  | 'token-expired'
  | 'token-revoked'
  | 'token-rotated-old'
  | 'token-other-repo'
  | 'wrong-password'
  | 'anonymous';

/**
 * A normalised result a protocol adapter returns for one publish or consume attempt, derived from
 * the raw HTTP status the request actually got (see `outcomeForStatus`), not from a client's exit
 * code alone: a real client's exit code says "it worked" or "it didn't", not which of these it was.
 */
export type Outcome = 'ok' | 'unauthorized' | 'forbidden' | 'conflict' | 'rejected';

/** Maps a raw HTTP status to the `Outcome` it represents. */
export function outcomeForStatus(status: number): Outcome {
  if (status >= 200 && status < 300) {
    return 'ok';
  }
  if (status === 401) {
    return 'unauthorized';
  }
  if (status === 403) {
    return 'forbidden';
  }
  if (status === 409) {
    return 'conflict';
  }
  return 'rejected';
}

export interface RepoSettingsSpec {
  privateRepo: boolean;
  /** Defaults to `true` (a freshly created repo's own default) when left out. */
  allowOverride?: boolean;
  /** Defaults to `true` when left out. */
  releases?: boolean;
  /** Defaults to `true` when left out. */
  snapshots?: boolean;
}

export interface ScenarioExpectation {
  publish: Outcome;
  consume: Outcome;
}

export interface Scenario {
  id: string;
  /** `@`-prefixed, enforced at the type level since ESLint's tag-format rule cannot check a value
   *  built at runtime from `scenariosFor()` (see eslint.config.js's `playwright/valid-test-tags`). */
  tags: readonly `@${string}`[];
  /**
   * The repo settings the scenario's own publish/consume run under. They are applied AFTER the
   * fixture's pre-publish (if the scenario has one), which runs against a freshly created repo's
   * permissive defaults: a scenario that switches a version kind off or forbids overriding therefore
   * still has its first deploy in place, which is exactly what the `redeploy-*-off` and
   * `no-override`/`snapshot-redeploy-no-override` scenarios need (RPS-1174).
   */
  repo: RepoSettingsSpec;
  credential: CredentialKind;
  /** Forces a RELEASE or SNAPSHOT coordinate; adapters that don't distinguish ignore it. */
  versionType?: 'release' | 'snapshot';
  /**
   * The scenario is about a REdeploy: the fixture pre-publishes (with admin, under the permissive
   * defaults) the very coordinate the scenario's own publish then targets, instead of a separate
   * one, and consume targets that same coordinate. Left out, a pre-publish (which only scenarios
   * expecting a refused publish and a successful consume get) lands on a separate coordinate so the
   * scenario's own attempt is a first deploy of a version that does not exist yet.
   */
  reuseCoordinates?: boolean;
  /** The shared, maven-pinned expectation. A protocol whose real behaviour differs overrides it
   *  via `expectByProtocol`, never by editing this field. */
  expect: ScenarioExpectation;
  /**
   * A per-protocol override of `expect`, keyed by `adapter.protocol` (e.g. `'npm'`). Lets a later
   * protocol (Cargo, NuGet, ...) pin its own real, probed status for a scenario whose outcome
   * differs from maven's, without touching the shared `expect` every other protocol still reads.
   * Read through `expectationFor`, never directly.
   */
  expectByProtocol?: Partial<Record<string, Partial<ScenarioExpectation>>>;
  /**
   * Restricts a scenario to specific protocols (lower-case runner/service names, e.g. `'maven'`).
   * Left out, the scenario applies to every protocol whose adapter registers support for it.
   */
  protocols?: string[];
}

/** Every scenario in the shared catalog, filtered to the ones `protocol` applies to. */
export function scenariosFor(catalog: readonly Scenario[], protocol: string): Scenario[] {
  return catalog.filter((scenario) => !scenario.protocols || scenario.protocols.includes(protocol));
}

/**
 * The outcomes a TARGET pins over the catalog's (RPS-1498), supplied by the target's `PanelBackend`
 * (`expectByTarget`) so a Repsy Cloud answer that differs from Repsy OS's never goes into `catalog.ts`.
 * Keyed by scenario id, then by protocol (`adapter.protocol`, e.g. `'maven'`) or `'*'` for every
 * protocol; only the sides named are overridden.
 */
export type ExpectationOverlay = Readonly<
  Record<string, Readonly<Record<string, Partial<ScenarioExpectation>>>>
>;

/**
 * `scenario`'s expectation for `protocol`: the shared, maven-pinned `expect`, with any
 * `expectByProtocol[protocol]` override merged on top (a scenario needing no override for a given
 * protocol, which is every scenario for npm in this step, has none), then the target's `overlay`
 * (`overlay[scenario.id]['*']`, then `overlay[scenario.id][protocol]`). The scenario loop and the
 * world fixture's "does this scenario need a pre-publish" check both read through this, never
 * `scenario.expect` directly, so a later protocol or target can differ without touching the shared
 * field. Without an overlay (every Repsy OS run) the result is exactly what it was before RPS-1498.
 */
export function expectationFor(
  scenario: Scenario,
  protocol: string,
  overlay?: ExpectationOverlay,
): ScenarioExpectation {
  const forScenario = overlay?.[scenario.id];
  return {
    ...scenario.expect,
    ...scenario.expectByProtocol?.[protocol],
    ...forScenario?.['*'],
    ...forScenario?.[protocol],
  };
}

/**
 * What a registry-changing operation needs (RPS-1475, the manage matrix in `manage-catalog.ts`):
 * `WRITE` is what publishing needs (an ADMIN or USER account, a read-write deploy token), `MANAGE`
 * removes stored files and only an ADMIN account has it (a deploy token never does, RPS-1424), and
 * `NO_ROUTE` marks a protocol that has no such wire operation at all, so every credential gets the
 * same answer and nothing changes.
 */
export type ManagePermission = 'WRITE' | 'MANAGE' | 'NO_ROUTE';

/** The credentials the manage matrix runs every operation with: the subset of `CredentialKind` that
 *  says who is asking, without the states of a token (expired, revoked, ...) the publish loop covers. */
export type ManageCredentialKind = Extract<
  CredentialKind,
  'admin-password' | 'user-password' | 'token-rw' | 'token-ro' | 'anonymous'
>;

/** A cell of the manage matrix whose observed answer is not what `ManagePermission` derives. */
export interface ManageCellOverride {
  allowed?: boolean;
  /** The HTTP status of a refused (or `NO_ROUTE`) cell; 401 when left out. */
  status?: number;
}
