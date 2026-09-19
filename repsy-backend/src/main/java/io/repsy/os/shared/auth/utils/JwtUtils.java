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
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
import io.repsy.os.shared.constants.ErrorConstants;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
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
  private static final @NonNull String CLAIM_TOKEN_FAMILY = "token_family";
  private static final @NonNull String CLAIM_PATH = "path";
  private static final @NonNull String TOKEN_TYPE_REFRESH = "refresh";
  private static final @NonNull Pattern SLASHES = Pattern.compile("/{2,}");
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

  public @NonNull String verifyAndExtractUsername(
      final @NonNull String authHeader, final @NonNull TokenRealm realm) {
    return this.verifyAndDecode(this.getToken(authHeader), realm)
        .getClaim(CLAIM_USERNAME)
        .asString();
  }

  private @NonNull DecodedJWT decode(
      final @NonNull String token, final @NonNull String expiredMessageId) {
    try {
      return JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new UnAuthorizedException(expiredMessageId);
    } catch (final JWTVerificationException _) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  private @NonNull DecodedJWT verifyAndDecode(
      final @NonNull String token, final @NonNull TokenRealm realm) {
    final var decodedJWT = this.decode(token, "sessionExpired");

    if (isRefreshToken(decodedJWT)) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    checkRealm(decodedJWT, realm);

    return decodedJWT;
  }

  private static void checkRealm(
      final @NonNull DecodedJWT decodedJWT, final @NonNull TokenRealm realm) {
    final var audience = decodedJWT.getAudience();

    if (audience == null || audience.isEmpty()) {
      acceptClaimlessToken(realm);
      return;
    }

    if (!audience.contains(realm.getAudience())) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  /**
   * Tokens issued before they carried a realm have no audience and cannot be attributed to one
   * entry point. Protocol tokens outlive a deployment by days (npm: 90), so the protocol side keeps
   * accepting them. The panel side does not: its access tokens live for minutes, and answering
   * {@code sessionExpired} makes the frontend swap the token through its refresh token once.
   */
  private static void acceptClaimlessToken(final @NonNull TokenRealm realm) {
    switch (realm) {
      case PROTOCOL -> {
        /* Accepted, see above. */
      }
      case PANEL -> throw new UnAuthorizedException("sessionExpired");
      case DOWNLOAD -> throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  private static boolean isRefreshToken(final @NonNull DecodedJWT decodedJWT) {
    return TOKEN_TYPE_REFRESH.equals(decodedJWT.getClaim(CLAIM_TOKEN_TYPE).asString());
  }

  private @NonNull String getToken(final @NonNull String authHeader) {
    if (!authHeader.contains("Bearer")) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return authHeader.replaceFirst("^Bearer ", "");
  }

  /** Creates the access token of a panel session that starts now. */
  public @NonNull String createPanelAccessToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration) {
    return this.createSessionAccessToken(userId, username, timeoutDuration, Instant.now(), 0);
  }

  public @NonNull String createPanelAccessToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final int tokenVersion) {
    return this.createSessionAccessToken(
        userId, username, timeoutDuration, Instant.now(), tokenVersion);
  }

  public @NonNull String createProtocolToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration) {
    return JWT.create()
        .withSubject(userId.toString())
        .withAudience(TokenRealm.PROTOCOL.getAudience())
        .withClaim(CLAIM_USERNAME, username)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createProtocolToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull AuthenticationType authenticationType) {
    return JWT.create()
        .withSubject(userId.toString())
        .withAudience(TokenRealm.PROTOCOL.getAudience())
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
    return this.createSessionAccessToken(userId, username, timeoutDuration, sessionStart, 0);
  }

  public @NonNull String createSessionAccessToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull Instant sessionStart,
      final int tokenVersion) {
    return JWT.create()
        .withSubject(userId.toString())
        .withAudience(TokenRealm.PANEL.getAudience())
        .withClaim(CLAIM_USERNAME, username)
        .withClaim(CLAIM_SESSION_START, sessionStart)
        .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createRefreshToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull Instant sessionStart,
      final int tokenVersion) {
    return this.createRefreshToken(
        userId,
        username,
        timeoutDuration,
        sessionStart,
        tokenVersion,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  public @NonNull String createRefreshToken(
      final @NonNull UUID userId,
      final @NonNull String username,
      final @NonNull TemporalAmount timeoutDuration,
      final @NonNull Instant sessionStart,
      final int tokenVersion,
      final @NonNull UUID tokenId,
      final @NonNull UUID familyId) {
    return JWT.create()
        .withJWTId(tokenId.toString())
        .withSubject(userId.toString())
        .withClaim(CLAIM_USERNAME, username)
        .withClaim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
        .withClaim(CLAIM_SESSION_START, sessionStart)
        .withClaim(CLAIM_TOKEN_VERSION, tokenVersion)
        .withClaim(CLAIM_TOKEN_FAMILY, familyId.toString())
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  public @NonNull String createRepoScopedToken(
      final @NonNull UUID repoId,
      final @NonNull String scope,
      final @NonNull TemporalAmount timeoutDuration) {
    return JWT.create()
        .withSubject(repoId.toString())
        .withAudience(TokenRealm.PROTOCOL.getAudience())
        .withClaim(AUTH_TYPE, AuthenticationType.DOCKER_SCAN.getValue())
        .withClaim(CLAIM_SCOPE, scope)
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  /**
   * Creates a token that authorizes reading {@code path} of the repo {@code repoId} and nothing
   * else. It carries no user, as the caller was authorized when it asked for the token.
   */
  public @NonNull String createDownloadToken(
      final @NonNull UUID repoId,
      final @NonNull String path,
      final @NonNull TemporalAmount timeoutDuration) {
    return JWT.create()
        .withSubject(repoId.toString())
        .withAudience(TokenRealm.DOWNLOAD.getAudience())
        .withClaim(CLAIM_PATH, canonicalPath(path))
        .withExpiresAt(Instant.now().plus(timeoutDuration))
        .sign(Algorithm.HMAC512(this.secret));
  }

  /**
   * Checks that {@code token} is an unexpired download token for exactly this repo and path.
   *
   * @throws UnAuthorizedException if it is not
   */
  public void verifyDownloadToken(
      final @NonNull String token, final @NonNull UUID repoId, final @NonNull String path) {
    final var decodedJWT = this.decodeDownloadToken(token);

    if (isRefreshToken(decodedJWT)) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    checkRealm(decodedJWT, TokenRealm.DOWNLOAD);

    if (!repoId.toString().equals(decodedJWT.getSubject())
        || !canonicalPath(path).equals(decodedJWT.getClaim(CLAIM_PATH).asString())) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  private @NonNull DecodedJWT decodeDownloadToken(final @NonNull String token) {
    try {
      return JWT.require(Algorithm.HMAC512(this.secret)).build().verify(token);
    } catch (final TokenExpiredException _) {
      throw new UnAuthorizedException("downloadTokenExpired");
    } catch (final JWTVerificationException _) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  /**
   * The web UI asks for a token with the path it lists ({@code /com/lib/a.jar}) and the browser
   * requests it as {@code /repo//com/lib/a.jar}, which the servlet container collapses. Both sides
   * are compared in this one form.
   */
  private static @NonNull String canonicalPath(final @NonNull String path) {
    return "/" + SLASHES.matcher(path).replaceAll("/").replaceFirst("^/", "");
  }

  public @NonNull UUID extractUserId(
      final @NonNull String authHeader, final @NonNull TokenRealm realm) {
    return this.getUserId(this.getToken(authHeader), realm);
  }

  public @NonNull UUID getUserId(final @NonNull String token, final @NonNull TokenRealm realm) {
    return subjectAsUuid(this.verifyAndDecode(token, realm));
  }

  /**
   * Returns the start of the session the access token belongs to. A token without the claim (issued
   * before sessions were bounded) starts a new session now, which is safe as such a token expires
   * within {@link AuthUtils#TIMEOUT_ACCESS_TOKEN}.
   */
  public @NonNull Instant extractSessionStart(final @NonNull String authHeader) {
    final var sessionStart =
        this.verifyAndDecode(this.getToken(authHeader), TokenRealm.PANEL)
            .getClaim(CLAIM_SESSION_START)
            .asInstant();

    return sessionStart != null ? sessionStart : Instant.now();
  }

  public int extractTokenVersion(final @NonNull String authHeader) {
    final var tokenVersion =
        this.verifyAndDecode(this.getToken(authHeader), TokenRealm.PANEL)
            .getClaim(CLAIM_TOKEN_VERSION)
            .asInt();

    return tokenVersion != null ? tokenVersion : 0;
  }

  public @NonNull RefreshTokenClaims verifyRefreshToken(final @NonNull String token) {
    final var decodedJWT = this.decode(token, "refreshTokenExpired");

    if (!isRefreshToken(decodedJWT)) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    return this.refreshTokenClaims(decodedJWT);
  }

  private @NonNull RefreshTokenClaims refreshTokenClaims(final @NonNull DecodedJWT decodedJWT) {
    final var sessionStart = decodedJWT.getClaim(CLAIM_SESSION_START).asInstant();
    final var tokenVersion = decodedJWT.getClaim(CLAIM_TOKEN_VERSION).asInt();
    final var tokenId = decodedJWT.getId();
    final var familyId = decodedJWT.getClaim(CLAIM_TOKEN_FAMILY).asString();

    // A refresh token without these claims predates refresh-token rotation and cannot be exchanged.
    if (sessionStart == null || tokenVersion == null || tokenId == null || familyId == null) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    try {
      return new RefreshTokenClaims(
          subjectAsUuid(decodedJWT),
          UUID.fromString(tokenId),
          UUID.fromString(familyId),
          sessionStart,
          tokenVersion);
    } catch (final IllegalArgumentException _) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  private static @NonNull UUID subjectAsUuid(final @NonNull DecodedJWT decodedJWT) {
    try {
      return UUID.fromString(decodedJWT.getSubject());
    } catch (final IllegalArgumentException | NullPointerException _) {
      throw new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  public void verify(final @NonNull String authHeader, final @NonNull TokenRealm realm) {
    this.verifyAndDecode(this.getToken(authHeader), realm);
  }

  public @NonNull AuthenticationType extractAuthenticationType(
      final @NonNull String authHeader, final @NonNull TokenRealm realm) {
    return this.getAuthenticationType(this.getToken(authHeader), realm);
  }

  public @NonNull AuthenticationType getAuthenticationType(
      final @NonNull String token, final @NonNull TokenRealm realm) {
    final var decodedJWT = this.verifyAndDecode(token, realm);
    final var authTypeClaim = decodedJWT.getClaim(AUTH_TYPE);

    if (authTypeClaim.isNull() || authTypeClaim.asString() == null) {
      return AuthenticationType.USERNAME_PASSWORD;
    }

    return AuthenticationType.from(authTypeClaim.asString());
  }
}
