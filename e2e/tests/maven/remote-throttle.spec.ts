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
 * The "small sanity check" the plan's "Remote specifics" section asks for: pins `RemoteAuthBudget`
 * and `withBackoff429`'s accounting with an injected fake clock/sleep, so it runs in milliseconds
 * and needs no server (remote or otherwise) to exercise against.
 */
import { RemoteAuthBudget, withBackoff429 } from '../../src/scenarios/remote-throttle.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

test.describe('RemoteAuthBudget', () => {
  test('lets requests through under the budget without waiting', async () => {
    let now = 0;
    const sleeps: number[] = [];
    const budget = new RemoteAuthBudget({
      maxFailures: 2,
      windowMs: 1_000,
      clock: () => now,
      sleep: async (ms) => {
        sleeps.push(ms);
        now += ms;
      },
    });

    await budget.reserve();
    await budget.reserve();

    expect(sleeps).toEqual([]);
  });

  test('waits out the rest of the window once the budget is spent', async () => {
    let now = 0;
    const sleeps: number[] = [];
    const budget = new RemoteAuthBudget({
      maxFailures: 2,
      windowMs: 1_000,
      clock: () => now,
      sleep: async (ms) => {
        sleeps.push(ms);
        now += ms;
      },
    });

    await budget.reserve(); // t=0
    now = 300;
    await budget.reserve(); // t=300, still 2 in the window

    now = 400;
    await budget.reserve(); // over budget: must wait until the first reservation (t=0) ages out

    expect(sleeps).toEqual([600]);
  });

  test('a reservation older than the window is not counted against a later one', async () => {
    let now = 0;
    const sleeps: number[] = [];
    const budget = new RemoteAuthBudget({
      maxFailures: 1,
      windowMs: 1_000,
      clock: () => now,
      sleep: async (ms) => {
        sleeps.push(ms);
        now += ms;
      },
    });

    await budget.reserve(); // t=0
    now = 1_500; // window has fully elapsed
    await budget.reserve(); // must not wait: the only prior reservation has aged out

    expect(sleeps).toEqual([]);
  });
});

test.describe('withBackoff429', () => {
  test('returns the first status when it is not 429', async () => {
    const status = await withBackoff429(async () => 401);
    expect(status).toBe(401);
  });

  test('retries once, after a delay, on a 429', async () => {
    const sleeps: number[] = [];
    let calls = 0;
    const status = await withBackoff429(
      async () => {
        calls += 1;
        return calls === 1 ? 429 : 200;
      },
      { retryAfterMs: 5, sleep: async (ms) => void sleeps.push(ms) },
    );

    expect(status).toBe(200);
    expect(calls).toBe(2);
    expect(sleeps).toEqual([5]);
  });

  test('does not retry a second time on a repeated 429', async () => {
    let calls = 0;
    const status = await withBackoff429(
      async () => {
        calls += 1;
        return 429;
      },
      { retryAfterMs: 5, sleep: async () => undefined },
    );

    expect(status).toBe(429);
    expect(calls).toBe(2);
  });
});
