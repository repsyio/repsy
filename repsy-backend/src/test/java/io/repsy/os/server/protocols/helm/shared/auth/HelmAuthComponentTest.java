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
package io.repsy.os.server.protocols.helm.shared.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
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
import io.repsy.os.shared.auth.dtos.ProtocolUserClaims;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RPS-1040: Helm answers a bearer token with the behaviour of {@code ProtocolAuthService}, like
 * every other protocol, so these pin what a Helm request gets from it.
 */
@DisplayName("HelmAuthComponent")
class HelmAuthComponentTest {

  private static final String BEARER = "Bearer signed.jwt.token";

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);
  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
  private final DeployTokenService deployTokenService = Mockito.mock(DeployTokenService.class);

  private final HelmAuthComponent authComponent =
      new HelmAuthComponent(
          this.userTxService,
          this.jwtUtils,
          this.deployTokenService,
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          new AuthFailureThrottle(AuthThrottleProperties.disabled()));

  private static void assertUnauthorized(final ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  /** RPS-986: an anonymous token is minted by Docker; its username claim is only a label. */
  @Test
  @DisplayName("handleBearerAuth refuses an anonymous token without looking up its username")
  void anonymousTokenIsRefused() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.ANONYMOUS);

    assertUnauthorized(
        () -> this.authComponent.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
    verify(this.userTxService, never()).getUserByUsernameOptional(anyString());
    verify(this.userTxService, never()).getAuthenticatedUserByUsername(anyString());
  }

  @Test
  @DisplayName("handleBearerAuth refuses a scanner token, which only Docker knows how to authorize")
  void scannerTokenIsRefused() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.DOCKER_SCAN);

    assertUnauthorized(
        () -> this.authComponent.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
    verify(this.userTxService, never()).getAuthenticatedUserByUsername(anyString());
  }

  /** RPS-962: a valid token whose user no longer exists is an authentication failure. */
  @Test
  @DisplayName("handleBearerAuth answers unAuthorized for a user token whose user is gone")
  void userNoLongerExists() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.USERNAME_PASSWORD);
    when(this.jwtUtils.extractProtocolUserClaims(anyString()))
        .thenReturn(new ProtocolUserClaims("ghost", null));
    // A real UserTxService over an empty repository: the lookup itself is under test.
    final var component =
        new HelmAuthComponent(
            new UserTxService(
                Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
            this.jwtUtils,
            this.deployTokenService,
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

    assertUnauthorized(
        () -> component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
  }

  @Test
  @DisplayName("handleBearerAuth authorizes a deploy token sent as the bearer value")
  void deployTokenAsBearerValue() {
    final var repoId = UUID.randomUUID();
    final var info = new DeployTokenInfo();
    info.setId(UUID.randomUUID());
    when(this.deployTokenService.findByRepoIdAndToken(repoId, "signed.jwt.token"))
        .thenReturn(Optional.of(info));

    assertThatCode(() -> this.authComponent.handleBearerAuth(BEARER, repoId, Permission.READ))
        .doesNotThrowAnyException();
    verify(this.deployTokenService).updateLastUsedTime(info.getId());
    verify(this.userTxService, never()).getAuthenticatedUserByUsername(anyString());
  }
}
