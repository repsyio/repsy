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

import { ERROR_CODES } from '../../src/error-codes.js';
import { PanelHttpError } from '../../src/api/panel-backend.js';
import { retryWhileBusy } from '../../src/seed/retry-busy.js';

const busy = () => new PanelHttpError(ERROR_CODES.RESOURCE_BUSY, 503);

test.describe('retryWhileBusy (RPS-2520)', () => {
  test('repeats a 503 with a growing, capped backoff until the call succeeds', async () => {
    const waits: number[] = [];
    let calls = 0;
    const result = await retryWhileBusy(
      async () => {
        calls++;
        if (calls < 5) {
          throw busy();
        }
        return 'deleted';
      },
      { sleep: async (ms) => void waits.push(ms) },
    );
    expect(result).toBe('deleted');
    expect(calls).toBe(5);
    expect(waits).toEqual([250, 500, 1000, 2000]);
  });

  test('gives up with the 503 once the attempts are spent', async () => {
    let calls = 0;
    await expect(
      retryWhileBusy(
        async () => {
          calls++;
          throw busy();
        },
        { maxAttempts: 3, sleep: async () => {} },
      ),
    ).rejects.toMatchObject({ status: 503 });
    expect(calls).toBe(3);
  });

  test('does not repeat any other error, a 404 included', async () => {
    let calls = 0;
    await expect(
      retryWhileBusy(
        async () => {
          calls++;
          throw new PanelHttpError('gone', 404);
        },
        { sleep: async () => {} },
      ),
    ).rejects.toMatchObject({ status: 404 });
    expect(calls).toBe(1);
  });
});
