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
  isApiErrorStatus,
  type PanelApi,
  type RepoSettingsForm,
  type RepoType,
  UserRole,
} from '../api/panel-api.js';
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
 * Seeds panel data through `PanelApi` for one test and cleans up exactly what it created. Every
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
    private readonly api: PanelApi,
    private readonly runId: string,
  ) {}

  async createUser(opts: CreateUserOptions = {}): Promise<SeededUser> {
    this.userSeq += 1;
    const username = opts.username ?? userName(this.runId, this.userSeq);
    const pwd = opts.password ?? password(this.runId);
    const role = opts.role ?? UserRole.USER;

    const user = await this.api.createUser({ username, password: pwd, role });

    this.created.push({ kind: 'user', id: user.id });

    return { id: user.id, username: user.username, password: pwd, role: user.role };
  }

  async createRepo(repoType: RepoType, opts: CreateRepoOptions = {}): Promise<SeededRepo> {
    const seq = (this.repoSeq.get(repoType) ?? 0) + 1;
    this.repoSeq.set(repoType, seq);
    const name = opts.name ?? repoName(this.runId, repoType.toLowerCase(), seq);

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
      read_only: opts.readOnly ?? false,
      username: opts.username,
      expiration_date: opts.expirationDate
        ? new Date(opts.expirationDate).toISOString()
        : undefined,
    });

    // The create response carries the secret token but not its id; the id is only in the list.
    const listed = await this.api.listDeployTokens(repoName);
    const match = listed.find((item) => item.name === name);
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
      readOnly: match.read_only,
    };
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
        if (!isApiErrorStatus(err, NOT_FOUND)) {
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
        await this.api.deleteUser(entity.id);
        return;
    }
  }
}
