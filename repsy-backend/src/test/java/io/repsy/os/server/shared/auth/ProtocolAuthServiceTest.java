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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ProtocolAuthService")
class ProtocolAuthServiceTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";
  private static final String SALT = "salt";

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);

  private final ProtocolAuthService authService =
      new ProtocolAuthService(
          this.userTxService, Mockito.mock(JwtUtils.class), Mockito.mock(DeployTokenService.class));

  private static final UserInfo ALICE =
      UserInfo.builder()
          .id(UUID.randomUUID())
          .username(USERNAME)
          .salt(SALT)
          .hash(DigestUtils.sha256Hex(PASSWORD + SALT))
          .role(UserRole.USER)
          .build();

  private static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

  private static Credentials credentials(final String username, final String password) {
    return Credentials.builder().username(username).password(password).build();
  }

  private static void assertUnauthorized(final ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @BeforeEach
  void seedUser() {
    when(this.userTxService.getUserByUsernameOptional(USERNAME)).thenReturn(Optional.of(ALICE));
  }

  @Test
  @DisplayName("authenticateUser rejects a missing Authorization header with unAuthorized")
  void authenticateUserRejectsNullHeader() {
    assertThatThrownBy(() -> this.authService.authenticateUser(null))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("authenticateUser rejects an unsupported Authorization scheme with unAuthorized")
  void authenticateUserRejectsUnknownScheme() {
    assertThatThrownBy(() -> this.authService.authenticateUser("Digest abc"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }

  /** RPS-906: an unknown username must be indistinguishable from a wrong password. */
  @Nested
  @DisplayName("username/password authentication does not reveal which usernames exist")
  class UsernameEnumeration {

    @Test
    @DisplayName("authenticateWithPassword returns the user for valid credentials")
    void validCredentials() {
      assertThat(
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials(USERNAME, PASSWORD)))
          .isSameAs(ALICE);
    }

    @Test
    @DisplayName("authenticateWithPassword answers unAuthorized for an unknown username")
    void unknownUsername() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials("ghost", PASSWORD)));
    }

    @Test
    @DisplayName("authenticateWithPassword answers unAuthorized for a wrong password")
    void wrongPassword() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials(USERNAME, "wrong")));
    }

    @Test
    @DisplayName("authenticateWithPassword answers unAuthorized for a missing username or password")
    void missingUsernameOrPassword() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials(null, PASSWORD)));
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials(USERNAME, null)));
    }

    @Test
    @DisplayName("authenticateUser answers unAuthorized for Basic credentials of an unknown user")
    void authenticateUserUnknownUsername() {
      assertUnauthorized(
          () -> ProtocolAuthServiceTest.this.authService.authenticateUser(basicAuth("ghost", "x")));
    }

    @Test
    @DisplayName(
        "authenticateUser answers unAuthorized for Basic credentials with a wrong password")
    void authenticateUserWrongPassword() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateUser(
                  basicAuth(USERNAME, "wrong")));
    }

    @Test
    @DisplayName("authenticateUser returns the user for valid Basic credentials")
    void authenticateUserValidCredentials() {
      assertThat(
              ProtocolAuthServiceTest.this.authService.authenticateUser(
                  basicAuth(USERNAME, PASSWORD)))
          .isSameAs(ALICE);
    }

    @Test
    @DisplayName("handleBasicAuth answers unAuthorized for an unknown user and a wrong password")
    void handleBasicAuth() {
      final var repoId = UUID.randomUUID();

      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.handleBasicAuth(
                  basicAuth("ghost", "x"), Permission.READ, repoId));
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.handleBasicAuth(
                  basicAuth(USERNAME, "wrong"), Permission.READ, repoId));
    }

    @Test
    @DisplayName("never uses the lookup that throws userNotFound")
    void neverThrowsUserNotFound() {
      assertUnauthorized(
          () -> ProtocolAuthServiceTest.this.authService.authenticateUser(basicAuth("ghost", "x")));

      verify(ProtocolAuthServiceTest.this.userTxService, never()).getUserByUsername(anyString());
    }
  }
}
