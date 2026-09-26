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
}

/** Only Maven and NuGet consult the releases/snapshots settings (RPS-1210). */
const versionAllowanceTypes: readonly RepoType[] = ['MAVEN', 'NUGET'];
const supportsVersionAllowanceSettings = (repoType: RepoType): boolean =>
  versionAllowanceTypes.includes(repoType);

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
};

/**
 * PROVISIONAL until RPS-1491 (the probe of Repsy Cloud DEV) pins them. Known so far: the harness runs
 * on the FREE plan (1 deploy token and 1 collaborator per repo, RPS-1498), and Repsy Cloud validates
 * an expiration date as a future instant, so `token-expired` needs a short lifetime and a wait.
 * Guessed: directory listings (off, so no fingerprint may rely on them) and which repo types accept
 * `releases`/`snapshots` (the OS set, as Repsy Cloud forks the OS backend).
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
};

const CAPABILITIES: Record<RepsyTarget, TargetCapabilities> = {
  local: { ownsStack: true, canTuneThrottle: true, isRemote: false, ...OS_CAPABILITIES },
  ci: { ownsStack: true, canTuneThrottle: true, isRemote: false, ...OS_CAPABILITIES },
  remote: { ownsStack: false, canTuneThrottle: false, isRemote: true, ...OS_CAPABILITIES },
  // The harness never owns Repsy Cloud, even one that runs next to it (`cloud-local`), and Repsy
  // Cloud rate-limits failed authentications either way: both are `isRemote`.
  'cloud-remote': {
    ownsStack: false,
    canTuneThrottle: false,
    isRemote: true,
    ...CLOUD_CAPABILITIES,
  },
  'cloud-local': {
    ownsStack: false,
    canTuneThrottle: false,
    isRemote: true,
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
