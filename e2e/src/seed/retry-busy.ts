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

import { isPanelHttpStatus } from '../api/panel-backend.js';

const SERVICE_UNAVAILABLE = 503;

/** Tries before giving up: 250 + 500 + 1000 + 2000 + 2000 ms of waiting, about 5.75 s in all. */
const MAX_ATTEMPTS = 6;
const BASE_DELAY_MS = 250;
const MAX_DELAY_MS = 2_000;

export interface BusyRetryOptions {
  maxAttempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  /** Injected by the unit test, so it does not really wait. */
  sleep?: (ms: number) => Promise<void>;
}

/**
 * Runs a panel call that deletes something and repeats it, with a bounded exponential backoff, while
 * the panel answers 503. The API documents that 503 (`resourceBusy`: a row is locked by another
 * request or the database picked this one as a deadlock loser) is transient and that repeating the
 * request is expected to work; a cleanup that gave up on the first one left an `e2e-*` repository
 * behind and turned the leak check red although every test passed (RPS-2520). Any other error, and
 * a 503 that outlasts the attempts, is thrown as is. The generated client does not expose response
 * headers, so a `Retry-After` the panel sends cannot be read here; the backoff stands in for it.
 */
export async function retryWhileBusy<T>(
  operation: () => Promise<T>,
  opts: BusyRetryOptions = {},
): Promise<T> {
  const maxAttempts = opts.maxAttempts ?? MAX_ATTEMPTS;
  const baseDelayMs = opts.baseDelayMs ?? BASE_DELAY_MS;
  const maxDelayMs = opts.maxDelayMs ?? MAX_DELAY_MS;
  const sleep = opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));

  for (let attempt = 1; ; attempt++) {
    try {
      return await operation();
    } catch (err) {
      if (attempt >= maxAttempts || !isPanelHttpStatus(err, SERVICE_UNAVAILABLE)) {
        throw err;
      }
      await sleep(Math.min(baseDelayMs * 2 ** (attempt - 1), maxDelayMs));
    }
  }
}
