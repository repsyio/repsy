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

import type { RepoType } from './api/panel-backend.js';
import { env, type RepsyTarget } from './env.js';
import { uiBaseUrlFrom } from './ui/base-url.js';

/**
 * How a repository is addressed in a protocol URL (`src/repo-url.ts`):
 *  - `repo`:       `/<repo>/...`, Repsy OS.
 *  - `owner-repo`: `/<owner>/<repo>/...` (Docker `/v2/<owner>/<repo>/<image>`), an owner-scoped
 *                  registry such as Repsy Cloud; the owner is `env.repoOwner`.
 */
export type UrlScheme = 'repo' | 'owner-repo';

const URL_SCHEMES: readonly UrlScheme[] = ['repo', 'owner-repo'];

/**
 * How the harness gets an expired deploy-token credential (`token-expired`):
 *  - `past-date`: create the token with an expiration date in the past (needs `supportsExpiredTokenSeed`).
 *  - `short-ttl-wait`: create it with a short lifetime and wait until that has passed.
 *  - `unsupported`: there is no way; a scenario needing it is skipped with a reason.
 */
export type ExpiredTokenStrategy = 'past-date' | 'short-ttl-wait' | 'unsupported';

/**
 * The `localStorage` keys of the panel's session (`AuthService` of the SPA). Repsy OS keeps three;
 * Repsy Cloud keeps the account's email as well.
 */
export interface UiSessionStorageKeys {
  username: string;
  token: string;
  refreshToken: string;
  /** Only where the panel keeps the account's email (Repsy Cloud). */
  email?: string;
}

/**
 * The name of the login form's identifier field: the OS form takes a `username`, the Repsy Cloud one a
 * `usernameOrEmail`. The page object derives its test ids from it (`login-<loginField>`).
 */
export type UiLoginField = 'username' | 'usernameOrEmail';

/**
 * What differs between the two panels (RPS-1638), for the Playwright `ui` project (`src/ui/`,
 * `tests/ui/`). A page object or spec asks it and never writes `/${repo}`, `/profile` or a storage key
 * itself, so the same specs can run against Repsy Cloud's panel (Repsy Cloud's own e2e package,
 * RPS-1639) while what only Repsy OS has is tagged `@cloud-skip`.
 */
export interface UiCapabilities {
  /**
   * The SPA route of a repository, or of a page inside it: `repoRoute('r')` is `/r` on Repsy OS and
   * `/<owner>/r` on Repsy Cloud, `repoRoute('r', 'settings')` is `/r/settings` and `/<owner>/r/settings`.
   * The segments are joined as they are (no encoding: a caller that needs a query string appends it).
   * Reads `env.repoOwner` when it is called, never at import ("Import time is not run time", README),
   * and throws on Repsy Cloud when `REPSY_REPO_OWNER` is unset.
   */
  repoRoute(repo: string, ...segments: string[]): string;
  /** The route of the signed-in account's own page: `/profile` (OS) or `/account` (Cloud). */
  profilePath: string;
  /** The panel has an admin-only Users page (`/users`, the sidebar's Users entry): Repsy OS only. */
  hasUsersPage: boolean;
  /** The login form's identifier field: see `UiLoginField`. */
  loginField: UiLoginField;
  /** The `localStorage` keys the panel keeps its session in: see `UiSessionStorageKeys`. */
  sessionStorageKeys: UiSessionStorageKeys;
  /**
   * Where the panel is served from: `REPSY_UI_BASE_URL`, then (Repsy Cloud only) `REPSY_FRONTEND_BASE_URL`,
   * then `REPSY_API_BASE_URL`, then `http://localhost:8080` (`src/ui/base-url.ts`). Read when called.
   */
  frontendBaseUrl(): string;
}

export interface TargetCapabilities {
  /** Whether the harness started this stack and may restart or reconfigure it. */
  ownsStack: boolean;
  /** Whether AUTH_THROTTLE_* can be raised for this run, so negative-auth scenarios can run freely. */
  canTuneThrottle: boolean;
  /**
   * Whether this is an instance the harness must leave exactly as it found it and cannot tune: the
   * scenario loop runs `@negative` scenarios serially and spends a `RemoteAuthBudget` on them. Every
   * Repsy Cloud target counts, since its ingress rate-limits failed authentications.
   */
  isRemote: boolean;

