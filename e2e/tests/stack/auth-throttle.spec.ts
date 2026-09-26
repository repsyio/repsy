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
 * RPS-1477: the auth throttle (`AuthFailureThrottle`, RPS-1092) proven end to end, on the wire. The
 * backend's own `AuthThrottleIT` runs with MockMvc and one address; it cannot see what only the real
 * stack decides: that Tomcat's `RemoteIpValve` turns a trusted proxy's `X-Forwarded-For` into the
 * client key on BOTH listeners (the repo port 9090 and the panel port 8080), that the two ports share
 * one count, that a Bearer value nobody issued counts like a wrong password (RPS-1209), what the
 * refusal looks like to a client (429, the `tooManyRequests` envelope, `Retry-After`), that the window
 * ends, and that the WARN log line is written once per client and window.
 *
 * It runs on a stack started with the throttle overlay only (`./run.sh local up --throttle`,
 * `docker-compose.stack-throttle.yml`: 3 failures per 10 s window per client), in the "stack" runner
 * because the log case reads the Repsy container's log (`logLinesContaining`); every other case is
 * plain HTTP. `run.sh test` opts the runner in when `REPSY_E2E_THROTTLE=1` (README.md "Auth-throttle
 * leg"); without the overlay the whole file skips, and the nightly leg fails on a skip.
 *
 * Every test is its OWN client: it sends its own `X-Forwarded-For` (the docker gateway of the compose
 * network, where the runner's requests come from, counts as a trusted proxy: probed), so its count is
 * never shared with another test or with the seeder's admin, whose address is the gateway's. Nothing
 * here fails authentication without that header. A request WITHOUT the header is made only with
 * correct credentials, which never count.
 *
 * `@throttle`: the tag `--grep` uses to select these specs in the nightly; the same tag marks AUTH-11
 * in the UI suite, which the ui runner runs on this stack after this file (it locks the gateway's
 * bucket, README.md "Auth-throttle leg").
 */
import type { TestInfo } from '@playwright/test';

import { findRepsyContainer, logLinesContaining } from '../../src/clients/stack.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder, SeededUser } from '../../src/seed/seeder.js';
import { optedIn } from '../../src/stack-overlays.js';
import { RepoType } from '../../src/api/panel-api.js';
import { target } from '../../src/target.js';

// docker-compose.stack-throttle.yml
const MAX_FAILURES = 3;
const WINDOW_SECONDS = 10;
const TOO_MANY = 'tooManyRequests';
const TOO_MANY_TEXT = 'Too many failed authentication attempts. Please try again later.';

interface Answer {
  status: number;
  msgId: string | undefined;
  text: string | undefined;
  retryAfter: string | null;
}

async function answerOf(res: Response): Promise<Answer> {
  const body = (await res.json().catch(() => ({}))) as { msgId?: string; text?: string };
  return {
    status: res.status,
    msgId: body.msgId,
    text: body.text,
    retryAfter: res.headers.get('retry-after'),
  };
}

function withClient(client: string | undefined): Record<string, string> {
  return client === undefined ? {} : { 'X-Forwarded-For': client };
}

/** A GET of a file that does not exist in a private Maven repo: 401 without a credential that passes. */
async function basicGet(
  repoName: string,
  credential: { username: string; password: string },
  client?: string,
): Promise<Answer> {
  const basic = Buffer.from(`${credential.username}:${credential.password}`).toString('base64');
  const res = await fetch(repoUrl(repoName, 'e2e/throttle/1.0/throttle-1.0.pom'), {
    headers: { Authorization: `Basic ${basic}`, ...withClient(client) },
  });
  return answerOf(res);
}

/** `npm whoami`'s request with a Bearer value nobody issued. */
async function junkBearerWhoami(repoName: string, client: string): Promise<Answer> {
  const res = await fetch(repoUrl(repoName, '-/whoami'), {
    headers: { Authorization: 'Bearer not-a-token-of-any-kind', ...withClient(client) },
  });
  return answerOf(res);
}

/** The panel's login on the API port. */
async function panelLogin(
  credential: { username: string; password: string },
  client: string,
): Promise<Answer> {
  const res = await fetch(`${env.apiBaseUrl}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...withClient(client) },
    body: JSON.stringify(credential),
  });
  return answerOf(res);
}

function expectRefused(answer: Answer, what: string): void {
  expect(answer.status, `${what}: status`).toBe(429);
  expect(answer.msgId, `${what}: msgId`).toBe(TOO_MANY);
  expect(answer.text, `${what}: text`).toBe(TOO_MANY_TEXT);
  const retryAfter = Number(answer.retryAfter);
  expect(retryAfter, `${what}: Retry-After ${answer.retryAfter}`).toBeGreaterThanOrEqual(1);
  expect(retryAfter, `${what}: Retry-After ${answer.retryAfter}`).toBeLessThanOrEqual(
    WINDOW_SECONDS,
  );
}

/** A repo-and-user pair no other test touches, and a client address nobody else uses. */
let clientSeq = 0;
function uniqueClient(testInfo: TestInfo): string {
  clientSeq += 1;
  // 10.<run>.<worker>.<n>: private space, nothing the docker gateway or the runners ever use.
  const runOctet = (Number.parseInt(env.runId.slice(0, 4), 36) % 250) + 1;
  return `10.${runOctet}.${testInfo.parallelIndex + 1}.${clientSeq}`;
}

async function privateMavenRepo(seeder: Seeder): Promise<string> {
  return (await seeder.createRepo(RepoType.MAVEN, { privateRepo: true })).name;
}

const wrong = (user: SeededUser): { username: string; password: string } => ({
  username: user.username,
  password: `${user.password}-wrong`,
});

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

test.describe('auth throttle on the wire', { tag: ['@throttle', '@cloud-skip'] }, () => {
  // eslint-disable-next-line playwright/no-skipped-test -- the local-only guard of every tests/stack spec
  test.skip(!target.ownsStack || target.isRemote, 'needs a stack this harness owns');
  // eslint-disable-next-line playwright/no-skipped-test -- the opt-in switch of README.md "Stack overlays"
  test.skip(
    !optedIn('throttle'),
    'opt-in: needs the throttle overlay; ./run.sh local up --throttle, then REPSY_E2E_THROTTLE=1 ./run.sh test --protocol stack --grep @throttle',
  );
  // One case waits out a whole window.
  test.setTimeout(60_000);

  test('Basic on the repo port: 3 wrong passwords, then 429, even for the right password', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const user = await seeder.createUser();

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      const answer = await basicGet(repo, wrong(user), client);
      expect(answer.status, `wrong password #${n}`).toBe(401);
      expect(answer.msgId).toBe('unAuthorized');
    }
    expectRefused(await basicGet(repo, wrong(user), client), 'wrong password #4');
    // A refusal precedes the password check: a password that was never verified before does not get
    // through either (only a remembered one does, see below).
    expectRefused(await basicGet(repo, user, client), 'right password of a user not seen before');
  });

  test('Bearer on the repo port: a value nobody issued counts like a wrong password (RPS-1209)', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = (await seeder.createRepo(RepoType.NPM, { privateRepo: true })).name;

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      const answer = await junkBearerWhoami(repo, client);
      expect(answer.status, `junk Bearer #${n}`).toBe(401);
      expect(answer.msgId).toBe('unAuthorized');
    }
    expectRefused(await junkBearerWhoami(repo, client), 'junk Bearer #4');
  });

  test('panel login on the API port: 3 wrong passwords, then 429, even for the right password', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const user = await seeder.createUser();

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      const answer = await panelLogin(wrong(user), client);
      expect(answer.status, `wrong password #${n}`).toBe(401);
      expect(answer.msgId).toBe('invalidCredentials');
    }
    expectRefused(await panelLogin(wrong(user), client), 'wrong password #4');
    expectRefused(await panelLogin(user, client), 'right password');
  });

  test('both ports count into one bucket per client: 2 on the API port + 1 on the repo port lock both', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const user = await seeder.createUser();

    expect((await panelLogin(wrong(user), client)).status).toBe(401);
    expect((await panelLogin(wrong(user), client)).status).toBe(401);
    expect((await basicGet(repo, wrong(user), client)).status).toBe(401);

    expectRefused(await basicGet(repo, wrong(user), client), 'repo port, 4th failure overall');
    expectRefused(await panelLogin(wrong(user), client), 'API port, 4th failure overall');
  });

  test('the count is per client: another address, and a request with no X-Forwarded-For, are not locked', async ({
    seeder,
  }, testInfo) => {
    const locked = uniqueClient(testInfo);
    const other = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const user = await seeder.createUser();

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      expect((await basicGet(repo, wrong(user), locked)).status).toBe(401);
    }
    expectRefused(await basicGet(repo, user, locked), 'the locked client, right password');

    // Another client gets its own count: a first failure is a plain 401, and the right password
    // passes authentication (the file does not exist: 404 is what a signed-in user gets).
    expect((await basicGet(repo, wrong(user), other)).status).toBe(401);
    // No X-Forwarded-For is the runner's own address, the seeder's admin bucket: only a correct
    // credential is sent there, which never counts.
    expect((await basicGet(repo, user)).status).toBe(404);
  });

  test('a remembered credential keeps working in a blocked bucket; a new one does not', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const remembered = await seeder.createUser();
    const fresh = await seeder.createUser();

    // The first success is remembered (VerifiedPasswordCache), from any client.
    expect((await basicGet(repo, remembered, uniqueClient(testInfo))).status).toBe(404);

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      expect((await basicGet(repo, wrong(fresh), client)).status).toBe(401);
    }
    expectRefused(await basicGet(repo, wrong(fresh), client), 'a wrong password');
    expectRefused(await basicGet(repo, fresh, client), 'a right password never seen before');
    expect((await basicGet(repo, remembered, client)).status, 'the remembered credential').toBe(
      404,
    );
  });

  test('the window ends: after it the same client authenticates again and starts a new count', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const user = await seeder.createUser();

    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      expect((await basicGet(repo, wrong(user), client)).status).toBe(401);
    }
    expectRefused(await basicGet(repo, user, client), 'inside the window');

    // The window is fixed and starts at the first failure, which is behind us: a whole window and a
    // second from now is past its end.
    await sleep((WINDOW_SECONDS + 1) * 1000);

    expect((await basicGet(repo, user, client)).status, 'right password after the window').toBe(
      404,
    );
    // A clean count: wrong passwords are 401s again, up to the limit.
    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      expect((await basicGet(repo, wrong(user), client)).status, `new window, #${n}`).toBe(401);
    }
    expectRefused(await basicGet(repo, wrong(user), client), 'new window, #4');
  });

  test('the WARN line is logged once per client and window, however many attempts follow', async ({
    seeder,
  }, testInfo) => {
    const client = uniqueClient(testInfo);
    const repo = await privateMavenRepo(seeder);
    const user = await seeder.createUser();
    const container = await findRepsyContainer();
    const warnings = (): Promise<string[]> => logLinesContaining(container, `Client ${client} `);

    expect(await warnings(), 'nothing logged before the client fails').toEqual([]);
    for (let n = 1; n < MAX_FAILURES; n += 1) {
      await basicGet(repo, wrong(user), client);
    }
    expect(await warnings(), 'nothing logged below the limit').toEqual([]);

    // The failure that reaches the limit, then refused attempts (which count too): still one line.
    await basicGet(repo, wrong(user), client);
    for (let n = 0; n < 3; n += 1) {
      expectRefused(await basicGet(repo, wrong(user), client), `refused attempt ${n + 1}`);
    }
    const first = await warnings();
    expect(first).toHaveLength(1);
    expect(first[0]).toContain('WARN');
    expect(first[0]).toContain(
      `Client ${client} (network ${client}) made ${MAX_FAILURES} failed password checks, refusing its password checks until its window ends`,
    );

    // A new window that reaches the limit again is a new line.
    await sleep((WINDOW_SECONDS + 1) * 1000);
    for (let n = 1; n <= MAX_FAILURES; n += 1) {
      expect((await basicGet(repo, wrong(user), client)).status).toBe(401);
    }
    expect(await warnings()).toHaveLength(2);
  });
});
