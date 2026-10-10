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
 * The stable `code` (Java `msgId`) an API error answers with. Each value is the spelling of the matching Java
 * error constant and is a client contract: never change a value here.
 */
export const ERROR_CODES = {
  ACCESS_DENIED: 'accessDenied',
  ACCESS_NOT_ALLOWED: 'accessNotAllowed',
  INVALID_CREDENTIALS: 'invalidCredentials',
  LOGIN_REQUIRED: 'loginRequired',
  REFRESH_TOKEN_EXPIRED: 'refreshTokenExpired',
  RESOURCE_BUSY: 'resourceBusy',
  SESSION_EXPIRED: 'sessionExpired',
} as const;
