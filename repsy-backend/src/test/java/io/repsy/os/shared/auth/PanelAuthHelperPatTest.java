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
package io.repsy.os.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.PanelTokenClaims;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1903: the three entry points of {@link PanelAuthHelper} that take a personal access token,
 * and the ones that must not. {@code authenticate} and everything that builds on it take the access
 * token of a login only; the repo list, the repo creation and {@code /current} are the routes a
 * command line client needs.
 */
@DisplayName("PanelAuthHelper personal access tokens")
class PanelAuthHelperPatTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private final JwtUtils jwtUtils = mock(JwtUtils.class);
  private final UserTxService userTxService = mock(UserTxService.class);
  private final PersonalAccessTokenService tokens = mock(PersonalAccessTokenService.class);
  private final PanelAuthHelper helper = new PanelAuthHelper(this.jwtUtils, this.userTxService);

  private String secret;
  private String header;

  @BeforeEach
  void setUp() {
    this.helper.setPersonalAccessTokens(this.tokens);
    this.secret = TokenFactory.personalAccessToken();
    this.header = "Bearer " + this.secret;
    when(this.userTxService.getAuthenticatedUserById(OWNER_ID))
        .thenReturn(UserInfo.builder().id(OWNER_ID).username("owner").role(UserRole.USER).build());
  }

  private static PersonalAccessTokenInfo token(
      final Instant expirationDate, final TokenScope... scopes) {
    return new PersonalAccessTokenInfo(
        UUID.randomUUID(),
        OWNER_ID,
        "owner",
        "ci",
        TokenScope.withImplicit(List.of(scopes)),
        expirationDate,
        null,
        Instant.now());
  }

  private PersonalAccessTokenInfo found(final TokenScope... scopes) {
    final var token = token(Instant.now().plus(Duration.ofDays(30)), scopes);

    when(this.tokens.findByToken(this.secret)).thenReturn(Optional.of(token));

    return token;
  }

  @Test
  @DisplayName("authenticate takes a login only: a token is never looked up")
  void authenticateIsLoginOnly() {
    when(this.jwtUtils.extractPanelClaims(this.header)).thenThrow(new UnAuthorizedException("x"));

    assertThatThrownBy(() -> this.helper.authenticate(this.header))
        .isInstanceOf(UnAuthorizedException.class);

    verifyNoInteractions(this.tokens);
  }

  @Test
  @DisplayName("authenticateSession and requireAdmin take no token either")
  void sessionIsLoginOnly() {
    when(this.jwtUtils.extractPanelClaims(this.header)).thenThrow(new UnAuthorizedException("x"));

    assertThatThrownBy(() -> this.helper.authenticateSession(this.header))
        .isInstanceOf(UnAuthorizedException.class);

    verifyNoInteractions(this.tokens);
  }

  @Test
  @DisplayName("the repo list takes a token that may read")
  void repoReaderTakesRead() {
    final var token = found(TokenScope.REPO_READ);

    assertThat(this.helper.authenticateRepoReader(this.header).getId()).isEqualTo(OWNER_ID);

    verify(this.tokens).updateLastUsedTime(token.id());
  }

  @Test
  @DisplayName("the repo list refuses a token without a read scope with 403")
  void repoReaderNeedsRead() {
    found(TokenScope.SCAN_READ);

    assertThatThrownBy(() -> this.helper.authenticateRepoReader(this.header))
        .isExactlyInstanceOf(AccessNotAllowedException.class)
        .hasMessage("accessDenied");
    verify(this.tokens, never()).updateLastUsedTime(any());
  }

  @Test
  @DisplayName("the repo creation needs repo:manage, which write and read do not give")
  void repoCreatorNeedsManage() {
    found(TokenScope.REPO_WRITE, TokenScope.REPO_READ);

    assertThatThrownBy(() -> this.helper.authenticateRepoCreator(this.header))
        .isExactlyInstanceOf(AccessNotAllowedException.class);

    found(TokenScope.REPO_MANAGE);

    assertThat(this.helper.authenticateRepoCreator(this.header).getId()).isEqualTo(OWNER_ID);
  }

  @Test
  @DisplayName("an unknown or expired token is 401")
  void unknownOrExpired() {
    when(this.tokens.findByToken(this.secret)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.helper.authenticateRepoReader(this.header))
        .isExactlyInstanceOf(UnAuthorizedException.class);

    when(this.tokens.findByToken(this.secret))
        .thenReturn(Optional.of(token(Instant.now().minusSeconds(1), TokenScope.REPO_READ)));

    assertThatThrownBy(() -> this.helper.authenticateRepoReader(this.header))
        .isExactlyInstanceOf(UnAuthorizedException.class);
    assertThatThrownBy(() -> this.helper.authenticateAccessToken(this.header))
        .isExactlyInstanceOf(UnAuthorizedException.class);
  }

  @Test
  @DisplayName("without the token service no token is known")
  void noService() {
    final var bare = new PanelAuthHelper(this.jwtUtils, this.userTxService);

    assertThatThrownBy(() -> bare.authenticateRepoReader(this.header))
        .isExactlyInstanceOf(UnAuthorizedException.class);
  }

  @Test
  @DisplayName("a login is authenticated as before by the repo entry points")
  void aLoginStillWorks() {
    final var user = UserInfo.builder().id(OWNER_ID).username("owner").role(UserRole.USER).build();
    final var login = "Bearer a.login.jwt";

    when(this.jwtUtils.extractPanelClaims(login))
        .thenReturn(new PanelTokenClaims(OWNER_ID, "owner", 0, Instant.now()));
    when(this.userTxService.getAuthenticatedUserByUsername("owner")).thenReturn(user);

    assertThat(this.helper.authenticateRepoReader(login)).isEqualTo(user);
    assertThat(this.helper.authenticateRepoCreator(login)).isEqualTo(user);
    verifyNoInteractions(this.tokens);
  }

  @Test
  @DisplayName("/current answers the token it is called with")
  void currentAnswersTheToken() {
    final var token = found(TokenScope.REPO_READ);

    assertThat(this.helper.authenticateAccessToken(this.header)).isEqualTo(token);
  }

  @Test
  @DisplayName("/current answers notAnAccessToken (400) to a login, once it is authenticated")
  void currentRefusesALogin() {
    final var login = "Bearer a.login.jwt";

    when(this.jwtUtils.extractPanelClaims(login))
        .thenReturn(new PanelTokenClaims(OWNER_ID, "owner", 0, Instant.now()));
    when(this.userTxService.getAuthenticatedUserByUsername("owner"))
        .thenReturn(UserInfo.builder().id(OWNER_ID).username("owner").role(UserRole.USER).build());

    assertThatThrownBy(() -> this.helper.authenticateAccessToken(login))
        .isExactlyInstanceOf(BadRequestException.class)
        .hasMessage("notAnAccessToken");
  }

  @Test
  @DisplayName("/current answers 401, not 400, to a request that is not authenticated at all")
  void currentRefusesNothing() {
    when(this.jwtUtils.extractPanelClaims("Bearer junk")).thenThrow(new UnAuthorizedException("x"));

    assertThatThrownBy(() -> this.helper.authenticateAccessToken("Bearer junk"))
        .isInstanceOf(UnAuthorizedException.class);
  }
}
