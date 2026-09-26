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

import { env, type RepsyTarget } from './env.js';

/**
 * How a repository is addressed in a protocol URL (`src/repo-url.ts`):
 *  - `repo`:       `/<repo>/...`, Repsy OS.
 *  - `owner-repo`: `/<owner>/<repo>/...` (Docker `/v2/<owner>/<repo>/<image>`), an owner-scoped
 *                  registry such as Repsy Cloud; the owner is `env.repoOwner`.
 */
export type UrlScheme = 'repo' | 'owner-repo';

const URL_SCHEMES: readonly UrlScheme[] = ['repo', 'owner-repo'];

export interface TargetCapabilities {
  /** Whether the harness started this stack and may restart or reconfigure it. */
  ownsStack: boolean;
  /** Whether AUTH_THROTTLE_* can be raised for this run, so negative-auth scenarios can run freely. */
  canTuneThrottle: boolean;
  /** Whether this is a shared instance the harness must leave exactly as it found it. */
  isRemote: boolean;
  /** How this target addresses a repository in a URL: see `UrlScheme`. Every OS target is `repo`. */
  urlScheme: UrlScheme;
}

const CAPABILITIES: Record<RepsyTarget, TargetCapabilities> = {
  local: { ownsStack: true, canTuneThrottle: true, isRemote: false, urlScheme: 'repo' },
  ci: { ownsStack: true, canTuneThrottle: true, isRemote: false, urlScheme: 'repo' },
  remote: { ownsStack: false, canTuneThrottle: false, isRemote: true, urlScheme: 'repo' },
};

export function capabilitiesFor(target: RepsyTarget): TargetCapabilities {
  return CAPABILITIES[target];
}

/**
 * `REPSY_E2E_URL_SCHEME` overrides the target's default scheme, for a stack that serves
 * `owner/repo` URLs (set `REPSY_REPO_OWNER` with it). Unset, it is the target's own: `repo`.
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
