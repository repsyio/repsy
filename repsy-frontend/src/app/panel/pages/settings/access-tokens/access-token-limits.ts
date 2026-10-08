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

import { AccessTokenScope } from '../../../../../generated/api';

/** The most tokens a user can hold that have not expired (the backend answers accessTokenLimitReached). */
export const MAX_LIVE_ACCESS_TOKENS = 50;

/** The scopes `/cli/auth` selects when its link names none that the page knows. */
export const DEFAULT_CLI_SCOPES: readonly AccessTokenScope[] = ['repo:read', 'repo:write', 'repo:manage'];

/** The longest token name the backend accepts (the `AccessTokenForm` schema). */
export const MAX_ACCESS_TOKEN_NAME_LENGTH = 80;

export function isExpired(token: { expirationDate: string }, now: number = Date.now()): boolean {
  return new Date(token.expirationDate).getTime() <= now;
}

export function countLive(tokens: readonly { expirationDate: string }[], now: number = Date.now()): number {
  return tokens.filter((t) => !isExpired(t, now)).length;
}
