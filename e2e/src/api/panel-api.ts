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
 * A thin, hand-written wrapper around the generated panel API client (`src/api/generated`, built by
 * `pnpm gen:api` from `repsy-backend/src/main/resources/openapi/openapi-spec.yaml`). It only covers
 * what the seeder needs: users, repos, settings and deploy tokens.
 *
 * The OpenAPI spec lists no `Authorization` header parameter on any operation (RPS-1161): the backend
 * reads the header through an argument resolver the spec does not document. `PanelClient`'s `TOKEN`
 * resolver supplies it on every request, so no call here passes it explicitly.
 */
import { ApiError, PanelClient, RepoType, UserRole } from './generated/index.js';
import type { ArtifactVersionInfo } from './generated/models/ArtifactVersionInfo.js';
import type { DeployTokenForm } from './generated/models/DeployTokenForm.js';
import type { DeployTokenInfoListItem } from './generated/models/DeployTokenInfoListItem.js';
import type { LoginInfo } from './generated/models/LoginInfo.js';
import type { PagedModelRepoListInfo } from './generated/models/PagedModelRepoListInfo.js';
import type { PgpPublicKeyItem } from './generated/models/PgpPublicKeyItem.js';
import type { RepoCreateRequest } from './generated/models/RepoCreateRequest.js';
import type { RepoListInfo } from './generated/models/RepoListInfo.js';
import type { RepoSettingsForm } from './generated/models/RepoSettingsForm.js';
import type { RepoSettingsInfo } from './generated/models/RepoSettingsInfo.js';
import type { TokenInfo } from './generated/models/TokenInfo.js';
import type { UserCreateForm } from './generated/models/UserCreateForm.js';
import type { UserResponse } from './generated/models/UserResponse.js';

export { ApiError, RepoType, UserRole };
export type {
  ArtifactVersionInfo,
  DeployTokenForm,
  DeployTokenInfoListItem,
  LoginInfo,
  PagedModelRepoListInfo,
  PgpPublicKeyItem,
  RepoListInfo,
  RepoSettingsForm,
  RepoSettingsInfo,
  TokenInfo,
  UserCreateForm,
  UserResponse,
};

/** The body of `POST /api/repos` without its `type`, which `createRepo` takes as its own argument. */
export type RepoCreateForm = Omit<RepoCreateRequest, 'type'>;

/** Thrown when a successful REST response's envelope unexpectedly carries no `data`. */
export class PanelApiError extends Error {}

function unwrap<T>(data: T | null | undefined, what: string): T {
  if (data === null || data === undefined) {
    throw new PanelApiError(`Panel API response for "${what}" carried no data`);
  }
  return data;
}

/** True when `err` is an `ApiError` with the given HTTP status (for tolerating double-deletes). */
export function isApiErrorStatus(err: unknown, status: number): boolean {
  return err instanceof ApiError && err.status === status;
}

/** One page of `GET /api/repos/{repoName}/deploy-tokens`, read straight off the JSON envelope. */
export interface DeployTokenPage {
  content: DeployTokenInfoListItem[];
  totalPages: number;
}

/**
 * One page of `GET /api/mvn/key-stores/{repoName}/public-keys`, read straight off the JSON
 * envelope (RPS-1189).
 */
export interface PgpPublicKeyPage {
  content: PgpPublicKeyItem[];
  totalPages: number;
}

/** The query of `GET /api/repos`; every field is optional. */
export interface RepoListParams {
  type?: RepoType;
  q?: string;
  page?: number;
  size?: number;
  sort?: string[];
}

