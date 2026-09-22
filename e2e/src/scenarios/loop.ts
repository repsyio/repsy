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
 * The scenario-driven publish/consume loop (plan section "Client adapters", the `for (const s of
 * scenariosFor(protocol))` loop), generalised (step 3a, RPS-294) out of what was
 * `tests/maven/publish-consume.spec.ts` so every protocol adapter (`ProtocolAdapter`,
 * `scenarios/adapter.ts`) can reuse it verbatim: `registerPublishConsumeLoop(mavenAdapter)`,
 * `registerPublishConsumeLoop(npmAdapter)`, and so on. The test titles (`${adapter.protocol} >
 * ${scenario.id}`) and describe-block names (`${adapter.protocol} publish/consume[ (negative)]`)
 * keep the exact shape the maven suite always had, so its JUnit/HTML report is unchanged.
 *
 * Every catalog scenario that applies to a protocol publishes and then consumes with the scenario's
 * credential, asserting the `Outcome` `expectationFor(scenario, adapter.protocol)` pins. Beyond the
 * pinned `Outcome`, every scenario also checks what a status alone cannot: the real client's exit
 * code agrees with the outcome (a refused publish fails the client, an accepted one does not); a
 * refused publish left the repository exactly as it was (`adapter.expectNothingStored`); and a
 * consumer that succeeded got the very artifact that was published (`expectResolvedContent`). A
 * successful round trip additionally runs `adapter.afterSuccessfulRoundTrip`, when the adapter has
 * one (maven: follow a SNAPSHOT through its version-level `maven-metadata.xml`).
 *
 * `adapter.knownConsumeFailure` (RPS-1205's routing-around hook) runs after the consume side's raw
 * outcome is asserted but before the client-exit-code and content-equality checks on that side: it
 * never weakens the outcome assertion (still the real, probed HTTP status) or the publish-side
 * checks, only marks the remaining consume-side assertions as an expected failure via
 * `test.fail(true, ...)` when the adapter names one. Maven has no such hook.
 *
 * `adapter.knownPublishSideEffect` (step 3b, cargo's storage-before-DB-check routing-around hook)
 * runs right before `adapter.expectNothingStored`, the symmetric case on the PUBLISH side: the
 * outcome and client-exit-code assertions for the publish still ran and were asserted for real, only
 * the "nothing changed" comparison becomes an expected failure when the adapter names one.
 *
 * Remote hardening (plan section "Execution targets", "Remote specifics"): on a `remote` target,
 * `@local-only` scenarios are skipped, and `@negative` scenarios run serially, each reserving a slot
 * from a `RemoteAuthBudget` (one per protocol, scoped to this function's closure) first, so their
 * failed-auth attempts stay under the server's own throttle instead of tripping it for every client
 * behind the same address.
 */
import type { AdapterResult, ProtocolAdapter } from './adapter.js';
import { SCENARIOS } from './catalog.js';
import { expect, test } from './fixtures.js';
import { RemoteAuthBudget } from './remote-throttle.js';
import type { Outcome, Scenario } from './types.js';
import { expectationFor, scenariosFor } from './types.js';
import { target } from '../target.js';
import type { World } from './world.js';

function expectOutcome(adapter: ProtocolAdapter, result: AdapterResult, expected: Outcome): void {
  expect(
    result.outcome,
    `expected "${expected}", got "${result.outcome}" (http ${result.httpStatus}; ` +
      `${adapter.client.name} exit ${result.clientExitCode}; ${result.command})`,
  ).toBe(expected);
}

/**
 * The real client's exit code must agree with the outcome the raw HTTP status gave: a refused
 * publish or a refused resolve fails the real client, an accepted one does not. It cannot say
 * *which* refusal it was, but it catches a raw probe and a real client disagreeing about what the
 * server did.
 */
function expectClientAgrees(
  adapter: ProtocolAdapter,
  result: AdapterResult,
  expected: Outcome,
  what: string,
): void {
  const shouldSucceed = expected === 'ok';
  expect(
    result.clientExitCode === 0,
    `the real ${adapter.client.name} ${what} should ${shouldSucceed ? 'succeed' : 'fail'} for ` +
      `"${expected}" (${adapter.client.name} exit ${result.clientExitCode}; http ` +
      `${result.httpStatus}; ${result.command})`,
  ).toBe(shouldSucceed);
}

/**
 * A consumer that succeeded got the artifact of the latest accepted publish of the coordinate:
 * this test's own publish if it was accepted, else the one the fixture pre-published (a refused
 * redeploy must not have changed what consumers get).
 */
function expectResolvedContent(w: World, published: AdapterResult, resolved: AdapterResult): void {
  const expected = published.outcome === 'ok' ? published.contentSha256 : w.seeded?.contentSha256;
  expect(
    expected,
    'no digest of the published artifact to compare the resolved one with',
  ).toBeDefined();
  expect(
    resolved.contentSha256,
    `the resolved artifact (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
  ).toBe(expected);
}

/** Registers the whole scenario catalog for `adapter.protocol`, as two `test.describe` blocks. */
export function registerPublishConsumeLoop<F>(adapter: ProtocolAdapter<F>): void {
  const remoteAuthBudget = new RemoteAuthBudget();

  /** Publishes, then resolves, and checks everything the catalog pins for `scenario`. */
  async function runScenario(w: World, scenario: Scenario): Promise<void> {
    const expectation = expectationFor(scenario, adapter.protocol);

    // The state a refused publish must leave untouched: taken after the fixture seeded and
    // configured the repo, right before the scenario's own publish.
    const before = expectation.publish === 'ok' ? undefined : await adapter.fingerprint(w);

    const published = await adapter.publish(w);
    expectOutcome(adapter, published, expectation.publish);
    expectClientAgrees(adapter, published, expectation.publish, adapter.client.publishVerb);
    if (before !== undefined) {
      const knownSideEffect = adapter.knownPublishSideEffect?.(scenario);
      if (knownSideEffect) {
        test.fail(true, knownSideEffect);
      }
      await adapter.expectNothingStored(w, before);
    }

    const resolved = await adapter.resolve(w);
    expectOutcome(adapter, resolved, expectation.consume);

    // RPS-1205-style routing-around: only the remaining consume-side checks (client exit code,
    // content equality) become an expected failure -- the raw-probe-based outcome above already ran
    // and was asserted for real.
    const knownFailure = adapter.knownConsumeFailure?.(scenario);
    if (knownFailure) {
      test.fail(true, knownFailure);
    }

    expectClientAgrees(adapter, resolved, expectation.consume, adapter.client.consumeVerb);

    if (expectation.consume === 'ok') {
      expectResolvedContent(w, published, resolved);
    }
    if (expectation.publish === 'ok' && expectation.consume === 'ok') {
      await adapter.afterSuccessfulRoundTrip?.(w, published, resolved);
    }
  }

  function registerScenario(scenario: Scenario): void {
    test(`${adapter.protocol} > ${scenario.id}`, { tag: [...scenario.tags] }, async ({ world }) => {
      test.skip(
        target.isRemote && scenario.tags.includes('@local-only'),
        'a @local-only scenario is skipped on a remote target',
      );

      if (target.isRemote && scenario.tags.includes('@negative')) {
        await remoteAuthBudget.reserve();
      }

      await runScenario(await world(scenario, adapter), scenario);
    });
  }

  const scenarios = scenariosFor(SCENARIOS, adapter.protocol);
  const negativeScenarios = scenarios.filter((scenario) => scenario.tags.includes('@negative'));
  const otherScenarios = scenarios.filter((scenario) => !scenario.tags.includes('@negative'));

  test.describe(`${adapter.protocol} publish/consume`, () => {
    for (const scenario of otherScenarios) {
      registerScenario(scenario);
    }
  });

  test.describe(`${adapter.protocol} publish/consume (negative)`, () => {
    // Parallel on local/ci (the stack's own throttle is raised for exactly this, see
    // docker-compose.stack.yml); serial on remote, where it cannot be, so these scenarios' failed-auth
    // attempts spend the RemoteAuthBudget one at a time instead of bursting together.
    test.describe.configure({ mode: target.isRemote ? 'serial' : 'parallel' });

    for (const scenario of negativeScenarios) {
      registerScenario(scenario);
    }
  });
}
