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
 * The scenario-driven nuget suite (step 3c, RPS-294): `registerPublishConsumeLoop(nugetAdapter)`
 * wires the whole shared catalog into nuget, exactly like `tests/cargo/publish-consume.spec.ts` does
 * for cargo. Plus two hand-built-`World` real-client tests the catalog loop itself cannot exercise:
 *
 *  - "api-key-only push" pins the panel's "Option B" token flow LITERALLY: `--api-key <deploy token>`
 *    with a `nuget.config` that has NO `packageSourceCredentials` at all, so the push can only
 *    succeed via `X-NuGet-ApiKey` alone (confirmed live, see README.md's "H1"/"H2"). The follow-up
 *    restore uses a normal, credentialed config (nothing about restore involves an api-key).
 *  - "mixed-case package id round trip" pins RPS-1200-adjacent-but-distinct storage behaviour (H8):
 *    `findOrCreatePackage` stores `packageId.toLowerCase()`, so a package published as
 *    `E2E-<runid>-MixedCase` is served/restored under the lowercase spelling, and the registration
 *    echoes that lowercase spelling too, never the one it was actually published under.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import * as nuget from '../../src/clients/nuget.js';
import {
  nugetAdapter,
  renderConsumerProject,
  renderNugetConfig,
  nugetEnv,
} from '../../src/clients/nuget.js';
import {
  adminCredential,
  buildNupkg,
  normalizeVersion,
  parseRegistrationIndex,
  parseVersions,
  rawGetRegistrationIndex,
  rawGetVersions,
} from '../../src/clients/nuget-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(nugetAdapter);

test(
  'nuget > api-key-only push (Option B), no packageSourceCredentials at all',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential = {
      transport: 'basic' as const,
      username: token.username,
      password: token.token,
      kind: 'token' as const,
    };
    const packageId = `e2e-${seeder.runId}-apikeyonly`;
    const version = nugetAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`nuget-apikeyonly-push-${seeder.runId}`);
    const nupkgBytes = buildNupkg({ packageId, version });
    const nupkgFile = path.join(work, 'package.nupkg');
    await fs.writeFile(nupkgFile, nupkgBytes);

    // includeCredentials: false -- the config carries ONLY the source, no packageSourceCredentials,
    // so a successful push proves X-NuGet-ApiKey alone authenticated it.
    const pushCfg = await renderNugetConfig(home, repo.name, credential, {
      includeCredentials: false,
    });

    const pushResult = await run(
      'dotnet',
      [
        'nuget',
        'push',
        nupkgFile,
        '--source',
        'repsy',
        '--configfile',
        pushCfg,
        '--allow-insecure-connections',
        '--no-symbols',
        '--timeout',
        '60',
        '--api-key',
        credential.password,
      ],
      {
        cwd: work,
        env: nugetEnv(home),
        timeoutMs: 120_000,
        redact: [credential.password],
        label: 'nuget-apikeyonly-push',
      },
    );
    expect(pushResult.exitCode, `dotnet nuget push: ${pushResult.command}`).toBe(0);

    const idLower = packageId.toLowerCase();
    const verLower = normalizeVersion(version);
    const versionsRes = await rawGetVersions(repo.name, adminCredential(), idLower);
    expect(versionsRes.status, 'the pushed version is listed').toBe(200);
    expect(parseVersions(versionsRes.body)).toContain(verLower);

    // The follow-up restore uses a normal, credentialed config -- restore has no api-key concept.
    const { home: restoreHome, work: restoreWork } = await isolatedWorkDir(
      `nuget-apikeyonly-restore-${seeder.runId}`,
    );
    const csprojPath = await renderConsumerProject(restoreWork, packageId, version);
    const restoreCfg = await renderNugetConfig(restoreHome, repo.name, credential);
    const restoreResult = await run(
      'dotnet',
      [
        'restore',
        csprojPath,
        '--configfile',
        restoreCfg,
        '--packages',
        path.join(restoreHome, 'nuget-packages'),
        '--no-http-cache',
        '--disable-build-servers',
        '-p:NuGetAudit=false',
        '-v',
        'minimal',
      ],
      {
        cwd: restoreWork,
        env: nugetEnv(restoreHome),
        timeoutMs: 120_000,
        redact: [credential.password],
        label: 'nuget-apikeyonly-restore',
      },
    );
    expect(restoreResult.exitCode, `dotnet restore: ${restoreResult.command}`).toBe(0);
  },
);

test(
  'nuget > mixed-case package id real client round trip',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NUGET, { privateRepo: true });
    const credential = adminCredential();
    const packageId = `E2E-${seeder.runId}-MixedCase`;
    const version = nugetAdapter.version('release');
    const target: Coordinates = { packageName: packageId, version };
    const scenario: Scenario = {
      id: 'mixed-case-id',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'nuget',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await nuget.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; dotnet ` +
        `exit ${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `dotnet nuget push: ${published.command}`).toBe(0);

    const idLower = packageId.toLowerCase();
    const verLower = normalizeVersion(version);

    // H8, confirmed live: the flat version list is keyed by the LOWERCASED id -- the mixed-case
    // spelling itself is never queryable.
    const versionsRes = await rawGetVersions(repo.name, credential, idLower);
    expect(versionsRes.status, 'looked up by the lowercased id').toBe(200);
    expect(parseVersions(versionsRes.body)).toContain(verLower);

    const regRes = await rawGetRegistrationIndex(repo.name, credential, idLower);
    expect(regRes.status).toBe(200);
    const leaf = parseRegistrationIndex(regRes.body).find((l) => l.version === verLower);
    expect(leaf, `a registration leaf for version "${verLower}"`).toBeDefined();
    // H8, confirmed live: the registration ECHOES the lowercased, stored spelling, never the
    // mixed-case one this test actually published under (`findOrCreatePackage` stores
    // `packageId.toLowerCase()`).
    expect(
      leaf?.packageId,
      `the registration echoes "${leaf?.packageId}" for a package published as "${packageId}"`,
    ).toBe(idLower);

    const resolved = await nuget.resolve(world);
    expect(
      resolved.outcome,
      `resolve: the auth-only versions-list GET should still succeed (http ${resolved.httpStatus})`,
    ).toBe('ok');
    expect(resolved.clientExitCode, `dotnet restore: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved nupkg (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);
