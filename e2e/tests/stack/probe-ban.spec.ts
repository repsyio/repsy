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

/**
 * RPS-2328: the probe ban (`SuspiciousRequestThrottle`, RPS-2319/RPS-2321) must never count what the
 * real clients send. A false positive bans a user, and the unit tables of the classifier are built from
 * request shapes someone knew about; the real Maven, npm, Docker, Go, NuGet, PyPI, Helm, Cargo and Ruby
 * clients and the panel in a browser are the only proof.
 *
 * The leg (README.md "Probe-ban leg") starts the stack with the probe overlay
 * (`./run.sh local up --probe-ban`, `docker-compose.stack-probe-ban.yml`: enforce, ONE strike bans), runs the
 * `@smoke` set of every protocol runner and of the panel on it, and this file LAST in the stack runner.
 * Every runner reaches Repsy from the docker gateway, so a request that any signal counts bans the
 * gateway and fails every request after it with 429: the first spec that suffers names the culprit, and
 * the second case below names the signal.
 *
 * Case 1 proves the overlay is live (an overlay that silently did nothing would pass the whole leg): a
 * probe from a client of its own bans it at once on both ports. Case 2 reads the Repsy container log,
 * which holds every request of the leg, and fails when any client but that one was ever counted, even
 * by a signal that does not ban alone (a scanner User-Agent adds strikes without banning).
 *
 * Both cases use their own `X-Forwarded-For` (the docker gateway counts as a trusted proxy, see
 * auth-throttle.spec.ts), so the gateway itself is never banned by this file. The api runner (which
 * sends `../x` on purpose, tests/api/maven-browser.spec.ts) stays out of the leg.
 *
 * `@probe-ban`: the tag `--grep` uses to select this file in the nightly. The log needles are the
 * wording of `SuspiciousRequestThrottle`; `@cloud-skip` keeps it out of the Cloud runs, whose stack has
 * its own overlay (RPS-2328, Cloud part).
 */
import type { TestInfo } from '@playwright/test';

import { findRepsyContainer, logLinesContaining } from '../../src/clients/stack.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { optedIn } from '../../src/stack-overlays.js';
import { target } from '../../src/target.js';

// docker-compose.stack-probe-ban.yml sets PROBE_THROTTLE_MAX_STRIKES=1; the ban time is the default.
const BAN_SECONDS = 3600;
// "Client X (network Y) sent a suspicious request [...]" (a window's first probe, no ban yet) and
// "Client X (network Y) sent suspicious requests [...] reaching N strikes" (the ban).
const PROBER_LOG_NEEDLE = 'suspicious request';

/** A client address nobody else uses: 10.<run>.<worker>.<n>, as in auth-throttle.spec.ts. */
function uniqueClient(testInfo: TestInfo): string {
  const runOctet = (Number.parseInt(env.runId.slice(0, 4), 36) % 250) + 1;
  return `10.${runOctet}.${testInfo.parallelIndex + 1}.200`;
}

test.describe.configure({ mode: 'serial' });

test.describe('probe ban on the wire', { tag: ['@probe-ban', '@cloud-skip'] }, () => {
  // eslint-disable-next-line playwright/no-skipped-test -- the local-only guard of every tests/stack spec
  test.skip(!target.ownsStack || target.isRemote, 'needs a stack this harness owns');
  // eslint-disable-next-line playwright/no-skipped-test -- the opt-in switch of README.md "Stack overlays"
  test.skip(
    !optedIn('probe-ban'),
    'opt-in: needs the probe overlay; ./run.sh local up --probe-ban, then REPSY_E2E_PROBE_BAN=1 ./run.sh test --protocol stack --grep @probe-ban',
  );

  let prober = '';

  test('a probe bans its client at once, on both ports, and the ban says how long', async ({}, testInfo) => {
    prober = uniqueClient(testInfo);
    const headers = { 'X-Forwarded-For': prober };

    // A secret-hunting path on the API port: one signal, one strike, enough with the overlay's limit.
    // The request itself is answered as before (it is the one that tipped the ban), not with 429.
    const probe = await fetch(`${env.apiBaseUrl}/.env`, { headers });
    expect(probe.status, 'the probe itself').not.toBe(429);

    for (const [port, base] of [
      ['API', env.apiBaseUrl],
      ['repo', env.repoBaseUrl],
    ] as const) {
      const refused = await fetch(`${base}/anything-at-all`, { headers });
      expect(refused.status, `${port} port after the ban`).toBe(429);
      const retryAfter = Number(refused.headers.get('retry-after'));
      expect(retryAfter, `${port} port: Retry-After`).toBeGreaterThanOrEqual(1);
      expect(retryAfter, `${port} port: Retry-After`).toBeLessThanOrEqual(BAN_SECONDS);
      expect(await refused.text(), `${port} port: body`).toContain('Too many requests');
    }
  });

  test('no client but the probing one was counted by the whole leg', async () => {
    expect(prober, 'the case above ran first and set its client').not.toBe('');

    const counted = await logLinesContaining(await findRepsyContainer(), PROBER_LOG_NEEDLE);

    // The first case's own lines prove that the log is read, and that the needles match.
    expect(
      counted.filter((line) => line.includes(prober)).length,
      `the Repsy log has lines of the probing client ${prober}`,
    ).toBeGreaterThanOrEqual(1);
    // Any other line is a real client that the probe ban counted: the line names the client and the
    // signal families (TRAVERSAL, SECRET_PATH, SCRIPT_TARGET, SQLI, ..., SCANNER_UA).
    expect(
      counted.filter((line) => !line.includes(prober)),
      'lines of clients the probe ban counted, which no real client may be',
    ).toEqual([]);
  });
});
