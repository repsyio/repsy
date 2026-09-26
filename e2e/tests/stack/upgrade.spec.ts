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
 * RPS-1487: the upgrade path. The PREVIOUS release's published image runs on fresh volumes, is filled
 * with the real clients (`mvn`, `npm`, `crane`) and the previous release's own panel API, and its
 * container is then recreated on the image under test, on the same volumes. What the README's "Upgrading"
 * section promises is asserted on what comes out: the Flyway migrations ran, every package published
 * before is consumed again by the real client (with the admin's password and a deploy token), the
 * Docker manifest layout repair renamed the legacy manifest files (pull by tag AND by digest), the
 * startup warnings an operator relies on are logged, the panel lists everything, and the accounts of
 * the old release have to use the passwords Repsy prints (V0017: the SHA-256 hashes cannot become
 * BCrypt, so every password is reset).
 *
 * It needs a stack started for it: `./run.sh local up --upgrade [--h2]` (docker-compose.stack-upgrade.yml
 * and the previous release's image, src/upgrade/previous-release.ts), and is opted in by
 * `REPSY_E2E_UPGRADE=1` (README.md "Upgrade path"). Without the opt-in, or on a stack that is not on the
 * previous release (started without --upgrade, or its image could not be pulled: no network), it SKIPS
 * with the reason. The nightly leg (`upgrade`, `upgrade-h2`) fails on a skip, so a broken leg cannot
 * pass by doing nothing.
 *
 * Serial, one worker: the container is recreated under the spec. It runs on whichever database the stack
 * has, PostgreSQL (the postgres volume outlives the recreate, `--no-deps`) or embedded H2 (the file is in
 * the `/app/data` volume): the assertions are the same. The admin's password is reset by the upgrade, so
 * the spec puts `REPSY_ADMIN_PASSWORD` back before it ends: every other runner of the stack needs it.
 *
 * `@local-only` (it recreates the container of a stack this harness owns) and `@upgrade`.
 */
import path from 'node:path';

import { createPanelBackend, loginPanel } from '../../src/api/backend-registry.js';
import { PanelHttpError, type PanelBackend, RepoType } from '../../src/api/panel-backend.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import { craneEnv, renderDockerConfig } from '../../src/clients/docker.js';
import { buildImage } from '../../src/clients/docker-image.js';
import { dockerAdapter } from '../../src/clients/docker.js';
import { imageRef, sha256Hex } from '../../src/clients/docker-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import { npmAdapter } from '../../src/clients/npm.js';
import {
  ADMIN,
  consume,
  expectListedInPanel,
  publishInto,
  type Package,
} from '../../src/clients/stack-packages.js';
import {
  containerImage,
  dataMount,
  dockerExec,
  findRepsyContainer,
  logLinesContaining,
  recreateRepsy,
  waitForLogLines,
} from '../../src/clients/stack.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { perTestRunId } from '../../src/seed/run-id.js';
import { Seeder } from '../../src/seed/seeder.js';
import { optedIn } from '../../src/stack-overlays.js';
import { target } from '../../src/target.js';
import { LegacyPanel, type LegacyUser } from '../../src/upgrade/legacy-panel.js';
import { PREVIOUS_RELEASE, releaseImage } from '../../src/upgrade/previous-release.js';

const FROM_TAG = process.env.REPSY_E2E_UPGRADE_FROM || PREVIOUS_RELEASE;
const FROM_IMAGE = releaseImage(FROM_TAG);
/** The image under test: what `run.sh local up --upgrade` prepared (`REPSY_IMAGE`, or the project's tag). */
const TO_IMAGE =
  process.env.REPSY_IMAGE || `repsy-os-e2e:${process.env.REPSY_E2E_IMAGE_TAG || 'local'}`;

/** What the previous release's Flyway had applied last (the release's `db/migration`, V0011). */
const PREVIOUS_SCHEMA_VERSION = 11;
const REPAIR_TIMEOUT_MS = 120_000;
const RESET_LINE = /Admin password has been reset for user (\S+)\. New password: (\S+)/;

interface DockerImages {
  /** The repo holding the images below. */
  repoName: string;
  image: string;
  /** tag -> manifest digest (`sha256:...`) of every single-platform image pushed under its own tag. */
  tags: Map<string, string>;
  /** The multi-platform index: image, tag, its digest and the digests of its two children. */
  multi: { image: string; tag: string; digest: string; children: string[] };
}

let container: string;
let seeder: Seeder;
let panelApi: PanelBackend;
let legacy: LegacyPanel;
const packages: Package[] = [];
let docker: DockerImages;
let secondAdmin: LegacyUser;
let plainUser: LegacyUser;
/** The passwords the upgraded Repsy printed, by username. */
const printed = new Map<string, string>();
let upgraded = false;
let legacyManifestFiles: string[] = [];

async function storageDir(id: string): Promise<string> {
  return (await dockerExec(id, ['printenv', 'STORAGE_BASE_PATH'])).stdout;
}

/** Runs `crane <args>` as `credential` (a private HOME/DOCKER_CONFIG per call). */
async function crane(args: readonly string[], label: string) {
  const { home, work } = await isolatedWorkDir(label);
  await renderDockerConfig(home, ADMIN);
  return run('crane', [...args], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: 120_000,
    redact: [ADMIN.password ?? ''],
    label,
  });
}

/** Builds an OCI layout of one image (a fresh marker: its own digest) and `crane push`es it under `tag`. */
async function pushImage(
  repoName: string,
  image: string,
  tag: string,
  arch: string,
): Promise<string> {
  const { home, work } = await isolatedWorkDir(`upgrade-push-${tag}`);
  await renderDockerConfig(home, ADMIN);
  const built = await buildImage({
    dir: path.join(work, tag),
    marker: `${seeder.runId}-${image}-${tag}`,
    arch,
  });
  const result = await run('crane', ['push', built.dir, imageRef(repoName, image, tag)], {
    cwd: work,
    env: craneEnv(home),
    timeoutMs: 120_000,
    redact: [ADMIN.password ?? ''],
    label: `upgrade-push-${tag}`,
  });
  expect(result.exitCode, `crane push ${tag}: ${result.command}`).toBe(0);
  return built.manifestDigest;
}

/** The manifest `ref` serves (by tag or by digest), read with the real client, and its digest. */
async function manifestOf(ref: string, when: string): Promise<{ digest: string; text: string }> {
  const result = await crane(['manifest', ref], 'upgrade-manifest');
  expect(result.exitCode, `${when}: crane manifest ${ref}`).toBe(0);
  return { digest: `sha256:${sha256Hex(Buffer.from(result.stdout, 'utf8'))}`, text: result.stdout };
}

/** Every Docker image is pulled again, by tag and by digest, byte for byte (the manifest, and for a single image its blobs). */
async function expectDockerImages(when: string): Promise<void> {
  const { repoName, image, tags, multi } = docker;
  for (const [tag, digest] of tags) {
    for (const reference of [`:${tag}`, `@${digest}`]) {
      const ref = `${imageRef(repoName, image, tag).replace(/:[^:/]+$/, '')}${reference}`;
      const manifest = await manifestOf(
        ref,
        `${when}, ${tag} by ${reference[0] === ':' ? 'tag' : 'digest'}`,
      );
      expect(manifest.digest, `${when}: ${ref} serves the manifest that was pushed`).toBe(digest);
      const { work } = await isolatedWorkDir('upgrade-pull');
      const pulled = await crane(
        ['pull', '--format=oci', ref, path.join(work, 'pulled')],
        'upgrade-pull',
      );
      expect(pulled.exitCode, `${when}: crane pull ${ref}`).toBe(0);
    }
  }
  const base = imageRef(repoName, multi.image, multi.tag).replace(/:[^:/]+$/, '');
  const byTag = await manifestOf(`${base}:${multi.tag}`, `${when}, multi-platform index by tag`);
  expect(byTag.digest, `${when}: the index by tag`).toBe(multi.digest);
  expect(
    (JSON.parse(byTag.text) as { manifests: { digest: string }[] }).manifests.map((m) => m.digest),
    `${when}: the index still names its two children`,
  ).toEqual(expect.arrayContaining(multi.children));
  expect((await manifestOf(`${base}@${multi.digest}`, `${when}, index by digest`)).digest).toBe(
    multi.digest,
  );
  for (const child of multi.children) {
    expect((await manifestOf(`${base}@${child}`, `${when}, child by digest`)).digest).toBe(child);
  }
}

/** The files under the storage that belong to the Docker manifests, one path per line. */
async function manifestFiles(): Promise<string[]> {
  const root = await storageDir(container);
  const found = await dockerExec(container, [
    'find',
    root,
    '-type',
    'f',
    '-path',
    '*/docker/*/manifests/*',
  ]);
  // Not the trash: the repair moves the copies named after a digest there (soft delete), it does not remove them.
  return found.stdout
    .split('\n')
    .filter((line) => line !== '' && !line.includes('/docker/trash/'))
    .sort();
}

/**
 * The tarball of the npm package as the wire serves it, read with a plain GET: the previous release
 * bakes a tarball URL into the packument that answers 404 (it repeats the repo name), so `npm install`
 * does not work on it and only the file itself can be proven there.
 */
async function npmTarballSha(pkg: Package, credential: MaterializedCredential): Promise<string> {
  const { repoName } = pkg.world;
  const { packageName, version } = pkg.world.publishTarget;
  const res = await fetch(repoUrl(repoName, `${packageName}/-/${packageName}-${version}.tgz`), {
    headers: {
      Authorization: `Basic ${Buffer.from(`${credential.username}:${credential.password}`).toString('base64')}`,
    },
  });
  expect(res.status, `npm tarball of ${packageName} as ${credential.kind}`).toBe(200);
  return sha256Hex(Buffer.from(await res.arrayBuffer()));
}

const npmTarballs = new Map<string, string>();

async function expectEverythingConsumable(
  when: string,
  credential: 'token' | 'admin',
  realNpmClient: boolean,
): Promise<void> {
  for (const pkg of packages) {
    const who = credential === 'token' ? pkg.token : ADMIN;
    if (pkg.adapter.protocol === 'npm') {
      const sha = await npmTarballSha(pkg, who);
      expect(sha, `${when}: the npm tarball is the one that was seen first`).toBe(
        npmTarballs.get(who.kind ?? '') ?? sha,
      );
      npmTarballs.set(who.kind ?? '', sha);
      if (!realNpmClient) {
        continue;
      }
    }
    await consume(pkg, who, when);
  }
  if (credential === 'admin') {
    await expectDockerImages(when);
  }
}

test.describe.serial(
  'upgrade path from the previous release (RPS-1487)',
  { tag: ['@local-only', '@upgrade', '@cloud-skip'] },
  () => {
    test.skip(
      !optedIn('upgrade'),
      'needs the upgrade stack: REPSY_E2E_UPGRADE=1, ./run.sh local up --upgrade',
    );
    test.skip(
      !target.ownsStack || target.isRemote,
      'needs docker compose of a stack this harness owns',
    );
    test.describe.configure({ timeout: 300_000 });

    test.beforeAll(async () => {
      test.setTimeout(600_000);
      container = await findRepsyContainer();
      const running = await containerImage(container);
      test.skip(
        running !== FROM_IMAGE,
        `the stack runs ${running}, not the previous release ${FROM_IMAGE}: start it with ./run.sh local up --upgrade ` +
          '(that needs the release image to be pullable: registry and network)',
      );

      panelApi = await createPanelBackend();
      seeder = new Seeder(panelApi, perTestRunId(env.runId, 37, 1));
      legacy = new LegacyPanel(env.apiBaseUrl);
      await legacy.login(env.adminUsername, env.adminPassword);

      // Accounts: a second admin and a plain user, both with a password of their own.
      secondAdmin = await legacy.createUser(seeder.reserveUsername(), 'UpAdmin-Pwd1', 'ADMIN');
      plainUser = await legacy.createUser(seeder.reserveUsername(), 'UpUser-Pwd1', 'USER');

      // One private repo per protocol with a read-write deploy token, and a package in each.
      for (const [adapter, repoType] of [
        [mavenAdapter, RepoType.MAVEN],
        [npmAdapter, RepoType.NPM],
        [dockerAdapter, RepoType.DOCKER],
      ] as const) {
        const name = seeder.reserveRepoName(repoType);
        await legacy.createRepo(repoType, name);
        const token = await legacy.createDeployToken(name, `upgrade-${adapter.protocol}`);
        packages.push(
          await publishInto(adapter, seeder.runId, name, {
            username: token.username,
            password: token.token,
          }),
        );
      }

      // Docker: a second tag of the image, and a multi-platform index (the layouts the old release stored per tag).
      const dockerPackage = packages.find((pkg) => pkg.adapter.protocol === 'docker') as Package;
      const repoName = dockerPackage.world.repoName;
      const image = dockerPackage.world.publishTarget.packageName;
      const tags = new Map<string, string>([
        [dockerPackage.world.publishTarget.version, `sha256:${dockerPackage.contentSha256}`],
        ['second', await pushImage(repoName, image, 'second', 'amd64')],
      ]);
      const multiImage = `${image}-multi`;
      const amd64 = await pushImage(repoName, multiImage, 'amd64', 'amd64');
      const arm64 = await pushImage(repoName, multiImage, 'arm64', 'arm64');
      const index = await crane(
        [
          'index',
          'append',
          '-m',
          imageRef(repoName, multiImage, 'amd64'),
          '-m',
          imageRef(repoName, multiImage, 'arm64'),
          '-t',
          imageRef(repoName, multiImage, 'multi'),
        ],
        'upgrade-index-append',
      );
      expect(index.exitCode, `crane index append: ${index.command}`).toBe(0);
      const indexDigest = await manifestOf(imageRef(repoName, multiImage, 'multi'), 'populate');
      docker = {
        repoName,
        image,
        tags,
        multi: {
          image: multiImage,
          tag: 'multi',
          digest: indexDigest.digest,
          children: [amd64, arm64],
        },
      };
    });

    test.afterAll(async () => {
      test.setTimeout(300_000);
      if (!upgraded) {
        return;
      }
      await restoreAdminPassword();
      await panelApi.login(env.adminUsername, env.adminPassword);
      await seeder.cleanup();
    });

    test('control: on the previous release the packages are consumable and the Docker manifests keep the legacy layout', async () => {
      await expectEverythingConsumable('on the previous release', 'token', false);
      await expectEverythingConsumable('on the previous release', 'admin', false);
      legacyManifestFiles = await manifestFiles();
      expect(
        legacyManifestFiles.filter((file) => /\/manifests\/manifest_[0-9a-f]{12}_/.test(file)),
        'the previous release stores a manifest per tag, named after it',
      ).toHaveLength(legacyManifestFiles.length);
      expect(
        legacyManifestFiles.length,
        'one legacy file per tag and per digest reference',
      ).toBeGreaterThanOrEqual(docker.tags.size + 3);
    });

    test('recreated on the image under test with the same volumes: the migrations ran and the warnings are logged', async () => {
      const mountBefore = await dataMount(container);
      const previous = container;
      // The repair job is switched off for this first start, so the pulls below prove that a legacy manifest
      // is served from its old file name (README.md "Upgrading"); the next test switches it on.
      container = await recreateRepsy({ env: { REPSY_E2E_UPGRADE_REPAIR: 'false' } });
      upgraded = true;
      expect(container, 'compose created a new container').not.toBe(previous);
      expect(await containerImage(container), 'the container runs the image under test').toBe(
        TO_IMAGE,
      );
      expect(await dataMount(container), 'the /app/data volume is the same one').toBe(mountBefore);

      const log = await fullLog(container);
      // Flyway: from the previous release's schema up to the newest migration this image carries.
      const migrations = await migrationVersions(container);
      const newest = Math.max(...migrations);
      const pending = migrations.filter((version) => version > PREVIOUS_SCHEMA_VERSION).length;
      expect(log, 'Flyway found the previous release schema').toMatch(
        new RegExp(`Current version of schema "[^"]+": 0*${PREVIOUS_SCHEMA_VERSION}\\b`),
      );
      const applied =
        /Successfully applied (\d+) migrations? to schema "[^"]+", now at version v0*(\d+)/.exec(
          log,
        );
      expect(applied, 'Flyway reports the migrations it applied').not.toBeNull();
      expect(Number(applied?.[1]), 'every migration after the previous release ran').toBe(pending);
      expect(Number(applied?.[2]), 'the schema is at the newest version of the image').toBe(newest);

      // README.md "DB_HOST, DB_PORT and DB_DATABASE are no longer read" (RPS-1423): the overlay sets the old variables.
      const dbWarnings = log
        .split('\n')
        .filter((line) => line.includes('are no longer read: only DB_URL selects the database'));
      expect(dbWarnings, 'the legacy database variables are warned about, once').toHaveLength(1);
      expect(dbWarnings[0]).toContain(
        'The environment variables DB_HOST, DB_PORT, DB_DATABASE are no longer read',
      );
      expect(dbWarnings[0], 'the warning never repeats the credentials').not.toContain('repsy123');
      expect(dbWarnings[0], 'the warning never repeats an H2 DB_URL').not.toContain('jdbc:h2');
      const dbUrl = (await dockerExec(container, ['printenv', 'DB_URL'])).stdout;
      expect(
        dbWarnings[0].includes('Repsy is starting on the embedded H2 database'),
        'only an H2 start adds that Repsy is on the embedded database',
      ).toBe(dbUrl.startsWith('jdbc:h2:'));

      // The overlay sets STORAGE_BASE_PATH, so the image's legacy-directory fallback must stay quiet.
      expect(log, 'no fallback to /home/appuser/.repsy').not.toContain('holds artifacts and');
      expect(
        log.split('\n').filter((line) => / ERROR /.test(line)),
        'the upgrade logs no error',
      ).toEqual([]);
    });

    test('every account of the previous release was reset: the old passwords are refused, the printed ones work', async () => {
      const lines = await logLinesContaining(container, 'Admin password has been reset for user');
      for (const line of lines) {
        const match = RESET_LINE.exec(line);
        expect(
          match,
          `the reset line has the documented shape: ${line.replace(/New password: \S+/, '')}`,
        ).not.toBeNull();
        printed.set(match?.[1] ?? '', match?.[2] ?? '');
      }
      expect(
        [...printed.keys()].sort(),
        'exactly the admin accounts are reset and printed',
      ).toEqual([env.adminUsername, secondAdmin.username].sort());
      for (const [name, password] of printed) {
        expect(password.length, `the new password of ${name} is not blank`).toBeGreaterThan(5);
      }

      // Everything the harness created is the harness's to delete again, by name and id.
      seeder.adoptUser(secondAdmin.id);
      seeder.adoptUser(plainUser.id);
      for (const pkg of packages) {
        seeder.adoptRepo(pkg.world.repoName);
      }

      // The old passwords (the SHA-256 hashes could not be converted) are refused on every door.
      for (const [username, password] of [
        [env.adminUsername, env.adminPassword],
        [secondAdmin.username, secondAdmin.password],
        [plainUser.username, plainUser.password],
      ]) {
        await expect(
          loginPanel(username, password),
          `${username} with the password of the previous release`,
        ).rejects.toMatchObject({ status: 401 });
      }
      const maven = packages.find((pkg) => pkg.adapter.protocol === 'maven') as Package;
      const wire = await maven.adapter.resolve({ ...maven.world, credential: ADMIN });
      expect(wire.httpStatus, 'a Maven client with the old admin password').toBe(401);

      // The printed passwords work, and only they.
      for (const [username, password] of printed) {
        const info = await loginPanel(username, password);
        expect(info.token, `${username} signs in with the printed password`).toBeTruthy();
      }

      // A deploy token is not a password: it keeps working through the reset.
      await expectEverythingConsumable(
        'after the upgrade, before any password is restored',
        'token',
        true,
      );

      // A plain user has no printed password: an admin resets it (the README's second way).
      await panelApi.login(env.adminUsername, printed.get(env.adminUsername) as string);
      const reset = await panelApi.rawRequest(
        'POST',
        `/api/users/${plainUser.id}/actions/reset-password`,
      );
      expect(reset.status, 'an admin resets the user').toBe(200);
      const newPassword = reset.body.data as string;
      expect(typeof newPassword).toBe('string');
      const info = await loginPanel(plainUser.username, newPassword);
      expect(info.token, 'the plain user signs in with the password the admin got').toBeTruthy();

      // The harness needs its admin password back for everything that follows (and for every other runner).
      await panelApi.changeOwnPassword(env.adminPassword);
      await loginPanel(env.adminUsername, env.adminPassword);
    });

    test('everything published before is consumed again with the real clients and listed by the panel', async () => {
      await panelApi.login(env.adminUsername, env.adminPassword);
      await expectEverythingConsumable('after the upgrade', 'admin', true);
      await expectEverythingConsumable('after the upgrade', 'token', true);

      for (const pkg of packages) {
        if (pkg.adapter.protocol !== 'docker') {
          await expectListedInPanel(panelApi, pkg, 'after the upgrade');
        }
        const tokensListed = (await panelApi.listDeployTokens(pkg.world.repoName)).map(
          (t) => t.name,
        );
        expect(tokensListed, `${pkg.world.repoName}: the deploy token is listed`).toContain(
          `upgrade-${pkg.adapter.protocol}`,
        );
      }
      const repos = await panelApi.listAllRepos({ q: `e2e-${seeder.runId}` });
      expect(
        repos.map((repo) => `${repo.type}:${repo.name}`).sort(),
        'the panel lists the three repositories with their types',
      ).toEqual(
        packages.map((pkg) => `${pkg.world.protocol.toUpperCase()}:${pkg.world.repoName}`).sort(),
      );
      const users = await panelApi.listAllUsers({ q: `e2e-${seeder.runId}` });
      expect(
        users.map((user) => `${user.role}:${user.username}`).sort(),
        'the panel lists the accounts of the previous release with their roles',
      ).toEqual([`ADMIN:${secondAdmin.username}`, `USER:${plainUser.username}`].sort());

      // Docker: the panel lists both images with their tags. The previous release never filled the image
      // row's size and digest (the listing shows them empty even before the upgrade, probed), so those two
      // are not asserted here: only that what was pushed is listed.
      const images = await panelApi.rawRequest(
        'GET',
        `/api/docker/images/${encodeURIComponent(docker.repoName)}?size=100`,
      );
      const listed = (images.body.data as { content?: { name?: string; tagCount?: number }[] })
        .content;
      expect(
        (listed ?? []).map((item) => `${item.name}:${item.tagCount}`).sort(),
        'the panel lists both images with their tag counts',
      ).toEqual(
        [
          `${docker.image}:${docker.tags.size}`,
          `${docker.multi.image}:3`, // amd64, arm64 and the index tag
        ].sort(),
      );
      const tagNames = await panelApi.rawRequest(
        'GET',
        `/api/docker/images/${encodeURIComponent(docker.repoName)}/${encodeURIComponent(docker.image)}/tags?size=100`,
      );
      expect(
        ((tagNames.body.data as { content?: { name?: string }[] }).content ?? [])
          .map((tag) => tag.name)
          .sort(),
        'the panel lists the tags of the image',
      ).toEqual([...docker.tags.keys()].sort());
    });

    test('the Docker manifest layout repair renames the legacy files, and a pull by tag or by digest still works', async () => {
      // Off in the first start: nothing was renamed, and everything above was served from the legacy names.
      expect(
        await logLinesContaining(container, 'Docker manifest layout repair'),
        'the repair is off',
      ).toEqual([]);
      expect(await manifestFiles(), 'the legacy files are untouched').toEqual(legacyManifestFiles);

      container = await recreateRepsy();
      await panelApi.login(env.adminUsername, env.adminPassword);
      const [line] = await waitForLogLines(
        container,
        /Docker manifest layout repair:/,
        REPAIR_TIMEOUT_MS,
      );
      expect(line, 'the repair job logs its result').toBeDefined();
      const report = /(\d+) repaired, (\d+) left as they are .*, (\d+) failed/.exec(line ?? '');
      expect(report, `the report has the documented shape: ${line}`).not.toBeNull();
      const wanted = [...docker.tags.values(), docker.multi.digest, ...docker.multi.children];
      expect(Number(report?.[1]), 'every distinct manifest is repaired, and no other').toBe(
        wanted.length,
      );
      expect(Number(report?.[2]), 'none is left unresolved').toBe(0);
      expect(Number(report?.[3]), 'none failed').toBe(0);

      const files = await manifestFiles();
      expect(
        files.filter((file) => file.includes('/manifests/manifest_')),
        'no manifest keeps a name derived from its tag',
      ).toEqual([]);
      for (const digest of wanted) {
        expect(
          files.some((file) => file.endsWith(`/manifests/${digest}`)),
          `manifests/${digest} exists`,
        ).toBe(true);
      }
      expect(
        await logLinesContaining(container, 'Admin password has been reset'),
        'the reset password is printed once, by the start that migrated',
      ).toEqual([]);

      await expectEverythingConsumable('after the layout repair', 'admin', true);
      await expectEverythingConsumable('after the layout repair', 'token', true);
    });
  },
);

