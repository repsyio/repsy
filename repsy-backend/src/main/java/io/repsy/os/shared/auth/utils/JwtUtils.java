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
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
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
  private static final @NonNull String CLAIM_SCOPE = "scope";
  private static final @NonNull String CLAIM_TOKEN_TYPE = "token_type";
  private static final @NonNull String CLAIM_SESSION_START = "session_start";
  private static final @NonNull String CLAIM_TOKEN_VERSION = "token_version";
  private static final @NonNull String TOKEN_TYPE_REFRESH = "refresh";
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

  private @NonNull DecodedJWT decode(
      final @NonNull String token, final @NonNull String expiredMessageId) {
    try {
      return JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new AccessNotAllowedException(expiredMessageId);
    } catch (final JWTVerificationException _) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  private @NonNull DecodedJWT verifyAndDecode(final @NonNull String token) {
    final var decodedJWT = this.decode(token, "sessionExpired");

    if (isRefreshToken(decodedJWT)) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return decodedJWT;
  }

  private static boolean isRefreshToken(final @NonNull DecodedJWT decodedJWT) {
    return TOKEN_TYPE_REFRESH.equals(decodedJWT.getClaim(CLAIM_TOKEN_TYPE).asString());
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

  /**
   * Creates the access token of a panel session. It carries the session start, so that a fresh
   * token pair issued from it (e.g. after a username change) stays within the same session.
   */
  public @NonNull String createSessionAccessToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull Instant sessionStart) {
    return JWT.create()
        .withSubject(userId.toString())
        .withClaim(CLAIM_USERNAME, username)
        .withClaim(CLAIM_SESSION_START, sessionStart)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createRefreshToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull Instant sessionStart,
      final int tokenVersion) {
    return JWT.create()
        .withSubject(userId.toString())
        .withClaim(CLAIM_USERNAME, username)
        .withClaim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
        .withClaim(CLAIM_SESSION_START, sessionStart)
        .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createRepoScopedToken(
      final @NonNull UUID repoId,
      final @NonNull String scope,
      final @NonNull TemporalAmount timeoutDuration) {
    return JWT.create()
        .withSubject(repoId.toString())
        .withClaim(AUTH_TYPE, AuthenticationType.DOCKER_SCAN.getValue())
        .withClaim(CLAIM_SCOPE, scope)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull UUID extractUserId(final @NonNull String authHeader) {
    return this.getUserId(this.getToken(authHeader));
  }

  public @NonNull UUID getUserId(final @NonNull String token) {
    return subjectAsUuid(this.verifyAndDecode(token));
  }

  /**
   * Returns the start of the session the access token belongs to. A token without the claim (issued
   * before sessions were bounded) starts a new session now, which is safe as such a token expires
   * within {@link AuthUtils#TIMEOUT_ACCESS_TOKEN}.
   */
  public @NonNull Instant extractSessionStart(final @NonNull String authHeader) {
    final var sessionStart =
        this.verifyAndDecode(this.getToken(authHeader)).getClaim(CLAIM_SESSION_START).asInstant();

    return sessionStart != null ? sessionStart : Instant.now();
  }

  public @NonNull RefreshTokenClaims verifyRefreshToken(final @NonNull String token) {
    final var decodedJWT = this.decode(token, "refreshTokenExpired");

    if (!isRefreshToken(decodedJWT)) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    final var sessionStart = decodedJWT.getClaim(CLAIM_SESSION_START).asInstant();
    final var tokenVersion = decodedJWT.getClaim(CLAIM_TOKEN_VERSION).asInt();

    // A refresh token without these claims predates bounded sessions and cannot be exchanged.
    if (sessionStart == null || tokenVersion == null) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return new RefreshTokenClaims(subjectAsUuid(decodedJWT), sessionStart, tokenVersion);
  }

  private static @NonNull UUID subjectAsUuid(final @NonNull DecodedJWT decodedJWT) {
    try {
      return UUID.fromString(decodedJWT.getSubject());
    } catch (final IllegalArgumentException | NullPointerException _) {
      throw new AccessNotAllowedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  public void verify(final @NonNull String authHeader) {
    this.verifyAndDecode(this.getToken(authHeader));
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
