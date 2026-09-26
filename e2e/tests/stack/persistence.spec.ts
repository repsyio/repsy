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
 * RPS-1476: what survives a restart of the Repsy container and a recreate of it on the same volumes,
 * with the real clients (`mvn`, `npm`, `crane`) and the panel API. RPS-1401 moved the default storage
 * onto the `/app/data` volume and shipped with no test that a package outlives its container; the H2 file
 * database (the same volume) is the riskier half of it. The spec runs against whichever stack profile is
 * up: `./run.sh local up` (PostgreSQL) and `./run.sh local up --h2` (embedded H2) are the two legs, the
 * assertions are the same (the database-specific one, that H2's file is on the volume, only runs on H2).
 *
 * Serial, one worker (docker-compose.runners.yml sets REPSY_E2E_WORKERS=1 for the stack runner): the
 * container is restarted under the spec, so nothing else may talk to this stack at the same time, and
 * never against a shared stack. It packs three protocols into one repo each, then:
 *
 *  1. control: everything is consumable and listed in the panel before anything happens;
 *  2. `docker restart`: consumed again with the admin password and with a deploy token, listed in the
 *     panel, and a user created before still logs in (rows, files and tokens all outlived the process);
 *  3. recreate on the same volume (`docker compose up --force-recreate`): the same checks, and the
 *     `/app/data` volume is the very same one;
 *  4. a crash: `docker kill` (SIGKILL) and `docker start`, the H2 file database gets no flush on the way;
 *  5. sessions: without `OS_APP_JWT_SECRET` (the default stack) a restart regenerates the secret, so the
 *     access token and the refresh token from before it are refused; with the secret set
 *     (docker-compose.stack-jwt.yml) both stay valid across a restart and a recreate; going back to
 *     the unset stack ends them again.
 *
 * The admin JWT the harness itself holds dies with every restart of the unset stack, so `relogin()`
 * signs in again before any further panel call, and before the cleanup. A Basic-auth client (`mvn`,
 * `npm`, `crane`) authenticates per request and does not notice.
 *
 * `@local-only`: it needs the container of a stack this harness owns, so it skips on a remote target.
 * Runs in the "stack" runner: `./run.sh test --protocol stack --grep persistence` (README.md "Stack runner").
 */
import { randomBytes } from 'node:crypto';

import { PanelApi, RepoType } from '../../src/api/panel-api.js';
import {
  crashRepsy,
  currentComposeFiles,
  dataMount,
  dockerExec,
  findRepsyContainer,
  recreateRepsy,
  restartRepsy,
  restoreStack,
  type ComposeFiles,
} from '../../src/clients/stack.js';
import { dockerAdapter } from '../../src/clients/docker.js';
import { splitPackageName } from '../../src/clients/maven-raw.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import { npmAdapter } from '../../src/clients/npm.js';
import { env } from '../../src/env.js';
import type { ProtocolAdapter } from '../../src/scenarios/adapter.js';
import { SCENARIOS } from '../../src/scenarios/catalog.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential, World } from '../../src/scenarios/world.js';
import { perTestRunId } from '../../src/seed/run-id.js';
import { Seeder } from '../../src/seed/seeder.js';
import { target } from '../../src/target.js';

/** The overlay that fixes the JWT secret (docker-compose.stack-jwt.yml), relative to the stack's directory. */
const JWT_OVERLAY = 'docker-compose.stack-jwt.yml';
/** Repsy answers a token it cannot verify (unknown session, wrong secret) with this. */
const REFUSED_STATUS = 401;
const REFUSED_MSG_ID = 'accessNotAllowed';
/** Long enough for H2's auto-commit delay (1 s) to have written the last commit to the file. */
const SETTLE_MS = 3_000;

interface Package {
  /** What the scenario adapter published (real client, no raw companion probe). */
  adapter: ProtocolAdapter;
  world: World;
  /** A read-write deploy token of the repo, the credential of the consumes that are not the admin's. */
  token: MaterializedCredential;
  /** sha256 of the primary file the real client sent; what every later consume must give back. */
  contentSha256: string | undefined;
}

let panelApi: PanelApi;
let seeder: Seeder;
let originalStack: ComposeFiles;
let packages: Package[] = [];
let user: { username: string; password: string };

const ADMIN: MaterializedCredential = {
  transport: 'basic',
  username: env.adminUsername,
  password: env.adminPassword,
  kind: 'password',
};

/** Signs the admin in again: a restart of a stack without a fixed JWT secret ended the old session. */
async function relogin(): Promise<void> {
  await panelApi.login(env.adminUsername, env.adminPassword);
}

/** Publishes one package with the protocol's real client (no raw companion probe) into a repo of its own. */
async function publish(adapter: ProtocolAdapter, repoType: RepoType): Promise<Package> {
  const scenario = SCENARIOS.find((candidate) => candidate.id === 'password-admin');
  if (!scenario) {
    throw new Error('the catalog has no password-admin scenario');
  }
  const repo = await seeder.createRepo(repoType, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const coordinates = {
    packageName: adapter.packageName(seeder.runId, scenario),
    version: adapter.version('release'),
  };
  const world: World = {
    scenario,
    protocol: adapter.protocol,
    repoName: repo.name,
    credential: ADMIN,
    publishTarget: coordinates,
    consumeTarget: coordinates,
  };
  const seeded = await adapter.seedPublish(world);
  expect(
    seeded.contentSha256,
    `${adapter.protocol}: the real client reported no content digest`,
  ).toBeTruthy();
  return {
    adapter,
    world,
    contentSha256: seeded.contentSha256,
    token: {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    },
  };
}

/** A real-client consume with `credential`: it succeeds and returns the very bytes that were published. */
async function consume(
  pkg: Package,
  credential: MaterializedCredential,
  when: string,
): Promise<void> {
  const what = `${pkg.adapter.protocol} ${when} (${credential.kind})`;
  const resolved = await pkg.adapter.resolve({ ...pkg.world, credential });
  expect(
    resolved.outcome,
    `${what}: outcome (http ${resolved.httpStatus}; ${resolved.command})`,
  ).toBe('ok');
  expect(resolved.clientExitCode, `${what}: client exit code (${resolved.command})`).toBe(0);
  expect(resolved.contentSha256, `${what}: the consumed content is not the published one`).toBe(
    pkg.contentSha256,
  );
}

/** What the panel lists for the package: the name, the version (or, for Docker, the manifest digest). */
async function expectListedInPanel(pkg: Package, when: string): Promise<void> {
  const { repoName } = pkg.world;
  const { packageName, version } = pkg.world.publishTarget;
  const what = `${pkg.adapter.protocol} ${when}: the panel`;
  if (pkg.adapter.protocol === 'maven') {
    const [groupId, artifactId] = splitPackageName(packageName);
    expect(
      await panelApi.listMavenArtifactNames(repoName, groupId),
      `${what} lists the artifact`,
    ).toContain(artifactId);
    expect(
      await panelApi.listMavenArtifactVersionNames(repoName, groupId, artifactId),
      `${what} lists the version`,
    ).toContain(version);
    return;
  }
  const path =
    pkg.adapter.protocol === 'npm'
      ? `/api/npm/packages/${encodeURIComponent(repoName)}?size=100`
      : `/api/docker/images/${encodeURIComponent(repoName)}?size=100`;
  const res = await panelApi.rawRequest('GET', path);
  expect(res.status, `${what}: GET ${path}`).toBe(200);
  const content = (
    res.body.data as { content?: { name?: string; latestVersion?: string; digest?: string }[] }
  ).content;
  const item = (content ?? []).find((candidate) => candidate.name === packageName);
  expect(item, `${what} lists ${packageName}`).toBeDefined();
  if (pkg.adapter.protocol === 'npm') {
    expect(item?.latestVersion, `${what}: the npm latest version`).toBe(version);
  } else {
    expect(item?.digest, `${what}: the image digest`).toBe(`sha256:${pkg.contentSha256}`);
  }
}

/** On the H2 profile (DB_URL is a jdbc:h2 file URL), whether the database file is NOT on the volume; never on PostgreSQL. */
async function h2FileMissing(container: string): Promise<boolean> {
  const dbUrl = (await dockerExec(container, ['printenv', 'DB_URL'])).stdout;
  if (!dbUrl.startsWith('jdbc:h2:')) {
    return false;
  }
  return (await dockerExec(container, ['test', '-s', '/app/data/repsy.mv.db'])).exitCode !== 0;
}

/** Everything that must outlive the container: files, rows, tokens and users, as a client and as the panel see them. */
async function expectEverythingThere(when: string): Promise<void> {
  await relogin();
  for (const pkg of packages) {
    await consume(pkg, ADMIN, when);
    await consume(pkg, pkg.token, when);
    await expectListedInPanel(pkg, when);
  }
  // The admin (the seeded row) and a user created through the panel both still log in with their passwords.
  await new PanelApi(env.apiBaseUrl).login(env.adminUsername, env.adminPassword);
  await new PanelApi(env.apiBaseUrl).login(user.username, user.password);
}

/** `GET /api/repos` with a session token as it stands: 200, or the refusal of a token Repsy cannot verify. */
async function panelStatus(token: string): Promise<{ status: number; msgId: string | undefined }> {
  const res = await fetch(`${env.apiBaseUrl}/api/repos?size=1`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = (await res.json()) as { msgId?: string };
  return { status: res.status, msgId: body.msgId };
}

async function refreshStatus(
  refreshToken: string,
): Promise<{ status: number; msgId: string | undefined }> {
  const res = await fetch(`${env.apiBaseUrl}/api/auth/tokens/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  const body = (await res.json()) as { msgId?: string };
  return { status: res.status, msgId: body.msgId };
}

/** A fresh session of the admin, as a browser or a script would hold it: an access and a refresh token. */
async function newSession(): Promise<{ token: string; refreshToken: string }> {
  const info = await new PanelApi(env.apiBaseUrl).login(env.adminUsername, env.adminPassword);
  if (!info.token || !info.refreshToken) {
    throw new Error('login returned no token or no refresh token');
  }
  return { token: info.token, refreshToken: info.refreshToken };
}

async function expectSessionAccepted(
  session: { token: string; refreshToken: string },
  when: string,
): Promise<void> {
  expect((await panelStatus(session.token)).status, `${when}: the access token`).toBe(200);
  // Refreshing is answered with a new pair; it is the only call that consumes the refresh token.
  expect((await refreshStatus(session.refreshToken)).status, `${when}: the refresh token`).toBe(
    200,
  );
}

async function expectSessionRefused(
  session: { token: string; refreshToken: string },
  when: string,
): Promise<void> {
  expect(await panelStatus(session.token), `${when}: the access token`).toEqual({
    status: REFUSED_STATUS,
    msgId: REFUSED_MSG_ID,
  });
  expect(await refreshStatus(session.refreshToken), `${when}: the refresh token`).toEqual({
    status: REFUSED_STATUS,
    msgId: REFUSED_MSG_ID,
  });
}

test.describe.serial(
  'persistence across restart and recreate (RPS-1476)',
  { tag: '@local-only' },
  () => {
    test.skip(
      !target.ownsStack || target.isRemote,
      'needs docker restart/compose up of a stack this harness owns',
    );
    test.describe.configure({ timeout: 300_000 });

    test.beforeAll(async () => {
      test.setTimeout(600_000);
      originalStack = await currentComposeFiles();
      panelApi = new PanelApi(env.apiBaseUrl);
      await relogin();
      // One worker, so a fixed worker digit that no fixture-created Seeder of this run shares.
      seeder = new Seeder(panelApi, perTestRunId(env.runId, 35, 1));
      user = await seeder.createUser();
      packages = [
        await publish(mavenAdapter, RepoType.MAVEN),
        await publish(npmAdapter, RepoType.NPM),
        await publish(dockerAdapter, RepoType.DOCKER),
      ];
    });

    test.afterAll(async () => {
      test.setTimeout(300_000);
      // A failed test may have left the overlay on: the shared stack must go back to what run.sh started.
      const current = await currentComposeFiles();
      if (current.files.join() !== originalStack.files.join()) {
        await restoreStack(originalStack);
      }
      await relogin();
      await seeder.cleanup();
    });

    test('control: the data is on the volume and everything is there before anything restarts', async () => {
      const container = await findRepsyContainer();
      const mount = await dataMount(container);
      expect(mount, '/app/data is a volume, so the data outlives the container (RPS-1401)').toMatch(
        /^(volume|bind):/,
      );
      const storage = (await dockerExec(container, ['printenv', 'STORAGE_BASE_PATH'])).stdout;
      expect(storage, 'the storage path is under /app/data').toMatch(/^\/app\/data(\/|$)/);
      const files = await dockerExec(container, ['find', storage, '-type', 'f', '-print', '-quit']);
      expect(files.stdout, `${storage} holds the published files`).not.toBe('');

      expect(await h2FileMissing(container), 'the H2 database file is on the volume').toBe(false);

      await expectEverythingThere('before the restart');
    });

    test('docker restart: every package, token and user is still there', async () => {
      await restartRepsy();
      await expectEverythingThere('after docker restart');
    });

    test('recreate on the same volume: the container is new, the data volume is the same one', async () => {
      const before = await dataMount();
      const previous = await findRepsyContainer();
      const recreated = await recreateRepsy();
      expect(recreated, 'compose created a new container').not.toBe(previous);
      expect(await dataMount(recreated), 'the same /app/data volume is mounted again').toBe(before);
      await expectEverythingThere('after the container was recreated');
    });

    test('a crash (SIGKILL, no shutdown hook) loses nothing that was committed', async () => {
      // H2 writes what it committed to its file after `autoCommitDelay` (1 s): a SIGKILL inside that second
      // loses the last publish, which the client was already told succeeded (probed: a crash straight after
      // the last publish lost the Docker image, and sometimes the npm row, on H2; PostgreSQL loses nothing).
      // So this is "committed and settled". See README.md "Restart, crash and recreate".
      await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
      const before = await findRepsyContainer();
      expect(await crashRepsy(), 'the crashed container is started again').toBe(before);
      await expectEverythingThere('after a SIGKILL');
    });

    test('without OS_APP_JWT_SECRET a restart or a recreate ends every session', async () => {
      const beforeRestart = await newSession();
      expect((await panelStatus(beforeRestart.token)).status, 'the session works before').toBe(200);
      await restartRepsy();
      await expectSessionRefused(beforeRestart, 'after docker restart');
      // A new login is the way back in, and the account is the same one.
      await expectSessionAccepted(await newSession(), 'after signing in again');

      const beforeRecreate = await newSession();
      await recreateRepsy();
      await expectSessionRefused(beforeRecreate, 'after recreate');
    });

    test('with OS_APP_JWT_SECRET set both tokens outlive a restart and a recreate, and stop with the secret', async () => {
      const secret = randomBytes(32).toString('base64');
      const plain = await findRepsyContainer();
      const overlay = { env: { REPSY_E2E_JWT_SECRET: secret }, extraFiles: [JWT_OVERLAY] };

      expect(await recreateRepsy({ ...overlay, files: originalStack }), 'a new container').not.toBe(
        plain,
      );
      const session = await newSession();
      await expectSessionAccepted(session, 'right after starting with the secret');

      // The check above spent the refresh token on a refresh; take a session that stays whole for the restart.
      const durable = await newSession();
      await restartRepsy();
      await expectSessionAccepted(durable, 'after docker restart');

      const durableAcrossRecreate = await newSession();
      await recreateRepsy({ ...overlay, files: originalStack });
      await expectSessionAccepted(durableAcrossRecreate, 'after recreate with the same secret');
      await expectEverythingThere('with the secret set');

      // Back to the stack run.sh started (no secret): a different secret ends the session again.
      const beforeRestore = await newSession();
      await restoreStack(originalStack);
      await expectSessionRefused(beforeRestore, 'after the secret was unset');
      await expectEverythingThere('after the stack was restored');
    });
  },
);
