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

const RUN_ID = 'abc123';

test.describe('workerTestRunId (RPS-2581)', () => {
  test('a replacement worker on the same slot gets other ids than the worker it replaced', async () => {
    // Playwright keeps parallelIndex for the slot but hands the new process a new workerIndex, and its counter starts at 1.
    const deadWorker = workerTestRunId(RUN_ID, 2, 6);
    const replacement = workerTestRunId(RUN_ID, 38, 6);

    expect(replacement).not.toBe(deadWorker);
    expect(userName(replacement, 1)).not.toBe(userName(deadWorker, 1));
  });

  test('never repeats an id over many workers and tests', async () => {
    const seen = new Set<string>();
    for (let workerIndex = 0; workerIndex < 300; workerIndex++) {
      for (let testSeq = 1; testSeq <= 50; testSeq++) {
        seen.add(workerTestRunId(RUN_ID, workerIndex, testSeq));
      }
    }

    expect(seen.size).toBe(300 * 50);
  });

  test('keeps the character after the run id different for workers that run at once', async () => {
    // Cloud's collaboratorName takes that character, so two live workers must not share it.
    const live = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 51, 52, 60];
    const slots = live.map((workerIndex) => workerTestRunId(RUN_ID, workerIndex, 1)[RUN_ID.length]);

    expect(new Set(slots).size).toBe(live.length);
  });

  test('keeps the id as long as perTestRunId writes it, so seeded names stay inside the 25 character limits', async () => {
    const runId = workerTestRunId(RUN_ID, 250, 1295);

    expect(runId).toHaveLength(perTestRunId(RUN_ID, 0, 0).length);
    expect(() => userName(runId, 12)).not.toThrow();
    expect(`e2e-${runId}-docker-12`.length).toBeLessThanOrEqual(25);
  });

  test('refuses a test or a worker it has no id for instead of repeating one', async () => {
    expect(() => workerTestRunId(RUN_ID, 0, 1296)).toThrow(/No run id left/);
    expect(() => workerTestRunId(RUN_ID, 36 * 36, 1)).toThrow(/No run id left/);
  });
});
