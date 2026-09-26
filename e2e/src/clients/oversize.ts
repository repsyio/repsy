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

/**
 * One push of a package of a chosen size with the REAL client of a protocol, plus a raw replay of the
 * same bytes on the wire, for the size-limit leg (RPS-1482, README.md "Size-limit leg",
 * `tests/<protocol>/size-limits.spec.ts`). The catalog loop cannot do this: its adapters build the
 * smallest package there is, and they report a client's exit code but never what it prints or the
 * body of the server's answer, which is what a size limit is about (the exit code says only "it did
 * not work"; the message says why; the raw replay says which status and which JSON the server sent).
 *
 * Every function copies the client invocation of the protocol's adapter (`pypi.ts`, `nuget.ts`,
 * `helm-classic.ts`, `ruby.ts`, `cargo.ts`, `golang.ts`: the same environment, arguments and
 * timeouts), builds the package with `padBytes` of random padding (`padding.ts`), and replays the
 * package to the route the client uses with `fetch`. Whether anything was stored is asked of the
 * adapter afterwards (`adapter.fingerprint`, `adapter.expectNothingStored`), never of these.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { expect } from '@playwright/test';

import type { Scenario } from '../scenarios/types.js';
import type { Coordinates, MaterializedCredential, World } from '../scenarios/world.js';
import { renderCrate, cargoEnv, renderCargoConfig } from './cargo.js';
import { buildPublishBody, rawPublish as rawCargoPublish } from './cargo-raw.js';
import { env } from '../env.js';
import { isolatedWorkDir, run } from './exec.js';
import { gemEnv } from './ruby.js';
import { buildGem, rawPublish as rawRubyPublish } from './ruby-raw.js';
import {
  buildModuleZip,
  rawUpload as rawGoUpload,
  uploadUrl as goUploadUrl,
} from './golang-raw.js';
import { buildChart, writeChartFile } from './helm-chart.js';
import { helmEnv } from './helm.js';
import { chartFileName, classicRepoUrl, rawUploadChart } from './helm-raw.js';
import { nugetEnv, renderNugetConfig } from './nuget.js';
import { buildNupkg, nugetApiKey, rawPublish as rawNugetPublish } from './nuget-raw.js';
import { buildWheel, rawUpload as rawPypiUpload, uploadUrl as pypiUploadUrl } from './pypi-raw.js';
import { twineEnv } from './pypi.js';
import { clientEnv } from './client-env.js';
import type { RawResponse } from './raw-http.js';

const TIMEOUT_MS = 120_000;

/** What one push produced: the real client's side, and the raw replay of the same bytes. */
export interface OversizePush {
  exitCode: number;
  /** stdout and stderr of the client, for the message it prints. */
  output: string;
  /** The (redacted) command line, for failure messages. */
  command: string;
  /** The raw replay of the package: what the server answered on the route the client uses. */
  replay: RawResponse;
  /** Size of the package the client was given. */
  packageBytes: number;
  /** `curl` only: its own report of the HTTP status (`%{http_code}`). */
  httpStatus?: number;
}

/** The scenario of a hand-built `World` (the catalog's own are about auth and settings): its `id` is
 *  what `adapter.packageName` slugs into the package name. */
export const SIZE_LIMIT_SCENARIO: Scenario = {
  id: 'size-limit',
  tags: ['@limits'],
  repo: { privateRepo: true },
  credential: 'token-rw',
  expect: { publish: 'rejected', consume: 'rejected' },
};

/** A hand-built `World` for one push (the fixture's `world` is for the catalog): a fresh private repo
 *  of `protocol`'s type, the credential the caller made for it and the package to push. */
export function oversizeWorld(
  protocol: string,
  repoName: string,
  credential: MaterializedCredential,
  target: Coordinates,
): World {
  return {
    scenario: SIZE_LIMIT_SCENARIO,
    protocol,
    repoName,
    credential,
    publishTarget: target,
    consumeTarget: target,
  };
}

