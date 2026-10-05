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
 * RPS-1481, for `../credential-invalidation-seed.spec.ts`: a fake Repsy Cloud whose registry does what the
 * real one does with a credential, and nothing else. A user may deploy to a repo only when it has a GRANT on
 * it (Cloud: a collaborator of the repo; a tenant merely registered has none and is answered 401), only with
 * its current password, and only while it exists. It is a backend module (`REPSY_E2E_BACKEND_MODULE`) so that
 * the real `registerCredentialInvalidation` tests run against it, and `fakeRegistryAdapter` is the client
 * of the same registry. The state is on `globalThis` because Playwright may load this file once as the
 * backend module and once as an import of the spec.
 */
import type {
  CredentialSeedContext,
  LoginInfo,
  PanelBackend,
} from '../../../src/api/panel-backend.js';
import type { UserResponse, UserSpec } from '../../../src/api/panel-backend.js';
import type { AdapterResult, ProtocolAdapter } from '../../../src/scenarios/adapter.js';
import type { MaterializedCredential, World } from '../../../src/scenarios/world.js';
import { FakeCloudPanelBackend } from '../fake-cloud-panel-backend.js';

interface Registry {
  /** username -> the account. */
  users: Map<string, { id: string; password: string }>;
  /** `username@repo`: who may use which repo. */
  grants: Set<string>;
  /** `repo/package@version` that were stored. */
  stored: Set<string>;
  /** Every `seedUserCredential` the scenario made: the repo it was for. */
  seedCalls: string[];
}

const KEY = Symbol.for('repsy.e2e.granting-cloud');

export function registry(): Registry {
  const holder = globalThis as unknown as Record<symbol, Registry | undefined>;
  holder[KEY] ??= { users: new Map(), grants: new Set(), stored: new Set(), seedCalls: [] };
  return holder[KEY];
}

class GrantingCloudBackend extends FakeCloudPanelBackend {
  private loggedInAs: string | undefined;

  override async login(username: string, password?: string): Promise<LoginInfo> {
    // The owner (the admin of the run) is not in the registry and always logs in; a collaborator needs its password.
    const account = registry().users.get(username);
    if (account && account.password !== password) {
      throw new Error(`fake cloud: the login of ${username} is refused`);
    }
    this.loggedInAs = username;
    return super.login(username);
  }

  override async changeOwnPassword(password?: string): Promise<void> {
    const account = registry().users.get(this.loggedInAs ?? '');
    if (!account || password === undefined) {
      throw new Error('fake cloud: not logged in as a user');
    }
    account.password = password;
  }

  override async createRepoUser(spec: UserSpec): Promise<UserResponse> {
    const user = await super.createRepoUser(spec);
    registry().users.set(spec.username, { id: user.id, password: spec.password });
    return user;
  }

  override async deleteRepoUser(userId: string): Promise<void> {
    await super.deleteRepoUser(userId);
    for (const [username, account] of registry().users) {
      if (account.id === userId) {
        registry().users.delete(username);
      }
    }
  }

  /** What the real Cloud backend does: register the collaborator AND grant it read/write on the repo. */
  override async seedUserCredential(ctx: CredentialSeedContext): Promise<MaterializedCredential> {
    registry().seedCalls.push(ctx.repoName);
    const created = await ctx.seeder.createUser(
      ctx.username === undefined ? {} : { username: ctx.username },
    );
    registry().grants.add(`${created.username}@${ctx.repoName}`);
    return {
      transport: 'basic',
      username: created.username,
      password: created.password,
      kind: 'password',
    };
  }
}

/** The factory contract of `REPSY_E2E_BACKEND_MODULE`. */
export function createPanelBackend(baseUrl: string): PanelBackend {
  return new GrantingCloudBackend(baseUrl);
}

/** What the registry answers a deploy: 200 for a known user with its password and a grant, else 401. */
function deployStatus(world: World): number {
  const { username, password } = world.credential;
  const account = registry().users.get(username ?? '');
  const granted = registry().grants.has(`${username}@${world.repoName}`);
  return account !== undefined && account.password === password && granted ? 200 : 401;
}

const stored = (repoName: string, packageName: string, version: string): string =>
  `${repoName}/${packageName}@${version}`;

/** Whether the registry stored `version` of `packageName` in `repoName` (`CredentialInvalidationProtocol.isStored`). */
export async function isStored(
  repoName: string,
  packageName: string,
  version: string,
): Promise<boolean> {
  return registry().stored.has(stored(repoName, packageName, version));
}

let versionSeq = 0;

/** A client of the registry above: it deploys with the world's credential and reports what came back. */
export const fakeRegistryAdapter: ProtocolAdapter = {
  protocol: 'npm',
  client: { name: 'fake-client', publishVerb: 'publish', consumeVerb: 'install' },
  packageName: (runId) => `fake-${runId}`,
  version: () => `1.0.${(versionSeq += 1)}`,
  async publish(world): Promise<AdapterResult> {
    const status = deployStatus(world);
    if (status === 200) {
      registry().stored.add(
        stored(world.repoName, world.publishTarget.packageName, world.publishTarget.version),
      );
    }
    return {
      outcome: status === 200 ? 'ok' : 'unauthorized',
      httpStatus: status,
      clientExitCode: status === 200 ? 0 : 1,
      command: 'fake-client publish',
    };
  },
  async seedPublish(world) {
    const result = await this.publish(world);
    if (result.clientExitCode !== 0) {
      throw new Error(
        `fake-client publish as ${world.credential.username} answered ${result.httpStatus}`,
      );
    }
    return {};
  },
  async resolve(): Promise<AdapterResult> {
    throw new Error('not used');
  },
  async fingerprint() {
    return undefined;
  },
  async expectNothingStored() {},
};
