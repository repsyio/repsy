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
 * A package published with the protocol's REAL client into a repository of its own, and the checks the
 * stack specs make on it afterwards (RPS-1476 `tests/stack/persistence.spec.ts`, RPS-1487
 * `tests/stack/upgrade.spec.ts`): consumed again with the real client (a password and a deploy token),
 * byte for byte, and listed by the panel. Shared so the two specs assert the same thing the same way.
 */
import { expect } from '@playwright/test';

import type { PanelBackend } from '../api/panel-backend.js';
import { env } from '../env.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import { SCENARIOS } from '../scenarios/catalog.js';
import type { MaterializedCredential, World } from '../scenarios/world.js';
import { splitPackageName } from './maven-raw.js';

export interface Package {
  /** What the scenario adapter published (real client, no raw companion probe). */
  adapter: ProtocolAdapter;
  world: World;
  /** A read-write deploy token of the repo, the credential of the consumes that are not the admin's. */
  token: MaterializedCredential;
  /** sha256 of the primary file the real client sent; what every later consume must give back. */
  contentSha256: string | undefined;
}

/**
 * The admin the stack was started with, as a Basic-auth credential. The password is read on use, not when
 * this module loads: a spec of the `stack` project is imported by `playwright test --list` on a
 * `cloud-*` target too, which has no OS admin password (RPS-1500).
 */
export const ADMIN: MaterializedCredential = {
  transport: 'basic',
  username: env.adminUsername,
  get password(): string {
    return env.adminPassword;
  },
  kind: 'password',
};

/**
 * Publishes one package with the real client of `adapter` (no raw companion probe) as the admin into
 * `repoName`, and remembers the deploy token `{ username, password }` of that repo for the later consumes.
 * The repo and its token were created by the caller, through whichever panel API the running release has.
 */
export async function publishInto(
  adapter: ProtocolAdapter,
  runId: string,
  repoName: string,
  token: { username: string; password: string },
): Promise<Package> {
  const scenario = SCENARIOS.find((candidate) => candidate.id === 'password-admin');
  if (!scenario) {
    throw new Error('the catalog has no password-admin scenario');
  }
  const coordinates = {
    packageName: adapter.packageName(runId, scenario),
    version: adapter.version('release'),
  };
  const world: World = {
    scenario,
    protocol: adapter.protocol,
    repoName,
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
      password: token.password,
      kind: 'token',
    },
  };
}

/** A real-client consume with `credential`: it succeeds and returns the very bytes that were published. */
export async function consume(
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

/** `{ name, latestVersion }` (or, for Docker, `{ name, digest }`) listing endpoints, keyed by protocol. */
const NAME_VERSION_LISTING_PATH: Record<string, string> = {
  npm: '/api/npm/packages',
  helm: '/api/helm/charts',
  docker: '/api/docker/images',
};

/** What the panel lists for the package: the name, the version (or, for Docker, the manifest digest). */
export async function expectListedInPanel(
  panelApi: PanelBackend,
  pkg: Package,
  when: string,
): Promise<void> {
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
  if (pkg.adapter.protocol === 'golang') {
    // No `{ name, latestVersion }` listing (RPS-1720): a module lists only its path, so this is the
    // same two-step lookup as maven's, against `/api/go/modules` instead of dedicated helpers.
    const modulesPath = `/api/go/modules/${encodeURIComponent(repoName)}?size=100`;
    const modulesRes = await panelApi.rawRequest('GET', modulesPath);
    expect(modulesRes.status, `${what}: GET ${modulesPath}`).toBe(200);
    const modules = (modulesRes.body.data as { content?: { modulePath?: string }[] }).content;
    expect(
      (modules ?? []).some((candidate) => candidate.modulePath === packageName),
      `${what} lists the module ${packageName}`,
    ).toBe(true);
    const versionsPath = `/api/go/modules/${encodeURIComponent(repoName)}/versions?modulePath=${encodeURIComponent(packageName)}&size=100`;
    const versionsRes = await panelApi.rawRequest('GET', versionsPath);
    expect(versionsRes.status, `${what}: GET ${versionsPath}`).toBe(200);
    const versions = (versionsRes.body.data as { content?: { version?: string }[] }).content;
    expect(
      (versions ?? []).some((candidate) => candidate.version === version),
      `${what} lists the version ${version}`,
    ).toBe(true);
    return;
  }
  const base = NAME_VERSION_LISTING_PATH[pkg.adapter.protocol];
  expect(
    base,
    `expectListedInPanel: no panel listing path known for ${pkg.adapter.protocol}`,
  ).toBeDefined();
  const path = `${base}/${encodeURIComponent(repoName)}?size=100`;
  const res = await panelApi.rawRequest('GET', path);
  expect(res.status, `${what}: GET ${path}`).toBe(200);
  // Helm and npm answer the bare PagedModel (API guideline, Decision 5); the others still use the envelope.
  const bare = pkg.adapter.protocol === 'helm' || pkg.adapter.protocol === 'npm';
  const page = bare ? res.body : res.body.data;
  const content = (
    page as { content?: { name?: string; latestVersion?: string; digest?: string }[] }
  ).content;
  const item = (content ?? []).find((candidate) => candidate.name === packageName);
  expect(item, `${what} lists ${packageName}`).toBeDefined();
  if (pkg.adapter.protocol === 'docker') {
    expect(item?.digest, `${what}: the image digest`).toBe(`sha256:${pkg.contentSha256}`);
  } else {
    expect(item?.latestVersion, `${what}: the latest version`).toBe(version);
  }
}
