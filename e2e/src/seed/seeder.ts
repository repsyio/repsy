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

import {
  isPanelHttpStatus,
  type PanelBackend,
  type RepoSettingsForm,
  type RepoType,
  UserRole,
} from '../api/panel-backend.js';
import { password, repoName, RUN_PREFIX, userName } from './run-id.js';

const NOT_FOUND = 404;

export interface SeededUser {
  id: string;
  username: string;
  password: string;
  role: UserRole;
}

export interface SeededRepo {
  name: string;
  type: RepoType;
}

export interface SeededToken {
  id: string;
  repoName: string;
  name: string;
  token: string;
  username: string;
  readOnly: boolean;
}

export interface CreateUserOptions {
  username?: string;
  password?: string;
  role?: UserRole;
}

export interface CreateRepoOptions {
  name?: string;
  description?: string;
  privateRepo?: boolean;
}

export interface CreateTokenOptions {
  name?: string;
  readOnly?: boolean;
  /** Accepts a past date: the server does not reject it, which is how an expired token is seeded. */
  expirationDate?: Date | string;
  username?: string;
}

type Tracked =
  | { kind: 'token'; repoName: string; tokenId: string }
  | { kind: 'repo'; name: string }
  | { kind: 'user'; id: string };

/**
 * Seeds panel data through `PanelBackend` for one test and cleans up exactly what it created. Every
 * name is `e2e-<runid>-...` (see run-id.ts), and every created entity is tracked so `cleanup()` can
 * delete it in reverse order, tolerating an entity already gone (a token whose repo was deleted
 * first, or something the test itself deleted).
 */
export class Seeder {
  private readonly created: Tracked[] = [];
  private userSeq = 0;
  private readonly repoSeq = new Map<string, number>();
  private readonly tokenSeq = new Map<string, number>();

  constructor(
    private readonly api: PanelBackend,
    public readonly runId: string,
  ) {}

  /** The backend this seeder creates its data through (RPS-1498: the credential hooks live on it). */
  get backend(): PanelBackend {
    return this.api;
  }

  /**
   * The next unique repo name for this test (`e2e-<runid>-<type>-<n>`), NOT created and NOT tracked.
   * For flows where the UI creates the repo (the create-repository modal): type the reserved name
   * into the form, then `adoptRepo()` it so `cleanup()` deletes it. `createRepo()` draws from the
   * same counter, so a reserved name never clashes with a seeded one.
   */
  reserveRepoName(repoType: RepoType): string {
    const seq = (this.repoSeq.get(repoType) ?? 0) + 1;
    this.repoSeq.set(repoType, seq);
    return repoName(this.runId, repoType.toLowerCase(), seq);
  }

  /**
   * The next unique username for this test (`e2e-<runid>-user-<n>`), NOT created and NOT tracked.
   * Also the source of a rename target (a profile/user-edit rename must keep the `e2e-` prefix that
   * sweep.ts relies on). `createUser()` draws from the same counter.
   */
  reserveUsername(): string {
    this.userSeq += 1;
    return userName(this.runId, this.userSeq);
  }

  /** Tracks a repo the UI created, so `cleanup()` deletes it (a 404, e.g. deleted in the UI, is tolerated). */
  adoptRepo(name: string): void {
    this.created.push({ kind: 'repo', name });
  }

  /** Tracks a user the UI created, by id, so a later rename does not orphan it. */
  adoptUser(id: string): void {
    this.created.push({ kind: 'user', id });
  }

  /** Looks a user up by exact username through the panel API, adopts it and returns its id. */
  async adoptUserByUsername(username: string): Promise<string> {
    const matches = await this.api.listUsers({ q: username });
    const user = matches.find((candidate) => candidate.username === username);
    if (!user) {
      throw new Error(`Seeder.adoptUserByUsername: no user named "${username}" to adopt`);
    }
    this.adoptUser(user.id);
    return user.id;
  }

  async createUser(opts: CreateUserOptions = {}): Promise<SeededUser> {
    // Always draw a number, even when the caller names the user, so the numbering of the default
    // names is unchanged for tests that pass their own.
    const reserved = this.reserveUsername();
    const username = opts.username ?? reserved;
    const pwd = opts.password ?? password(this.runId);
    const role = opts.role ?? UserRole.USER;

    const user = await this.api.createRepoUser({ username, password: pwd, role });

    this.created.push({ kind: 'user', id: user.id });

    return { id: user.id, username: user.username, password: pwd, role: user.role };
  }

