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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.auth.AuthFailureThrottle;
import io.repsy.protocols.shared.auth.BasicAuthCacheProperties;
import io.repsy.protocols.shared.auth.VerifiedPasswordCache;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RPS-1903: the personal access token checks of {@link ProtocolAuthService}, with every
 * collaborator a mock, so what is checked is which collaborator a secret reaches and what the
 * answer is. The integration tests ({@code PatPasswordOnlyIT}, {@code PatScopeIntersectionIT}) run
 * the real chain.
 */
@DisplayName("ProtocolAuthService personal access tokens")
class ProtocolAuthServicePatTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String OWNER = "owner";

  private final UserTxService userTxService = mock(UserTxService.class);
  private final JwtUtils jwtUtils = mock(JwtUtils.class);
  private final DeployTokenService deployTokenService = mock(DeployTokenService.class);
  private final AuthFailureThrottle throttle = mock(AuthFailureThrottle.class);
  private final PersonalAccessTokenService tokens = mock(PersonalAccessTokenService.class);

  private final ProtocolAuthService authService =
      new ProtocolAuthService(
          this.userTxService,
          this.jwtUtils,
          this.deployTokenService,
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          this.throttle);

  /** A Docker-like component: it takes no raw secret as a Bearer value. */
  private final ProtocolAuthService noRawBearer =
      new ProtocolAuthService(
          this.userTxService,
          this.jwtUtils,
          this.deployTokenService,
          new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
          this.throttle) {
        @Override
        protected boolean acceptsRawDeployTokenBearer() {
          return false;
        }
      };

  private String secret;

  @BeforeEach
  void setUp() {
    this.authService.setPersonalAccessTokens(this.tokens);
    this.noRawBearer.setPersonalAccessTokens(this.tokens);
    this.secret = TokenFactory.personalAccessToken();
  }

  private static PersonalAccessTokenInfo token(
      final Instant expirationDate, final TokenScope... scopes) {
    return new PersonalAccessTokenInfo(
        UUID.randomUUID(),
        OWNER_ID,
        OWNER,
        PersonalAccessTokenKind.PERSONAL,
        "ci",
        TokenScope.withImplicit(java.util.List.of(scopes)),
        expirationDate,
        null,
        Instant.now());
  }

  private static PersonalAccessTokenInfo live(final TokenScope... scopes) {
    return token(Instant.now().plus(Duration.ofDays(30)), scopes);
  }

  private void found(final PersonalAccessTokenInfo token) {
    when(this.tokens.findByToken(this.secret)).thenReturn(Optional.of(token));
  }

  private void ownerIs(final UserRole role) {
    when(this.userTxService.getAuthenticatedUserById(OWNER_ID))
        .thenReturn(UserInfo.builder().id(OWNER_ID).username(OWNER).role(role).build());
  }

  private static void assertUnauthorized(
      final org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  @Nested
  @DisplayName("tryAuthorizeWithPat")
  class TryAuthorize {

    @ParameterizedTest(name = "\"{0}\" is not a personal access token")
    @ValueSource(
        strings = {
          "",
          "password",
          "rdt-deploy",
          "RUT-upper",
          "RCT-upper",
          "xrut-x",
          "xrct-x",
          "ru-t"
        })
    void anythingWithoutThePrefixIsLeftAlone(final String other) {
      assertThat(authService.tryAuthorizeWithPat(REPO_ID, other, Permission.READ)).isFalse();

      verifyNoInteractions(tokens, throttle, userTxService, deployTokenService, jwtUtils);
    }

    @Test
    @DisplayName("an unknown secret is a counted failure and never touches a user")
    void unknownIsCounted() {
      when(tokens.findByToken(secret)).thenReturn(Optional.empty());

      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ));

      verify(throttle).checkAllowed();
      verify(throttle).recordFailure();
      verifyNoInteractions(userTxService, deployTokenService, jwtUtils);
    }

    @Test
    @DisplayName("an expired token is a counted failure")
    void expiredIsCounted() {
      found(token(Instant.now().minusSeconds(1), TokenScope.REPO_READ));

      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ));

      verify(throttle).recordFailure();
      verify(tokens, never()).updateLastUsedTime(any());
    }

    @Test
    @DisplayName("without the token service no secret is known")
    void noServiceIsAnUnknownSecret() {
      final var bare =
          new ProtocolAuthService(
              userTxService,
              jwtUtils,
              deployTokenService,
              new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
              throttle);

      assertUnauthorized(() -> bare.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ));

      verify(throttle).recordFailure();
    }

    @Test
    @DisplayName("a blocked client is answered 429 before the lookup result matters")
    void aBlockedClientIsRefused() {
      when(tokens.findByToken(secret)).thenReturn(Optional.empty());
      doThrow(new io.repsy.protocols.shared.exceptions.TooManyRequestsException(7))
          .when(throttle)
          .checkAllowed();

      assertThatThrownBy(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ))
          .isInstanceOf(io.repsy.protocols.shared.exceptions.TooManyRequestsException.class);

      verify(throttle, never()).recordFailure();
    }

    @Test
    @DisplayName("a valid token that may do it is accepted, not counted, and its use recorded")
    void validIsAccepted() {
      final var token = live(TokenScope.REPO_READ);

      found(token);

      assertThat(authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ)).isTrue();

      verify(tokens).updateLastUsedTime(token.id());
      verifyNoInteractions(throttle, userTxService);
    }

    @Test
    @DisplayName("the token is not bound to a repo")
    void anyRepo() {
      found(live(TokenScope.REPO_READ));

      assertThat(authService.tryAuthorizeWithPat(UUID.randomUUID(), secret, Permission.READ))
          .isTrue();
      assertThat(authService.tryAuthorizeWithPat(UUID.randomUUID(), secret, Permission.READ))
          .isTrue();
    }

    @Test
    @DisplayName("a token that does not reach the permission is refused and not counted")
    void tooFewScopes() {
      found(live(TokenScope.REPO_READ));

      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.WRITE));
      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.MANAGE));

      verifyNoInteractions(throttle);
      verify(tokens, never()).updateLastUsedTime(any());
    }

    @Test
    @DisplayName("a scope grants its own permission and the ones below it")
    void theLadder() {
      found(live(TokenScope.REPO_WRITE));

      assertThat(authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ)).isTrue();
      assertThat(authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.WRITE)).isTrue();
      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.MANAGE));
    }

    @Test
    @DisplayName("no repo scope at all grants no permission")
    void noRepoScope() {
      found(live(TokenScope.SCAN_READ));

      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ));
    }

    @ParameterizedTest(name = "repo:manage for a {0} owner")
    @EnumSource(UserRole.class)
    void manageNeedsAnAdminOwner(final UserRole role) {
      found(live(TokenScope.REPO_MANAGE));
      ownerIs(role);

      final ThrowingAssertion manage =
          () -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.MANAGE);

      if (role == UserRole.ADMIN) {
        assertThatCode(manage::run).doesNotThrowAnyException();
      } else {
        assertUnauthorized(manage::run);
      }
    }

    @Test
    @DisplayName("repo:write never gives MANAGE, even to an admin owner")
    void writeNeverManages() {
      found(live(TokenScope.REPO_WRITE));
      ownerIs(UserRole.ADMIN);

      assertUnauthorized(() -> authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.MANAGE));
    }

    @Test
    @DisplayName("the owner's role is not read for a read or a write")
    void theRoleIsReadOnlyForManage() {
      found(live(TokenScope.REPO_MANAGE));

      authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.READ);
      authService.tryAuthorizeWithPat(REPO_ID, secret, Permission.WRITE);

      verifyNoInteractions(userTxService);
    }
  }

  /** A call that may throw, to run one check as an assertion or as an exception. */
  @FunctionalInterface
  private interface ThrowingAssertion {
    void run();
  }

  @Nested
  @DisplayName("Basic and Bearer credentials")
  class Credentials_ {

    private String basic(final String password) {
      return "Basic "
          + Base64.getEncoder()
              .encodeToString(("any:" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a rut- Basic password never reaches the deploy token or the password check")
    void basicNeverFallsThrough() {
      when(tokens.findByToken(secret)).thenReturn(Optional.empty());

      assertUnauthorized(
          () -> authService.handleBasicAuth(basic(secret), Permission.READ, REPO_ID));

      verifyNoInteractions(deployTokenService, userTxService);
    }

    @Test
    @DisplayName("a valid Basic password is accepted whatever the username")
    void basicAccepts() {
      found(live(TokenScope.REPO_READ));

      assertThatCode(() -> authService.handleBasicAuth(basic(secret), Permission.READ, REPO_ID))
          .doesNotThrowAnyException();

      verifyNoInteractions(deployTokenService, userTxService);
    }

    @Test
    @DisplayName("a rut- Bearer value never reaches the JWT decode or the deploy token lookup")
    void bearerNeverFallsThrough() {
      when(tokens.findByToken(secret)).thenReturn(Optional.empty());

      assertUnauthorized(
          () -> authService.handleBearerAuth("Bearer " + secret, REPO_ID, Permission.READ));

      verifyNoInteractions(jwtUtils, deployTokenService, userTxService);
    }

    @Test
    @DisplayName("a valid Bearer secret is accepted")
    void bearerAccepts() {
      found(live(TokenScope.REPO_READ));

      assertThatCode(
              () -> authService.handleBearerAuth("Bearer " + secret, REPO_ID, Permission.READ))
          .doesNotThrowAnyException();

      verifyNoInteractions(jwtUtils);
    }

    @Test
    @DisplayName("a protocol that takes no raw Bearer secret refuses even a valid one, counted")
    void noRawBearerSecret() {
      found(live(TokenScope.REPO_READ));

      assertUnauthorized(
          () -> noRawBearer.handleBearerAuth("Bearer " + secret, REPO_ID, Permission.READ));

      verify(throttle).recordFailure();
      verify(tokens, never()).findByToken(any());
    }

    @Test
    @DisplayName("a rut- password is never hashed against a user's password")
    void aPasswordLikeAToken() {
      final var credentials = Credentials.builder().username(OWNER).password(secret).build();

      assertUnauthorized(() -> authService.authenticateWithPassword(credentials));

      verify(throttle).recordFailure();
      verifyNoInteractions(userTxService);
    }
  }

  @Nested
  @DisplayName("the JWT of a personal access token")
  class Jwt {

    private final String header = "Bearer some.jwt.value";
    private final UUID tokenId = UUID.randomUUID();

    @BeforeEach
    void jwtIsOfAToken() {
      when(jwtUtils.extractAuthenticationType(header, TokenRealm.PROTOCOL))
          .thenReturn(AuthenticationType.PERSONAL_ACCESS_TOKEN);
      when(jwtUtils.extractUserId(header, TokenRealm.PROTOCOL)).thenReturn(tokenId);
    }

    private PersonalAccessTokenInfo withId(final PersonalAccessTokenInfo token) {
      return new PersonalAccessTokenInfo(
          tokenId,
          token.userId(),
          token.username(),
          token.kind(),
          token.name(),
          token.scopes(),
          token.expirationDate(),
          null,
          token.createdAt());
    }

    @Test
    @DisplayName("is authorized as the token it names, read again, with the scopes it has now")
    void authorizedAsTheToken() {
      when(tokens.findById(tokenId)).thenReturn(Optional.of(withId(live(TokenScope.REPO_READ))));

      assertThatCode(() -> authService.handleBearerAuth(header, REPO_ID, Permission.READ))
          .doesNotThrowAnyException();
      assertUnauthorized(() -> authService.handleBearerAuth(header, REPO_ID, Permission.WRITE));

      verify(tokens, times(2)).findById(tokenId);
    }

    @Test
    @DisplayName("ends with the token: a revoked or expired token is unAuthorized, not counted")
    void endsWithTheToken() {
      when(tokens.findById(tokenId)).thenReturn(Optional.empty());

      assertUnauthorized(() -> authService.handleBearerAuth(header, REPO_ID, Permission.READ));

      when(tokens.findById(tokenId))
          .thenReturn(
              Optional.of(withId(token(Instant.now().minusSeconds(1), TokenScope.REPO_READ))));

      assertUnauthorized(() -> authService.handleBearerAuth(header, REPO_ID, Permission.READ));

      verify(throttle, never()).recordFailure();
    }

    @Test
    @DisplayName("is never looked up as a user")
    void neverAUser() {
      when(tokens.findById(tokenId)).thenReturn(Optional.of(withId(live(TokenScope.REPO_READ))));

      authService.handleBearerAuth(header, REPO_ID, Permission.READ);

      verifyNoInteractions(userTxService);
      verify(jwtUtils, never()).extractProtocolUserClaims(any());
    }
  }

  @Nested
  @DisplayName("a web UI API request to a repo")
  class Panel {

    private final RepoInfo repo =
        RepoInfo.builder()
            .id(REPO_ID)
            .name("some-repo")
            .privateRepo(true)
            .type(RepoType.MAVEN)
            .build();

    private String bearer() {
      return "Bearer " + secret;
    }

    @Test
    @DisplayName("reports the effective permissions: scopes intersected with the role")
    void effectivePermissions() {
      found(live(TokenScope.REPO_WRITE));
      ownerIs(UserRole.ADMIN);

      final var permissions = authService.authorizeUserRequest(repo, bearer(), Permission.READ);

      assertThat(permissions.getCanRead()).isTrue();
      assertThat(permissions.getCanWrite()).isTrue();
      assertThat(permissions.getCanManage()).isFalse();
    }

    @Test
    @DisplayName("repo:manage for an admin reports manage")
    void manageForAnAdmin() {
      found(live(TokenScope.REPO_MANAGE));
      ownerIs(UserRole.ADMIN);

      assertThat(authService.authorizeUserRequest(repo, bearer(), Permission.MANAGE).getCanManage())
          .isTrue();
    }

    @Test
    @DisplayName("signed in but not allowed is 403 accessDenied, not 401, and not counted")
    void notAllowedIsForbidden() {
      found(live(TokenScope.REPO_READ));
      ownerIs(UserRole.ADMIN);

      final var bearer = bearer();

      assertThatThrownBy(() -> authService.authorizeUserRequest(repo, bearer, Permission.WRITE))
          .isExactlyInstanceOf(AccessNotAllowedException.class)
          .hasMessage("accessDenied");

      verify(throttle, never()).recordFailure();
    }

    @Test
    @DisplayName("repo:manage on a user who is not an admin is 403")
    void manageOnAUser() {
      found(live(TokenScope.REPO_MANAGE));
      ownerIs(UserRole.USER);

      final var bearer = bearer();

      assertThatThrownBy(() -> authService.authorizeUserRequest(repo, bearer, Permission.MANAGE))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("an unknown secret is 401 and counted")
    void unknown() {
      when(tokens.findByToken(secret)).thenReturn(Optional.empty());

      assertUnauthorized(() -> authService.authorizeUserRequest(repo, bearer(), Permission.READ));

      verify(throttle).recordFailure();
    }

    @Test
    @DisplayName("a repo that does not exist is answered with the same checks")
    void unknownRepo() {
      found(live(TokenScope.REPO_READ));
      ownerIs(UserRole.USER);

      final var bearer = bearer();

      assertThatCode(() -> authService.authorizeUnknownRepoRequest(bearer, Permission.READ))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> authService.authorizeUnknownRepoRequest(bearer, Permission.WRITE))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("scan:read alone passes a scan route and no other route (RPS-2260)")
    void scanReadOpensTheScanRoutesOnly() {
      found(live(TokenScope.SCAN_READ));
      ownerIs(UserRole.USER);

      final var bearer = bearer();
      final var permissions = authService.authorizeUserRequest(repo, bearer, Permission.READ, true);

      assertThat(permissions.getCanRead()).isTrue();
      assertThat(permissions.getCanWrite()).isFalse();
      assertThat(permissions.getCanManage()).isFalse();

      // the same token on a route that is not a scan route
      assertThatThrownBy(() -> authService.authorizeUserRequest(repo, bearer, Permission.READ))
          .isExactlyInstanceOf(AccessNotAllowedException.class)
          .hasMessage("accessDenied");
      assertThatThrownBy(
              () -> authService.authorizeUserRequest(repo, bearer, Permission.READ, false))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("scan:read never starts a scan, even on a scan route")
    void scanReadNeverWrites() {
      found(live(TokenScope.SCAN_READ));
      ownerIs(UserRole.ADMIN);

      final var bearer = bearer();

      assertThatThrownBy(
              () -> authService.authorizeUserRequest(repo, bearer, Permission.WRITE, true))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
      assertThatThrownBy(
              () -> authService.authorizeUserRequest(repo, bearer, Permission.MANAGE, true))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("repo:read and a token without either scope on a scan route")
    void repoReadStillPassesAndNothingDoesNot() {
      found(live(TokenScope.REPO_READ));
      ownerIs(UserRole.USER);

      final var bearer = bearer();

      assertThat(authService.authorizeUserRequest(repo, bearer, Permission.READ, true).getCanRead())
          .isTrue();

      found(live());

      assertThatThrownBy(
              () -> authService.authorizeUserRequest(repo, bearer, Permission.READ, true))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("a repo that does not exist is answered the same way on a scan route")
    void unknownRepoOnAScanRoute() {
      found(live(TokenScope.SCAN_READ));
      ownerIs(UserRole.USER);

      final var bearer = bearer();

      assertThatCode(() -> authService.authorizeUnknownRepoRequest(bearer, Permission.READ, true))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> authService.authorizeUnknownRepoRequest(bearer, Permission.READ))
          .isExactlyInstanceOf(AccessNotAllowedException.class);
    }

    @Test
    @DisplayName("a rut- Basic password on the panel is never hashed")
    void basicOnThePanel() {
      final var basic =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((OWNER + ":" + secret).getBytes(StandardCharsets.UTF_8));

      assertUnauthorized(() -> authService.authorizeUserRequest(repo, basic, Permission.READ));

      verify(userTxService, never()).findUserInfoByUsername(any());
    }
  }

  /** RPS-2039: the temporary {@code rct-} token goes through the same checks as a {@code rut-}. */
  @Nested
  @DisplayName("a temporary rct- token")
  class Temporary {

    private String cliSecret;

    @BeforeEach
    void cliSecretIsGenerated() {
      cliSecret = TokenFactory.cliToken();
    }

    private PersonalAccessTokenInfo temporary(final TokenScope... scopes) {
      return new PersonalAccessTokenInfo(
          UUID.randomUUID(),
          OWNER_ID,
          OWNER,
          PersonalAccessTokenKind.TEMPORARY,
          "Repsy CLI login",
          TokenScope.withImplicit(java.util.List.of(scopes)),
          Instant.now().plus(Duration.ofHours(24)),
          null,
          Instant.now());
    }

    @Test
    @DisplayName("is recognized as a personal access token and authorized by its scopes")
    void isAuthorizedLikeAToken() {
      when(tokens.findByToken(cliSecret)).thenReturn(Optional.of(temporary(TokenScope.REPO_READ)));

      assertThat(authService.tryAuthorizeWithPat(REPO_ID, cliSecret, Permission.READ)).isTrue();
      assertUnauthorized(
          () -> authService.tryAuthorizeWithPat(REPO_ID, cliSecret, Permission.WRITE));
    }

    @Test
    @DisplayName("an unknown or expired one is a counted failure")
    void unknownIsCounted() {
      when(tokens.findByToken(cliSecret)).thenReturn(Optional.empty());

      assertUnauthorized(
          () -> authService.tryAuthorizeWithPat(REPO_ID, cliSecret, Permission.READ));

      verify(throttle).recordFailure();
    }

    @Test
    @DisplayName(
        "a Basic password that starts with rct- never reaches the deploy token or the password check")
    void basicNeverFallsThrough() {
      when(tokens.findByToken(cliSecret)).thenReturn(Optional.empty());
      final var basic =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(("any:" + cliSecret).getBytes(StandardCharsets.UTF_8));

      assertUnauthorized(() -> authService.handleBasicAuth(basic, Permission.READ, REPO_ID));

      verifyNoInteractions(deployTokenService, userTxService);
    }

    @Test
    @DisplayName("a rct- password is never hashed against a user's password")
    void aPasswordLikeATemporaryToken() {
      final var credentials = Credentials.builder().username(OWNER).password(cliSecret).build();

      assertUnauthorized(() -> authService.authenticateWithPassword(credentials));

      verify(throttle).recordFailure();
      verifyNoInteractions(userTxService);
    }

    @Test
    @DisplayName("a rct- Bearer value never reaches the JWT decode")
    void bearerNeverFallsThrough() {
      when(tokens.findByToken(cliSecret)).thenReturn(Optional.empty());

      assertUnauthorized(
          () -> authService.handleBearerAuth("Bearer " + cliSecret, REPO_ID, Permission.READ));

      verifyNoInteractions(jwtUtils, deployTokenService, userTxService);
    }
  }

  @Test
  @DisplayName("a scope set is unmodifiable and comes back in canonical order")
  void scopesOfTheInfo() {
    final var token =
        token(Instant.now().plusSeconds(60), TokenScope.SCAN_READ, TokenScope.REPO_READ);

    assertThat(token.scopes())
        .containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ, TokenScope.SCAN_READ);
    final var scopes = token.scopes();
    assertThatThrownBy(() -> scopes.add(TokenScope.REPO_MANAGE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(EnumSet.copyOf(scopes)).isNotEmpty().doesNotContain(TokenScope.REPO_MANAGE);
  }
}
