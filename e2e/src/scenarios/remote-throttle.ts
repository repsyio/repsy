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
 * Remote hardening (plan section "Execution targets", "Remote specifics"): on a `remote` target the
 * harness cannot raise `AUTH_THROTTLE_MAX_FAILURES` the way `docker-compose.stack.yml` does for
 * `local`/`ci` (see `target.ts`'s `canTuneThrottle`), so a `@negative` scenario's failed-auth
 * attempts have to stay under the server's own budget (`AuthFailureThrottle`: 20 failures / 60s per
 * client, see `AGENTS.md`-adjacent facts in the plan) instead of tripping it and getting every
 * client behind the same address refused, including the harness's own admin login.
 *
 * `RemoteAuthBudget` is a client-side token bucket mirroring that limit: `reserve()` is called
 * once per `@negative` scenario before it runs (see `tests/maven/publish-consume.spec.ts`), and
 * waits out the window if the budget is already spent, rather than firing the request and finding
 * out from a 429. `clock`/`sleep` are injectable so `remote-throttle.spec.ts` can pin this
 * deterministically without a real 60-second wait.
 */
export interface RemoteAuthBudgetOptions {
  maxFailures?: number;
  windowMs?: number;
  clock?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

const DEFAULT_MAX_FAILURES = 20;
const DEFAULT_WINDOW_MS = 60_000;

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class RemoteAuthBudget {
  private readonly maxFailures: number;
  private readonly windowMs: number;
  private readonly clock: () => number;
  private readonly sleep: (ms: number) => Promise<void>;
  private timestamps: number[] = [];

  constructor(opts: RemoteAuthBudgetOptions = {}) {
    this.maxFailures = opts.maxFailures ?? DEFAULT_MAX_FAILURES;
    this.windowMs = opts.windowMs ?? DEFAULT_WINDOW_MS;
    this.clock = opts.clock ?? Date.now;
    this.sleep = opts.sleep ?? defaultSleep;
  }

  /**
   * Reserves one failure slot, waiting out the rest of the window first if the budget for it is
   * already spent. Called once per `@negative` scenario, a conservative approximation (a scenario
   * can cost more than one real failed request) that is simple, and errs toward waiting more than
   * strictly necessary rather than risking the server's own throttle.
   */
  async reserve(): Promise<void> {
    this.prune();

    if (this.timestamps.length >= this.maxFailures) {
      const oldest = this.timestamps[0];
      const waitMs = this.windowMs - (this.clock() - oldest);
      if (waitMs > 0) {
        await this.sleep(waitMs);
      }
      this.prune();
    }

    this.timestamps.push(this.clock());
  }

  private prune(): void {
    const cutoff = this.clock() - this.windowMs;
    this.timestamps = this.timestamps.filter((timestamp) => timestamp > cutoff);
  }
}

/**
 * Backs off once on a 429 (the server's `AuthFailureThrottle` response once a client is over
 * budget) and retries `fn` a single time; any other status, or a second 429, is returned as is.
 * Used by the raw-HTTP checks in a protocol adapter, which `mvn`/etc. itself has no equivalent for.
 */
export async function withBackoff429(
  fn: () => Promise<number>,
  opts: { retryAfterMs?: number; sleep?: (ms: number) => Promise<void> } = {},
): Promise<number> {
  const sleep = opts.sleep ?? defaultSleep;
  const status = await fn();
  if (status !== 429) {
    return status;
  }
  await sleep(opts.retryAfterMs ?? 2_000);
  return fn();
}
