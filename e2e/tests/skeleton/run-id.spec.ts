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

import { expect, test } from '@playwright/test';

import { perTestRunId, userName, workerTestRunId } from '../../src/seed/run-id.js';

test.describe('workerTestRunId (RPS-2581)', () => {
  test('a replacement worker on the same slot gets other ids than the worker it replaced', async () => {
    // Playwright keeps parallelIndex for the slot but hands the new process a new workerIndex, and its counter starts at 1.
    const deadWorker = workerTestRunId('abc123', 2, 6);
    const replacement = workerTestRunId('abc123', 17, 6);

    expect(replacement).not.toBe(deadWorker);
    expect(userName(replacement, 1)).not.toBe(userName(deadWorker, 1));
  });

  test('stays clear of the fixed worker slots specs pass to perTestRunId by hand', async () => {
    const fixed = [0, 1, 2, 3, 4, 35, 37].map((slot) => perTestRunId('abc123', slot, 1));

    for (let workerIndex = 0; workerIndex < 200; workerIndex++) {
      expect(fixed).not.toContain(workerTestRunId('abc123', workerIndex, 1));
    }
  });

  test('keeps the longest seeded names inside the 25 character limits', async () => {
    const runId = workerTestRunId('abc123', 40, 999);

    expect(() => userName(runId, 12)).not.toThrow();
    expect(`e2e-${runId}-docker-12`.length).toBeLessThanOrEqual(25);
  });
});
