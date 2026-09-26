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
 * The panel operations the harness needs from a Repsy target, as an interface: login, repositories,
 * settings, deploy tokens, users and the protocol-specific panel calls. The seeder, the fixtures, the
 * sweep, the UI helpers and the specs are typed against this interface and never against a concrete
 * client, so another target (Repsy Cloud) can supply its own implementation without touching them.
 *
 * `OsPanelBackend` (`os-panel-backend.ts`) is the built-in implementation over the OpenAPI-generated
 * client. `backend-registry.ts` picks the implementation for a run (`env.target`, or the module named by
 * `REPSY_E2E_BACKEND_MODULE`), see README "Targets".
 *
 * This file has NO runtime dependency on the generated client (`src/api/generated`): the models are
 * imported as types only, which are erased, and the two enums specs need as values (`RepoType`,
 * `UserRole`) are declared here. A run against a backend that does not use the generated client
 * therefore never loads it.
 */
import type { ArtifactVersionInfo } from './generated/models/ArtifactVersionInfo.js';
import type { DeployTokenForm } from './generated/models/DeployTokenForm.js';
import type { DeployTokenInfoListItem } from './generated/models/DeployTokenInfoListItem.js';
import type { ImageListItem } from './generated/models/ImageListItem.js';
import type { LoginInfo } from './generated/models/LoginInfo.js';
import type { PgpPublicKeyItem } from './generated/models/PgpPublicKeyItem.js';
import type { RepoCreateRequest } from './generated/models/RepoCreateRequest.js';
import type { RepoListInfo } from './generated/models/RepoListInfo.js';
import type { RepoSecuritySummary } from './generated/models/RepoSecuritySummary.js';
import type { RepoSettingsForm } from './generated/models/RepoSettingsForm.js';
import type { RepoSettingsInfo } from './generated/models/RepoSettingsInfo.js';
import type { SecurityScansSummary } from './generated/models/SecurityScansSummary.js';
import type { TokenInfo } from './generated/models/TokenInfo.js';
import type { UserResponse } from './generated/models/UserResponse.js';
import type { VulnerabilityFindingInfo } from './generated/models/VulnerabilityFindingInfo.js';
import type { VulnerabilityScanInfo } from './generated/models/VulnerabilityScanInfo.js';
import type { ExpectationOverlay } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { Seeder } from '../seed/seeder.js';

export type {
  ArtifactVersionInfo,
  DeployTokenForm,
  DeployTokenInfoListItem,
  ImageListItem,
  LoginInfo,
  PgpPublicKeyItem,
  RepoListInfo,
  RepoSecuritySummary,
  RepoSettingsForm,
  RepoSettingsInfo,
  SecurityScansSummary,
  TokenInfo,
  UserResponse,
  VulnerabilityFindingInfo,
  VulnerabilityScanInfo,
};

/**
 * The repository formats, spelled as the API's canonical upper-case names. Declared here (same values
 * as the generated enum) so that specs can use it without loading the generated client. A generated
 * `RepoType` is assignable to this type, the other way round needs a cast, which only
 * `OsPanelBackend` does.
 */
export const RepoType = {
  MAVEN: 'MAVEN',
  NPM: 'NPM',
  PYPI: 'PYPI',
  DOCKER: 'DOCKER',
  CARGO: 'CARGO',
  NUGET: 'NUGET',
  GOLANG: 'GOLANG',
  HELM: 'HELM',
  RUBY: 'RUBY',
} as const;
export type RepoType = (typeof RepoType)[keyof typeof RepoType];

/** A user's role on a Repsy OS instance (the generated `UserRole`'s values, see `RepoType`). */
export const UserRole = {
  USER: 'USER',
  ADMIN: 'ADMIN',
} as const;
export type UserRole = (typeof UserRole)[keyof typeof UserRole];

/** The body of `POST /api/repos` without its `type`, which `createRepo` takes as its own argument. */
export type RepoCreateForm = Omit<RepoCreateRequest, 'type'>;

/** The query of the repository list; every field is optional. */
export interface RepoListParams {
  type?: RepoType;
  q?: string;
  page?: number;
  size?: number;
  sort?: string[];
}

/**
 * A user to create through the panel. `role` is Repsy OS's coarse account role (`USER` by default);
 * `permissions` is the fine-grained alternative a backend with per-repository permissions (Repsy Cloud)
 * understands. A backend that supports only one of them rejects the other, an OS backend rejects
 * `permissions`. The shape is provisional until RPS-1491 (the Cloud panel probe) confirms it.
 */
