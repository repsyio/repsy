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
 * RPS-1313 (follow-up of RPS-1107 part B): the password reset marker file, proven in the real Docker
 * image. The backend's own tests (`PasswordResetMarkerScanner*`) run against a temp directory as
 * whoever runs the build; they cannot see what only the image decides: that `/app/data/password-reset`
 * exists and is writable by the non-root runtime user, that `PASSWORD_RESET_MARKER_DIR` points
 * there, and that the README's `docker exec ... touch` recipe works end to end.
 *
 * The case: create a user through the panel API, `docker exec <repsy> touch
 * /app/data/password-reset/<user>`, wait for the scanner (polls every 5 s) to log the new password,
 * and log in with it. The user is always one the test created (never `admin`), and the seeder deletes
 * it afterwards; a marker the test leaves behind is removed too.
 *
 * `@local-only`: it needs the container of a stack this harness owns (`docker exec`), so it skips on a
 * remote target. It runs in the "stack" runner (`./run.sh test --protocol stack`).
 */
import { ApiError, PanelApi } from '../../src/api/panel-api.js';
import {
  dockerExec,
  findRepsyContainer,
  logLinesContaining,
  MARKER_DIR,
  RUNTIME_USER,
} from '../../src/clients/stack.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { target } from '../../src/target.js';

// Long enough for two 5 s polls plus a slow JVM; the marker is normally applied within one.
const SCANNER_TIMEOUT_MS = 40_000;

let container: string;
const markersToRemove: string[] = [];

test.describe('password reset marker file in the Docker image', { tag: '@local-only' }, () => {
  test.skip(
    !target.ownsStack || target.isRemote,
    'needs docker exec into a stack this harness owns',
  );

  test.beforeAll(async () => {
    container = await findRepsyContainer();
  });

  test.afterEach(async () => {
    // Leftover markers of a failed test would reset a user later (or fail the next run's listing).
    for (const name of markersToRemove.splice(0)) {
      await dockerExec(container, ['rm', '-f', `${MARKER_DIR}/${name}`]);
    }
  });

  async function touchMarker(name: string): Promise<void> {
    markersToRemove.push(name);
    const touched = await dockerExec(container, ['touch', `${MARKER_DIR}/${name}`]);
    expect(touched.exitCode, `docker exec touch ${MARKER_DIR}/${name}`).toBe(0);
  }

  async function markerExists(name: string): Promise<boolean> {
    return (await dockerExec(container, ['test', '-e', `${MARKER_DIR}/${name}`])).exitCode === 0;
  }

  test('the image gives the non-root runtime user a writable marker directory', async () => {
    const user = await dockerExec(container, ['id', '-un']);
    expect(user.stdout).toBe(RUNTIME_USER);

    const owner = await dockerExec(container, ['stat', '-c', '%U:%G', MARKER_DIR]);
    expect(owner.stdout).toBe(`${RUNTIME_USER}:appgroup`);

    const marker = await dockerExec(container, ['printenv', 'PASSWORD_RESET_MARKER_DIR']);
    expect(marker.stdout).toBe(MARKER_DIR);
  });

  test('touching a marker resets that user only: the log carries the new password and it logs in', async ({
    seeder,
    panelApi,
  }) => {
    const user = await seeder.createUser();
    const bystander = await seeder.createUser();
    // The old password works before the reset (a failed login below must come from the reset).
    await new PanelApi(env.apiBaseUrl).login(user.username, user.password);

    await touchMarker(user.username);

    const expectedLine = new RegExp(
      `Password of user ${user.username} has been reset by the marker file ` +
        `${MARKER_DIR}/${user.username}\\. New password: (\\S+)`,
    );
    let newPassword = '';
    await expect
      .poll(
        async () => {
          const lines = await logLinesContaining(container, `Password of user ${user.username} `);
          newPassword = lines.map((line) => expectedLine.exec(line)?.[1]).find(Boolean) ?? '';
          return newPassword !== '';
        },
        {
          message: `the scanner logs the reset of ${user.username} within ${SCANNER_TIMEOUT_MS} ms`,
          timeout: SCANNER_TIMEOUT_MS,
          intervals: [1_000],
        },
      )
      .toBe(true);

    // The marker is unlinked before the reset, so once the line is in the log it is gone.
    expect(await markerExists(user.username)).toBe(false);

    // The old password no longer works, the new one from the log does.
    await expect(
      new PanelApi(env.apiBaseUrl).login(user.username, user.password),
    ).rejects.toBeInstanceOf(ApiError);
    const login = await new PanelApi(env.apiBaseUrl).login(user.username, newPassword);
    expect(login.token).toBeTruthy();

    // Only the named user was reset, and the admin the seeder logged in with is untouched.
    await new PanelApi(env.apiBaseUrl).login(bystander.username, bystander.password);
    expect((await panelApi.listUsers({ search: user.username })).map((u) => u.username)).toContain(
      user.username,
    );
  });

  test('a marker for a user that does not exist is removed with a warning, nothing is created', async ({
    seeder,
    panelApi,
  }) => {
    const missing = seeder.reserveUsername();

    await touchMarker(missing);

    await expect
      .poll(
        async () => (await logLinesContaining(container, `there is no user ${missing}`)).length > 0,
        { timeout: SCANNER_TIMEOUT_MS, intervals: [1_000] },
      )
      .toBe(true);
    expect(await markerExists(missing)).toBe(false);
    const matches = await panelApi.listUsers({ search: missing });
    expect(matches.filter((u) => u.username === missing)).toEqual([]);
  });

  test('a marker whose name is not a valid username is removed with a warning', async () => {
    // Upper case is outside the username rule; the run id keeps the name unique and sweepable.
    const invalid = `E2E-${env.runId}-MARKER`;

    await touchMarker(invalid);

    await expect
      .poll(
        async () =>
          (await logLinesContaining(container, `its name is not a valid username`)).some((line) =>
            line.includes(invalid),
          ),
        { timeout: SCANNER_TIMEOUT_MS, intervals: [1_000] },
      )
      .toBe(true);
    expect(await markerExists(invalid)).toBe(false);
  });
});
