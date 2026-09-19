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
package io.repsy.os.shared.auth.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.codec.binary.Base64.decodeBase64;

import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@UtilityClass
public class AuthUtils {

  public static final String AUTH_BEARER = "Bearer ";
  public static final String AUTH_BASIC = "Basic ";
  public static final Duration TIMEOUT_ACCESS_TOKEN = Duration.of(30, ChronoUnit.MINUTES);
  public static final Duration TIMEOUT_REFRESH_TOKEN = Duration.of(60, ChronoUnit.MINUTES);

  /** Absolute lifetime of a panel session, however often its refresh token is exchanged. */
  public static final Duration TIMEOUT_SESSION = Duration.of(24, ChronoUnit.HOURS);

  /**
   * Caps a token lifetime so the token cannot outlive the session it belongs to.
   *
   * @param timeout the token's regular lifetime
   * @param sessionStart when the session's login happened
   * @return {@code timeout}, or the time left until the session ends if that is shorter
   */
  public static @NonNull Duration boundBySession(
      final @NonNull Duration timeout, final @NonNull Instant sessionStart) {

    final var untilSessionEnd = Duration.between(Instant.now(), sessionStart.plus(TIMEOUT_SESSION));

    return untilSessionEnd.compareTo(timeout) < 0 ? untilSessionEnd : timeout;
  }

  /**
   * Extract auth credentials from the basic authorization header. This function returns null if the
   * decoded token has no {@code username:password} separator. The decoder skips characters outside
   * the base64 alphabet, so a token that is not base64 at all decodes to an empty value and is
   * treated the same way.
   *
   * @param authHeader Authorization HTTP header from request
   * @return request Credentials, or null if the token is invalid
   */
  public static @Nullable Credentials extractCredentialsFromBasicToken(
      final @NonNull String authHeader) {

    final var credentials = new String(decodeBase64(authHeader), UTF_8).split(":", -1);

    if (credentials.length < 2) {
      return null;
    }

    final var username =
        switch (credentials[0]) {
          case StringUtils.EMPTY -> null;
          case final String uname -> uname;
        };

    return Credentials.builder().username(username).password(credentials[1]).build();
  }

  public static @NonNull String removeBasicPrefix(final @NonNull String authHeader) {

    return authHeader.substring(AUTH_BASIC.length());
  }

  public static @NonNull String removeBearerHeader(final @NonNull String authHeader) {

    return authHeader.substring(AUTH_BEARER.length());
  }

  public static boolean isBearerToken(final @NonNull String authHeader) {

    return authHeader.startsWith(AUTH_BEARER);
  }

  public static boolean isBasicToken(final @NonNull String authHeader) {

    return authHeader.startsWith(AUTH_BASIC);
  }

  public static @Nullable Credentials extractCredentialsFromAuthHeader(
      final @NonNull String authHeader) {

    final var basicToken = removeBasicPrefix(authHeader);

    return extractCredentialsFromBasicToken(basicToken);
  }
}