  async createRepo(repoType: RepoType, opts: CreateRepoOptions = {}): Promise<SeededRepo> {
    const reserved = this.reserveRepoName(repoType);
    const name = opts.name ?? reserved;

    await this.api.createRepo(repoType, {
      name,
      description: opts.description,
      privateRepo: opts.privateRepo ?? true,
    });

    this.created.push({ kind: 'repo', name });

    return { name, type: repoType };
  }

  async setSettings(repoName: string, form: RepoSettingsForm): Promise<void> {
    // Settings live on the repo row, so they are removed along with it; nothing to track here.
    await this.api.updateSettings(repoName, form);
  }

  async createToken(repoName: string, opts: CreateTokenOptions = {}): Promise<SeededToken> {
    const seq = (this.tokenSeq.get(repoName) ?? 0) + 1;
    this.tokenSeq.set(repoName, seq);
    const name = opts.name ?? `${RUN_PREFIX}-${this.runId}-token-${seq}`;

    const created = await this.api.createDeployToken(repoName, {
      name,
      readOnly: opts.readOnly ?? false,
      username: opts.username,
      expirationDate: opts.expirationDate ? new Date(opts.expirationDate).toISOString() : undefined,
    });

    // The create response carries the secret token but not its id; the id is only in the list.
    // findDeployTokenByName pages through (newest first) instead of assuming one default-sized
    // page holds every token a repo has, so this stays correct however many tokens a repo holds.
    const match = await this.api.findDeployTokenByName(repoName, name);
    if (!match) {
      throw new Error(
        `Seeder: created deploy token "${name}" on repo "${repoName}" but it is missing from ` +
          'the token list right after creation',
      );
    }

    this.created.push({ kind: 'token', repoName, tokenId: match.id });

    return {
      id: match.id,
      repoName,
      name,
      token: created.token ?? '',
      username: created.username ?? '',
      readOnly: match.readOnly,
    };
  }

  /**
   * Registers an armored OpenPGP public key directly on a repo's Maven key store (RPS-1189).
   * Nothing to track for cleanup: the row cascades on delete when the repo is deleted.
   */
  async registerPgpPublicKey(repoName: string, armoredKey: string) {
    return this.api.registerPgpPublicKey(repoName, armoredKey);
  }

  /**
   * Revokes an already-created token right away, for a "token-revoked" credential: the token was
   * tracked (and will be cleaned up, tolerating the 404 a second revoke gets) when it was created;
   * this just makes it stop working immediately instead of at test teardown.
   */
  async revokeNow(repoName: string, tokenId: string): Promise<void> {
    await this.api.revokeDeployToken(repoName, tokenId);
  }

  /**
   * Rotates an already-created token, invalidating its old value immediately and returning the new
   * one. Used for a "token-rotated-old" credential, which deliberately keeps using the value from
   * before this call.
   */
  async rotateNow(repoName: string, tokenId: string): Promise<string> {
    return this.api.rotateDeployToken(repoName, tokenId);
  }

  /**
   * Deletes everything this seeder created, in reverse order, tolerating an entity that is already
   * gone (404). Every other failure is collected and thrown once cleanup has been attempted for
   * every tracked entity, so one failure never leaves the rest behind.
   */
  async cleanup(): Promise<void> {
    const errors: unknown[] = [];

    for (const entity of this.created.reverse()) {
      try {
        await this.deleteTracked(entity);
      } catch (err) {
        if (!isPanelHttpStatus(err, NOT_FOUND)) {
          errors.push(err);
        }
      }
    }

    this.created.length = 0;

    if (errors.length > 0) {
      throw new AggregateError(errors, `Seeder.cleanup() failed for ${errors.length} entities`);
    }
  }

  private async deleteTracked(entity: Tracked): Promise<void> {
    switch (entity.kind) {
      case 'token':
        await this.api.revokeDeployToken(entity.repoName, entity.tokenId);
        return;
      case 'repo':
        await this.api.deleteRepo(entity.name);
        return;
      case 'user':
        await this.api.deleteRepoUser(entity.id);
        return;
    }
  }
}
