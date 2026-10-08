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
 * The personal access token routes of the panel API (`/api/profile/access-tokens`, RPS-1901..1904), as plain
 * `fetch` calls authenticated with the bearer token of a test's session (like `RepoSettingsReadback`). The
 * PAT specs seed tokens through it and read back what the UI persisted instead of trusting the UI to show its
 * own state. The path is the same on Repsy OS and Repsy Cloud (it names no owner), so it is a literal here.
 */
import { env } from '../env.js';

const PATH = '/api/profile/access-tokens';

/** The scopes a person can ask for (`scan:read` is not offered in the UI; `profile:read` is implicit). */
export type AccessTokenScope = 'repo:read' | 'repo:write' | 'repo:manage';

/** `profile:read` is on every token without being asked for. */
type StoredScope = AccessTokenScope | 'profile:read';

/** The scopes a person asked for, sorted: the stored ones without the implicit `profile:read`. */
export function explicitScopes(scopes: readonly StoredScope[]): StoredScope[] {
  return scopes.filter((scope) => scope !== 'profile:read').sort();
}

export interface AccessTokenListItem {
  id: string;
  name: string;
  scopes: StoredScope[];
  expirationDate: string;
  createdAt: string;
  lastUsedAt?: string;
}

export interface CreatedAccessToken {
  id: string;
  name: string;
  scopes: StoredScope[];
  expirationDate: string;
  /** The secret, `rut-...`: shown by the API once. */
  token: string;
}

export interface AccessTokenWhoAmI {
  username: string;
  name: string;
  scopes: StoredScope[];
  expirationDate: string;
  lastUsedAt?: string;
}

export class AccessTokenApi {
  constructor(private readonly bearerToken: string) {}

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${this.bearerToken}`, 'Content-Type': 'application/json' };
  }

  /** Creates a token for the session's user; the expiry defaults to 365 days out. */
  async create(
    name: string,
    scopes: AccessTokenScope[] = ['repo:read'],
    expirationDate?: string,
  ): Promise<CreatedAccessToken> {
    const res = await fetch(`${env.apiBaseUrl}${PATH}`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ name, scopes, ...(expirationDate ? { expirationDate } : {}) }),
    });
    if (res.status !== 201) {
      throw new Error(`POST ${PATH} answered ${res.status}: ${await res.text()}`);
    }
    return (await res.json()) as CreatedAccessToken;
  }

  /** Creates `count` tokens called `<prefix>-<n>`, a few requests at a time. */
  async createMany(count: number, prefix: string): Promise<void> {
    const batch = 10;
    for (let start = 0; start < count; start += batch) {
      const size = Math.min(batch, count - start);
      await Promise.all(
        Array.from({ length: size }, (_, i) => this.create(`${prefix}-${start + i + 1}`)),
      );
    }
  }

  /** Every token of the user (the first 100: the live ones are capped at 50). */
  async list(): Promise<AccessTokenListItem[]> {
    const res = await fetch(`${env.apiBaseUrl}${PATH}?page=0&size=100`, {
      headers: this.headers(),
    });
    if (!res.ok) {
      throw new Error(`GET ${PATH} answered ${res.status}`);
    }
    const body = (await res.json()) as { content?: AccessTokenListItem[] };
    return body.content ?? [];
  }
}

/**
 * `GET /api/profile/access-tokens/current` with a token's secret as the Bearer credential: the status, and the
 * body when it answered 200. A revoked token must answer 401 at once.
 */
export async function whoAmI(
  secret: string,
): Promise<{ status: number; body?: AccessTokenWhoAmI }> {
  const res = await fetch(`${env.apiBaseUrl}${PATH}/current`, {
    headers: { Authorization: `Bearer ${secret}` },
  });
  if (res.status !== 200) {
    return { status: res.status };
  }
  return { status: res.status, body: (await res.json()) as AccessTokenWhoAmI };
}
