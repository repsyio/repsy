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
 * `PanelBackend` for Repsy OS: a thin, hand-written wrapper around the generated panel API client
 * (`src/api/generated`, built by `pnpm gen:api` from
 * `repsy-backend/src/main/resources/openapi/openapi-spec.yaml`). It only covers what the harness
 * needs: users, repos, settings, deploy tokens and the protocol-specific panel operations.
 *
 * The generated client is imported LAZILY (on the first call), so that a run whose backend is another
 * implementation (`REPSY_E2E_BACKEND_MODULE`, README "Targets") never needs it. Everything else in this
 * file is either a type import (erased) or plain `fetch`.
 *
 * The OpenAPI spec lists no `Authorization` header parameter on any operation (RPS-1161): the backend
 * reads the header through an argument resolver the spec does not document. `PanelClient`'s `TOKEN`
 * resolver supplies it on every request, so no call here passes it explicitly.
 */
import type { PanelClient, RepoType as GeneratedRepoType } from './generated/index.js';
import type { PagedModelRepoListInfo } from './generated/models/PagedModelRepoListInfo.js';
import type { UserCreateForm } from './generated/models/UserCreateForm.js';
import {
  type ArtifactVersionInfo,
  type DeployTokenForm,
  type DeployTokenInfoListItem,
  type DeployTokenPage,
  type ImageListItem,
  type LoginInfo,
  type PanelBackend,
  PanelApiError,
  PanelHttpError,
  type PgpPublicKeyItem,
  type RepoCreateForm,
  type RepoListInfo,
  type RepoListParams,
  type RepoSecuritySummary,
  type RepoSettingsForm,
  type RepoSettingsInfo,
  type RepoType,
  type SecurityScansSummary,
  type TokenInfo,
  UserRole,
  type UserResponse,
  type UserSpec,
  type VulnerabilityFindingInfo,
  type VulnerabilityScanInfo,
} from './panel-backend.js';

type Generated = typeof import('./generated/index.js');

/** The panel's own query shapes, whose `RepoType` is the generated enum (see `RepoType` in panel-backend). */
type GeneratedRepoTypeFilter = { repoType?: GeneratedRepoType; repoName?: string };
type GeneratedRepoListParams = Omit<RepoListParams, 'type'> & { type?: GeneratedRepoType };

function unwrap<T>(data: T | null | undefined, what: string): T {
  if (data === null || data === undefined) {
    throw new PanelApiError(`Panel API response for "${what}" carried no data`);
  }
  return data;
}

/**
 * One page of `GET /api/mvn/key-stores/{repoName}/public-keys`, read straight off the JSON
 * envelope (RPS-1189).
 */
interface PgpPublicKeyPage {
  content: PgpPublicKeyItem[];
  totalPages: number;
}

export class OsPanelBackend implements PanelBackend {
  private generated: Promise<{ module: Generated; client: PanelClient }> | undefined;
  private readonly baseUrl: string;
  private token: string | undefined;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  /** Loads the generated client on first use, see the file header. */
  private load(): Promise<{ module: Generated; client: PanelClient }> {
    this.generated ??= import('./generated/index.js').then((module) => ({
      module,
      client: new module.PanelClient({
        BASE: this.baseUrl,
        // Resolved per request, not fixed at construction time, so login() can populate it later.
        TOKEN: async () => this.token ?? '',
      }),
    }));
    return this.generated;
  }

  /**
   * Runs one generated-client call. The client's `ApiError` (an HTTP error status) becomes the
   * backend-neutral `PanelHttpError`, so specs and the seeder match on that instead.
   */
  private async call<T>(operation: (client: PanelClient) => Promise<T>): Promise<T> {
    const { module, client } = await this.load();
    try {
      return await operation(client);
    } catch (err) {
      if (err instanceof module.ApiError) {
        throw new PanelHttpError(err.message, err.status, err.body, { cause: err });
      }
      throw err;
    }
  }

  async login(username: string, password: string): Promise<LoginInfo> {
    const res = await this.call((c) =>
      c.authController.login({ requestBody: { username, password } }),
    );
    const data = unwrap(res.data, 'login');
    this.token = unwrap(data.token, 'login.token');
    return data;
  }

