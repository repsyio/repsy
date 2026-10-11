/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.shared.constants;

import lombok.NoArgsConstructor;

/**
 * The error codes only the panel and the backend emit. The codes the protocol libraries and the
 * shared backend code emit are in {@code ProtocolErrorCodes}. The strings are the wire contract;
 * see {@code ErrorConstantsTest}.
 */
@NoArgsConstructor
public final class ErrorConstants {

  public static final String ACCESS_TOKEN_EXPIRATION_IN_PAST = "accessTokenExpirationInPast";
  public static final String ACCESS_TOKEN_EXPIRATION_TOO_LATE = "accessTokenExpirationTooLate";
  public static final String ACCESS_TOKEN_LIMIT_REACHED = "accessTokenLimitReached";
  public static final String ACCESS_TOKEN_NOT_FOUND = "accessTokenNotFound";
  public static final String CANNOT_DELETE_LAST_ADMIN_USER = "cannotDeleteLastAdminUser";
  public static final String CANNOT_DEMOTE_LAST_ADMIN_USER = "cannotDemoteLastAdminUser";
  public static final String DOWNLOAD_TOKEN_EXPIRED = "downloadTokenExpired";
  public static final String INVALID_AUTH_TYPE = "invalidAuthType";
  public static final String INVALID_CREDENTIALS = "invalidCredentials";
  public static final String NOT_AN_ACCESS_TOKEN = "notAnAccessToken";
  public static final String PGP_SETTINGS_UNSUPPORTED = "pgpSettingsUnsupported";
  public static final String REFRESH_TOKEN_EXPIRED = "refreshTokenExpired";
  public static final String RELEASES_SNAPSHOTS_UNSUPPORTED = "releasesSnapshotsUnsupported";
  public static final String REPO_EXISTS = "repoExists";
  public static final String REPO_NAME_RESERVED = "repoNameReserved";
  public static final String SESSION_EXPIRED = "sessionExpired";
  public static final String USERNAME_IN_USE = "usernameInUse";
  public static final String USER_NOT_FOUND = "userNotFound";
}
