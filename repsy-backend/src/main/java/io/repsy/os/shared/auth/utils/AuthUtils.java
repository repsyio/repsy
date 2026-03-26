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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@UtilityClass
public class AuthUtils {

  public static final String AUTH_BEARER = "Bearer ";
  public static final String AUTH_BASIC = "Basic ";
  public static final TemporalAmount TIMEOUT_ACCESS_TOKEN = Duration.of(30, ChronoUnit.MINUTES);
  public static final TemporalAmount TIMEOUT_REFRESH_TOKEN = Duration.of(60, ChronoUnit.MINUTES);

  /**
   * Extract auth credentials from the basic authorization header. This function returns null if the
   * header is not basic and is invalid.
   *
   * @param authHeader Authorization HTTP header from request
   * @return request Credentials
   */
  public static @Nullable Credentials extractCredentialsFromBasicToken(
      final @NonNull String authHeader) {

    final var credentials = new String(decodeBase64(authHeader), UTF_8).split(":", -1);

    final var username =
        switch (credentials[0]) {
          case StringUtils.EMPTY -> null;
          case final String uname -> uname;
        };

    return Credentials.builder().username(username).password(credentials[1]).build();
  }

  /** Check if the given password is correct */
  public static boolean checkPassword(
      final @NonNull String hash, final @NonNull String salt, final @NonNull String password) {

    return DigestUtils.sha256Hex(password + salt).equals(hash);
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