function secretsOf(credential: MaterializedCredential): string[] {
  return credential.password ? [credential.password] : [];
}

/** `python3 -m twine upload` of a wheel padded with `padBytes` (`pypi.ts` `publishWithClient`). */
export async function pushPypi(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;
  const built = buildWheel({ name: packageName, version, marker: randomUUID(), padBytes });
  const distDir = path.join(work, 'dist');
  await fs.mkdir(distDir, { recursive: true });
  const wheelFile = path.join(distDir, built.filename);
  await fs.writeFile(wheelFile, built.bytes);

  const result = await run(
    'python3',
    [
      '-m',
      'twine',
      'upload',
      '--non-interactive',
      '--disable-progress-bar',
      '--repository-url',
      pypiUploadUrl(world.repoName),
      wheelFile,
    ],
    {
      cwd: work,
      env: twineEnv(home, world.credential),
      timeoutMs: TIMEOUT_MS,
      redact: secretsOf(world.credential),
      label,
    },
  );
  const replay = await rawPypiUpload(world.repoName, world.credential, built);
  return {
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
    command: result.command,
    replay,
    packageBytes: built.bytes.length,
  };
}

/** `dotnet nuget push` of a `.nupkg` padded with `padBytes` (`nuget.ts` `publishWithClient`). */
export async function pushNuget(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: packageId, version } = world.publishTarget;
  const nupkgBytes = buildNupkg({ packageId, version, marker: randomUUID(), padBytes });
  const nupkgFile = path.join(work, 'package.nupkg');
  await fs.writeFile(nupkgFile, nupkgBytes);

  const cfgPath = await renderNugetConfig(home, world.repoName, world.credential);
  const apiKey = nugetApiKey(world.credential);
  const args = [
    'nuget',
    'push',
    nupkgFile,
    '--source',
    'repsy',
    '--configfile',
    cfgPath,
    '--allow-insecure-connections',
    '--no-symbols',
    '--timeout',
    '60',
  ];
  if (apiKey !== undefined) {
    args.push('--api-key', apiKey);
  }
  const result = await run('dotnet', args, {
    cwd: work,
    env: nugetEnv(home),
    timeoutMs: TIMEOUT_MS,
    redact: [...secretsOf(world.credential), ...(apiKey ? [apiKey] : [])],
    label,
  });
  const replay = await rawNugetPublish(world.repoName, world.credential, nupkgBytes);
  return {
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
    command: result.command,
    replay,
    packageBytes: nupkgBytes.length,
  };
}

/** `helm cm-push <tgz> <repo url>` of a chart padded with `padBytes` (`helm-classic.ts`
 *  `publishWithClient`). `cm-push` repackages the chart, so the replay sends the built `.tgz`, which is
 *  as big (the padding is incompressible). */
export async function pushHelm(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: chart, version } = world.publishTarget;
  const built = await buildChart({ name: chart, version, marker: randomUUID(), padBytes });
  const tgzFile = await writeChartFile(work, built);

  const credentialArgs =
    world.credential.transport === 'basic'
      ? [
          '--username',
          world.credential.username ?? '',
          '--password',
          world.credential.password ?? '',
        ]
      : [];
  const result = await run(
    'helm',
    ['cm-push', tgzFile, classicRepoUrl(world.repoName), ...credentialArgs],
    {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: TIMEOUT_MS,
      redact: secretsOf(world.credential),
      label,
    },
  );
  const replay = await rawUploadChart(
    world.repoName,
    world.credential,
    built.tgzBytes,
    chartFileName(chart, version),
  );
  return {
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
    command: result.command,
    replay,
    packageBytes: built.tgzBytes.length,
  };
}