export interface UserSpec {
  username: string;
  password: string;
  role?: UserRole;
  permissions?: readonly string[];
}

/** Thrown when a successful REST response's envelope unexpectedly carries no `data`. */
export class PanelApiError extends Error {}

/**
 * Thrown by a backend when the panel answers a request with an HTTP error status. Backend-neutral
 * stand-in for the generated client's `ApiError`, so specs can match a status without importing it.
 */
export class PanelHttpError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body?: unknown,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'PanelHttpError';
  }
}

/** True when `err` is a `PanelHttpError` with the given HTTP status (for tolerating double-deletes). */
export function isPanelHttpStatus(err: unknown, status: number): boolean {
  return err instanceof PanelHttpError && err.status === status;
}

/**
 * Thrown by a backend for an operation its target does not offer. `ticket` is the Jira key that tracks
 * the gap (for example `RPS-1498`). A spec turns it into a known gap or a skip instead of a failure.
 */
export class UnsupportedPanelOperation extends Error {
  constructor(
    readonly ticket: string,
    readonly operation?: string,
  ) {
    super(
      `Panel operation${operation ? ` "${operation}"` : ''} is not supported by this backend (${ticket})`,
    );
    this.name = 'UnsupportedPanelOperation';
  }
}

export function isUnsupportedPanelOperation(err: unknown): err is UnsupportedPanelOperation {
  return err instanceof UnsupportedPanelOperation;
}

/**
 * What a backend needs to seed one credential of a scenario (RPS-1498): the repo it is for and the
 * `seeder` that tracks every entity it creates, so the test's cleanup removes them.
 */
export interface CredentialSeedContext {
  seeder: Seeder;
  repoName: string;
  repoType: RepoType;
}

/** Which half of a scenario a known gap is about: its publish (with everything before it) or its consume. */
export type ScenarioSide = 'publish' | 'consume';

/** One page of a repo's deploy tokens, read straight off the JSON envelope. */
export interface DeployTokenPage {
  content: DeployTokenInfoListItem[];
  totalPages: number;
}

export interface PanelBackend {
  // Session ---------------------------------------------------------------------------------------

  /** Logs in and keeps the session for every later call of this instance. */
  login(username: string, password: string): Promise<LoginInfo>;
  /** Changes the password of the user this instance is logged in as and adopts the fresh session. */
  changeOwnPassword(password: string): Promise<void>;
  /** A panel request the typed operations cannot express (a repo type in another case), as raw HTTP. */
  rawRequest(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
  ): Promise<{ status: number; body: { data?: unknown } }>;

  // Target-specific credentials, expectations and known gaps (RPS-1498) --------------------------
  //
  // `fixtures.ts` asks the backend for the two credentials that differ per product, after the
  // capability check of `target.ts` (`supportsUserRole`/`supportsRepoUsers`, `expiredTokenStrategy`)
  // did not already skip the scenario. A backend that cannot seed one throws
  // `UnsupportedPanelOperation`, which the fixture turns into `test.skip` with the reason.

  /** The `user-password` credential: an account (OS: a `USER`) or a collaborator of `ctx.repoName` (Cloud). */
  seedUserCredential(ctx: CredentialSeedContext): Promise<MaterializedCredential>;
  /** The `token-expired` credential: a deploy token of `ctx.repoName` that no longer authenticates. */
  seedExpiredTokenCredential(ctx: CredentialSeedContext): Promise<MaterializedCredential>;
  /**
   * Outcomes this target pins over the catalog's, so a difference of Repsy Cloud never goes into
   * `catalog.ts` (`expectationFor`, `scenarios/types.ts`). Absent: the catalog's, as on every OS run.
   */
  readonly expectByTarget?: ExpectationOverlay;
  /**
   * A known, already-filed gap of this target for one scenario of a protocol: a reason string (name
   * the Jira key) when the scenario is expected to fail on that `side`, `undefined` otherwise. The
   * loop consults it before the adapter's own `known*` hooks and marks the test `test.fail`, so a gap
   * that starts passing FAILS the run ("Expected to fail, but passed"): fix it and delete the entry in
   * the same change as the bump that fixed it. Absent: no gaps, as on every OS run.
   */
  knownGap?(protocol: string, scenarioId: string, side: ScenarioSide): string | undefined;

  // Users -----------------------------------------------------------------------------------------

