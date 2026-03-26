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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.constants.ErrorConstants;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtUtils {

  private static final @NonNull String CLAIM_USERNAME = "username";
  private static final @NonNull String AUTH_TYPE = "authentication_type";
  private static final int SECRET_BYTE_LENGTH = 32;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  @Value("${os.app.jwt-secret:}")
  private String secret;

  @PostConstruct
  private void init() {
    if (this.secret == null || this.secret.isBlank()) {
      final var randomBytes = new byte[SECRET_BYTE_LENGTH];
      SECURE_RANDOM.nextBytes(randomBytes);
      this.secret = HexFormat.of().formatHex(randomBytes);
      log.debug("No jwt-secret configured, a random secret has been generated for this session.");
    }
  }

  public @NonNull String verifyAndExtractUsername(final @NonNull String authHeader) {
    return this.verifyAndDecode(this.getToken(authHeader)).getClaim(CLAIM_USERNAME).asString();
  }

  private @NonNull DecodedJWT verifyAndDecode(final @NonNull String token) {
    final DecodedJWT decodedJWT;

    try {
      decodedJWT = JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new AccessNotAllowedException("sessionExpired");
    } catch (final JWTVerificationException _) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return decodedJWT;
  }

  private @NonNull String getToken(final @NonNull String authHeader) {
    if (!authHeader.contains("Bearer")) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return authHeader.replaceFirst("^Bearer ", "");
  }

  public @NonNull String createTokenWithDuration(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration) {
    return JWT.create()
        .withSubject(userId.toString())
        .withClaim(CLAIM_USERNAME, username)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createTokenWithDuration(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull AuthenticationType authenticationType) {
    return JWT.create()
        .withSubject(userId.toString())
        .withClaim(CLAIM_USERNAME, username)
        .withClaim(AUTH_TYPE, authenticationType.getValue())
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull UUID extractUserId(final @NonNull String authHeader) {
    return this.getUserId(this.getToken(authHeader));
  }

  public @NonNull UUID getUserId(final @NonNull String token) {
    return UUID.fromString(this.verifyAndDecode(token).getSubject());
  }

  public void isRefreshTokenExpired(final @NonNull String token) {
    try {
      JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new AccessNotAllowedException("refreshTokenExpired");
    } catch (final JWTVerificationException _) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  public void verify(final @NonNull String authHeader) {
    this.isTokenExpired(this.getToken(authHeader));
  }

  private void isTokenExpired(final @NonNull String token) {
    try {
      JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new AccessNotAllowedException("sessionExpired");
    } catch (final JWTVerificationException _) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  public @NonNull AuthenticationType extractAuthenticationType(final @NonNull String authHeader) {
    return this.getAuthenticationType(this.getToken(authHeader));
  }

  public @NonNull AuthenticationType getAuthenticationType(final @NonNull String token) {
    final var decodedJWT = this.verifyAndDecode(token);
    final var authTypeClaim = decodedJWT.getClaim(AUTH_TYPE);

    if (authTypeClaim.isNull() || authTypeClaim.asString() == null) {
      return AuthenticationType.USERNAME_PASSWORD;
    }

    return AuthenticationType.from(authTypeClaim.asString());
  }
}