/** `gem push <file> --host <repo>` of a gem padded with `padBytes` (`ruby.ts` `publishWithClient`). */
export async function pushRuby(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;
  const built = await buildGem({ name: packageName, version, marker: randomUUID(), padBytes });
  const gemFile = path.join(work, built.filename);
  await fs.writeFile(gemFile, built.bytes);

  const result = await run(
    'gem',
    ['push', gemFile, '--host', `${env.repoBaseUrl}/${world.repoName}`],
    {
      cwd: work,
      env: gemEnv(home, world.credential),
      timeoutMs: TIMEOUT_MS,
      redact: secretsOf(world.credential),
      label,
      input: '',
    },
  );
  const replay = await rawRubyPublish(world.repoName, world.credential, built.bytes);
  return {
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
    command: result.command,
    replay,
    packageBytes: built.bytes.length,
  };
}

/** `cargo publish --registry repsy --no-verify` of a crate padded with `padBytes` (`cargo.ts`
 *  `publishWithClient`); the replay is the packaged `.crate` in Cargo's own publish body. */
export async function pushCargo(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName, version } = world.publishTarget;
  await renderCrate(work, packageName, version, padBytes);
  await renderCargoConfig(work, world.repoName);

  const packaged = await run('cargo', ['package', '--no-verify', '--offline'], {
    cwd: work,
    env: cargoEnv(home, {}),
    timeoutMs: TIMEOUT_MS,
    label: `${label}-package`,
  });
  expect(packaged.exitCode, `cargo package: ${packaged.command}\n${packaged.stderr}`).toBe(0);
  const crateBytes = await fs.readFile(
    path.join(work, 'target', 'package', `${packageName}-${version}.crate`),
  );

  const result = await run('cargo', ['publish', '--registry', 'repsy', '--no-verify'], {
    cwd: work,
    env: { ...cargoEnv(home, world.credential), CARGO_PUBLISH_TIMEOUT: '30' },
    timeoutMs: TIMEOUT_MS,
    redact: secretsOf(world.credential),
    label,
  });
  const replay = await rawCargoPublish(
    world.repoName,
    world.credential,
    buildPublishBody({ name: packageName, version, crateBytes }),
  );
  return {
    exitCode: result.exitCode,
    output: `${result.stdout}\n${result.stderr}`,
    command: result.command,
    replay,
    packageBytes: crateBytes.length,
  };
}

/** `curl -T` of a Go module zip padded with `padBytes` to the `.zip` URL (`golang.ts`
 *  `publishWithClient`, the panel's documented incantation, `--fail-with-body` included). */
export async function pushGolang(
  world: World,
  padBytes: number,
  label: string,
): Promise<OversizePush> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: modulePath, version } = world.publishTarget;
  const built = await buildModuleZip({ modulePath, version, padBytes });
  const zipFile = path.join(work, 'module.zip');
  await fs.writeFile(zipFile, built.bytes);
  const responseFile = path.join(work, 'response.body');

  const credential = world.credential;
  const args = [
    '-sS',
    '--fail-with-body',
    '--max-time',
    '60',
    '-o',
    responseFile,
    '-w',
    '%{http_code}',
  ];
  if (credential.transport === 'basic') {
    args.push('-u', `${credential.username ?? ''}:${credential.password ?? ''}`);
  }
  args.push(
    '-T',
    zipFile,
    '-H',
    `Content-Sha256: ${built.sha256Hex}`,
    '-H',
    'Content-Type: application/zip',
    goUploadUrl(world.repoName, modulePath, version),
  );
  const result = await run('curl', args, {
    cwd: work,
    env: clientEnv(home),
    timeoutMs: TIMEOUT_MS,
    redact: secretsOf(credential),
    label,
  });
  const responseBody = await fs.readFile(responseFile, 'utf8').catch(() => '');
  const replay = await rawGoUpload(world.repoName, world.credential, built);
  return {
    exitCode: result.exitCode,
    // curl's own stderr line (`curl: (22) ...`) and the body it saved with --fail-with-body.
    output: `${result.stdout}\n${result.stderr}\n${responseBody}`,
    command: result.command,
    replay,
    packageBytes: built.bytes.length,
    httpStatus: Number.parseInt(result.stdout.trim(), 10) || 0,
  };
}
