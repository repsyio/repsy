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
  repo: RepoSettingsSpec;
  credential: CredentialKind;
  /** Forces a RELEASE or SNAPSHOT coordinate; adapters that don't distinguish ignore it. */
  versionType?: 'release' | 'snapshot';
  expect: ScenarioExpectation;
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
