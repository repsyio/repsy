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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
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
import java.time.Instant;
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
          this.userTxService,
          Mockito.mock(JwtUtils.class),
          Mockito.mock(DeployTokenService.class),
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

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

  /** RPS-961: a legacy SHA-256 hash is replaced by BCrypt once its owner authenticates. */
  @Nested
  @DisplayName("password hash upgrade")
  class HashUpgrade {

    private final UserInfo bcryptUser =
        UserInfo.builder()
            .id(UUID.randomUUID())
            .username("carol")
            .salt(SALT)
            .hash(PasswordHasher.hash(PASSWORD))
            .role(UserRole.USER)
            .build();

    @Test
    @DisplayName("authenticateWithPassword upgrades a legacy hash after a correct password")
    void upgradesLegacyHash() {
      ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
          credentials(USERNAME, PASSWORD));

      verify(ProtocolAuthServiceTest.this.userTxService).upgradePasswordHash(ALICE, PASSWORD);
    }

    @Test
    @DisplayName("authenticateWithPassword leaves a current BCrypt hash alone")
    void keepsCurrentHash() {
      when(ProtocolAuthServiceTest.this.userTxService.getUserByUsernameOptional("carol"))
          .thenReturn(Optional.of(this.bcryptUser));

      final var user =
          ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
              credentials("carol", PASSWORD));

      assertThat(user).isSameAs(this.bcryptUser);
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .upgradePasswordHash(any(), anyString());
    }

    @Test
    @DisplayName("authenticateWithPassword does not upgrade after a wrong password")
    void doesNotUpgradeOnWrongPassword() {
      assertUnauthorized(
          () ->
              ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
                  credentials(USERNAME, "wrong")));

      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .upgradePasswordHash(any(), anyString());
    }

    @Test
    @DisplayName("an unknown username still pays for one hash check, like a wrong password")
    void unknownUsernameCostsAHashCheck() {
      final var wrongPassword = averageNanos(USERNAME);
      final var unknownUser = averageNanos("ghost");

      // A generous bound: the point is the order of magnitude. Without the dummy check the unknown
      // username returns in microseconds while a BCrypt check takes tens of milliseconds.
      assertThat(unknownUser).isGreaterThan(wrongPassword / 4);
    }

    private long averageNanos(final String username) {
      final var rounds = 3;
      final var start = System.nanoTime();

      for (var i = 0; i < rounds; i++) {
        try {
          ProtocolAuthServiceTest.this.authService.authenticateWithPassword(
              credentials(username, "wrong"));
        } catch (final UnAuthorizedException _) {
          // The outcome is asserted elsewhere; only the elapsed time matters here.
        }
      }

      return (System.nanoTime() - start) / rounds;
    }
  }

  /**
   * RPS-1025: HTTP Basic sends the password on every request, so a successful check is remembered.
   * Everything the request depends on besides the password is still read on every request.
   */
  @Nested
  @DisplayName("remembered password checks")
  class RememberedChecks {

    private final UserTxService users = Mockito.mock(UserTxService.class);
    private final VerifiedPasswordCache cache =
        new VerifiedPasswordCache(new BasicAuthCacheProperties(true, 300, 100));
    private final ProtocolAuthService service =
        new ProtocolAuthService(
            this.users,
            Mockito.mock(JwtUtils.class),
            Mockito.mock(DeployTokenService.class),
            this.cache);

    private final UserInfo carol =
        UserInfo.builder()
            .id(UUID.randomUUID())
            .username("carol")
            .salt(SALT)
            .hash(PasswordHasher.hash(PASSWORD))
            .role(UserRole.USER)
            .build();

    @BeforeEach
    void seedCarol() {
      when(this.users.getUserByUsernameOptional("carol")).thenReturn(Optional.of(this.carol));
    }

    @Test
    @DisplayName("the second request with the same Basic credentials skips the hash check")
    void secondRequestHits() {
      assertThat(this.service.authenticateUser(basicAuth("carol", PASSWORD))).isSameAs(this.carol);
      assertThat(this.service.authenticateUser(basicAuth("carol", PASSWORD))).isSameAs(this.carol);

      assertThat(this.cache.hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong password stays unAuthorized after the right one was remembered")
    void wrongPasswordStillFails() {
      this.service.authenticateUser(basicAuth("carol", PASSWORD));

      assertUnauthorized(() -> this.service.authenticateUser(basicAuth("carol", "wrong")));
      assertThat(this.cache.hitCount()).isZero();
    }

    @Test
    @DisplayName("a changed password is unAuthorized for the old one at once")
    void changedPasswordRejectsOldOne() {
      this.service.authenticateUser(basicAuth("carol", PASSWORD));

      final var changed =
          UserInfo.builder()
              .id(this.carol.getId())
              .username("carol")
              .salt(SALT)
              .hash(PasswordHasher.hash("new-s3cret"))
              .role(UserRole.USER)
              .build();
      when(this.users.getUserByUsernameOptional("carol")).thenReturn(Optional.of(changed));

      assertUnauthorized(() -> this.service.authenticateUser(basicAuth("carol", PASSWORD)));
      assertThat(this.service.authenticateUser(basicAuth("carol", "new-s3cret"))).isSameAs(changed);
    }

    @Test
    @DisplayName("a deleted user is unAuthorized at once")
    void deletedUserIsRejected() {
      this.service.authenticateUser(basicAuth("carol", PASSWORD));

      when(this.users.getUserByUsernameOptional("carol")).thenReturn(Optional.empty());

      assertUnauthorized(() -> this.service.authenticateUser(basicAuth("carol", PASSWORD)));
    }

    @Test
    @DisplayName("a changed role applies to a remembered check")
    void roleIsReadOnEveryRequest() {
      this.service.authenticateUser(basicAuth("carol", PASSWORD));

      final var promoted =
          UserInfo.builder()
              .id(this.carol.getId())
              .username("carol")
              .salt(SALT)
              .hash(this.carol.getHash())
              .role(UserRole.ADMIN)
              .build();
      when(this.users.getUserByUsernameOptional("carol")).thenReturn(Optional.of(promoted));

      assertThat(this.service.authenticateUser(basicAuth("carol", PASSWORD)).getRole())
          .isEqualTo(UserRole.ADMIN);
      assertThat(this.cache.hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown username is not remembered and stays unAuthorized")
    void unknownUsernameIsNotRemembered() {
      assertUnauthorized(() -> this.service.authenticateUser(basicAuth("ghost", PASSWORD)));
      assertUnauthorized(() -> this.service.authenticateUser(basicAuth("ghost", PASSWORD)));

      assertThat(this.cache.hitCount()).isZero();
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
            Mockito.mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

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
   * RPS-979: a JWT minted from a deploy token carries the username the client put in the Basic
   * credentials, so it has to be authorized as the deploy token and never resolved to the user of
   * that name.
   */
  @Nested
  @DisplayName("a deploy-token bearer JWT is authorized as the deploy token, not as a user")
  class DeployTokenJwt {

    private static final String BEARER = "Bearer signed.jwt.token";

    private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
    private final DeployTokenService deployTokenService = Mockito.mock(DeployTokenService.class);
    private final UUID repoId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    private final ProtocolAuthService jwtAuthService =
        new ProtocolAuthService(
            ProtocolAuthServiceTest.this.userTxService,
            this.jwtUtils,
            this.deployTokenService,
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

    DeployTokenJwt() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.DEPLOY_TOKEN);
      when(this.jwtUtils.extractUserId(anyString(), any(TokenRealm.class)))
          .thenReturn(this.tokenId);
      // The claim names a real user; it must not be looked at.
      when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
          .thenReturn(USERNAME);
    }

    private void storeToken(final boolean readOnly, final Instant expirationDate) {
      final var info = new DeployTokenInfo();
      info.setId(this.tokenId);
      info.setReadOnly(readOnly);
      info.setExpirationDate(expirationDate);
      when(this.deployTokenService.findByRepoIdAndTokenId(this.repoId, this.tokenId))
          .thenReturn(Optional.of(info));
    }

    @Test
    @DisplayName("a read-write token is authorized for its repo without looking up the user")
    void authorizesTheDeployToken() {
      this.storeToken(false, null);

      this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ);
      this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.WRITE);

      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getAuthenticatedUserByUsername(anyString());
      verify(this.deployTokenService, times(2)).updateLastUsedTime(this.tokenId);
    }

    @Test
    @DisplayName("a read-only token can read but not write")
    void readOnlyTokenCannotWrite() {
      this.storeToken(true, null);

      this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ);
      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.WRITE));
    }

    @Test
    @DisplayName("a token of another repo is unAuthorized, however the username claim reads")
    void tokenOfAnotherRepo() {
      this.storeToken(false, null);

      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, UUID.randomUUID(), Permission.READ));
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredToken() {
      this.storeToken(false, Instant.now().minusSeconds(60));

      assertThatThrownBy(
              () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ))
          .isExactlyInstanceOf(UnAuthorizedException.class)
          .hasMessage("deployTokenExpired");
    }

    @Test
    @DisplayName("a token that was revoked is refused")
    void revokedToken() {
      when(this.deployTokenService.findByRepoIdAndTokenId(this.repoId, this.tokenId))
          .thenReturn(Optional.empty());

      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ));
    }

    @Test
    @DisplayName("a scanner token is refused outside Docker")
    void scannerTokenIsRefused() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.DOCKER_SCAN);

      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ));
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getAuthenticatedUserByUsername(anyString());
    }

    /** RPS-986: the username of an anonymous token is a label, never a user to look up. */
    @Test
    @DisplayName("an anonymous token is refused, whoever its username claim names")
    void anonymousTokenIsRefused() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.ANONYMOUS);

      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.READ));
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getAuthenticatedUserByUsername(anyString());
      verify(ProtocolAuthServiceTest.this.userTxService, never())
          .getUserByUsernameOptional(anyString());
    }

    @Test
    @DisplayName("a user token still resolves the user, and MANAGE still needs an admin")
    void userTokenIsUnchanged() {
      when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
          .thenReturn(AuthenticationType.USERNAME_PASSWORD);
      when(ProtocolAuthServiceTest.this.userTxService.getAuthenticatedUserByUsername(USERNAME))
          .thenReturn(ALICE);

      this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.WRITE);
      assertUnauthorized(
          () -> this.jwtAuthService.handleBearerAuth(BEARER, this.repoId, Permission.MANAGE));
      verify(this.deployTokenService, never()).findByRepoIdAndTokenId(any(), any());
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

  /** RPS-980: the web UI downloads a Maven file with a token that opens that one path for reads. */
  @Nested
  @DisplayName("a download token authorizes reads only")
  class DownloadToken {

    private static final String TOKEN = "signed.jwt.token";
    private static final String PATH = "/com/example/lib.jar";

    private final UUID repoId = UUID.randomUUID();
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final ProtocolAuthService downloadAuthService =
        new ProtocolAuthService(
            ProtocolAuthServiceTest.this.userTxService,
            this.jwtUtils,
            mock(DeployTokenService.class),
            new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()));

    @Test
    @DisplayName("a read is checked against the repo and path of the token")
    void readIsVerifiedAgainstRepoAndPath() {
      this.downloadAuthService.handleDownloadToken(TOKEN, this.repoId, PATH, Permission.READ);

      verify(this.jwtUtils).verifyDownloadToken(TOKEN, this.repoId, PATH);
    }

    @Test
    @DisplayName("a token that fails verification fails the request")
    void failedVerificationFailsTheRequest() {
      doThrow(new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED))
          .when(this.jwtUtils)
          .verifyDownloadToken(TOKEN, this.repoId, PATH);

      assertThatThrownBy(
              () ->
                  this.downloadAuthService.handleDownloadToken(
                      TOKEN, this.repoId, PATH, Permission.READ))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessageContaining(ErrorConstants.ACCESS_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a write or manage request is refused without looking at the token")
    void writeAndManageAreRefused() {
      for (final var permission : new Permission[] {Permission.WRITE, Permission.MANAGE}) {
        assertUnauthorized(
            () ->
                this.downloadAuthService.handleDownloadToken(TOKEN, this.repoId, PATH, permission));
      }

      verify(this.jwtUtils, never()).verifyDownloadToken(anyString(), any(), anyString());
    }
  }
}
