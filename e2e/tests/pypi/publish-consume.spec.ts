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
 * The scenario-driven pypi suite (step 4c, RPS-294): `registerPublishConsumeLoop(pypiAdapter)` wires
 * the whole shared catalog into pypi, exactly like `tests/nuget/publish-consume.spec.ts` does for
 * nuget. Plus two hand-built-`World` real-client tests the catalog loop itself cannot exercise:
 *
 *  - "real pip install, offline, from a real pip download" (H19): proves the hand-built wheel is not
 *    merely byte-transportable but actually INSTALLABLE by a real `pip install` -- the `WHEEL`/
 *    `RECORD` metadata this harness writes (`pypi-raw.ts`'s `buildWheel`) has to be well-formed
 *    enough for pip's own installer, not just for twine's `parse_email`.
 *  - "mixed-case/dotted package name real client round trip" (H21): pins PEP 503 normalization
 *    end-to-end with the REAL clients -- `twine upload` under a mixed-case, dotted name, a raw
 *    non-normalized `GET` 307-redirecting to the normalized project page, and `pip download`
 *    resolving the same (canonicalized) name to the exact bytes that were published.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import * as pypi from '../../src/clients/pypi.js';
import { pipEnv, pypiAdapter } from '../../src/clients/pypi.js';
import { adminCredential, distName, rawGetSimplePageNoFollow } from '../../src/clients/pypi-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(pypiAdapter);

test(
  'pypi > real pip install, offline, from a real pip download (H19)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
    const credential = adminCredential();
    const packageName = `e2e-${seeder.runId}-pipinstall`;
    const version = pypiAdapter.version('release');
    const target: Coordinates = { packageName, version };
    const scenario: Scenario = {
      id: 'pip-install',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'pypi',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await pypi.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; twine ` +
        `exit ${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `twine upload: ${published.command}`).toBe(0);

    const { home, work } = await isolatedWorkDir(`pypi-pipinstall-${seeder.runId}`);
    const destDir = path.join(work, 'downloads');
    await fs.mkdir(destDir, { recursive: true });

    const dlResult = await run(
      'python3',
      [
        '-m',
        'pip',
        'download',
        '--no-deps',
        '--only-binary=:all:',
        '--no-cache-dir',
        '--dest',
        destDir,
        `${packageName}==${version}`,
      ],
      {
        cwd: work,
        env: pipEnv(home, credential, repo.name),
        timeoutMs: 120_000,
        label: 'pypi-pipinstall-download',
      },
    );
    expect(dlResult.exitCode, `pip download: ${dlResult.command}`).toBe(0);

    const installDir = path.join(work, 'install');
    await fs.mkdir(installDir, { recursive: true });

    // --no-index --find-links: a purely OFFLINE install off the file `pip download` just fetched,
    // so this step proves installability of the wheel itself, not the registry again.
    const installResult = await run(
      'python3',
      [
        '-m',
        'pip',
        'install',
        '--no-deps',
        '--no-index',
        '--find-links',
        destDir,
        '--target',
        installDir,
        `${packageName}==${version}`,
      ],
      {
        cwd: work,
        env: pipEnv(home, credential, repo.name),
        timeoutMs: 120_000,
        label: 'pypi-pipinstall-install',
      },
    );
    expect(installResult.exitCode, `pip install: ${installResult.command}`).toBe(0);

    const dist = distName(packageName);
    const markerPath = path.join(installDir, dist, 'e2e_marker.txt');
    const markerContent = await fs.readFile(markerPath, 'utf8');
    expect(markerContent.trim().length, 'the marker file survived the install').toBeGreaterThan(0);

    const initPath = path.join(installDir, dist, '__init__.py');
    await expect(fs.stat(initPath)).resolves.toBeDefined();

    const distInfoEntries = await fs.readdir(installDir);
    expect(
      distInfoEntries.some((e) => e.startsWith(`${dist}-${version}.dist-info`)),
      `a ${dist}-${version}.dist-info directory (RECORD honored): ${distInfoEntries.join(', ')}`,
    ).toBe(true);
  },
);

test(
  'pypi > mixed-case/dotted package name real client round trip (H21)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
    const credential = adminCredential();
    // Dots + mixed case only (no hyphens): PEP 503 normalization collapses runs of `[-_.]` to a
    // single `-` and lower-cases, so this exercises both rules without the wheel-filename's own
    // "no double underscore" ambiguity a hyphen-adjacent-to-dot name would raise.
    const rawName = `E2E.${seeder.runId}.MixedCase`;
    const normalizedName = rawName.replace(/[-_.]+/g, '-').toLowerCase();
    const version = pypiAdapter.version('release');
    const target: Coordinates = { packageName: rawName, version };
    const scenario: Scenario = {
      id: 'mixed-case-name',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'pypi',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await pypi.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; twine ` +
        `exit ${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `twine upload: ${published.command}`).toBe(0);

    // H14/H8: a raw GET of the RAW (non-normalized) name 307-redirects to the normalized page, at
    // exactly this repo's own single-segment URL.
    const noFollow = await rawGetSimplePageNoFollow(repo.name, credential, rawName);
    expect(noFollow.status, 'the raw name redirects to the normalized project page').toBe(307);
    expect(noFollow.location, 'the redirect names the normalized page').toBe(
      repoUrl(repo.name, `simple/${normalizedName}/`),
    );

    // pip itself canonicalizes the requirement name (packaging.utils.canonicalize_name) before
    // building the request, so a real `pip download` of the RAW name still resolves.
    const resolved = await pypi.resolve(world);
    expect(
      resolved.outcome,
      `resolve: expected "ok", got "${resolved.outcome}" (http ${resolved.httpStatus})`,
    ).toBe('ok');
    expect(resolved.clientExitCode, `pip download: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved wheel (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);