  private authorization(): string {
    if (!this.token) {
      throw new PanelApiError('OsPanelBackend.login() must succeed before an authenticated call');
    }
    return `Bearer ${this.token}`;
  }

  /** `POST /api/users`. Repsy OS has roles only: `permissions` is a Repsy Cloud concept. */
  async createRepoUser(spec: UserSpec): Promise<UserResponse> {
    if (spec.permissions !== undefined) {
      throw new Error('OsPanelBackend.createRepoUser: Repsy OS users have a role, not permissions');
    }
    const form: UserCreateForm = {
      username: spec.username,
      password: spec.password,
      role: (spec.role ?? UserRole.USER) as UserCreateForm['role'],
    };
    const res = await this.call((c) => c.userController.createUser({ requestBody: form }));
    return unwrap(res.data, 'createUser');
  }

  async deleteRepoUser(userId: string): Promise<void> {
    await this.call((c) => c.userController.deleteUser({ userId }));
  }

  /**
   * `PUT /api/profile/password` as the user this instance is logged in as (RPS-1481): the caller's OWN
   * password becomes `password`. Log a separate backend in as the user first, an admin one would
   * change the admin's password. The answer is a fresh session, which this instance adopts.
   */
  async changeOwnPassword(password: string): Promise<void> {
    const res = await this.call((c) =>
      c.profileController.updatePassword({ requestBody: { password } }),
    );
    this.token = unwrap(unwrap(res.data, 'updatePassword').token, 'updatePassword.token');
  }

  /**
   * One page of `GET /api/users` (RPS-1269): `q` filters by username on the server, `size` is 1-100
   * (server default 10) and `sort` defaults to `createdAt,desc`. Use `listAllUsers` to read everything.
   */
  async listUsers(
    params: { q?: string; page?: number; size?: number; sort?: string[] } = {},
  ): Promise<UserResponse[]> {
    const res = await this.call((c) => c.userController.listUsers(params));
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
      const res = await this.call((c) =>
        c.userController.listUsers({ ...filter, page, size: 100 }),
      );
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
    const res = await this.call((c) =>
      c.repoCollectionController.createRepository({
        requestBody: { ...form, type: repoType as GeneratedRepoType },
      }),
    );
    return unwrap(res.data, 'createRepo');
  }

  /** `GET /api/repos/{repoName}/format`: the repository's type, in the API's canonical upper case. */
  async getRepoFormat(repoName: string): Promise<RepoType> {
    const res = await this.call((c) => c.protocolRepoController.getRepoFormat({ repoName }));
    return unwrap(res.data, 'getRepoFormat');
  }