  /** Repsy OS or Repsy Cloud: for an adapter hook to branch on, when a capability below is too fine. */
  kind: 'os' | 'cloud';
  /** How this target addresses a repository in a URL: see `UrlScheme`. Repsy Cloud defaults to `owner-repo`. */
  urlScheme: UrlScheme;

  /** Accounts have an OS-style coarse role (`UserRole`): the `user-password` credential is a plain `USER` account. */
  supportsUserRole: boolean;
  /** A user is a collaborator of a repository (Repsy Cloud), not an account with a role. */
  supportsRepoUsers: boolean;
  /** A deploy token can be created with an expiration date in the past. */
  supportsExpiredTokenSeed: boolean;
  expiredTokenStrategy: ExpiredTokenStrategy;
  /** How many deploy tokens one repo may have (`Infinity` on OS; the Repsy Cloud FREE plan allows 1). */
  maxDeployTokensPerRepo: number;
  /** Whether the protocol port answers a `GET` of a directory path with a listing (fingerprints may walk it). */
  supportsDirectoryListing: boolean;
  /** Whether the repo settings accept `releases`/`snapshots` for this repo type (a 400 otherwise, RPS-1210). */
  supportsVersionAllowanceSettings(repoType: RepoType): boolean;
  /** What differs in the panel the `ui` project drives: see `UiCapabilities` (RPS-1638). */
  ui: UiCapabilities;
}

/** Only Maven and NuGet consult the releases/snapshots settings (RPS-1210). */
const versionAllowanceTypes: readonly RepoType[] = ['MAVEN', 'NUGET'];
const supportsVersionAllowanceSettings = (repoType: RepoType): boolean =>
  versionAllowanceTypes.includes(repoType);

const OS_UI: UiCapabilities = {
  repoRoute: (repo, ...segments) => ['', repo, ...segments].join('/'),
  profilePath: '/profile',
  hasUsersPage: true,
  loginField: 'username',
  sessionStorageKeys: { username: 'username', token: 'token', refreshToken: 'refresh-token' },
  frontendBaseUrl: () => uiBaseUrlFrom(process.env, false),
};

/**
 * PROVISIONAL, like `CLOUD_CAPABILITIES`: read from the Repsy Cloud frontend (`repsy-cloud/repsy-frontend`:
 * `app.routes.ts` has `account` and `:owner/:repoName`, `AuthService` the four storage keys, the login
 * form a `usernameOrEmail` control), not yet run against it. RPS-1639 (the Cloud `ui` project) and
 * RPS-1541 (test id parity) confirm or change them.
 */
const CLOUD_UI: UiCapabilities = {
  repoRoute: (repo, ...segments) => {
    if (!env.repoOwner) {
      throw new Error(
        'The panel of Repsy Cloud routes a repository as /<owner>/<repo>, but REPSY_REPO_OWNER is not set.',
      );
    }
    return ['', env.repoOwner, repo, ...segments].join('/');
  },
  profilePath: '/account',
  hasUsersPage: false,
  loginField: 'usernameOrEmail',
  sessionStorageKeys: {
    username: 'username',
    token: 'token',
    refreshToken: 'refresh-token',
    email: 'email',
  },
  frontendBaseUrl: () => uiBaseUrlFrom(process.env, true),
};

type ProductCapabilities = Omit<TargetCapabilities, 'ownsStack' | 'canTuneThrottle' | 'isRemote'>;

const OS_CAPABILITIES: ProductCapabilities = {
  kind: 'os',
  urlScheme: 'repo',
  supportsUserRole: true,
  supportsRepoUsers: false,
  supportsExpiredTokenSeed: true,
  expiredTokenStrategy: 'past-date',
  maxDeployTokensPerRepo: Number.POSITIVE_INFINITY,
  supportsDirectoryListing: true,
  supportsVersionAllowanceSettings,
  ui: OS_UI,
};

