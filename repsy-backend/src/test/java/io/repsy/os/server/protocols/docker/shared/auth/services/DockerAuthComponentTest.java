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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("DockerAuthComponent")
class DockerAuthComponentTest {

  private static final String USERNAME = "alice";
  private static final String PASSWORD = "s3cret";

  /** BCrypt is slow on purpose, so the hash is made once for the whole class. */
  private static final String PASSWORD_HASH = PasswordHasher.hash(PASSWORD);

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);

  private final DockerAuthComponent authComponent =
      new DockerAuthComponent(
          this.userTxService,
          Mockito.mock(JwtUtils.class),
          Mockito.mock(DeployTokenService.class),
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          new AuthFailureThrottle(AuthThrottleProperties.disabled()));

  private static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

  private static BaseRepoInfo<UUID> repo(final boolean privateRepo) {
    return BaseRepoInfo.<UUID>builder()
        .name("images")
        .storageKey(UUID.randomUUID())
        .privateRepo(privateRepo)
        .build();
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
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

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
              .hash(PASSWORD_HASH)
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
    @DisplayName(
        "authenticateUserDockerCli answers unAuthorized for Basic credentials with no password")
    void dockerLoginWithoutPassword() {
      final var component = DockerAuthComponentTest.this.authComponent;
      final var noSeparator =
          "Basic " + Base64.getEncoder().encodeToString("ghost".getBytes(StandardCharsets.UTF_8));

      assertUnauthorized(() -> component.authenticateUserDockerCli(noSeparator));
      assertUnauthorized(() -> component.authenticateUserDockerCli(basicAuth("", "x")));
      assertUnauthorized(() -> component.authenticateUserDockerCli("Bearer signed.jwt.token"));
      verify(DockerAuthComponentTest.this.userTxService, never()).getUserByUsername(anyString());
      verify(DockerAuthComponentTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }
  }

  /**
   * What {@code /v2/token} asks before it hands an anonymous token to a caller without credentials.
   * A private repo must not get one, and is answered as if it did not exist.
   */
  @Test
  @DisplayName("authorizePublicRead lets a caller without credentials read a public repo")
  void authorizePublicReadAllowsPublicRepo() {
    assertThatCode(() -> this.authComponent.authorizePublicRead(repo(false)))
        .doesNotThrowAnyException();
    verify(this.userTxService, never()).getUserByUsernameOptional(anyString());
  }

  @Test
  @DisplayName("authorizePublicRead answers repoNotFound for a private repo")
  void authorizePublicReadRefusesPrivateRepo() {
    assertThatThrownBy(() -> this.authComponent.authorizePublicRead(repo(true)))
        .isExactlyInstanceOf(ItemNotFoundException.class)
        .hasMessage("repoNotFound");
  }

  /**
   * RPS-1027: a valid bearer token of a user who no longer exists is an authentication failure, on
   * a read and on a write. It is never downgraded to an anonymous caller, which is what the other
   * protocols answer as well (RPS-962). A public read skips authentication in {@code
   * DockerAuthPreProcessor}, so this path only sees a write or a private repo.
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
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

    DeletedUserToken() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.USERNAME_PASSWORD);
      when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
          .thenReturn("ghost");
      when(DockerAuthComponentTest.this.userTxService.getUserByUsernameOptional("ghost"))
          .thenReturn(Optional.empty());
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
    @DisplayName("handleBearerAuth still lets the token of an existing user through")
    void existingUserStillAuthorized() {
      when(DockerAuthComponentTest.this.userTxService.getAuthenticatedUserByUsername("ghost"))
          .thenReturn(
              UserInfo.builder()
                  .id(UUID.randomUUID())
                  .username("ghost")
                  .role(UserRole.USER)
                  .build());

      assertThatCode(
              () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ))
          .doesNotThrowAnyException();
      assertThatCode(
              () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.WRITE))
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
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

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
  }

  /**
   * RPS-1171: Docker's clients exchange Basic credentials for a JWT at {@code /v2/token} first, so
   * {@code /v2} must never accept a raw deploy-token secret directly as the Bearer value the way
   * every other protocol accepts it (pinned end-to-end by {@code QueryTokenRefusedIT}).
   */
  @Nested
  @DisplayName("a raw deploy-token secret is never accepted as the bearer value")
  class RawDeployTokenBearer {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
    private final DeployTokenService deployTokenService = Mockito.mock(DeployTokenService.class);

    private final DockerAuthComponent component =
        new DockerAuthComponent(
            DockerAuthComponentTest.this.userTxService,
            this.jwtUtils,
            this.deployTokenService,
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

    @Test
    @DisplayName("handleBearerAuth never looks the value up as a deploy token")
    void neverTriesTheRawSecretAsADeployToken() {
      final var repoId = UUID.randomUUID();
      final var info = new DeployTokenInfo();
      info.setId(UUID.randomUUID());
      // Stubbed to succeed if it were ever tried, so this pins that the lookup is skipped
      // entirely, not merely that a not-found lookup happens to end in a refusal.
      when(this.deployTokenService.findByRepoIdAndToken(repoId, "signed.jwt.token"))
          .thenReturn(Optional.of(info));
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenThrow(new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED));

      assertThatThrownBy(() -> this.component.handleBearerAuth(BEARER, repoId, Permission.READ))
          .isInstanceOf(UnAuthorizedException.class);
      verify(this.deployTokenService, never()).findByRepoIdAndToken(any(), anyString());
    }
  }

  /**
   * RPS-1171: {@code DOCKER_SCAN} is the one bearer type the shared base class refuses by default,
   * because only Docker's vulnerability scanner is issued it.
   */
  @Nested
  @DisplayName("a scanner token authorizes a read of its own repo only")
  class ScannerToken {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

    private final DockerAuthComponent component =
        new DockerAuthComponent(
            DockerAuthComponentTest.this.userTxService,
            this.jwtUtils,
            Mockito.mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

    ScannerToken() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.DOCKER_SCAN);
    }

    @Test
    @DisplayName("authorizes a read of the repo it is scoped to")
    void authorizesReadOfItsOwnRepo() {
      final var repoId = UUID.randomUUID();
      when(this.jwtUtils.extractUserId(BEARER, TokenRealm.PROTOCOL)).thenReturn(repoId);

      assertThatCode(() -> this.component.handleBearerAuth(BEARER, repoId, Permission.READ))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a write")
    void refusesWrite() {
      final var repoId = UUID.randomUUID();
      when(this.jwtUtils.extractUserId(BEARER, TokenRealm.PROTOCOL)).thenReturn(repoId);

      assertUnauthorized(() -> this.component.handleBearerAuth(BEARER, repoId, Permission.WRITE));
    }

    @Test
    @DisplayName("refuses a read of a different repo")
    void refusesAnotherRepo() {
      when(this.jwtUtils.extractUserId(BEARER, TokenRealm.PROTOCOL)).thenReturn(UUID.randomUUID());

      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
    }
  }

  /**
   * RPS-1216: {@code DELETE /v2/<name>/manifests/<reference>} needs MANAGE. A deploy token (the JWT
   * {@code /v2/token} mints for one) reads and writes, so it must not manage, read-only or not.
   */
  @Nested
  @DisplayName("a deploy-token JWT never authorizes MANAGE")
  class DeployTokenManage {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
    private final DeployTokenService deployTokenService = Mockito.mock(DeployTokenService.class);

    private final DockerAuthComponent component =
        new DockerAuthComponent(
            DockerAuthComponentTest.this.userTxService,
            this.jwtUtils,
            this.deployTokenService,
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
            new AuthFailureThrottle(AuthThrottleProperties.disabled()));

    private final UUID repoId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    DeployTokenManage() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.DEPLOY_TOKEN);
      when(this.jwtUtils.extractUserId(BEARER, TokenRealm.PROTOCOL)).thenReturn(this.tokenId);
    }

    private void stubToken(final boolean readOnly) {
      final var info = new DeployTokenInfo();
      info.setId(this.tokenId);
      info.setReadOnly(readOnly);
      when(this.deployTokenService.findByRepoIdAndTokenId(this.repoId, this.tokenId))
          .thenReturn(Optional.of(info));
    }

    @Test
    @DisplayName("a read-write deploy token is refused MANAGE without being looked up")
    void readWriteTokenIsRefused() {
      this.stubToken(false);

      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, this.repoId, Permission.MANAGE));
      verify(this.deployTokenService, never()).updateLastUsedTime(any());
    }

    @Test
    @DisplayName("a read-only deploy token is refused MANAGE")
    void readOnlyTokenIsRefused() {
      this.stubToken(true);

      assertUnauthorized(
          () -> this.component.handleBearerAuth(BEARER, this.repoId, Permission.MANAGE));
    }

    @Test
    @DisplayName("a read-write deploy token still reads and writes")
    void readWriteTokenStillReadsAndWrites() {
      this.stubToken(false);

      assertThatCode(() -> this.component.handleBearerAuth(BEARER, this.repoId, Permission.READ))
          .doesNotThrowAnyException();
      assertThatCode(() -> this.component.handleBearerAuth(BEARER, this.repoId, Permission.WRITE))
          .doesNotThrowAnyException();
    }
  }
}
