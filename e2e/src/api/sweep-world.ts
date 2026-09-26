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
 * The data the role sweep (RPS-1483, `tests/api/role-sweep.spec.ts`) runs its second pass on: one repo
 * per protocol, each with a real package, plus a deploy token, a key store, a PGP key and a second user,
 * so an operation is called with the names of things that exist, and "nothing changed" can be read off
 * the panel. Also the snapshot that proves it: every repo-scoped read operation of the spec, called as
 * admin with the same names, so a write that slipped through shows up whatever it touched.
 */
import { edgeRequest, apiUrl } from '../clients/edge-raw.js';
import { generateKeyPair } from '../clients/pgp.js';
import { RepoType } from './panel-api.js';
import type { PanelApi } from './panel-api.js';
import {
  PACKAGE_PROTOCOLS,
  type PackageProtocol,
  type SeededPackage,
  seedPackage,
} from '../seed/packages.js';
import type { Seeder, SeededRepo, SeededUser } from '../seed/seeder.js';
import { type SpecOperation, requestFor } from './spec-ops.js';

const PROTOCOL_BY_SEGMENT: Readonly<Record<string, PackageProtocol>> = {
  mvn: 'maven',
  npm: 'npm',
  nuget: 'nuget',
  cargo: 'cargo',
  go: 'golang',
  docker: 'docker',
  helm: 'helm',
  ruby: 'ruby',
  pypi: 'pypi',
};

const REPO_TYPE_BY_PROTOCOL: Readonly<Record<PackageProtocol, RepoType>> = {
  maven: RepoType.MAVEN,
  npm: RepoType.NPM,
  nuget: RepoType.NUGET,
  cargo: RepoType.CARGO,
  golang: RepoType.GOLANG,
  docker: RepoType.DOCKER,
  helm: RepoType.HELM,
  ruby: RepoType.RUBY,
  pypi: RepoType.PYPI,
};

/**
 * The protocol whose repo an operation's `{repoName}` has to name: the `/api/<segment>/` prefix picks
 * it, and everything else (`/api/repos/{repoName}/...`, settings, tokens, scans) works on any repo, so
 * takes the Maven one.
 */
export function protocolOfPath(path: string): PackageProtocol {
  const segment = /^\/api\/([a-z]+)\//.exec(path)?.[1] ?? '';
  return PROTOCOL_BY_SEGMENT[segment] ?? 'maven';
}

export interface SweepWorld {
  repos: Record<PackageProtocol, SeededRepo>;
  packages: Record<PackageProtocol, SeededPackage>;
  /** A second npm package that is not scoped, for the routes without a `{scope}`. */
  unscopedNpm: SeededPackage;
  tokenId: string;
  keyStoreId: string;
  publicKeyId: string;
  /** A user the sweep's USER may not touch. */
  otherUser: SeededUser;
  /** Names for the bodies that create something (all prefixed `e2e-`, so a sweep removes a leak). */
  fresh: { username: string; repoName: string };
}

interface Envelope {
  data?: unknown;
}

