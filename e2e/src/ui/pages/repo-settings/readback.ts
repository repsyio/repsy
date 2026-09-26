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
 * Reads of the panel API that `PanelBackend` (`src/api/panel-backend.ts`, not owned by the settings story)
 * does not wrap: a repo's permissions/description, its disk usage and its Maven key stores; plus the
 * raw repo-port probe TOK-04 needs. Every method is a plain `fetch`, authenticated with the bearer
 * token of the test's `adminSession`, so the settings specs assert what a click PERSISTED through
 * the API instead of trusting the UI to show its own state back.
 */
import { env } from '../../../env.js';
import { repoUrl } from '../../../repo-url.js';

/** The `{ data: ... }` envelope of a panel REST response. */
interface Envelope<T> {
  data?: T;
}

export interface RepoPermissions {
  repoName?: string;
  description?: string;
  canRead: boolean;
  canWrite: boolean;
  canManage: boolean;
  private: boolean;
}

export interface KeyStore {
  id: string;
  allowedKeyserverId: string;
  host: string;
  displayName: string;
}

export interface AllowedKeyserver {
  id: string;
  host: string;
  displayName: string;
}

export class RepoSettingsReadback {
  constructor(private readonly bearerToken: string) {}

  private async get<T>(path: string): Promise<T> {
    const res = await fetch(`${env.apiBaseUrl}${path}`, {
      headers: { Authorization: `Bearer ${this.bearerToken}` },
    });
    if (!res.ok) {
      throw new Error(`GET ${path} answered ${res.status}`);
    }
    const body = (await res.json()) as Envelope<T>;
    if (body.data === undefined || body.data === null) {
      throw new Error(`GET ${path} carried no data`);
    }
    return body.data;
  }

  /** The repo's description, privacy and what the caller may do with it. */
  permissions(repoName: string): Promise<RepoPermissions> {
    return this.get<RepoPermissions>(`/api/repos/${encodeURIComponent(repoName)}/permissions`);
  }

  /** Bytes the repo occupies, as the Storage section's source (`diskUsed.value`). */
  async diskUsedBytes(repoName: string): Promise<number> {
    const usage = await this.get<{ diskUsed?: { value?: number } }>(
      `/api/repos/${encodeURIComponent(repoName)}/usage`,
    );
    return usage.diskUsed?.value ?? 0;
  }

  /** The key servers a Maven repo may register (the PGP section's selector options). */
  allowedKeyServers(): Promise<AllowedKeyserver[]> {
    return this.get<AllowedKeyserver[]>('/api/mvn/key-stores/allowed-servers');
  }

  /** The key servers registered on a Maven repo (first page of 50). */
  async keyStores(repoName: string): Promise<KeyStore[]> {
    const page = await this.get<{ content?: KeyStore[] }>(
      `/api/mvn/key-stores/${encodeURIComponent(repoName)}?page=0&size=50`,
    );
    return page.content ?? [];
  }
}

/**
 * The status the repo PORT (`REPSY_REPO_BASE_URL`, 9090 in the default stack, NOT the SPA/API port)
 * answers for `GET /<repo>/` with Basic auth: what a package manager would see for this credential.
 * With no credential the request is anonymous. A deploy token is accepted as the Basic password
 * with any username the server issued it for (`ProtocolAuthService`).
 */
export async function repoRootStatus(
  repoName: string,
  credential?: { username: string; token: string },
): Promise<number> {
  const headers: Record<string, string> = {};
  if (credential) {
    const basic = Buffer.from(`${credential.username}:${credential.token}`).toString('base64');
    headers.Authorization = `Basic ${basic}`;
  }
  const res = await fetch(repoUrl(repoName, ''), { headers });
  await res.arrayBuffer();
  return res.status;
}
