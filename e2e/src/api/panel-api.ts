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
 * Every endpoint here that the OpenAPI spec does not list an explicit `Authorization` header
 * parameter for (every `protocol-repo-controller` and `protocol-deploy-token-controller` route) is
 * still authenticated: the backend reads the same header, just through an argument resolver the
 * spec does not document. `PanelClient`'s `TOKEN` resolver supplies it on every request; the
 * `user-controller` routes additionally require the header as an explicit parameter, so it is
 * passed there too.
 */
import { ApiError, PanelClient, RepoType, UserRole } from './generated/index.js';
import type { DeployTokenForm } from './generated/models/DeployTokenForm.js';
import type { DeployTokenInfoListItem } from './generated/models/DeployTokenInfoListItem.js';
import type { LoginInfo } from './generated/models/LoginInfo.js';
import type { RepoCreateForm } from './generated/models/RepoCreateForm.js';
import type { RepoListInfo } from './generated/models/RepoListInfo.js';
import type { RepoSettingsForm } from './generated/models/RepoSettingsForm.js';
import type { RepoSettingsInfo } from './generated/models/RepoSettingsInfo.js';
import type { TokenInfo } from './generated/models/TokenInfo.js';
import type { UserCreateForm } from './generated/models/UserCreateForm.js';
import type { UserResponse } from './generated/models/UserResponse.js';

export { ApiError, RepoType, UserRole };
export type {
  DeployTokenForm,
  DeployTokenInfoListItem,
  LoginInfo,
  RepoCreateForm,
  RepoListInfo,
  RepoSettingsForm,
  RepoSettingsInfo,
  TokenInfo,
  UserCreateForm,
  UserResponse,
};

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
      authorization: this.authorization(),
      requestBody: form,
    });
    return unwrap(res.data, 'createUser');
  }

  async deleteUser(userId: string): Promise<void> {
    await this.client.userController.deleteUser({ authorization: this.authorization(), userId });
  }

  async listUsers(
    params: { search?: string; page?: number; size?: number } = {},
  ): Promise<UserResponse[]> {
    const res = await this.client.userController.listUsers({
      authorization: this.authorization(),
      ...params,
    });
    return unwrap(res.data, 'listUsers').content ?? [];
  }

  async createRepo(repoType: RepoType, form: RepoCreateForm): Promise<void> {
    await this.client.protocolRepoController.createRepo({ repoType, requestBody: form });
  }

  async deleteRepo(repoName: string): Promise<void> {
    await this.client.protocolRepoController.deleteRepo({ repoName });
  }

  async listRepos(repoType: RepoType): Promise<RepoListInfo[]> {
    const res = await this.client.protocolRepoController.getInfo({ repoType });
    return unwrap(res.data, 'listRepos');
  }

  async getSettings(repoName: string): Promise<RepoSettingsInfo> {
    const res = await this.client.protocolRepoController.getSettings({ repoName });
    return unwrap(res.data, 'getSettings');
  }

  async updateSettings(repoName: string, form: RepoSettingsForm): Promise<void> {
    await this.client.protocolRepoController.updateSettings({ repoName, requestBody: form });
  }

  async createDeployToken(repoName: string, form: DeployTokenForm): Promise<TokenInfo> {
    const res = await this.client.protocolDeployTokenController.createDeployToken({
      repoName,
      requestBody: form,
    });
    return unwrap(res.data, 'createDeployToken');
  }

  async revokeDeployToken(repoName: string, tokenId: string): Promise<void> {
    await this.client.protocolDeployTokenController.revoke({ repoName, tokenId });
  }

  /** Returns the new token value; the old one stops working immediately. */
  async rotateDeployToken(repoName: string, tokenId: string): Promise<string> {
    const res = await this.client.protocolDeployTokenController.rotate({ repoName, tokenId });
    return unwrap(res.data, 'rotateDeployToken');
  }

  /**
   * One page of a repo's deploy tokens, newest first. The generated client's `listDeployTokens`
   * serialises its `pageable` query param as `pageable[page]=..&pageable[size]=..`, which Spring's
   * `Pageable` resolver does not bind (it always falls back to the server's default page), so this
   * calls the endpoint directly with `page`/`size`/`sort` as plain query parameters instead — the
   * form Spring actually binds.
   */
  async listDeployTokensPage(
    repoName: string,
    page: number,
    size: number,
  ): Promise<DeployTokenPage> {
    const url = new URL(`${this.baseUrl}/api/repos/${encodeURIComponent(repoName)}/deploy-tokens`);
    url.searchParams.set('page', String(page));
    url.searchParams.set('size', String(size));
    // Newest first (repo_deploy_token.id is a UUIDv7, so DESC on it is DESC on creation time too):
    // a token this harness just created is always on page 0, but sorting explicitly rather than
    // relying on the server's own default keeps that true even if the default ever changes.
    url.searchParams.set('sort', 'id,desc');

    const res = await fetch(url, { headers: { Authorization: this.authorization() } });
    if (!res.ok) {
      throw new ApiError(
        { method: 'GET', url: url.toString() },
        {
          status: res.status,
          statusText: res.statusText,
          url: url.toString(),
          ok: false,
          body: null,
        },
        `listDeployTokensPage failed with status ${res.status}`,
      );
    }

    const body = (await res.json()) as {
      data?: { content?: DeployTokenInfoListItem[]; page?: { totalPages?: number } };
    };
    return {
      content: body.data?.content ?? [],
      totalPages: body.data?.page?.totalPages ?? 0,
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
}

const DEFAULT_TOKEN_LIST_PAGE_SIZE = 20;
const TOKEN_LOOKUP_PAGE_SIZE = 50;
const TOKEN_LOOKUP_MAX_PAGES = 40;