  /**
   * A panel request the generated client cannot make: it only sends the enum spellings, so a
   * repository type written in another case (`maven`) has to go over raw `fetch` (RPS-1269). The
   * answer is the HTTP status and the JSON envelope.
   */
  async rawRequest(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
  ): Promise<{ status: number; body: { data?: unknown } }> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        Authorization: this.authorization(),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, body: (await res.json()) as { data?: unknown } };
  }

  /** `GET /api/security/supported-repo-types`: the repo types that have a scanner (empty while it is off), sorted. */
  async supportedScanRepoTypes(): Promise<string[]> {
    const res = await this.call((c) => c.securityScanController.getSupportedRepoTypes());
    return [...unwrap(res.data, 'getSupportedRepoTypes')].sort();
  }

  /**
   * `GET .../versions/{version}/scans`: the scan history of one version, newest first (up to 100). Over
   * raw `fetch` because the generated client leaves a `/` in a path parameter as it is, and an npm
   * scope (`@scope/name`) has one, which the panel sends as `%2F`.
   */
  async listVersionScans(
    repoName: string,
    artifactName: string,
    version: string,
  ): Promise<VulnerabilityScanInfo[]> {
    const path = [repoName, 'artifacts', artifactName, 'versions', version]
      .map((part) => encodeURIComponent(part))
      .join('/');
    const url = `${this.baseUrl}/api/repos/${path}/scans?size=100&sort=createdAt,desc`;
    const res = await fetch(url, { headers: { Authorization: this.authorization() } });
    if (!res.ok) {
      throw new PanelApiError(`GET ${url} answered ${res.status}: ${await res.text()}`);
    }
    const body = (await res.json()) as { data?: { content?: VulnerabilityScanInfo[] } };
    return body.data?.content ?? [];
  }

  /** `GET /api/repos/{repo}/scans/{scanId}/findings`: the findings of one scan (up to 100), worst first. */
  async listScanFindings(repoName: string, scanId: string): Promise<VulnerabilityFindingInfo[]> {
    const res = await this.call((c) =>
      c.vulnerabilityScanController.getVulnerabilityScanFindings({
        repoName,
        scanId,
        size: 100,
      }),
    );
    return unwrap(res.data, 'getVulnerabilityScanFindings').content ?? [];
  }

  /** `GET /api/security/scans/summary`: findings per severity over the latest completed scan of every version (admin only). */
  async securityScansSummary(
    filter: { repoType?: RepoType; repoName?: string } = {},
  ): Promise<SecurityScansSummary> {
    const res = await this.call((c) =>
      c.securityScanController.getSecurityScansSummary(filter as GeneratedRepoTypeFilter),
    );
    return unwrap(res.data, 'getSecurityScansSummary');
  }

  /** `GET /api/repos/security-summary[?repoNames=...]`: one entry per repo that has one (all repos when none are named). */
  async repoSecuritySummary(repoNames?: string[]): Promise<Record<string, RepoSecuritySummary>> {
    const res = await this.call((c) =>
      c.vulnerabilityScanController.getSecuritySummary({ repoNames }),
    );
    return unwrap(res.data, 'getSecuritySummary');
  }

  async deleteRepo(repoName: string): Promise<void> {
    await this.call((c) => c.protocolRepoController.deleteRepo({ repoName }));
  }

  /**
   * One page of `GET /api/repos` (RPS-1268): `type` and `q` filter on the server, `size` is 1-100
   * (server default 10) and `sort` defaults to `createdAt,desc`. Use `listAllRepos` to read everything.
   */
  async listRepos(params: RepoListParams = {}): Promise<PagedModelRepoListInfo> {
    const res = await this.call((c) =>
      c.repoCollectionController.listRepos(params as GeneratedRepoListParams),
    );
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
    const res = await this.call((c) => c.repoCollectionController.getRepoCounts());
    return unwrap(res.data, 'repoCounts');
  }

  async getSettings(repoName: string): Promise<RepoSettingsInfo> {
    const res = await this.call((c) => c.protocolRepoController.getRepoSettings({ repoName }));
    return unwrap(res.data, 'getRepoSettings');
  }

  async updateSettings(repoName: string, form: RepoSettingsForm): Promise<void> {
    await this.call((c) =>
      c.protocolRepoController.updateRepoSettings({ repoName, requestBody: form }),
    );
  }

  async createDeployToken(repoName: string, form: DeployTokenForm): Promise<TokenInfo> {
    const res = await this.call((c) =>
      c.protocolDeployTokenController.createDeployToken({
        repoName,
        requestBody: form,
      }),
    );
    return unwrap(res.data, 'createDeployToken');
  }

  async revokeDeployToken(repoName: string, tokenId: string): Promise<void> {
    await this.call((c) =>
      c.protocolDeployTokenController.revokeDeployToken({ repoName, tokenId }),
    );
  }

  /** Returns the new token value; the old one stops working immediately. */
  async rotateDeployToken(repoName: string, tokenId: string): Promise<string> {
    const res = await this.call((c) =>
      c.protocolDeployTokenController.rotateDeployToken({
        repoName,
        tokenId,
      }),
    );
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
    const res = await this.call((c) =>
      c.protocolDeployTokenController.listDeployTokens({
        repoName,
        page,
        size,
        sort: ['id,desc'],
      }),
    );
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
    const res = await this.call((c) =>
      c.keyStoreController.createMavenPgpPublicKey({
        repoName,
        requestBody: { armoredKey },
      }),
    );
    return unwrap(res.data, 'registerPgpPublicKey');
  }

  /** One page of a Maven repo's registered PGP public keys, newest first. */
  async listPgpPublicKeysPage(
    repoName: string,
    page: number,
    size: number,
  ): Promise<PgpPublicKeyPage> {
    const res = await this.call((c) =>
      c.keyStoreController.listMavenPgpPublicKeys({
        repoName,
        page,
        size,
        sort: ['id,desc'],
      }),
    );
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
    const res = await this.call((c) =>
      c.mavenArtifactController.getMavenArtifactVersion({
        repoName,
        groupName,
        artifactName,
        version: versionName,
      }),
    );
    return unwrap(res.data, 'getMavenArtifactVersion');
  }

  /** The artifact names of one Maven group, as the panel lists them (its first page of 100). */
  async listMavenArtifactNames(repoName: string, groupName: string): Promise<string[]> {
    const res = await this.call((c) =>
      c.mavenArtifactController.listMavenArtifacts({
        repoName,
        groupName,
        size: 100,
      }),
    );
    return (unwrap(res.data, 'listMavenArtifacts').content ?? []).map((a) => a.artifactName ?? '');
  }

  /** The version names of one Maven artifact, as the panel lists them (its first page of 100). */
  async listMavenArtifactVersionNames(
    repoName: string,
    groupName: string,
    artifactName: string,
  ): Promise<string[]> {
    const res = await this.call((c) =>
      c.mavenArtifactController.listMavenArtifactVersions({
        repoName,
        groupName,
        artifactName,
        size: 100,
      }),
    );
    return (unwrap(res.data, 'listMavenArtifactVersions').content ?? []).map(
      (v) => v.versionName ?? '',
    );
  }

  /** Deletes one version of a Maven artifact, its files and its entry in the artifact's metadata. */
  async deleteMavenArtifactVersion(
    repoName: string,
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Promise<void> {
    await this.call((c) =>
      c.mavenArtifactController.deleteMavenArtifactVersion({
        repoName,
        groupName,
        artifactName,
        version: versionName,
      }),
    );
  }

  /** Removes a registered PGP public key from a Maven repo's key store (RPS-1189). */
  async deletePgpPublicKey(repoName: string, id: string): Promise<void> {
    await this.call((c) =>
      c.keyStoreController.deleteMavenPgpPublicKey({ repoName, publicKeyId: id }),
    );
  }

  /** Deletes one PyPI release (`DELETE /api/pypi/packages/{repoName}/{packageName}/releases/{version}`):
   *  PyPI has no wire delete, so this panel call is the only way a published file goes away. */
  async deletePypiRelease(repoName: string, packageName: string, version: string): Promise<void> {
    await this.call((c) =>
      c.pypiPackageController.deletePypiRelease({ repoName, packageName, version }),
    );
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
      throw new PanelHttpError(
        `deleteGolangModuleVersion failed with status ${res.status}`,
        res.status,
        {
          method: 'DELETE',
          url: url.toString(),
        },
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
      throw new PanelHttpError(
        `deleteRubyGemVersion failed with status ${res.status}`,
        res.status,
        {
          method: 'DELETE',
          url: url.toString(),
        },
      );
    }
  }

  /**
   * `GET /api/docker/images/{repoName}/{imageName}/summary` (RPS-1288): the image as the list shows
   * it, with `tagCount`, `untaggedManifestCount` and `untaggedSize`; a 404 `PanelHttpError` when the image
   * is gone (an image goes with its last manifest).
   */
  async getDockerImageSummary(repoName: string, imageName: string): Promise<ImageListItem> {
    const res = await this.call((c) =>
      c.dockerImageController.getDockerImageSummary({
        repoName,
        imageName,
      }),
    );
    return unwrap(res.data, 'getDockerImageSummary');
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
      throw new PanelHttpError(`deleteDockerTag failed with status ${res.status}`, res.status, {
        method: 'DELETE',
        url: url.toString(),
      });
    }
  }
}

const DEFAULT_TOKEN_LIST_PAGE_SIZE = 20;
const TOKEN_LOOKUP_PAGE_SIZE = 50;
const TOKEN_LOOKUP_MAX_PAGES = 40;
const PGP_PUBLIC_KEY_LIST_PAGE_SIZE = 50;
