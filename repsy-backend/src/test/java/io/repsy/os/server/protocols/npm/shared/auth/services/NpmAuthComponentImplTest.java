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
package io.repsy.os.server.protocols.npm.shared.auth.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.server.shared.auth.BasicAuthCacheProperties;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.dtos.ProtocolTokenClaims;
import io.repsy.os.shared.auth.services.RevokedProtocolTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.time.Period;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RPS-906: `npm login` must not reveal whether the username exists. RPS-1045: `npm login` with a
 * deploy token answers a deploy-token JWT, never the stored hash of the secret.
 */
@DisplayName("NpmAuthComponentImpl")
class NpmAuthComponentImplTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";

  /** BCrypt is slow on purpose, so the hash is made once for the whole class. */
  private static final String PASSWORD_HASH = PasswordHasher.hash(PASSWORD);

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);
  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
  private final DeployTokenService deployTokenService = Mockito.mock(DeployTokenService.class);
  private final RevokedProtocolTokenService revokedTokens =
      Mockito.mock(RevokedProtocolTokenService.class);

  private final NpmAuthComponentImpl authComponent =
      new NpmAuthComponentImpl(
          this.userTxService,
          this.jwtUtils,
          this.deployTokenService,
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          new AuthFailureThrottle(AuthThrottleProperties.disabled()),
          this.revokedTokens);

  private final BaseRepoInfo<UUID> repo =
      BaseRepoInfo.<UUID>builder().name("npm").storageKey(UUID.randomUUID()).build();

  private static void assertUnauthorized(final ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @BeforeEach
  void seedUser() {
    final var alice =
        UserInfo.builder()
            .id(UUID.randomUUID())
            .username(USERNAME)
            .hash(PASSWORD_HASH)
            .role(UserRole.USER)
            .build();
    when(this.userTxService.getUserByUsernameOptional(USERNAME)).thenReturn(Optional.of(alice));
  }

  @Test
  @DisplayName("authenticateRepoUser answers unAuthorized for an unknown user")
  void unknownUser() {
    assertUnauthorized(() -> this.authComponent.authenticateRepoUser(this.repo, "ghost", "x"));
    verify(this.userTxService, never()).getUserByUsername(anyString());
  }

  @Test
  @DisplayName("authenticateRepoUser answers unAuthorized for a wrong password")
  void wrongPassword() {
    assertUnauthorized(() -> this.authComponent.authenticateRepoUser(this.repo, USERNAME, "wrong"));
  }

  private DeployTokenInfo seedDeployToken(final String storedHash, final Instant expirationDate) {
    final var info = new DeployTokenInfo();
    info.setId(UUID.randomUUID());
    info.setToken(storedHash);
    info.setExpirationDate(expirationDate);
    when(this.deployTokenService.findByRepoIdAndToken(this.repo.getStorageKey(), "the-secret"))
        .thenReturn(Optional.of(info));
    return info;
  }

  @Test
  @DisplayName("authenticateRepoUser answers a deploy-token JWT for a deploy token, not its hash")
  void deployTokenLoginAnswersAJwt() {
    final var info = seedDeployToken("stored-sha-256-hash", null);
    when(this.jwtUtils.createProtocolToken(
            info.getId(), "typed", Period.ofDays(90), AuthenticationType.DEPLOY_TOKEN))
        .thenReturn("the-jwt");

    final var token = this.authComponent.authenticateRepoUser(this.repo, "typed", "the-secret");

    assertThat(token).isEqualTo("the-jwt").isNotEqualTo("stored-sha-256-hash");
    verify(this.deployTokenService).updateLastUsedTime(info.getId());
    verify(this.userTxService, never()).getUserByUsernameOptional(anyString());
  }

  @Test
  @DisplayName("authenticateRepoUser refuses an expired deploy token and mints nothing")
  void expiredDeployToken() {
    seedDeployToken("stored-sha-256-hash", Instant.now().minusSeconds(60));

    assertUnauthorized(
        () -> this.authComponent.authenticateRepoUser(this.repo, "typed", "the-secret"));
    verify(this.jwtUtils, never())
        .createProtocolToken(any(), anyString(), any(), any(AuthenticationType.class));
    verify(this.deployTokenService, never()).updateLastUsedTime(any());
  }

  // whoami: who the credentials of a request belong to.

  private static String basic(final String username, final String password) {
    return "Basic "
        + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(UTF_8));
  }

  private DeployTokenInfo whoamiToken(final String secret, final String username) {
    final var info = new DeployTokenInfo();
    info.setId(UUID.randomUUID());
    info.setUsername(username);
    when(this.deployTokenService.findByRepoIdAndToken(this.repo.getStorageKey(), secret))
        .thenReturn(Optional.of(info));
    return info;
  }

  @Test
  @DisplayName("resolveUsername answers unAuthorized for no header and for another scheme")
  void whoamiWithoutUsableHeader() {
    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, null));
    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Digest x"));
    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Basic !!!"));
  }

  @Test
  @DisplayName("resolveUsername answers the user of a Basic password")
  void whoamiBasicUser() {
    assertThat(this.authComponent.resolveUsername(this.repo, basic(USERNAME, PASSWORD)))
        .isEqualTo(USERNAME);
  }

  @Test
  @DisplayName("resolveUsername refuses a wrong Basic password")
  void whoamiBasicWrongPassword() {
    assertUnauthorized(
        () -> this.authComponent.resolveUsername(this.repo, basic(USERNAME, "wrong")));
  }

  @Test
  @DisplayName("resolveUsername answers a deploy token's own username, not the typed one")
  void whoamiBasicDeployToken() {
    whoamiToken("the-secret", "deploy-1a2b");

    assertThat(this.authComponent.resolveUsername(this.repo, basic(USERNAME, "the-secret")))
        .isEqualTo("deploy-1a2b");
    verify(this.userTxService, never()).getUserByUsernameOptional("typed");
  }

  @Test
  @DisplayName("resolveUsername refuses an expired deploy token, and one without a username")
  void whoamiDeployTokenNotUsable() {
    final var expired = whoamiToken("expired", "deploy-x");
    expired.setExpirationDate(Instant.now().minusSeconds(60));
    whoamiToken("nameless", null);

    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, basic("t", "expired")));
    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, basic("t", "nameless")));
  }

  @Test
  @DisplayName("resolveUsername answers a raw deploy token presented as a Bearer value")
  void whoamiRawDeployTokenBearer() {
    whoamiToken("raw-secret", "deploy-raw");

    assertThat(this.authComponent.resolveUsername(this.repo, "Bearer raw-secret"))
        .isEqualTo("deploy-raw");
  }

  @Test
  @DisplayName("resolveUsername answers the user of a protocol JWT")
  void whoamiUserJwt() {
    when(this.jwtUtils.extractAuthenticationType("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.USERNAME_PASSWORD);
    when(this.jwtUtils.verifyAndExtractUsername("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn(USERNAME);
    when(this.userTxService.getAuthenticatedUserByUsername(USERNAME))
        .thenReturn(UserInfo.builder().id(UUID.randomUUID()).username(USERNAME).build());

    assertThat(this.authComponent.resolveUsername(this.repo, "Bearer jwt")).isEqualTo(USERNAME);
  }

  @Test
  @DisplayName("resolveUsername answers the token's username for a deploy-token JWT")
  void whoamiDeployTokenJwt() {
    final var info = whoamiTokenById("deploy-jwt");
    when(this.jwtUtils.extractAuthenticationType("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.DEPLOY_TOKEN);
    when(this.jwtUtils.extractUserId("Bearer jwt", TokenRealm.PROTOCOL)).thenReturn(info.getId());

    assertThat(this.authComponent.resolveUsername(this.repo, "Bearer jwt")).isEqualTo("deploy-jwt");
  }

  @Test
  @DisplayName("resolveUsername refuses the JWT of a deploy token that is gone or expired")
  void whoamiDeployTokenJwtRevoked() {
    final var gone = UUID.randomUUID();
    when(this.jwtUtils.extractAuthenticationType("Bearer gone", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.DEPLOY_TOKEN);
    when(this.jwtUtils.extractUserId("Bearer gone", TokenRealm.PROTOCOL)).thenReturn(gone);
    when(this.deployTokenService.findByRepoIdAndTokenId(this.repo.getStorageKey(), gone))
        .thenReturn(Optional.empty());

    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer gone"));

    final var expired = whoamiTokenById("deploy-old");
    expired.setExpirationDate(Instant.now().minusSeconds(60));
    when(this.jwtUtils.extractAuthenticationType("Bearer old", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.DEPLOY_TOKEN);
    when(this.jwtUtils.extractUserId("Bearer old", TokenRealm.PROTOCOL))
        .thenReturn(expired.getId());

    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer old"));
  }

  @Test
  @DisplayName("resolveUsername refuses anonymous and scanner tokens, which name no user")
  void whoamiTokensWithoutAUser() {
    for (final var type : List.of(AuthenticationType.ANONYMOUS, AuthenticationType.DOCKER_SCAN)) {
      when(this.jwtUtils.extractAuthenticationType("Bearer " + type, TokenRealm.PROTOCOL))
          .thenReturn(type);

      assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer " + type));
    }
  }

  @Test
  @DisplayName("resolveUsername refuses a Bearer value that is no token, or has an unknown type")
  void whoamiJunkBearer() {
    when(this.jwtUtils.extractAuthenticationType("Bearer junk", TokenRealm.PROTOCOL))
        .thenThrow(new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED));
    when(this.jwtUtils.extractAuthenticationType("Bearer odd", TokenRealm.PROTOCOL))
        .thenThrow(new BadRequestException("invalidAuthenticationType"));

    assertThatThrownBy(() -> this.authComponent.resolveUsername(this.repo, "Bearer junk"))
        .isInstanceOf(UnAuthorizedException.class);
    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer odd"));
  }

  @Test
  @DisplayName("resolveUsername refuses the JWT of a user that no longer exists")
  void whoamiDeletedUser() {
    when(this.jwtUtils.extractAuthenticationType("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.USERNAME_PASSWORD);
    when(this.jwtUtils.verifyAndExtractUsername("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn("ghost");
    when(this.userTxService.getAuthenticatedUserByUsername("ghost"))
        .thenThrow(new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));

    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer jwt"));
  }

  private DeployTokenInfo whoamiTokenById(final String username) {
    final var info = new DeployTokenInfo();
    info.setId(UUID.randomUUID());
    info.setUsername(username);
    when(this.deployTokenService.findByRepoIdAndTokenId(this.repo.getStorageKey(), info.getId()))
        .thenReturn(Optional.of(info));
    return info;
  }

  // RPS-1361: revoking a login token (DELETE /-/user/token/<token>).

  private static final Instant EXPIRES = Instant.parse("2027-01-01T00:00:00Z");

  /** The caller presents the protocol JWT {@code Bearer caller} of a user that has {@code id}. */
  private UUID callerIsUserWithBearer(final String jwt) {
    final var id = UUID.randomUUID();
    when(this.jwtUtils.extractAuthenticationType("Bearer " + jwt, TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.USERNAME_PASSWORD);
    when(this.jwtUtils.verifyAndExtractUsername("Bearer " + jwt, TokenRealm.PROTOCOL))
        .thenReturn(USERNAME);
    when(this.userTxService.getAuthenticatedUserByUsername(USERNAME))
        .thenReturn(UserInfo.builder().id(id).username(USERNAME).build());
    return id;
  }

  private void tokenClaims(
      final String token, final UUID subject, final AuthenticationType authenticationType) {
    when(this.jwtUtils.verifyProtocolToken(token))
        .thenReturn(new ProtocolTokenClaims(subject, authenticationType, EXPIRES));
  }

  @Test
  @DisplayName("revokeToken revokes the token the caller presents, until it would expire")
  void revokeOwnBearerToken() {
    final var id = this.callerIsUserWithBearer("mine");
    tokenClaims("mine", id, AuthenticationType.USERNAME_PASSWORD);

    this.authComponent.revokeToken(this.repo, "Bearer mine", "mine");

    verify(this.revokedTokens).revoke("mine", EXPIRES);
  }

  @Test
  @DisplayName("revokeToken lets a Basic caller revoke a token issued to the same user")
  void revokeAnotherTokenOfTheSameUser() {
    final var user = this.userTxService.getUserByUsernameOptional(USERNAME).orElseThrow();
    tokenClaims("other-login", user.getId(), AuthenticationType.USERNAME_PASSWORD);

    this.authComponent.revokeToken(this.repo, basic(USERNAME, PASSWORD), "other-login");

    verify(this.revokedTokens).revoke("other-login", EXPIRES);
  }

  @Test
  @DisplayName("revokeToken refuses the token of somebody else and revokes nothing")
  void revokeSomebodyElsesToken() {
    this.callerIsUserWithBearer("mine");
    tokenClaims("theirs", UUID.randomUUID(), AuthenticationType.USERNAME_PASSWORD);

    assertThatThrownBy(() -> this.authComponent.revokeToken(this.repo, "Bearer mine", "theirs"))
        .isExactlyInstanceOf(AccessNotAllowedException.class)
        .hasMessage("loginTokenNotYours");
    verify(this.revokedTokens, never()).revoke(anyString(), any());
  }

  @Test
  @DisplayName("revokeToken refuses the secret of a deploy token, which the panel manages")
  void revokeDeployTokenSecret() {
    whoamiToken("the-secret", "deploy-1");

    assertThatThrownBy(
            () -> this.authComponent.revokeToken(this.repo, "Bearer the-secret", "the-secret"))
        .isExactlyInstanceOf(AccessNotAllowedException.class)
        .hasMessage("deployTokenNotRevocable");
    verify(this.revokedTokens, never()).revoke(anyString(), any());
    verify(this.jwtUtils, never()).verifyProtocolToken(anyString());
  }

  @Test
  @DisplayName("revokeToken lets a deploy-token JWT revoke itself, and only itself")
  void revokeDeployTokenJwt() {
    final var info = whoamiTokenById("deploy-jwt");
    when(this.jwtUtils.extractAuthenticationType("Bearer jwt", TokenRealm.PROTOCOL))
        .thenReturn(AuthenticationType.DEPLOY_TOKEN);
    when(this.jwtUtils.extractUserId("Bearer jwt", TokenRealm.PROTOCOL)).thenReturn(info.getId());
    tokenClaims("jwt", info.getId(), AuthenticationType.DEPLOY_TOKEN);
    tokenClaims("user-jwt", UUID.randomUUID(), AuthenticationType.USERNAME_PASSWORD);

    this.authComponent.revokeToken(this.repo, "Bearer jwt", "jwt");

    verify(this.revokedTokens).revoke("jwt", EXPIRES);
    assertThatThrownBy(() -> this.authComponent.revokeToken(this.repo, "Bearer jwt", "user-jwt"))
        .isInstanceOf(AccessNotAllowedException.class);
  }

  @Test
  @DisplayName("revokeToken answers tokenNotFound for a value that is no login token")
  void revokeJunk() {
    this.callerIsUserWithBearer("mine");
    when(this.jwtUtils.verifyProtocolToken("junk"))
        .thenThrow(new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED));
    when(this.jwtUtils.verifyProtocolToken("odd"))
        .thenThrow(new BadRequestException("invalidAuthenticationType"));
    tokenClaims("scanner", UUID.randomUUID(), AuthenticationType.DOCKER_SCAN);
    tokenClaims("anon", UUID.randomUUID(), AuthenticationType.ANONYMOUS);

    for (final var token : List.of("junk", "odd", "scanner", "anon")) {
      assertThatThrownBy(() -> this.authComponent.revokeToken(this.repo, "Bearer mine", token))
          .isExactlyInstanceOf(ItemNotFoundException.class)
          .hasMessage("loginTokenNotFound");
    }
    verify(this.revokedTokens, never()).revoke(anyString(), any());
  }

  @Test
  @DisplayName("revokeToken needs credentials, and revoking twice at once is not an error")
  void revokeNeedsCredentials() {
    assertUnauthorized(() -> this.authComponent.revokeToken(this.repo, null, "x"));
    assertUnauthorized(() -> this.authComponent.revokeToken(this.repo, "Digest x", "x"));

    final var id = this.callerIsUserWithBearer("mine");
    tokenClaims("mine", id, AuthenticationType.USERNAME_PASSWORD);
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .when(this.revokedTokens)
        .revoke("mine", EXPIRES);

    this.authComponent.revokeToken(this.repo, "Bearer mine", "mine");

    verify(this.revokedTokens).revoke("mine", EXPIRES);
  }

  @Test
  @DisplayName("a revoked login token identifies nobody: whoami and revokeToken refuse it")
  void revokedTokenIsRefused() {
    this.callerIsUserWithBearer("gone");
    when(this.revokedTokens.isRevoked("gone")).thenReturn(true);

    assertUnauthorized(() -> this.authComponent.resolveUsername(this.repo, "Bearer gone"));
    assertUnauthorized(() -> this.authComponent.revokeToken(this.repo, "Bearer gone", "gone"));
    verify(this.revokedTokens, never()).revoke(anyString(), any());
  }
}
