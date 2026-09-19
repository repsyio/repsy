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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
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

  /**
   * RPS-962: a correctly signed token whose user no longer exists is an authentication failure, so
   * the client re-authenticates, instead of a 404 that reads as a missing resource.
   */
  @Nested
  @DisplayName("a valid bearer token whose user no longer exists is unAuthorized")
  class TokenUserNoLongerExists {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

    // A real UserTxService over an empty repository: the lookup itself is under test.
    private final ProtocolAuthService ghostAuthService =
        new ProtocolAuthService(
            new UserTxService(
                Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
            this.jwtUtils,
            Mockito.mock(DeployTokenService.class));

    TokenUserNoLongerExists() {
      when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
          .thenReturn("ghost");
    }

    @Test
    @DisplayName("authenticateUser answers unAuthorized for a panel bearer token")
    void authenticateUser() {
      assertUnauthorized(() -> this.ghostAuthService.authenticateUser(BEARER));
    }

    @Test
    @DisplayName("handleBearerAuth answers unAuthorized for a protocol bearer token")
    void handleBearerAuth() {
      assertUnauthorized(
          () -> this.ghostAuthService.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
    }
  }

  /**
   * RPS-939: repos have no owner or access list, so "private" means "login required". Any signed-in
   * user can read and write every repo; only MANAGE needs the ADMIN role. These tests pin that
   * model, which the README documents, so a change to it has to be deliberate.
   */
  @Nested
  @DisplayName("a private repo is open to every signed-in user")
  class PrivateMeansLoginRequired {

    private final UserInfo admin =
        UserInfo.builder()
            .id(UUID.randomUUID())
            .username("root")
            .salt(SALT)
            .hash(DigestUtils.sha256Hex(PASSWORD + SALT))
            .role(UserRole.ADMIN)
            .build();

    private final RepoInfo privateRepo =
        RepoInfo.builder()
            .id(UUID.randomUUID())
            .name("secret")
            .privateRepo(true)
            .type(RepoType.MAVEN)
            .build();

    @Test
    @DisplayName("a non-admin user can read and write, but not manage")
    void userCanReadAndWriteButNotManage() {
      final var authService = ProtocolAuthServiceTest.this.authService;

      final var permissionInfo = authService.authorizeUser(ALICE, Permission.WRITE);

      assertThat(authService.authorizeUser(ALICE, Permission.READ).isCanRead()).isTrue();
      assertThat(permissionInfo.isCanRead()).isTrue();
      assertThat(permissionInfo.isCanWrite()).isTrue();
      assertThat(permissionInfo.isCanManage()).isFalse();
      assertUnauthorized(() -> authService.authorizeUser(ALICE, Permission.MANAGE));
    }

    @Test
    @DisplayName("an admin can also manage")
    void adminCanManage() {
      final var permissionInfo =
          ProtocolAuthServiceTest.this.authService.authorizeUser(this.admin, Permission.MANAGE);

      assertThat(permissionInfo.isCanRead()).isTrue();
      assertThat(permissionInfo.isCanWrite()).isTrue();
      assertThat(permissionInfo.isCanManage()).isTrue();
    }

    @Test
    @DisplayName("a non-admin user with valid credentials gets read and write on a private repo")
    void userReachesPrivateRepo() {
      final var authService = ProtocolAuthServiceTest.this.authService;
      final var authHeader = basicAuth(USERNAME, PASSWORD);

      final var read =
          authService.authorizeUserRequest(this.privateRepo, authHeader, Permission.READ);
      final var write =
          authService.authorizeUserRequest(this.privateRepo, authHeader, Permission.WRITE);

      assertThat(read.getCanRead()).isTrue();
      assertThat(write.getCanRead()).isTrue();
      assertThat(write.getCanWrite()).isTrue();
      assertThat(write.getCanManage()).isFalse();
      assertUnauthorized(
          () -> authService.authorizeUserRequest(this.privateRepo, authHeader, Permission.MANAGE));
    }

    @Test
    @DisplayName("an anonymous caller is still turned away from a private repo")
    void anonymousCannotReachPrivateRepo() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authorizeUserRequest(
                  this.privateRepo, null, Permission.READ));
    }
  }
}
