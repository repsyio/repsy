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
 * Package fixtures for the npm-family suite (RPS-1330): a tiny, dependency-free package (`package.json`,
 * `index.js`, and a fresh random marker file, so two publishes never share content and an install can
 * prove it got the published bytes), optionally with dependencies on other packages that live in the
 * same repository under test (a "lib + app" graph -- never a third-party package, since OS has no
 * proxy repository and the network is sealed) and with extra manifest fields (`os`, `cpu`,
 * `peerDependenciesMeta`, ...). Rendered from `src/packages/npm-family/*.template.*`.
 *
 * `publishPackage` is the one-call "render, pack, publish" every matrix spec starts from.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import mustache from 'mustache';

import { RepoType } from '../../api/panel-api.js';
import { adminCredential } from '../raw-http.js';
import type { MaterializedCredential } from '../../scenarios/world.js';
import type { Seeder, SeededRepo } from '../../seed/seeder.js';
import type { RunResult } from '../exec.js';
import { MARKER_FILENAME } from '../npm.js';
import type { ClientCtx, NpmFamilyClient, PackedTarball, RegistryBinding } from './client.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '../../packages/npm-family');

export interface PackageSpec {
  packageName: string;
  version: string;
  dependencies?: Record<string, string>;
  optionalDependencies?: Record<string, string>;
  /** Extra top-level package.json fields (`os`, `cpu`, `libc`, `funding`, `peerDependenciesMeta`, ...). */
  manifest?: Record<string, unknown>;
}

async function renderFile(templateName: string, view: Record<string, unknown>): Promise<string> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, templateName), 'utf8');
  return mustache.render(template, view);
}

/** Renders the package into `dir`; returns the marker written into it. */
export async function renderPackage(dir: string, spec: PackageSpec): Promise<{ marker: string }> {
  await fs.mkdir(dir, { recursive: true });

  const manifest = JSON.parse(
    await renderFile('package.template.json', {
      packageName: spec.packageName,
      version: spec.version,
    }),
  ) as Record<string, unknown>;
  if (spec.dependencies) {
    manifest.dependencies = spec.dependencies;
  }
  if (spec.optionalDependencies) {
    manifest.optionalDependencies = spec.optionalDependencies;
  }
  Object.assign(manifest, spec.manifest ?? {});

  await fs.writeFile(path.join(dir, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  await fs.writeFile(path.join(dir, 'index.js'), await renderFile('index.template.js', {}));
  const marker = randomUUID();
  await fs.writeFile(path.join(dir, MARKER_FILENAME), marker, 'utf8');
  return { marker };
}

/** A source-free consumer project in `dir`: its own `package.json`, optionally with dependencies. */
export async function renderConsumer(
  dir: string,
  name: string,
  dependencies?: Record<string, string>,
): Promise<void> {
  await fs.mkdir(dir, { recursive: true });
  const manifest: Record<string, unknown> = {
    name,
    version: '0.0.0',
    private: true,
    ...(dependencies ? { dependencies } : {}),
  };
  await fs.writeFile(path.join(dir, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`);
}

export interface PublishedPackage {
  spec: PackageSpec;
  /** The directory the package was rendered and packed in. */
  dir: string;
  marker: string;
  tarball: PackedTarball;
  result: RunResult;
}

let packageSeq = 0;

/**
 * Renders `spec` into a fresh sub-directory of `ctx.work`, packs it and publishes it with `client`.
 * `result` is the publish command's outcome; the caller decides whether it was expected to succeed.
 */
export async function publishPackage(
  client: NpmFamilyClient,
  ctx: ClientCtx,
  spec: PackageSpec,
  opts: { tag?: string; forceRepublish?: boolean } = {},
): Promise<PublishedPackage> {
  packageSeq += 1;
  const dir = path.join(ctx.work, `pkg-${packageSeq}`);
  const { marker } = await renderPackage(dir, spec);
  const tarball = await client.pack(ctx, dir);
  const result = await client.publish(ctx, { dir, tarball, ...opts });
  return { spec, dir, marker, tarball, result };
}

/** A fresh npm repository (private by default), tracked by `seeder` for cleanup. */
export function newRepo(seeder: Seeder, privateRepo = true): Promise<SeededRepo> {
  return seeder.createRepo(RepoType.NPM, { privateRepo });
}

/** The admin's Basic (`_auth`) credential bound to `repoName`. */
export function adminBinding(
  repoName: string,
  extra: Partial<RegistryBinding> = {},
): RegistryBinding {
  return { repoName, credential: adminCredential(), ...extra };
}

/** A deploy token of `repoName` (sent as `Bearer`, `_authToken`), bound to it. */
export async function tokenBinding(
  seeder: Seeder,
  repoName: string,
  opts: { readOnly?: boolean } & Partial<RegistryBinding> = {},
): Promise<RegistryBinding & { credential: MaterializedCredential & { username: string } }> {
  const { readOnly = false, ...extra } = opts;
  const token = await seeder.createToken(repoName, { readOnly });
  return {
    repoName,
    credential: {
      transport: 'basic',
      username: token.username,
      password: token.token,
      kind: 'token',
    },
    ...extra,
  } as RegistryBinding & { credential: MaterializedCredential & { username: string } };
}

/** A run-unique package name, unscoped (`e2e-<run>-<label>`) or under the run's own scope. */
export function packageNameFor(seeder: Seeder, label: string, scoped = false): string {
  return scoped ? `@e2e-${seeder.runId}/${label}` : `e2e-${seeder.runId}-${label}`;
}
