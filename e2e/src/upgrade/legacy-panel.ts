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
 * The panel API of the PREVIOUS release, by plain HTTP (RPS-1487, README.md "Upgrade path"). The generated
 * client (`src/api/generated`) is built from the CURRENT `openapi-spec.yaml`, and the API moved since
 * `v26.08.4` (probed against the published image): repositories are created with `POST /api/repos/{type}`
 * and not `POST /api/repos`, users are searched with `?search=` and not `?q=`, and so on. The envelope,
 * `POST /api/auth/login`, `POST /api/users` and `POST /api/repos/{repo}/deploy-tokens` are the same, so this
 * covers only what the upgrade spec seeds: a login, repositories, users and deploy tokens. Everything after
 * the upgrade goes through the current `PanelBackend`.
 */

interface Envelope<T> {
  msgId?: string;
  data?: T;
}

export interface LegacyUser {
  id: string;
  username: string;
  password: string;
  role: 'ADMIN' | 'USER';
}

export interface LegacyToken {
  repoName: string;
  /** The token's username (`repsy-deploy-token-...`) and its secret: what a client sends in Basic auth. */
  username: string;
  token: string;
}

export class LegacyPanelError extends Error {
  constructor(
    readonly status: number,
    what: string,
    body: string,
  ) {
    super(`previous release: ${what} answered ${status}: ${body.slice(0, 300)}`);
  }
}

export class LegacyPanel {
  private token: string | undefined;

  constructor(private readonly baseUrl: string) {}

  private async call<T>(method: string, path: string, body?: unknown): Promise<T | undefined> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) {
      throw new LegacyPanelError(res.status, `${method} ${path}`, text);
    }
    return (JSON.parse(text) as Envelope<T>).data;
  }

  async login(username: string, password: string): Promise<void> {
    const data = await this.call<{ token?: string }>('POST', '/api/auth/login', {
      username,
      password,
    });
    if (!data?.token) {
      throw new Error('previous release: login returned no token');
    }
    this.token = data.token;
  }

  /** `POST /api/repos/{TYPE}` (the type in upper case: MAVEN, NPM, DOCKER ...). */
  async createRepo(type: string, name: string, privateRepo = true): Promise<void> {
    await this.call('POST', `/api/repos/${encodeURIComponent(type)}`, {
      name,
      privateRepo,
      description: 'upgrade path',
    });
  }

  async createUser(
    username: string,
    password: string,
    role: 'ADMIN' | 'USER',
  ): Promise<LegacyUser> {
    const data = await this.call<{ id?: string }>('POST', '/api/users', {
      username,
      password,
      role,
    });
    if (!data?.id) {
      throw new Error(`previous release: creating ${username} returned no id`);
    }
    return { id: data.id, username, password, role };
  }

  async createDeployToken(repoName: string, name: string): Promise<LegacyToken> {
    const data = await this.call<{ token?: string; username?: string }>(
      'POST',
      `/api/repos/${encodeURIComponent(repoName)}/deploy-tokens`,
      { name, read_only: false },
    );
    if (!data?.token || !data.username) {
      throw new Error(`previous release: creating the token ${name} returned no secret`);
    }
    return { repoName, username: data.username, token: data.token };
  }
}
