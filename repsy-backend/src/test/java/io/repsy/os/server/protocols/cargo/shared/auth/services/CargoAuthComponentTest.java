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
package io.repsy.os.server.protocols.cargo.shared.auth.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RPS-906: Basic credentials of an unknown user must be indistinguishable from a wrong password.
 */
@DisplayName("CargoAuthComponent")
class CargoAuthComponentTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";
  private static final String SALT = "salt";

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);

  private final CargoAuthComponent authComponent =
      new CargoAuthComponent(
          this.userTxService, Mockito.mock(JwtUtils.class), Mockito.mock(DeployTokenService.class));

  private static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

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
            .salt(SALT)
            .hash(DigestUtils.sha256Hex(PASSWORD + SALT))
            .role(UserRole.USER)
            .build();
    when(this.userTxService.getUserByUsernameOptional(USERNAME)).thenReturn(Optional.of(alice));
  }

  @Test
  @DisplayName(
      "authenticateAndCreateToken answers unAuthorized for an unknown user and a wrong password")
  void authenticateAndCreateToken() {
    assertUnauthorized(
        () -> this.authComponent.authenticateAndCreateToken(basicAuth("ghost", "x")));
    assertUnauthorized(
        () -> this.authComponent.authenticateAndCreateToken(basicAuth(USERNAME, "wrong")));
    verify(this.userTxService, never()).getUserByUsername(anyString());
  }

  /** RPS-962: a valid bearer token whose user no longer exists is an authentication failure. */
  @Test
  @DisplayName(
      "authenticateAndCreateToken answers unAuthorized for a bearer token whose user is gone")
  void authenticateAndCreateTokenUserNoLongerExists() {
    final var jwtUtils = Mockito.mock(JwtUtils.class);
    when(jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class))).thenReturn("ghost");
    // A real UserTxService over an empty repository: the lookup itself is under test.
    final var component =
        new CargoAuthComponent(
            new UserTxService(
                Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
            jwtUtils,
            Mockito.mock(DeployTokenService.class));

    assertUnauthorized(() -> component.authenticateAndCreateToken("Bearer signed.jwt.token"));
  }

  /**
   * RPS-979: the JWT a deploy token gets at {@code /me} carries whatever username the client typed,
   * so it must not be exchanged for a token of the user of that name.
   */
  @Test
  @DisplayName("authenticateAndCreateToken refuses a deploy-token bearer JWT")
  void authenticateAndCreateTokenRefusesDeployTokenJwt() {
    final var jwtUtils = Mockito.mock(JwtUtils.class);
    when(jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.DEPLOY_TOKEN);
    when(jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
        .thenReturn(USERNAME);
    final var component =
        new CargoAuthComponent(
            this.userTxService, jwtUtils, Mockito.mock(DeployTokenService.class));

    assertUnauthorized(() -> component.authenticateAndCreateToken("Bearer signed.jwt.token"));
    verify(this.userTxService, never()).getAuthenticatedUserByUsername(anyString());
    verify(jwtUtils, never())
        .createProtocolToken(any(UUID.class), anyString(), any(TemporalAmount.class));
  }
}