export class PanelApi {
  private readonly client: PanelClient;
  private readonly baseUrl: string;
  private token: string | undefined;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
    this.client = new PanelClient({
      BASE: baseUrl,
      // Resolved per request, not fixed at construction time, so login() can populate it later.
      TOKEN: async () => this.token ?? '',
    });
  }

  async login(username: string, password: string): Promise<LoginInfo> {
    const res = await this.client.authController.login({ requestBody: { username, password } });
    const data = unwrap(res.data, 'login');
    this.token = unwrap(data.token, 'login.token');
    return data;
  }

  private authorization(): string {
    if (!this.token) {
      throw new PanelApiError('PanelApi.login() must succeed before an authenticated call');
    }
    return `Bearer ${this.token}`;
  }

  async createUser(form: UserCreateForm): Promise<UserResponse> {
    const res = await this.client.userController.createUser({
      requestBody: form,
    });
    return unwrap(res.data, 'createUser');
  }

  async deleteUser(userId: string): Promise<void> {
    await this.client.userController.deleteUser({ userId });
  }

  /**
   * One page of `GET /api/users` (RPS-1269): `q` filters by username on the server, `size` is 1-100
   * (server default 10) and `sort` defaults to `createdAt,desc`. Use `listAllUsers` to read everything.
   */
  async listUsers(
    params: { q?: string; page?: number; size?: number; sort?: string[] } = {},
  ): Promise<UserResponse[]> {
    const res = await this.client.userController.listUsers(params);
    return unwrap(res.data, 'listUsers').content ?? [];
  }

  /**
   * Every user matching `q`, read page by page (100 a page) until the last page. A user created
   * or deleted mid-read can still move a row across a page boundary, so an id is kept once. Collect
   * first, then act: deleting while reading pages skips the rows that move up into the page just read.
   */
  async listAllUsers(filter: { q?: string } = {}): Promise<UserResponse[]> {
    const byId = new Map<string, UserResponse>();

    for (let page = 0; ; page += 1) {
      const res = await this.client.userController.listUsers({ ...filter, page, size: 100 });
      const result = unwrap(res.data, 'listAllUsers');
      for (const user of result.content ?? []) {
        byId.set(user.id, user);
      }
      if (page + 1 >= (result.page?.totalPages ?? 0)) {
        return [...byId.values()];
      }
    }
  }

  /** `POST /api/repos`: the repository type travels in the body; the answer is the created repository. */
  async createRepo(repoType: RepoType, form: RepoCreateForm): Promise<RepoListInfo> {
    const res = await this.client.repoCollectionController.createRepository({
      requestBody: { ...form, type: repoType },
    });
    return unwrap(res.data, 'createRepo');
  }

  async deleteRepo(repoName: string): Promise<void> {
    await this.client.protocolRepoController.deleteRepo({ repoName });
  }

  /**
   * One page of `GET /api/repos` (RPS-1268): `type` and `q` filter on the server, `size` is 1-100
   * (server default 10) and `sort` defaults to `createdAt,desc`. Use `listAllRepos` to read everything.
   */
  async listRepos(params: RepoListParams = {}): Promise<PagedModelRepoListInfo> {
    const res = await this.client.repoCollectionController.listRepos(params);
    return unwrap(res.data, 'listRepos');
  }

  /**
   * Every repository matching `type` and `q`, read page by page (100 a page), sorted by name so the
   * pages do not shift when a parallel test creates a repository. A repository created or deleted
   * mid-read can still move a row across a page boundary, so a name is kept once. Collect first, then
   * act: deleting while reading pages skips the rows that move up into the page just read.
   */
  async listAllRepos(filter: { type?: RepoType; q?: string } = {}): Promise<RepoListInfo[]> {
    const byName = new Map<string, RepoListInfo>();

    for (let page = 0; ; page += 1) {
      const result = await this.listRepos({ ...filter, page, size: 100, sort: ['name,asc'] });
      for (const repo of result.content ?? []) {
        byName.set(repo.name, repo);
      }
      if (page + 1 >= (result.page?.totalPages ?? 0)) {
        return [...byName.values()];
      }
    }
  }

  /** `GET /api/repos/counts`: how many repositories there are of each type (every type is a key). */
  async repoCounts(): Promise<Record<string, number>> {
    const res = await this.client.repoCollectionController.getRepoCounts();
    return unwrap(res.data, 'repoCounts');
  }

  async getSettings(repoName: string): Promise<RepoSettingsInfo> {
    const res = await this.client.protocolRepoController.getRepoSettings({ repoName });
    return unwrap(res.data, 'getRepoSettings');
  }

  async updateSettings(repoName: string, form: RepoSettingsForm): Promise<void> {
    await this.client.protocolRepoController.updateRepoSettings({ repoName, requestBody: form });
  }

  async createDeployToken(repoName: string, form: DeployTokenForm): Promise<TokenInfo> {
    const res = await this.client.protocolDeployTokenController.createDeployToken({
      repoName,
      requestBody: form,
    });
    return unwrap(res.data, 'createDeployToken');
  }

  async revokeDeployToken(repoName: string, tokenId: string): Promise<void> {
    await this.client.protocolDeployTokenController.revokeDeployToken({ repoName, tokenId });
  }

  /** Returns the new token value; the old one stops working immediately. */
  async rotateDeployToken(repoName: string, tokenId: string): Promise<string> {
    const res = await this.client.protocolDeployTokenController.rotateDeployToken({
      repoName,
      tokenId,
    });
    return unwrap(res.data, 'rotateDeployToken');
  }

  /**
   * One page of a repo's deploy tokens, newest first (`repo_deploy_token.id` is a UUIDv7, so DESC on
   * it is DESC on creation time): a token this harness just created is always on page 0, and sorting
   * explicitly keeps that true even if the server's own default ever changes.
   */
  async listDeployTokensPage(
    repoName: string,
    page: number,
    size: number,
  ): Promise<DeployTokenPage> {
    const res = await this.client.protocolDeployTokenController.listDeployTokens({
      repoName,
      page,
      size,
      sort: ['id,desc'],
    });
    const result = unwrap(res.data, 'listDeployTokensPage');

    return {
      content: result.content ?? [],
      totalPages: result.page?.totalPages ?? 0,
    };
  }

  /** Lists every deploy token of a repo (its first page, `DEFAULT_TOKEN_LIST_PAGE_SIZE` items). */
  async listDeployTokens(repoName: string): Promise<DeployTokenInfoListItem[]> {
    const { content } = await this.listDeployTokensPage(repoName, 0, DEFAULT_TOKEN_LIST_PAGE_SIZE);
    return content;
  }

  /**
   * Finds one deploy token by name, paging through the repo's tokens (newest first,
   * `TOKEN_LOOKUP_PAGE_SIZE` at a time) instead of assuming it fits on one default-sized page —
   * unlike page 0 alone, this stays correct even if a repo ever holds enough tokens to need more
   * than one page, up to `TOKEN_LOOKUP_MAX_PAGES` pages as a sanity cap against an infinite loop.
   */
  async findDeployTokenByName(
    repoName: string,
    name: string,
  ): Promise<DeployTokenInfoListItem | undefined> {
    for (let page = 0; page < TOKEN_LOOKUP_MAX_PAGES; page += 1) {
      const { content, totalPages } = await this.listDeployTokensPage(
        repoName,
        page,
        TOKEN_LOOKUP_PAGE_SIZE,
      );

      const match = content.find((item) => item.name === name);
      if (match) {
        return match;
      }
      if (page + 1 >= totalPages) {
        return undefined;
      }
    }
    return undefined;
  }

  /** Registers an armored OpenPGP public key directly on a Maven repo's key store (RPS-1189). */
  async registerPgpPublicKey(repoName: string, armoredKey: string): Promise<PgpPublicKeyItem> {
    const res = await this.client.keyStoreController.createMavenPgpPublicKey({
      repoName,
      requestBody: { armoredKey },
    });
    return unwrap(res.data, 'registerPgpPublicKey');
  }

  /** One page of a Maven repo's registered PGP public keys, newest first. */
  async listPgpPublicKeysPage(
    repoName: string,
    page: number,
    size: number,
  ): Promise<PgpPublicKeyPage> {
    const res = await this.client.keyStoreController.listMavenPgpPublicKeys({
      repoName,
      page,
      size,
      sort: ['id,desc'],
    });
    const result = unwrap(res.data, 'listPgpPublicKeysPage');

    return {
      content: result.content ?? [],
      totalPages: result.page?.totalPages ?? 0,
    };
  }

  /** Lists every registered PGP public key of a repo (its first page, up to 50 items). */
  async listPgpPublicKeys(repoName: string): Promise<PgpPublicKeyItem[]> {
    const { content } = await this.listPgpPublicKeysPage(
      repoName,
      0,
      PGP_PUBLIC_KEY_LIST_PAGE_SIZE,
    );
    return content;
  }

  /**
   * One version of a Maven artifact, as the panel shows it (RPS-1188: its `signed` flag says
   * whether the POM signature, or with `pgpVerifyAllSignaturesEnabled` every file's, is verified).
   */
  async getMavenArtifactVersion(
    repoName: string,
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Promise<ArtifactVersionInfo> {
    const res = await this.client.mavenArtifactController.getMavenArtifactVersion({
      repoName,
      groupName,
      artifactName,
      version: versionName,
    });
    return unwrap(res.data, 'getMavenArtifactVersion');
  }

  /** Removes a registered PGP public key from a Maven repo's key store (RPS-1189). */
  async deletePgpPublicKey(repoName: string, id: string): Promise<void> {
    await this.client.keyStoreController.deleteMavenPgpPublicKey({ repoName, publicKeyId: id });
  }

  /**
   * Deletes one Go module version (`DELETE /api/go/modules/{repoName}/versions?modulePath=&
   * version=`, step 4d/RPS-294 R14/G8). Called directly with `fetch`, like {@link
   * listDeployTokensPage}: the generated client is not worth wiring in for a single query-param
   * endpoint no other part of this harness needs.
   */
  async deleteGolangModuleVersion(
    repoName: string,
    modulePath: string,
    version: string,
  ): Promise<void> {
    const url = new URL(`${this.baseUrl}/api/go/modules/${encodeURIComponent(repoName)}/versions`);
    url.searchParams.set('modulePath', modulePath);
    url.searchParams.set('version', version);

    const res = await fetch(url, {
      method: 'DELETE',
      headers: { Authorization: this.authorization() },
    });
    if (!res.ok) {
      throw new ApiError(
        { method: 'DELETE', url: url.toString() },
        {
          status: res.status,
          statusText: res.statusText,
          url: url.toString(),
          ok: false,
          body: null,
        },
        `deleteGolangModuleVersion failed with status ${res.status}`,
      );
    }
  }

  /**
   * Deletes one Ruby gem version (`DELETE /api/ruby/gems/{repoName}/{gemName}/versions/
   * {version}?platform=`, step 4e/RPS-294 R5/R16) -- a real panel-API delete, distinct from a
   * protocol-level `gem yank`. Called directly with `fetch`, like {@link
   * deleteGolangModuleVersion}: the generated client is not worth wiring in for a single endpoint no
   * other part of this harness needs.
   */
  async deleteRubyGemVersion(
    repoName: string,
    gemName: string,
    version: string,
    platform = 'ruby',
  ): Promise<void> {
    const url = new URL(
      `${this.baseUrl}/api/ruby/gems/${encodeURIComponent(repoName)}/${encodeURIComponent(gemName)}/versions/${encodeURIComponent(version)}`,
    );
    url.searchParams.set('platform', platform);

    const res = await fetch(url, {
      method: 'DELETE',
      headers: { Authorization: this.authorization() },
    });
    if (!res.ok) {
      throw new ApiError(
        { method: 'DELETE', url: url.toString() },
        {
          status: res.status,
          statusText: res.statusText,
          url: url.toString(),
          ok: false,
          body: null,
        },
        `deleteRubyGemVersion failed with status ${res.status}`,
      );
    }
  }

  /**
   * Deletes a Docker tag (`DELETE /api/docker/images/{repoName}/{imageName}/tags/{tagName}`,
   * RPS-1216): only the tag pointer goes, its manifest stays stored, untagged. Called directly with
   * `fetch`, like {@link deleteGolangModuleVersion}.
   */
  async deleteDockerTag(repoName: string, imageName: string, tagName: string): Promise<void> {
    const url = new URL(
      `${this.baseUrl}/api/docker/images/${encodeURIComponent(repoName)}/${encodeURIComponent(imageName)}/tags/${encodeURIComponent(tagName)}`,
    );

    const res = await fetch(url, {
      method: 'DELETE',
      headers: { Authorization: this.authorization() },
    });
    if (!res.ok) {
      throw new ApiError(
        { method: 'DELETE', url: url.toString() },
        {
          status: res.status,
          statusText: res.statusText,
          url: url.toString(),
          ok: false,
          body: null,
        },
        `deleteDockerTag failed with status ${res.status}`,
      );
    }
  }
}

const DEFAULT_TOKEN_LIST_PAGE_SIZE = 20;
const TOKEN_LOOKUP_PAGE_SIZE = 50;
const TOKEN_LOOKUP_MAX_PAGES = 40;
const PGP_PUBLIC_KEY_LIST_PAGE_SIZE = 50;