/** The container's whole log (stdout and stderr): read it once, do not print it (it carries the reset passwords). */
async function fullLog(id: string): Promise<string> {
  const result = await run('docker', ['logs', id], { cwd: '/tmp', label: 'docker-logs' });
  return `${result.stdout}\n${result.stderr}`;
}

/** The Flyway versions the image under test carries for its database (`db/migration/<vendor>/` in the jar). */
async function migrationVersions(id: string): Promise<number[]> {
  const dbUrl = (await dockerExec(id, ['printenv', 'DB_URL'])).stdout;
  const vendor = dbUrl.startsWith('jdbc:h2:') ? 'h2' : 'postgresql';
  const listed = await dockerExec(id, [
    'unzip',
    '-l',
    '/app/app.jar',
    `BOOT-INF/classes/db/migration/${vendor}/V*`,
  ]);
  // A migration is a V<n>__<name>.sql script or, on H2 where a script cannot do it, a V<n><Name>.class Java migration.
  const versions = [
    ...new Set(
      listed.stdout
        .split('\n')
        .map((entry) => /\/V(\d+)(?:__|[A-Z])/.exec(entry)?.[1])
        .filter((version): version is string => version !== undefined)
        .map(Number),
    ),
  ];
  expect(versions.length, `${vendor} migrations in the jar`).toBeGreaterThan(
    PREVIOUS_SCHEMA_VERSION,
  );
  return versions;
}

/** Puts the admin's password back to `REPSY_ADMIN_PASSWORD` (the upgrade reset it), from the printed one. */
async function restoreAdminPassword(): Promise<void> {
  try {
    await loginPanel(env.adminUsername, env.adminPassword);
    return;
  } catch (err) {
    if (!(err instanceof PanelHttpError)) {
      throw err;
    }
  }
  let password = printed.get(env.adminUsername);
  if (!password) {
    // A failed test may not have read it yet: the log of the container that migrated still has it.
    const lines = await logLinesContaining(container, 'Admin password has been reset for user');
    password = lines
      .map((line) => RESET_LINE.exec(line))
      .find((m) => m?.[1] === env.adminUsername)?.[2];
  }
  if (!password) {
    throw new Error(
      `the admin password was reset and no printed one was read for ${env.adminUsername}`,
    );
  }
  const api = await createPanelBackend();
  await api.login(env.adminUsername, password);
  await api.changeOwnPassword(env.adminPassword);
}
