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
package io.repsy.os.server.protocols.docker.shared.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.BasicAuthCacheProperties;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
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
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("DockerAuthComponent")
class DockerAuthComponentTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";
  private static final String SALT = "salt";

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);

  private final DockerAuthComponent authComponent =
      new DockerAuthComponent(
          this.userTxService,
          Mockito.mock(JwtUtils.class),
          Mockito.mock(DeployTokenService.class),
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

  private static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

  private static void assertUnauthorized(final ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("authenticateUser rejects a missing Authorization header with unAuthorized")
  void authenticateUserRejectsNullHeader() {
    assertThatThrownBy(() -> this.authComponent.authenticateUser(null))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("authenticateUser rejects a non-bearer Authorization header with unAuthorized")
  void authenticateUserRejectsNonBearerHeader() {
    assertThatThrownBy(() -> this.authComponent.authenticateUser("Basic abc"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }

  /** RPS-962: a valid bearer token whose user no longer exists is an authentication failure. */
  @Test
  @DisplayName("authenticateUser answers unAuthorized for a bearer token whose user is gone")
  void authenticateUserTokenUserNoLongerExists() {
    final var jwtUtils = Mockito.mock(JwtUtils.class);
    when(jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class))).thenReturn("ghost");
    // A real UserTxService over an empty repository: the lookup itself is under test.
    final var component =
        new DockerAuthComponent(
            new UserTxService(
                Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
            jwtUtils,
            Mockito.mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

    assertUnauthorized(() -> component.authenticateUser("Bearer signed.jwt.token"));
  }

  /**
   * RPS-906: Basic credentials of an unknown user must be indistinguishable from a wrong password.
   */
  @Nested
  @DisplayName("username/password authentication does not reveal which usernames exist")
  class UsernameEnumeration {

    UsernameEnumeration() {
      final var alice =
          UserInfo.builder()
              .id(UUID.randomUUID())
              .username(USERNAME)
              .salt(SALT)
              .hash(DigestUtils.sha256Hex(PASSWORD + SALT))
              .role(UserRole.USER)
              .build();
      when(DockerAuthComponentTest.this.userTxService.getUserByUsernameOptional(USERNAME))
          .thenReturn(Optional.of(alice));
    }

    @Test
    @DisplayName(
        "authenticateUserDockerCli answers unAuthorized for an unknown user and a wrong password")
    void dockerLogin() {
      final var component = DockerAuthComponentTest.this.authComponent;

      assertUnauthorized(() -> component.authenticateUserDockerCli(basicAuth("ghost", "x")));
      assertUnauthorized(() -> component.authenticateUserDockerCli(basicAuth(USERNAME, "wrong")));
      verify(DockerAuthComponentTest.this.userTxService, never()).getUserByUsername(anyString());
    }

    @Test
    @DisplayName("authorizeRequest answers unAuthorized for an unknown user and a wrong password")
    void authorizeRequest() {
      final var component = DockerAuthComponentTest.this.authComponent;
      final var repo =
          BaseRepoInfo.<UUID>builder()
              .name("images")
              .storageKey(UUID.randomUUID())
              .privateRepo(true)
              .build();

      assertUnauthorized(
          () -> component.authorizeRequest(repo, basicAuth("ghost", "x"), Permission.READ, true));
      assertUnauthorized(
          () ->
              component.authorizeRequest(
                  repo, basicAuth(USERNAME, "wrong"), Permission.READ, true));
      verify(DockerAuthComponentTest.this.userTxService, never()).getUserByUsername(anyString());
    }
  }

  /**
   * RPS-1027: a valid bearer token of a user who no longer exists is an authentication failure on
   * every path, a public repo included. It is never downgraded to an anonymous caller, which is
   * what the other protocols answer as well (RPS-962).
   */
  @Nested
  @DisplayName("a valid bearer token of a deleted user is never treated as anonymous")
  class DeletedUserToken {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

    private final DockerAuthComponent component =
        new DockerAuthComponent(
            DockerAuthComponentTest.this.userTxService,
            this.jwtUtils,
            Mockito.mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

    DeletedUserToken() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.USERNAME_PASSWORD);
      when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
          .thenReturn("ghost");
      when(DockerAuthComponentTest.this.userTxService.getUserByUsernameOptional("ghost"))
          .thenReturn(Optional.empty());
    }

    private BaseRepoInfo<UUID> repo(final boolean privateRepo) {
      return BaseRepoInfo.<UUID>builder()
          .name("images")
          .storageKey(UUID.randomUUID())
          .privateRepo(privateRepo)
          .build();
    }

    @Test
    @DisplayName("authorizeRequest answers unAuthorized for a public repo, read or write")
    void authorizeRequestPublicRepo() {
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(false), BEARER, Permission.READ, false));
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(false), BEARER, Permission.WRITE, false));
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(false), BEARER, Permission.READ, true));
    }

    @Test
    @DisplayName("authorizeRequest answers unAuthorized for a private repo")
    void authorizeRequestPrivateRepo() {
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(true), BEARER, Permission.READ, false));
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(true), BEARER, Permission.WRITE, false));
    }

    @Test
    @DisplayName("handleBearerAuth answers unAuthorized")
    void handleBearerAuth() {
      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.WRITE));
    }

    @Test
    @DisplayName("authorizeRequest still lets the token of an existing user read a public repo")
    void existingUserStillAuthorized() {
      when(DockerAuthComponentTest.this.userTxService.getUserByUsernameOptional("ghost"))
          .thenReturn(
              Optional.of(
                  UserInfo.builder()
                      .id(UUID.randomUUID())
                      .username("ghost")
                      .role(UserRole.USER)
                      .build()));

      assertThatCode(
              () ->
                  this.component.authorizeRequest(this.repo(false), BEARER, Permission.READ, false))
          .doesNotThrowAnyException();
    }
  }

  /**
   * RPS-986: the token handed to a caller without credentials is labelled {@code anonymous}. It has
   * to be typed, so a user who happens to be named {@code anonymous} is never acted for by it.
   */
  @Nested
  @DisplayName("an anonymous token is never resolved to the user its username claim names")
  class AnonymousToken {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

    private final DockerAuthComponent component =
        new DockerAuthComponent(
            DockerAuthComponentTest.this.userTxService,
            this.jwtUtils,
            Mockito.mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

    AnonymousToken() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.ANONYMOUS);
      // The claim names a real admin; it must not be looked at.
      when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
          .thenReturn("anonymous");
      when(DockerAuthComponentTest.this.userTxService.getUserByUsernameOptional("anonymous"))
          .thenReturn(
              Optional.of(
                  UserInfo.builder()
                      .id(UUID.randomUUID())
                      .username("anonymous")
                      .role(UserRole.ADMIN)
                      .build()));
    }

    private BaseRepoInfo<UUID> repo(final boolean privateRepo) {
      return BaseRepoInfo.<UUID>builder()
          .name("images")
          .storageKey(UUID.randomUUID())
          .privateRepo(privateRepo)
          .build();
    }

    @Test
    @DisplayName("createAnonymousUser mints a token typed as anonymous")
    void createAnonymousUserIsTyped() {
      when(this.jwtUtils.createProtocolToken(
              any(UUID.class),
              anyString(),
              any(TemporalAmount.class),
              any(AuthenticationType.class)))
          .thenReturn("typed.jwt.token");

      assertThat(this.component.createAnonymousUser()).isEqualTo("typed.jwt.token");
      verify(this.jwtUtils)
          .createProtocolToken(
              any(UUID.class),
              Mockito.eq("anonymous"),
              any(TemporalAmount.class),
              Mockito.eq(AuthenticationType.ANONYMOUS));
    }

    @Test
    @DisplayName("handleBearerAuth refuses it, as a write or a private read is all that reaches it")
    void handleBearerAuthRefuses() {
      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.WRITE));
      verify(DockerAuthComponentTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }

    @Test
    @DisplayName("authorizeRequest lets it read a public repo")
    void readsPublicRepo() {
      assertThatCode(
              () ->
                  this.component.authorizeRequest(this.repo(false), BEARER, Permission.READ, false))
          .doesNotThrowAnyException();
      verify(DockerAuthComponentTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }

    @Test
    @DisplayName("authorizeRequest refuses it a write, and any access to a private repo")
    void refusedBeyondPublicRead() {
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(false), BEARER, Permission.WRITE, false));
      assertUnauthorized(
          () -> this.component.authorizeRequest(this.repo(true), BEARER, Permission.READ, false));
      verify(DockerAuthComponentTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }
  }
}