/**
 * Repsy Cloud, as probed on DEV in RPS-1491 (`docs/cloud-expectations.md` of the repsy-mono
 * repository, "Proposed cloud column"). Pinned: the harness runs on the FREE plan (1 deploy token and
 * 1 collaborator per repo, RPS-1498), and Repsy Cloud
 * validates an expiration date as a future instant, so `token-expired` needs a short lifetime and a
 * wait (a 3 s lifetime is accepted). `supportsVersionAllowanceSettings` is the OS set: Repsy Cloud
 * ENFORCES releases/snapshots for Maven and NuGet only, but its panel still ACCEPTS them for every
 * type (RPS-1635), which is a known gap of the target, not a capability.
 *
 * `supportsDirectoryListing` is `false` although Maven repos do answer a directory `GET` with a listing
 * (no other protocol does): it is one flag for every protocol, so it stays off and no fingerprint may
 * rely on it. A per-protocol answer is the Repsy Cloud runner story's decision (RPS-1511).
 */
const CLOUD_CAPABILITIES: ProductCapabilities = {
  kind: 'cloud',
  urlScheme: 'owner-repo',
  supportsUserRole: false,
  supportsRepoUsers: true,
  supportsExpiredTokenSeed: false,
  expiredTokenStrategy: 'short-ttl-wait',
  maxDeployTokensPerRepo: 1,
  supportsDirectoryListing: false,
  supportsVersionAllowanceSettings,
  ui: CLOUD_UI,
};

const CAPABILITIES: Record<RepsyTarget, TargetCapabilities> = {
  local: { ownsStack: true, canTuneThrottle: true, isRemote: false, ...OS_CAPABILITIES },
  ci: { ownsStack: true, canTuneThrottle: true, isRemote: false, ...OS_CAPABILITIES },
  remote: { ownsStack: false, canTuneThrottle: false, isRemote: true, ...OS_CAPABILITIES },
  // The harness never owns Repsy Cloud and cannot tune it, on either target (RPS-1510, D7).
  //  - cloud-remote (a deployed environment, DEV): `isRemote`. Failed authentications count against a
  //    shared instance's rate limit, so the scenario loop runs `@negative` scenarios serially on a
  //    `RemoteAuthBudget`.
  //  - cloud-local (a Repsy Cloud stack built next to the harness, its own database): NOT `isRemote`.
  //    Nobody else shares it, so `@negative` scenarios run in parallel as on `local`. It still is not
  //    `ownsStack` (the harness does not start, restart or reconfigure it, so `size-limits`,
  //    `connector-limits` and the stack specs, which assume the OS environment overlays, stay off) and
  //    `canTuneThrottle` is `false` until Repsy Cloud ports the throttle (RPS-1542).
  'cloud-remote': {
    ownsStack: false,
    canTuneThrottle: false,
    isRemote: true,
    ...CLOUD_CAPABILITIES,
  },
  'cloud-local': {
    ownsStack: false,
    canTuneThrottle: false,
    isRemote: false,
    ...CLOUD_CAPABILITIES,
  },
};

export function capabilitiesFor(target: RepsyTarget): TargetCapabilities {
  return CAPABILITIES[target];
}

/**
 * `REPSY_E2E_URL_SCHEME` overrides the target's default scheme, for a stack that serves
 * `owner/repo` URLs (set `REPSY_REPO_OWNER` with it). Unset, it is the target's own (`repo` for OS, `owner-repo` for Repsy Cloud).
 */
function parseUrlScheme(value: string | undefined, fallback: UrlScheme): UrlScheme {
  if (!value) {
    return fallback;
  }
  if (!URL_SCHEMES.includes(value as UrlScheme)) {
    throw new Error(
      `REPSY_E2E_URL_SCHEME must be one of ${URL_SCHEMES.join(', ')}, got "${value}"`,
    );
  }
  return value as UrlScheme;
}

const defaults = capabilitiesFor(env.target);

export const target: TargetCapabilities = {
  ...defaults,
  urlScheme: parseUrlScheme(process.env.REPSY_E2E_URL_SCHEME, defaults.urlScheme),
};
