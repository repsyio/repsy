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
 * RPS-1717: the REAL, interactive `npm login` -- unscoped and with `--scope`, documented in
 * repsy-docs as `npm login --scope foo --registry ...` -- driven through a real pseudo-terminal
 * (`npm-login.ts`'s `script(1)` fallback; no PTY library is vendored here) instead of the `.npmrc`
 * every other npm spec writes directly. A deploy token as the password with an arbitrary username
 * (RPS-1045) is covered too. Every "login succeeded" case is proved with a REAL follow-up
 * `npm publish`/`npm install` using exactly the credential state login wrote, not just an exit code
 * -- see `npm-login.ts`'s header for the prompt-mechanics evidence (npm's `login` command asks only
 * Username/Password, and the web-login attempt Repsy 404s on falls back to the couch prompts).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { npmLoginInteractive } from '../../src/clients/npm-login.js';
import { adminCredential, parsePackument, rawGetPackument } from '../../src/clients/npm-raw.js';
import {
  MARKER_FILENAME,
  npmAdapter,
  npmEnv,
  packTarball,
  renderPackage,
} from '../../src/clients/npm.js';
import { env } from '../../src/env.js';
import { boundedSemverVersion } from '../../src/scenarios/coordinates.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { World } from '../../src/scenarios/world.js';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

const BASE_SCENARIO: Scenario = {
  id: 'npm-login-seed',
  tags: [],
  repo: { privateRepo: true },
  credential: 'admin-password',
  expect: { publish: 'ok', consume: 'ok' },
};

/** Reads the marker file `npm install` placed under a consumer's `node_modules`, or `undefined` when
 *  the package never landed (a failed/refused install). */
async function readMarker(work: string, packageName: string): Promise<string | undefined> {
  try {
    return await fs.readFile(
      path.join(work, 'node_modules', ...packageName.split('/'), MARKER_FILENAME),
      'utf8',
    );
  } catch {
    return undefined;
  }
}

/**
 * A real `npm publish` of a freshly rendered package, using an ALREADY-authenticated `.npmrc` (the
 * one `npm login` itself just wrote) instead of the harness's usual `renderNpmrc`. Proves the
 * written credential is not just accepted by the server but usable by the same client that wrote it.
 */
async function publishWithLoggedInNpmrc(
  npmrcPath: string,
  cacheDir: string,
  packageName: string,
  version: string,
  label: string,
): Promise<{ exitCode: number; command: string; marker: string }> {
  const { home, work } = await isolatedWorkDir(label);
  const { marker } = await renderPackage(work, packageName, version);
  const { file: tarballFile } = await packTarball(work, home, `${label}-pack`);

  const result = await run(
    'npm',
    ['publish', tarballFile, '--userconfig', npmrcPath, '--cache', cacheDir, '--ignore-scripts'],
    { cwd: work, env: npmEnv(home), timeoutMs: PUBLISH_TIMEOUT_MS, label },
  );

  return { exitCode: result.exitCode, command: result.command, marker };
}

/**
 * A real `npm install <packageName>@<version>`, using an already-authenticated `.npmrc`, with NO
 * `--registry` flag of its own -- whatever registry the credential's own `.npmrc` (or scope mapping)
 * names is what resolves it.
 */
async function installWithLoggedInNpmrc(
  npmrcPath: string,
  cacheDir: string,
  packageName: string,
  version: string,
  label: string,
): Promise<{ exitCode: number; command: string; marker: string | undefined }> {
  const { home, work } = await isolatedWorkDir(label);
  await fs.writeFile(
    path.join(work, 'package.json'),
    JSON.stringify({ name: `e2e-consumer-${label}`, version: '0.0.0', private: true }, null, 2),
    'utf8',
  );

  const result = await run(
    'npm',
    [
      'install',
      `${packageName}@${version}`,
      '--userconfig',
      npmrcPath,
      '--cache',
      cacheDir,
      '--ignore-scripts',
      '--no-save',
      '--no-package-lock',
      '--no-audit',
      '--no-fund',
    ],
    { cwd: work, env: npmEnv(home), timeoutMs: CONSUME_TIMEOUT_MS, label },
  );

  return {
    exitCode: result.exitCode,
    command: result.command,
    marker: await readMarker(work, packageName),
  };
}

/** Seeds `packageName`@`version` with `credential` through the ordinary (non-interactive) adapter
 *  path, for a test whose interactive-login half is about READING what is already there. */
async function seed(
  repoName: string,
  packageName: string,
  version: string,
  credential: World['credential'],
): Promise<void> {
  await npmAdapter.seedPublish({
    scenario: BASE_SCENARIO,
    protocol: 'npm',
    repoName,
    credential,
    publishTarget: { packageName, version },
    consumeTarget: { packageName, version },
  });
}

