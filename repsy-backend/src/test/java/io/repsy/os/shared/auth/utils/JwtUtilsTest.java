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
import io.repsy.os.shared.constants.ErrorConstants;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("JwtUtils")
class JwtUtilsTest {

  private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef";
  private JwtUtils jwtUtils;

  @BeforeEach
  void setUp() {
    this.jwtUtils = new JwtUtils();
    ReflectionTestUtils.setField(this.jwtUtils, "secret", TEST_SECRET);
  }

  @Test
  @DisplayName("refresh token is accepted by verifyRefreshToken and returns the user id")
  void verifyRefreshTokenAcceptsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofMinutes(30));

    final var result = this.jwtUtils.verifyRefreshToken(refreshToken);

    assertThat(result).isEqualTo(userId);
  }

  @Test
  @DisplayName("access token is rejected by verifyRefreshToken with accessNotAllowed")
  void verifyRefreshTokenRejectsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(15));

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
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(30));

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
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofMinutes(30));

    assertThatThrownBy(() -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + refreshToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by verify(bearerToken)")
  void verifyAcceptsAccessTokenBearer() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(15));

    this.jwtUtils.verify(AuthUtils.AUTH_BEARER + accessToken);
    // No exception means success
  }

  @Test
  @DisplayName("refresh token is rejected by verifyAndExtractUsername")
  void verifyAndExtractUsernameRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofMinutes(30));

    assertThatThrownBy(
            () -> this.jwtUtils.verifyAndExtractUsername(AuthUtils.AUTH_BEARER + refreshToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by verifyAndExtractUsername")
  void verifyAndExtractUsernameAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(15));

    final var result = this.jwtUtils.verifyAndExtractUsername(AuthUtils.AUTH_BEARER + accessToken);

    assertThat(result).isEqualTo(username);
  }

  @Test
  @DisplayName("refresh token is rejected by extractUserId")
  void extractUserIdRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofMinutes(30));

    assertThatThrownBy(() -> this.jwtUtils.extractUserId(AuthUtils.AUTH_BEARER + refreshToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by extractUserId")
  void extractUserIdAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(15));

    final var result = this.jwtUtils.extractUserId(AuthUtils.AUTH_BEARER + accessToken);

    assertThat(result).isEqualTo(userId);
  }

  @Test
  @DisplayName("refresh token is rejected by extractAuthenticationType")
  void extractAuthenticationTypeRejectsRefreshToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var refreshToken =
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofMinutes(30));

    assertThatThrownBy(
            () -> this.jwtUtils.extractAuthenticationType(AuthUtils.AUTH_BEARER + refreshToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token is accepted by extractAuthenticationType")
  void extractAuthenticationTypeAcceptsAccessToken() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var accessToken =
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(15));

    final var result = this.jwtUtils.extractAuthenticationType(AuthUtils.AUTH_BEARER + accessToken);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("refresh token with a non-UUID subject is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsNonUuidSubject() {
    final var token = this.signedToken("not-a-uuid", "refresh");

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("refresh token without a subject is rejected by verifyRefreshToken")
  void verifyRefreshTokenRejectsMissingSubject() {
    final var token = this.signedToken(null, "refresh");

    assertThatThrownBy(() -> this.jwtUtils.verifyRefreshToken(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token with a non-UUID subject is rejected by getUserId")
  void getUserIdRejectsNonUuidSubject() {
    final var token = this.signedToken("not-a-uuid", null);

    assertThatThrownBy(() -> this.jwtUtils.getUserId(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  @Test
  @DisplayName("access token without a subject is rejected by getUserId")
  void getUserIdRejectsMissingSubject() {
    final var token = this.signedToken(null, null);

    assertThatThrownBy(() -> this.jwtUtils.getUserId(token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }

  private String signedToken(final String subject, final String tokenType) {
    return JWT.create()
        .withSubject(subject)
        .withClaim("username", "testuser")
        .withClaim("token_type", tokenType)
        .withExpiresAt(Instant.now().plus(Duration.ofMinutes(30)))
        .sign(Algorithm.HMAC512(TEST_SECRET));
  }

  @Test
  @DisplayName("expired refresh token yields refreshTokenExpired on verifyRefreshToken")
  void expiredRefreshTokenYieldsRefreshTokenExpired() {
    final var userId = UUID.randomUUID();
    final var username = "testuser";
    final var expiredToken =
        this.jwtUtils.createRefreshToken(userId, username, Duration.ofSeconds(-1));

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
        this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofSeconds(-1));

    assertThatThrownBy(() -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + expiredToken))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining("sessionExpired");
  }

  @Test
  @DisplayName(
      "expired refresh token yields sessionExpired, not accessNotAllowed, on the access side")
  void expiredRefreshTokenOnAccessSideYieldsSessionExpired() {
    final var expiredToken =
        this.jwtUtils.createRefreshToken(UUID.randomUUID(), "testuser", Duration.ofSeconds(-1));

    assertThatThrownBy(() -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + expiredToken))
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

    assertThatThrownBy(() -> this.jwtUtils.verify(AuthUtils.AUTH_BEARER + token))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
  }
}
