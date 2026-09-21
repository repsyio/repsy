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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.server.shared.auth.BasicAuthCacheProperties;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.time.Period;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

  private final NpmAuthComponentImpl authComponent =
      new NpmAuthComponentImpl(
          this.userTxService,
          this.jwtUtils,
          this.deployTokenService,
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          new AuthFailureThrottle(AuthThrottleProperties.disabled()));

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
}
