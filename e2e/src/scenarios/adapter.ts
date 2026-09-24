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
 * `ProtocolAdapter` (step 3a, RPS-294): what `scenarios/loop.ts`'s scenario loop needs from a
 * protocol client to run the whole catalog against it, generalised out of `clients/maven.ts` so a
 * second protocol (npm, then Cargo/NuGet/...) can reuse the loop verbatim instead of copying it.
 * No adapter registry: the object is passed directly into `fixtures.ts`'s `world` factory and into
 * `registerPublishConsumeLoop`, so there is no module-load-order dependency the old
 * `registerSeedPublisher` registry had (see `world.ts`'s file header).
 *
 * No `unsupportedReason`: a scenario that does not apply to a protocol restricts itself via the
 * catalog's own `protocols` field (`scenariosFor`), so an adapter never needs to say "I can't do
 * this one". No cache-volume hook either: that is Maven-specific (a shared `.m2` read-only tail
 * repo) and stays entirely inside `clients/maven.ts`/`docker-compose.runners.yml`; npm needs no
 * such thing (its test packages declare no dependencies, so there is nothing third-party to cache).
 */
import type { Scenario } from './types.js';
import type { SeedResult, World } from './world.js';

/**
 * A normalised result a protocol adapter returns for one publish or consume attempt, derived from
 * the raw HTTP status the request actually got (see `outcomeForStatus`), not from a client's exit
 * code alone: a real client's exit code says "it worked" or "it didn't", not which of these it was.
 */
export interface AdapterResult {
  outcome: import('./types.js').Outcome;
  httpStatus: number;
  clientExitCode: number;
  /** The (redacted) command line the real client ran, useful in assertion failure messages. */
  command: string;
  /**
   * sha256 of the primary artifact file this call handled: for `publish`, the file the adapter
   * built and sent (every publish packs a fresh random marker, so two publishes never share a
   * digest); for `resolve`, the file that landed in the consumer's clean local cache. Comparing the
   * two proves the consumer got the very bytes that were published, not merely "something".
   * `undefined` when there was no such file.
   */
  contentSha256?: string;
  /** `resolve` only: the file name of the resolved artifact (e.g. a timestamped name for a maven
   *  SNAPSHOT). */
  resolvedFile?: string;
}

/**
 * What a protocol's client adapter (`clients/maven-adapter.ts`, `clients/npm.ts`, ...) gives
 * `scenarios/loop.ts`'s `registerPublishConsumeLoop` to run the whole scenario catalog against that
 * protocol. `F` is the shape of a "fingerprint" of a repo's contents (maven: a `RepoTree`, a
 * path -> sha256 map; npm could be simpler), used only to prove a refused publish left the
 * repository untouched.
 */
export interface ProtocolAdapter<F = unknown> {
  /** Lower-case runner/service name, e.g. `'maven'` | `'npm'` -- also `adapter.protocol` is what
   *  `scenariosFor`/`expectationFor` filter and look up by. */
  readonly protocol: string;
  /** Used only in failure/log messages (e.g. "the real mvn deploy should succeed for ..."). */
  readonly client: { name: string; publishVerb: string; consumeVerb: string };

  packageName(runId: string, scenario: Scenario): string;
  version(versionType: 'release' | 'snapshot'): string;

  publish(world: World): Promise<AdapterResult>;
  resolve(world: World): Promise<AdapterResult>;
  /** The pre-publish for a scenario whose own credential cannot publish, or that redeploys a
   *  coordinate (`reuseCoordinates`): the real client only, no raw-HTTP companion probe. */
  seedPublish(world: World): Promise<SeedResult>;

  /** A snapshot of "what exists" in `world.repoName`, taken with the admin credential right before
   *  a refused publish, so `expectNothingStored` can prove nothing changed. */
  fingerprint(world: World): Promise<F>;
  expectNothingStored(world: World, before: F): Promise<void>;

  /** Runs only after BOTH the publish and the consume of one scenario were expected to (and did)
   *  succeed. Maven's own adapter uses this to follow a SNAPSHOT through its version-level
   *  `maven-metadata.xml`; a protocol with nothing extra to check leaves it out. */
  afterSuccessfulRoundTrip?(
    world: World,
    published: AdapterResult,
    resolved: AdapterResult,
  ): Promise<void>;

  /**
   * A known, already-filed backend bug that makes this scenario's CONSUME side fail for reasons
   * unrelated to the scenario itself. Returning a string routes the loop's final client-exit-code
   * and content-equality assertions on the consume side through `test.fail(true, <that string>)`
   * instead of weakening them; the earlier assertions (the publish-side pins, and the raw
   * packument/status check on the consume side) are never affected. npm's RPS-1205 (`fixTarballUrl`
   * misrewriting the tarball path on Repsy OS's single-tenant layout) is fixed, so no adapter
   * currently implements this -- maven never had a bug here either.
   */
  knownConsumeFailure?(scenario: Scenario): string | undefined;

  /**
   * A known, already-filed backend bug that makes a REFUSED publish still change what is stored.
   * Returning a string routes the loop's `adapter.expectNothingStored` call through
   * `test.fail(true, <that string>)` instead of a plain assertion; the outcome and client-exit-code
   * assertions right before it are never affected. No adapter uses it at the moment: cargo's
   * storage-before-DB bug that introduced it (RPS-1124) is fixed, so every adapter leaves this out
   * and asserts `expectNothingStored` for real. Keep it for the next such bug.
   */
  knownPublishSideEffect?(scenario: Scenario): string | undefined;
}
