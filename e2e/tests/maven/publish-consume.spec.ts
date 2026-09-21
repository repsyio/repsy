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
 * The scenario-driven maven suite (plan section "Client adapters", the `for (const s of
 * scenariosFor('maven'))` loop): every catalog scenario that applies to maven publishes and then
 * consumes with the scenario's credential, asserting the `Outcome` the catalog pins (`catalog.ts` --
 * every one of those was pinned by probing a running instance, not assumed from the plan).
 *
 * Remote hardening (plan section "Execution targets", "Remote specifics"): on a `remote` target,
 * `@local-only` scenarios are skipped (none exist in this catalog yet, but the guard is here for
 * when one does), and `@negative` scenarios run serially, each reserving a slot from
 * `RemoteAuthBudget` first, so their failed-auth attempts stay under the server's own throttle
 * instead of tripping it for every client behind the same address.
 */
import * as maven from '../../src/clients/maven.js';
import type { AdapterResult } from '../../src/clients/maven.js';
import { RemoteAuthBudget } from '../../src/scenarios/remote-throttle.js';
import { SCENARIOS } from '../../src/scenarios/catalog.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import { scenariosFor, type Outcome } from '../../src/scenarios/types.js';
import { target } from '../../src/target.js';

const PROTOCOL = 'maven';

const remoteAuthBudget = new RemoteAuthBudget();

function expectOutcome(result: AdapterResult, expected: Outcome): void {
  expect(
    result.outcome,
    `expected "${expected}", got "${result.outcome}" (http ${result.httpStatus}; mvn exit ` +
      `${result.clientExitCode}; ${result.command})`,
  ).toBe(expected);
}

function registerScenario(scenario: Scenario): void {
  test(`maven > ${scenario.id}`, { tag: [...scenario.tags] }, async ({ world }) => {
    test.skip(
      target.isRemote && scenario.tags.includes('@local-only'),
      'a @local-only scenario is skipped on a remote target',
    );

    if (target.isRemote && scenario.tags.includes('@negative')) {
      await remoteAuthBudget.reserve();
    }

    const w = await world(scenario, PROTOCOL);

    expectOutcome(await maven.publish(w), scenario.expect.publish);
    expectOutcome(await maven.resolve(w), scenario.expect.consume);
  });
}

const scenarios = scenariosFor(SCENARIOS, PROTOCOL);
const negativeScenarios = scenarios.filter((scenario) => scenario.tags.includes('@negative'));
const otherScenarios = scenarios.filter((scenario) => !scenario.tags.includes('@negative'));

test.describe('maven publish/consume', () => {
  for (const scenario of otherScenarios) {
    registerScenario(scenario);
  }
});

test.describe('maven publish/consume (negative)', () => {
  // Parallel on local/ci (the stack's own throttle is raised for exactly this, see
  // docker-compose.stack.yml); serial on remote, where it cannot be, so these scenarios' failed-auth
  // attempts spend the RemoteAuthBudget one at a time instead of bursting together.
  test.describe.configure({ mode: target.isRemote ? 'serial' : 'parallel' });

  for (const scenario of negativeScenarios) {
    registerScenario(scenario);
  }
});
