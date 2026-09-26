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
 * A `PanelBackend` that lives in memory and talks to no server: the "external backend" of
 * `backend-module.spec.ts`, loaded through `REPSY_E2E_BACKEND_MODULE` the way a Repsy Cloud backend
 * module would be. It is also the smallest worked example of the contract: a module exports
 * `createPanelBackend(baseUrl)`, returns an object that implements `PanelBackend`, and throws
 * `UnsupportedPanelOperation` for what its target does not offer.
 */
import {
  type ArtifactVersionInfo,
  type DeployTokenForm,
  type DeployTokenInfoListItem,
  type ImageListItem,
  type LoginInfo,
  type PanelBackend,
  type PgpPublicKeyItem,
  type RepoCreateForm,
  type RepoListInfo,
  type RepoSecuritySummary,
  type RepoSettingsInfo,
  type RepoType,
  type SecurityScansSummary,
  type TokenInfo,
  type UserResponse,
  type UserSpec,
  type VulnerabilityFindingInfo,
  type VulnerabilityScanInfo,
  UnsupportedPanelOperation,
} from '../../src/api/panel-backend.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';

/** The ticket the fake names for every operation it does not implement. */
export const FAKE_UNSUPPORTED_TICKET = 'RPS-1498';

function unsupported(operation: string): never {
  throw new UnsupportedPanelOperation(FAKE_UNSUPPORTED_TICKET, operation);
}

export class FakePanelBackend implements PanelBackend {
  /** What was called, in order (`login`, `createRepo:<name>`, ...), for the spec to assert. */
  readonly calls: string[] = [];
  readonly repos = new Map<string, RepoType>();
  readonly users = new Map<string, UserResponse>();
  private nextId = 1;

  constructor(readonly baseUrl: string) {}

  async login(username: string): Promise<LoginInfo> {
    this.calls.push('login');
    return { username, token: 'fake-token', refreshToken: 'fake-refresh-token' } as LoginInfo;
  }

  async createRepoUser(spec: UserSpec): Promise<UserResponse> {
    this.calls.push(`createRepoUser:${spec.username}`);
    const user = {
      id: `fake-user-${this.nextId++}`,
      username: spec.username,
      role: spec.role ?? 'USER',
      createdAt: new Date().toISOString(),
      lastLoginAt: new Date().toISOString(),
    } as UserResponse;
    this.users.set(user.id, user);
    return user;
  }

  async deleteRepoUser(userId: string): Promise<void> {
    this.calls.push(`deleteRepoUser:${userId}`);
    this.users.delete(userId);
  }

  async listUsers(params: { q?: string } = {}): Promise<UserResponse[]> {
    return [...this.users.values()].filter((user) => user.username.includes(params.q ?? ''));
  }

  async listAllUsers(filter: { q?: string } = {}): Promise<UserResponse[]> {
    return this.listUsers(filter);
  }

  async createRepo(repoType: RepoType, form: RepoCreateForm): Promise<RepoListInfo> {
    this.calls.push(`createRepo:${form.name}`);
    this.repos.set(form.name, repoType);
    return { name: form.name, type: repoType } as RepoListInfo;
  }

  async deleteRepo(repoName: string): Promise<void> {
    this.calls.push(`deleteRepo:${repoName}`);
    this.repos.delete(repoName);
  }

  async listAllRepos(filter: { type?: RepoType; q?: string } = {}): Promise<RepoListInfo[]> {
    return [...this.repos]
      .filter(
        ([name, type]) => name.includes(filter.q ?? '') && (!filter.type || filter.type === type),
      )
      .map(([name, type]) => ({ name, type }) as RepoListInfo);
  }

  async updateSettings(repoName: string): Promise<void> {
    this.calls.push(`updateSettings:${repoName}`);
  }

  // Everything below is what this fake target does not offer.

  async seedUserCredential(): Promise<MaterializedCredential> {
    return unsupported('seedUserCredential');
  }
  async seedExpiredTokenCredential(): Promise<MaterializedCredential> {
    return unsupported('seedExpiredTokenCredential');
  }

  async changeOwnPassword(): Promise<void> {
    return unsupported('changeOwnPassword');
  }
  async rawRequest(): Promise<{ status: number; body: { data?: unknown } }> {
    return unsupported('rawRequest');
  }
  async getRepoFormat(): Promise<RepoType> {
    return unsupported('getRepoFormat');
  }
  async repoCounts(): Promise<Record<string, number>> {
    return unsupported('repoCounts');
  }
  async getSettings(): Promise<RepoSettingsInfo> {
    return unsupported('getSettings');
  }
  async createDeployToken(_repoName: string, _form: DeployTokenForm): Promise<TokenInfo> {
    return unsupported('createDeployToken');
  }
  async revokeDeployToken(_repoName: string, _tokenId: string): Promise<void> {
    return unsupported('revokeDeployToken');
  }
  async rotateDeployToken(): Promise<string> {
    return unsupported('rotateDeployToken');
  }
  async listDeployTokens(): Promise<DeployTokenInfoListItem[]> {
    return unsupported('listDeployTokens');
  }
  async findDeployTokenByName(
    _repoName: string,
    _name: string,
  ): Promise<DeployTokenInfoListItem | undefined> {
    return unsupported('findDeployTokenByName');
  }
  async registerPgpPublicKey(): Promise<PgpPublicKeyItem> {
    return unsupported('registerPgpPublicKey');
  }
  async listPgpPublicKeys(): Promise<PgpPublicKeyItem[]> {
    return unsupported('listPgpPublicKeys');
  }
  async deletePgpPublicKey(): Promise<void> {
    return unsupported('deletePgpPublicKey');
  }
  async getMavenArtifactVersion(): Promise<ArtifactVersionInfo> {
    return unsupported('getMavenArtifactVersion');
  }
  async listMavenArtifactNames(): Promise<string[]> {
    return unsupported('listMavenArtifactNames');
  }
  async listMavenArtifactVersionNames(): Promise<string[]> {
    return unsupported('listMavenArtifactVersionNames');
  }
  async deleteMavenArtifactVersion(): Promise<void> {
    return unsupported('deleteMavenArtifactVersion');
  }
  async deletePypiRelease(): Promise<void> {
    return unsupported('deletePypiRelease');
  }
  async deleteGolangModuleVersion(): Promise<void> {
    return unsupported('deleteGolangModuleVersion');
  }
  async deleteRubyGemVersion(): Promise<void> {
    return unsupported('deleteRubyGemVersion');
  }
  async getDockerImageSummary(): Promise<ImageListItem> {
    return unsupported('getDockerImageSummary');
  }
  async deleteDockerTag(): Promise<void> {
    return unsupported('deleteDockerTag');
  }
  async supportedScanRepoTypes(): Promise<string[]> {
    return unsupported('supportedScanRepoTypes');
  }
  async listVersionScans(): Promise<VulnerabilityScanInfo[]> {
    return unsupported('listVersionScans');
  }
  async listScanFindings(): Promise<VulnerabilityFindingInfo[]> {
    return unsupported('listScanFindings');
  }
  async securityScansSummary(): Promise<SecurityScansSummary> {
    return unsupported('securityScansSummary');
  }
  async repoSecuritySummary(): Promise<Record<string, RepoSecuritySummary>> {
    return unsupported('repoSecuritySummary');
  }
}

/** The factory contract of `REPSY_E2E_BACKEND_MODULE`. */
export function createPanelBackend(baseUrl: string): PanelBackend {
  return new FakePanelBackend(baseUrl);
}
