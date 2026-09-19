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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.constants.ErrorConstants;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("JwtUtils")
class JwtUtilsTest {

  private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef";
  private static final Instant SESSION_START = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final int TOKEN_VERSION = 3;
  private JwtUtils jwtUtils;

  @BeforeEach
  void setUp() {
    this.jwtUtils = new JwtUtils();
    ReflectionTestUtils.setField(this.jwtUtils, "secret", TEST_SECRET);
  }

  @Test
  @DisplayName("refresh token is accepted by verifyRefreshToken and returns its claims")
  void verifyRefreshTokenAcceptsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    final var result = this.jwtUtils.verifyRefreshToken(refreshToken);

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.sessionStart()).isEqualTo(SESSION_START);
    assertThat(result.tokenVersion()).isEqualTo(TOKEN_VERSION);
    assertThat(result.tokenId()).isEqualTo(UUID.fromString(JWT.decode(refreshToken).getId()));
    assertThat(result.familyId())
        .isEqualTo(UUID.fromString(JWT.decode(refreshToken).getClaim("token_family").asString()));
  }

  @Test
  @DisplayName("access token is rejected by verifyRefreshToken with accessNotAllowed")
  void verifyRefreshTokenRejectsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(15));

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(accessToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("claim-less legacy token is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsClaimlessLegacyToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var legacyToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(30));

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(legacyToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("refresh token is rejected by verify(bearerToken) with accessNotAllowed")
  void verifyRejectsRefreshTokenBearer() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + refreshToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by verify(bearerToken)")
  void verifyAcceptsAccessTokenBearer() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(15));

    this.jwtUtils.verify(AuthUtils.AUTH_BEARER + accessToken, TokenRealm.PANEL);
    // No exception means success
  }

  @Test
  @DisplayName("refresh token is rejected by verifyAndExtractUsername")
  void verifyAndExtractUsernameRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () ->
                this.jwtUtils.verifyAndExtractUsername(
                    AuthUtils.AUTH_BEARER + refreshToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by verifyAndExtractUsername")
  void verifyAndExtractUsernameAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(15));

    final var result =
        this.jwtUtils.verifyAndExtractUsername(
            AuthUtils.AUTH_BEARER + accessToken, TokenRealm.PANEL);

    assertThat(result).isEqualTo(username);
  }

  @Test
  @DisplayName("refresh token is rejected by extractUserId")
  void extractUserIdRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () ->
                this.jwtUtils.extractUserId(AuthUtils.AUTH_BEARER + refreshToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by extractUserId")
  void extractUserIdAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(15));

    final var result =
        this.jwtUtils.extractUserId(AuthUtils.AUTH_BEARER + accessToken, TokenRealm.PANEL);

    assertThat(result).isEqualTo(userId);
  }

  @Test
  @DisplayName("refresh token is rejected by extractAuthenticationType")
  void extractAuthenticationTypeRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () ->
                this.jwtUtils.extractAuthenticationType(
                    AuthUtils.AUTH_BEARER + refreshToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by extractAuthenticationType")
  void extractAuthenticationTypeAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(15));

    final var result =
        this.jwtUtils.extractAuthenticationType(
            AuthUtils.AUTH_BEARER + accessToken, TokenRealm.PANEL);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("refresh token with a non-UUID subject is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsNonUuidSubject() {
    final var token = this.signedToken("not-a-uuid", "refresh", null);

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("refresh token without a subject is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsMissingSubject() {
    final var token = this.signedToken(null, "refresh", null);

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token with a non-UUID subject is rejected by getUserId")
  void getUserIdRejectsNonUuidSubject() {
    final var token = this.signedToken("not-a-uuid", null, "panel");

    assertThatThrownBy(() -> this.jwtUtils.getUserId(token, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token without a subject is rejected by getUserId")
  void getUserIdRejectsMissingSubject() {
    final var token = this.signedToken(null, null, "panel");

    assertThatThrownBy(() -> this.jwtUtils.getUserId(token, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  private String signedToken(final String subject, final String tokenType, final String audience) {
    return JWT.create()
        .withSubject(subject)
        .withAudience(audience == null ? new String[0] : new String[] {audience})
        .withClaim("username", "testuser")
        .withClaim("token_type", tokenType)
        .withClaim("session_start", SESSION_START)
        .withClaim("token_version", TOKEN_VERSION)
        .withExpiresAt(Instant.now().plus(Duration.ofMinutes(30)))
        .sign(Algorithm.HMAC512(TEST_SECRET));
  }

  @Test
  @DisplayName("expired refresh token yields refreshTokenExpired on verifyRefreshToken")
  void expiredRefreshTokenYieldsRefreshTokenExpired() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var expiredToken =
        this.jwtUtils.createRefreshToken(
            userId, username, Duration.ofSeconds(-1), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(expiredToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("refreshTokenExpired");
  }

  @Test
  @DisplayName("expired access token yields sessionExpired on verify(bearerToken)")
  void expiredAccessTokenYieldsSessionExpired() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var expiredToken =
        this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofSeconds(-1));

    assertThatThrownBy(
            () -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + expiredToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
  }

  @Test
  @DisplayName(
      "expired refresh token yields sessionExpired, not accessNotAllowed, on the access side")
  void expiredRefreshTokenOnAccessSideYieldsSessionExpired() {
    final var expiredToken =
        this.jwtUtils.createRefreshToken(
            UUID.randomUUID(), "testuser", Duration.ofSeconds(-1), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + expiredToken, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
  }

  @Test
  @DisplayName("token signed with different secret is rejected by verifyRefreshToken")
  void differentSecretRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var otherSecret = "othersecretothersecretothersecr";
    final var token =
        JWT.create()
            .withSubject(userId.toString())
            .withClaim("username", username)
            .withClaim("token_type", "refresh")
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(30)))
            .sign(Algorithm.HMAC512(otherSecret));

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("token signed with different secret is rejected by verify(bearerToken)")
  void differentSecretRejectsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var otherSecret = "othersecretothersecretothersecr";
    final var token =
        JWT.create()
            .withSubject(userId.toString())
            .withClaim("username", username)
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(15)))
            .sign(Algorithm.HMAC512(otherSecret));

    assertThatThrownBy(() -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + token, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("refresh token without a session_start claim is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsMissingSessionStart() {
    final var token =
        JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("token_type", "refresh")
            .withClaim("token_version", TOKEN_VERSION)
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(30)))
            .sign(Algorithm.HMAC512(TEST_SECRET));

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("refresh token without a token_version claim is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsMissingTokenVersion() {
    final var token =
        JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("token_type", "refresh")
            .withClaim("session_start", SESSION_START)
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(30)))
            .sign(Algorithm.HMAC512(TEST_SECRET));

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("session access token carries its session start, readable by extractSessionStart")
  void extractSessionStartReturnsTheSessionStart() {
    final var accessToken =
        this.jwtUtils.createSessionAccessToken(
            UUID.randomUUID(), "testuser", Duration.ofMinutes(15), SESSION_START);

    final var result = this.jwtUtils.extractSessionStart(AuthUtils.AUTH_BEARER + accessToken);

    assertThat(result).isEqualTo(SESSION_START);
  }

  @Test
  @DisplayName("access token without a session start begins a new session in extractSessionStart")
  void extractSessionStartFallsBackToNow() {
    final var accessToken =
        JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withAudience("panel")
            .withClaim("username", "testuser")
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(15)))
            .sign(Algorithm.HMAC512(TEST_SECRET));

    final var before = Instant.now().minusSeconds(1);
    final var result = this.jwtUtils.extractSessionStart(AuthUtils.AUTH_BEARER + accessToken);

    assertThat(result).isBetween(before, Instant.now().plusSeconds(1));
  }

  @Test
  @DisplayName("refresh token is rejected by extractSessionStart")
  void extractSessionStartRejectsRefreshToken() {
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            UUID.randomUUID(), "testuser", Duration.ofMinutes(30), SESSION_START, TOKEN_VERSION);

    assertThatThrownBy(
            () -> this.jwtUtils.extractSessionStart(AuthUtils.AUTH_BEARER + refreshToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("panel access token is accepted on the panel side and rejected on the protocol side")
  void panelAccessTokenIsScopedToThePanel() {
    final var userId = UUID.randomUUID();
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createPanelAccessToken(userId, "testuser", Duration.ofMinutes(15));

    assertThat(this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PANEL))
        .isEqualTo("testuser");
    assertThat(this.jwtUtils.extractUserId(header, TokenRealm.PANEL)).isEqualTo(userId);
    assertThatThrownBy(() -> this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PROTOCOL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    assertThatThrownBy(() -> this.jwtUtils.verify(header, TokenRealm.PROTOCOL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("session access token is scoped to the panel, including its session start")
  void sessionAccessTokenIsScopedToThePanel() {
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createSessionAccessToken(
                UUID.randomUUID(), "testuser", Duration.ofMinutes(15), SESSION_START);

    assertThat(this.jwtUtils.extractSessionStart(header)).isEqualTo(SESSION_START);
    assertThatThrownBy(() -> this.jwtUtils.verify(header, TokenRealm.PROTOCOL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("protocol token is accepted on the protocol side and rejected on the panel side")
  void protocolTokenIsScopedToProtocolEndpoints() {
    final var userId = UUID.randomUUID();
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createProtocolToken(userId, "testuser", Duration.ofMinutes(15));

    assertThat(this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PROTOCOL))
        .isEqualTo("testuser");
    assertThat(this.jwtUtils.extractUserId(header, TokenRealm.PROTOCOL)).isEqualTo(userId);
    assertThatThrownBy(() -> this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    assertThatThrownBy(() -> this.jwtUtils.extractUserId(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    assertThatThrownBy(() -> this.jwtUtils.extractSessionStart(header))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("protocol token carrying an authentication type is scoped to the protocol side")
  void authenticatedProtocolTokenIsScopedToProtocolEndpoints() {
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createProtocolToken(
                UUID.randomUUID(),
                "testuser",
                Duration.ofMinutes(15),
                AuthenticationType.DEPLOY_TOKEN);

    assertThat(this.jwtUtils.extractAuthenticationType(header, TokenRealm.PROTOCOL))
        .isEqualTo(AuthenticationType.DEPLOY_TOKEN);
    assertThatThrownBy(() -> this.jwtUtils.extractAuthenticationType(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("repo scoped scanner token is scoped to the protocol side")
  void repoScopedTokenIsScopedToProtocolEndpoints() {
    final var repoId = UUID.randomUUID();
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createRepoScopedToken(repoId, "repo:pull", Duration.ofMinutes(15));

    assertThat(this.jwtUtils.extractUserId(header, TokenRealm.PROTOCOL)).isEqualTo(repoId);
    assertThatThrownBy(() -> this.jwtUtils.extractUserId(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("claim-less legacy token is still accepted on the protocol side")
  void claimlessLegacyTokenIsAcceptedOnTheProtocolSide() {
    final var userId = UUID.randomUUID();
    final var header = AuthUtils.AUTH_BEARER + this.signedToken(userId.toString(), null, null);

    assertThat(this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PROTOCOL))
        .isEqualTo("testuser");
    assertThat(this.jwtUtils.extractUserId(header, TokenRealm.PROTOCOL)).isEqualTo(userId);
  }

  @Test
  @DisplayName("claim-less legacy token is answered sessionExpired on the panel side")
  void claimlessLegacyTokenIsRejectedOnThePanelSide() {
    final var header =
        AuthUtils.AUTH_BEARER + this.signedToken(UUID.randomUUID().toString(), null, null);

    assertThatThrownBy(() -> this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
    assertThatThrownBy(() -> this.jwtUtils.verify(header, TokenRealm.PANEL))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
    assertThatThrownBy(() -> this.jwtUtils.extractSessionStart(header))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
  }

  @Test
  @DisplayName("refresh token is rejected on both sides")
  void refreshTokenIsRejectedOnBothSides() {
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createRefreshToken(
                UUID.randomUUID(),
                "testuser",
                Duration.ofMinutes(30),
                SESSION_START,
                TOKEN_VERSION);

    for (final var realm : TokenRealm.values()) {
      assertThatThrownBy(() -> this.jwtUtils.verify(header, realm))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  @Test
  @DisplayName("token for an unknown audience is rejected on both sides")
  void unknownAudienceIsRejectedOnBothSides() {
    final var header =
        AuthUtils.AUTH_BEARER + this.signedToken(UUID.randomUUID().toString(), null, "other");

    for (final var realm : TokenRealm.values()) {
      assertThatThrownBy(() -> this.jwtUtils.verify(header, realm))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  @Test
  @DisplayName("download token is accepted for the repo and path it was issued for")
  void downloadTokenIsAcceptedForItsRepoAndPath() {
    final var repoId = UUID.randomUUID();
    final var token =
        this.jwtUtils.createDownloadToken(repoId, "/com/example/lib.jar", Duration.ofMinutes(1));

    this.jwtUtils.verifyDownloadToken(token, repoId, "/com/example/lib.jar");
  }

  @Test
  @DisplayName("download token treats a doubled or missing leading slash as the same path")
  void downloadTokenPathIsCanonical() {
    final var repoId = UUID.randomUUID();
    final var token =
        this.jwtUtils.createDownloadToken(repoId, "/com/example/lib.jar", Duration.ofMinutes(1));

    this.jwtUtils.verifyDownloadToken(token, repoId, "//com//example/lib.jar");
    this.jwtUtils.verifyDownloadToken(token, repoId, "com/example/lib.jar");
  }

  @Test
  @DisplayName("download token is rejected for another repo")
  void downloadTokenIsRejectedForAnotherRepo() {
    final var token =
        this.jwtUtils.createDownloadToken(UUID.randomUUID(), "/lib.jar", Duration.ofMinutes(1));
    final var otherRepoId = UUID.randomUUID();

    assertThatThrownBy(() -> this.jwtUtils.verifyDownloadToken(token, otherRepoId, "/lib.jar"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("download token is rejected for another path, including a sibling and a parent")
  void downloadTokenIsRejectedForAnotherPath() {
    final var repoId = UUID.randomUUID();
    final var token =
        this.jwtUtils.createDownloadToken(repoId, "/com/lib/a.jar", Duration.ofMinutes(1));

    for (final var other : new String[] {"/com/lib/b.jar", "/com/lib", "/com/lib/a.jar/x", ""}) {
      assertThatThrownBy(() -> this.jwtUtils.verifyDownloadToken(token, repoId, other))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  @Test
  @DisplayName("expired download token yields downloadTokenExpired")
  void expiredDownloadTokenYieldsDownloadTokenExpired() {
    final var repoId = UUID.randomUUID();
    final var token = this.jwtUtils.createDownloadToken(repoId, "/lib.jar", Duration.ofSeconds(-1));

    assertThatThrownBy(() -> this.jwtUtils.verifyDownloadToken(token, repoId, "/lib.jar"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("downloadTokenExpired");
  }

  @Test
  @DisplayName("download token signed with a different secret is rejected")
  void downloadTokenWithForeignSignatureIsRejected() {
    final var repoId = UUID.randomUUID();
    final var token =
        JWT.create()
            .withSubject(repoId.toString())
            .withAudience(TokenRealm.DOWNLOAD.getAudience())
            .withClaim("path", "/lib.jar")
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(1)))
            .sign(Algorithm.HMAC512("another-secret-another-secret-00000"));

    assertThatThrownBy(() -> this.jwtUtils.verifyDownloadToken(token, repoId, "/lib.jar"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("panel, protocol, refresh and claim-less tokens are not download tokens")
  void otherTokensAreNotDownloadTokens() {
    final var repoId = UUID.randomUUID();
    final var tokens =
        new String[] {
          this.jwtUtils.createPanelAccessToken(repoId, "testuser", Duration.ofMinutes(1)),
          this.jwtUtils.createProtocolToken(repoId, "testuser", Duration.ofMinutes(1)),
          this.jwtUtils.createRepoScopedToken(repoId, "repo:pull", Duration.ofMinutes(1)),
          this.jwtUtils.createRefreshToken(
              repoId, "testuser", Duration.ofMinutes(1), SESSION_START, TOKEN_VERSION),
          this.signedToken(repoId.toString(), null, null)
        };

    for (final var token : tokens) {
      assertThatThrownBy(() -> this.jwtUtils.verifyDownloadToken(token, repoId, "/lib.jar"))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }

  @Test
  @DisplayName("download token is rejected on the panel and protocol sides")
  void downloadTokenIsRejectedOnEveryBearerSide() {
    final var header =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createDownloadToken(
                UUID.randomUUID(), "/lib.jar", Duration.ofMinutes(1));

    for (final var realm : new TokenRealm[] {TokenRealm.PANEL, TokenRealm.PROTOCOL}) {
      assertThatThrownBy(() -> this.jwtUtils.verify(header, realm))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }
  }
}