async function adminJson(
  adminToken: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ status: number; data: unknown }> {
  const res = await edgeRequest(apiUrl(path), {
    method,
    headers: {
      Authorization: `Bearer ${adminToken}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, data: (res.json as Envelope | undefined)?.data };
}

/** Seeds the world in the calling test's own `seeder`, which removes it all again. */
export async function seedSweepWorld(
  seeder: Seeder,
  admin: PanelApi,
  adminToken: string,
): Promise<SweepWorld> {
  const repos = {} as Record<PackageProtocol, SeededRepo>;
  const packages = {} as Record<PackageProtocol, SeededPackage>;
  for (const protocol of PACKAGE_PROTOCOLS) {
    // Private, so an anonymous caller has to be refused on every one of them.
    repos[protocol] = await seeder.createRepo(REPO_TYPE_BY_PROTOCOL[protocol], {
      privateRepo: true,
    });
    packages[protocol] = await seedPackage(repos[protocol], seeder, {});
  }
  const unscopedNpm = await seedPackage(repos.npm, seeder, { scoped: false, index: 2 });

  const token = await seeder.createToken(repos.maven.name);

  const servers = await adminJson(adminToken, 'GET', '/api/mvn/key-stores/allowed-servers');
  const keyserver = (servers.data as { id?: string }[] | undefined)?.[0]?.id;
  if (!keyserver) {
    throw new Error(`no allowed key server to seed a key store with: ${JSON.stringify(servers)}`);
  }
  const keyStore = await adminJson(adminToken, 'POST', `/api/mvn/key-stores/${repos.maven.name}`, {
    allowedKeyserverId: keyserver,
  });
  const keyStores = await adminJson(adminToken, 'GET', `/api/mvn/key-stores/${repos.maven.name}`);
  const keyStoreId = ((keyStores.data as { content?: { id: string }[] } | undefined)?.content ??
    [])[0]?.id;
  if (keyStore.status !== 200 || !keyStoreId) {
    throw new Error(`could not seed a key store: ${keyStore.status} ${JSON.stringify(keyStores)}`);
  }
  const { publicKeyArmored } = await generateKeyPair();
  const publicKey = await seeder.registerPgpPublicKey(repos.maven.name, publicKeyArmored);

  const otherUser = await seeder.createUser();
  void admin;
  return {
    repos,
    packages,
    unscopedNpm,
    tokenId: token.id,
    keyStoreId,
    publicKeyId: publicKey.id,
    otherUser,
    fresh: {
      username: `${seeder.reserveUsername()}`,
      repoName: seeder.reserveRepoName(RepoType.MAVEN),
    },
  };
}

/** The values of an operation's path and required-query parameters, from the world's real names. */
export function valuesFor(op: SpecOperation, world: SweepWorld): Record<string, string> {
  const protocol = protocolOfPath(op.path);
  const scoped = op.pathParams.includes('scope');
  const pkg = protocol === 'npm' && !scoped ? world.unscopedNpm : world.packages[protocol];
  const [first, second] = pkg.name.split(/[:/]/);
  // A scoped npm name is `@scope/name`; a Maven one is `group:artifact`.
  const isScopedName = pkg.name.startsWith('@');
  return {
    repoName: world.repos[protocol].name,
    userId: world.otherUser.id,
    tokenId: world.tokenId,
    keyStoreId: world.keyStoreId,
    publicKeyId: world.publicKeyId,
    version: pkg.version,
    groupName: first ?? '',
    artifactName: second ?? pkg.name,
    scope: isScopedName ? (first ?? '') : '',
    packageName: isScopedName ? (second ?? '') : pkg.name,
    chartName: pkg.name,
    crateName: pkg.name,
    gemName: pkg.name,
    imageName: pkg.name,
    tagName: pkg.version,
    reference: pkg.extra.digest ?? pkg.version,
    digest: pkg.extra.configDigest ?? '',
    modulePath: pkg.name,
  };
}

/** A body that passes the operation's validation, for the operations that create or change something. */
export function bodyFor(operationId: string, world: Pick<SweepWorld, 'fresh'>): unknown {
  switch (operationId) {
    case 'updateUser':
      return { username: world.fresh.username, role: 'USER' };
    case 'createUser':
      return { username: world.fresh.username, password: 'E2e-Sweep-Pwd1', role: 'USER' };
    case 'createRepository':
      return { name: world.fresh.repoName, type: 'MAVEN', privateRepo: true };
    case 'updateRepoSettings':
      return { privateRepo: false, allowOverride: false };
    case 'updateRepoDescription':
      return { description: 'changed by the sweep' };
    case 'renameRepo':
      return { name: world.fresh.repoName };
    case 'createDeployToken':
      return { name: 'e2e-sweep-token' };
    case 'createMavenKeyStore':
      return { allowedKeyserverId: 'none' };
    default:
      return {};
  }
}

/** An error answer carries a fresh `errorCode` (a log correlation id) every time; the rest is the answer. */
function withoutErrorCode(body: unknown): unknown {
  if (body !== null && typeof body === 'object' && !Array.isArray(body)) {
    const { errorCode: _errorCode, ...rest } = body as Record<string, unknown>;
    return rest;
  }
  return body;
}

export interface Reading {
  status: number;
  body: unknown;
}

/**
 * Everything a repo-scoped read operation of the spec answers as admin, keyed by `operationId`, plus the
 * user list and repo list narrowed to this run's names. The volatile ones (usage, the cross-repo security
 * lists, counts) are left out: they change with what other specs do at the same time.
 */
export async function snapshotWorld(
  adminToken: string,
  world: SweepWorld,
  operations: readonly SpecOperation[],
  runPrefix: string,
): Promise<Record<string, Reading>> {
  const readings: Record<string, Reading> = {};
  const reads = operations.filter(
    (op) =>
      op.method === 'GET' && op.pathParams.includes('repoName') && !op.path.endsWith('/usage'),
  );
  for (const op of reads) {
    const request = requestFor(op, { values: valuesFor(op, world) });
    const res = await edgeRequest(apiUrl(request.target), {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    readings[op.operationId] = { status: res.status, body: withoutErrorCode(res.json ?? res.text) };
  }
  for (const [key, path] of Object.entries({
    users: `/api/users?q=${runPrefix}&size=100&sort=username,asc`,
    repos: `/api/repos?q=${runPrefix}&size=100&sort=name,asc`,
  })) {
    const res = await edgeRequest(apiUrl(path), {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    readings[key] = { status: res.status, body: withoutErrorCode(res.json ?? res.text) };
  }
  return readings;
}