test.describe('npm login (real interactive client, RPS-1717)', () => {
  test(
    'unscoped: real username/password prompts write a working credential (real publish + install round-trip)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

      const attempt = await npmLoginInteractive({
        repoName: repo.name,
        username: env.adminUsername,
        password: env.adminPassword,
        persistRegistry: true,
        label: `npm-login-unscoped-${seeder.runId}`,
      });

      expect(attempt.exitCode, `npm login: ${attempt.command}\n${attempt.output}`).toBe(0);
      expect(
        attempt.npmrcContents,
        'the interactive login wrote a Bearer token into the userconfig file',
      ).toMatch(/:_authToken=/);

      const packageName = `e2e-${seeder.runId}-login-unscoped`;
      const version = boundedSemverVersion();

      const published = await publishWithLoggedInNpmrc(
        attempt.npmrcPath,
        attempt.cacheDir,
        packageName,
        version,
        `npm-login-publish-${seeder.runId}`,
      );
      expect(
        published.exitCode,
        `npm publish with the login-written credential: ${published.command}`,
      ).toBe(0);

      // The registry really has it (not just "the client exited 0"): a raw admin GET sees the version.
      const packument = await rawGetPackument(repo.name, adminCredential(), packageName);
      expect(packument.status, 'the packument exists after the real publish').toBe(200);
      expect(Object.keys(parsePackument(packument.body).versions)).toContain(version);

      const installed = await installWithLoggedInNpmrc(
        attempt.npmrcPath,
        attempt.cacheDir,
        packageName,
        version,
        `npm-login-install-${seeder.runId}`,
      );
      expect(
        installed.exitCode,
        `npm install with the login-written credential: ${installed.command}`,
      ).toBe(0);
      expect(
        installed.marker,
        'the installed package has the exact marker the login-authenticated publish wrote',
      ).toBe(published.marker);
    },
  );

  test('scoped (--scope): the saved scope-to-registry mapping alone resolves an install', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const scope = `@e2e-${seeder.runId}-scope`;
    const packageName = `${scope}/login-scoped`;
    const version = boundedSemverVersion();

    // Seed the package with the ordinary (non-interactive) admin credential; this test is about
    // whether the SCOPED login's saved mapping resolves the install, not about publish.
    await seed(repo.name, packageName, version, adminCredential());

    const attempt = await npmLoginInteractive({
      repoName: repo.name,
      username: env.adminUsername,
      password: env.adminPassword,
      scope,
      // Deliberately NOT pre-seeded with a bare `registry=`: only `<scope>:registry=` (which
      // `--scope` makes `npm login` persist) may resolve this install, or the proof is worthless.
      persistRegistry: false,
      label: `npm-login-scoped-${seeder.runId}`,
    });

    expect(attempt.exitCode, `npm login --scope: ${attempt.command}\n${attempt.output}`).toBe(0);
    expect(attempt.npmrcContents, 'the scope-to-registry mapping was saved').toContain(
      `${scope}:registry=`,
    );
    expect(attempt.npmrcContents, 'the interactive login wrote a Bearer token').toMatch(
      /:_authToken=/,
    );

    const installed = await installWithLoggedInNpmrc(
      attempt.npmrcPath,
      attempt.cacheDir,
      packageName,
      version,
      `npm-login-scoped-install-${seeder.runId}`,
    );
    expect(
      installed.exitCode,
      `npm install of a scoped package with no --registry, relying on the login's own scope ` +
        `mapping: ${installed.command}`,
    ).toBe(0);
    expect(installed.marker, 'the installed content matches the seeded publish').toBeTruthy();
  });

  test('a deploy-token secret as the password, with an arbitrary username, logs in (RPS-1045)', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const packageName = `e2e-${seeder.runId}-login-token`;
    const version = boundedSemverVersion();

    // Seed content with the token itself over the ordinary (non-interactive) path, so the
    // interactive login below only has to prove it can READ what is already there.
    await seed(repo.name, packageName, version, {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    });

    const attempt = await npmLoginInteractive({
      repoName: repo.name,
      // A deploy token's secret authenticates regardless of the username typed alongside it
      // (RPS-1045): a name that belongs to no real account.
      username: `not-a-real-user-${seeder.runId}`,
      password: token.token,
      persistRegistry: true,
      label: `npm-login-token-${seeder.runId}`,
    });

    expect(
      attempt.exitCode,
      `npm login with a deploy token: ${attempt.command}\n${attempt.output}`,
    ).toBe(0);
    expect(attempt.npmrcContents, 'a Bearer token was written').toMatch(/:_authToken=/);

    const installed = await installWithLoggedInNpmrc(
      attempt.npmrcPath,
      attempt.cacheDir,
      packageName,
      version,
      `npm-login-token-install-${seeder.runId}`,
    );
    expect(
      installed.exitCode,
      `npm install with the token-login credential: ${installed.command}`,
    ).toBe(0);
    expect(installed.marker, 'the installed content matches the seeded publish').toBeTruthy();
  });

  test(
    'wrong password is refused, not silently accepted',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });

      const attempt = await npmLoginInteractive({
        repoName: repo.name,
        username: env.adminUsername,
        password: `${env.adminPassword}-definitely-wrong`,
        persistRegistry: true,
        label: `npm-login-wrong-${seeder.runId}`,
      });

      expect(attempt.exitCode, 'npm login with a wrong password must not exit 0').not.toBe(0);
      expect(
        attempt.npmrcContents,
        'a refused login must not write ANY credential line into the userconfig file',
      ).not.toMatch(/:_authToken=|:_auth=/);

      // ... and the untouched userconfig genuinely cannot do anything: no working credential exists.
      const installed = await installWithLoggedInNpmrc(
        attempt.npmrcPath,
        attempt.cacheDir,
        `e2e-${seeder.runId}-login-wrong-never-installed`,
        '1.0.0',
        `npm-login-wrong-install-${seeder.runId}`,
      );
      expect(
        installed.exitCode,
        'no credential means no install, refused client-side or by the server',
      ).not.toBe(0);
    },
  );
});