  createRepoUser(spec: UserSpec): Promise<UserResponse>;
  deleteRepoUser(userId: string): Promise<void>;
  /** One page of users; `q` filters by username. Use `listAllUsers` to read everything. */
  listUsers(params?: {
    q?: string;
    page?: number;
    size?: number;
    sort?: string[];
  }): Promise<UserResponse[]>;
  /** Every user matching `q`. Collect first, then act: deleting while paging skips rows. */
  listAllUsers(filter?: { q?: string }): Promise<UserResponse[]>;

  // Repositories ----------------------------------------------------------------------------------

  createRepo(repoType: RepoType, form: RepoCreateForm): Promise<RepoListInfo>;
  deleteRepo(repoName: string): Promise<void>;
  /** Every repository matching `type` and `q`, sorted by name. Collect first, then act. */
  listAllRepos(filter?: { type?: RepoType; q?: string }): Promise<RepoListInfo[]>;
  /** The repository's type, in the API's canonical upper case. */
  getRepoFormat(repoName: string): Promise<RepoType>;
  /** How many repositories there are of each type (every type is a key). */
  repoCounts(): Promise<Record<string, number>>;
  getSettings(repoName: string): Promise<RepoSettingsInfo>;
  updateSettings(repoName: string, form: RepoSettingsForm): Promise<void>;

  // Deploy tokens ---------------------------------------------------------------------------------

  createDeployToken(repoName: string, form: DeployTokenForm): Promise<TokenInfo>;
  revokeDeployToken(repoName: string, tokenId: string): Promise<void>;
  /** Returns the new token value; the old one stops working immediately. */
  rotateDeployToken(repoName: string, tokenId: string): Promise<string>;
  /** A repo's first page of deploy tokens, newest first. */
  listDeployTokens(repoName: string): Promise<DeployTokenInfoListItem[]>;
  /** One deploy token by name, paging as far as needed. */
  findDeployTokenByName(
    repoName: string,
    name: string,
  ): Promise<DeployTokenInfoListItem | undefined>;

  // Maven -----------------------------------------------------------------------------------------

  registerPgpPublicKey(repoName: string, armoredKey: string): Promise<PgpPublicKeyItem>;
  /** A repo's first page of registered PGP public keys. */
  listPgpPublicKeys(repoName: string): Promise<PgpPublicKeyItem[]>;
  deletePgpPublicKey(repoName: string, id: string): Promise<void>;
  getMavenArtifactVersion(
    repoName: string,
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Promise<ArtifactVersionInfo>;
  listMavenArtifactNames(repoName: string, groupName: string): Promise<string[]>;
  listMavenArtifactVersionNames(
    repoName: string,
    groupName: string,
    artifactName: string,
  ): Promise<string[]>;
  deleteMavenArtifactVersion(
    repoName: string,
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Promise<void>;

  // Other protocols -------------------------------------------------------------------------------

  deletePypiRelease(repoName: string, packageName: string, version: string): Promise<void>;
  deleteGolangModuleVersion(repoName: string, modulePath: string, version: string): Promise<void>;
  deleteRubyGemVersion(
    repoName: string,
    gemName: string,
    version: string,
    platform?: string,
  ): Promise<void>;
  /** The image as the panel list shows it; a 404 `PanelHttpError` once the image is gone. */
  getDockerImageSummary(repoName: string, imageName: string): Promise<ImageListItem>;
  /** Deletes only the tag pointer; its manifest stays stored, untagged. */
  deleteDockerTag(repoName: string, imageName: string, tagName: string): Promise<void>;

  // Security scanning -----------------------------------------------------------------------------

  /** The repo types that have a scanner (empty while it is off), sorted. */
  supportedScanRepoTypes(): Promise<string[]>;
  /** The scan history of one version, newest first (up to 100). */
  listVersionScans(
    repoName: string,
    artifactName: string,
    version: string,
  ): Promise<VulnerabilityScanInfo[]>;
  /** The findings of one scan (up to 100), worst first. */
  listScanFindings(repoName: string, scanId: string): Promise<VulnerabilityFindingInfo[]>;
  /** Findings per severity over the latest completed scan of every version (admin only). */
  securityScansSummary(filter?: {
    repoType?: RepoType;
    repoName?: string;
  }): Promise<SecurityScansSummary>;
  /** One entry per repo that has one (all repos when none are named). */
  repoSecuritySummary(repoNames?: string[]): Promise<Record<string, RepoSecuritySummary>>;
}
