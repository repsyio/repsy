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

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.PanelSession;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class PanelAuthHelper {

  private final JwtUtils jwtUtils;
  private final UserTxService userTxService;

  private @Nullable PersonalAccessTokenService personalAccessTokens;

  /**
   * Setter-injected, so that a helper built by hand in a unit test needs no service. Without it no
   * personal access token is known.
   */
  @Autowired(required = false)
  public void setPersonalAccessTokens(
      final @Nullable PersonalAccessTokenService personalAccessTokens) {
    this.personalAccessTokens = personalAccessTokens;
  }

  /**
   * Authenticates a web UI API request with the access token of a login. A personal access token is
   * not accepted here: {@link #authenticate} and every method that builds on it take a login only.
   * The routes a command line client needs have an entry point of their own below.
   */
  public UserInfo authenticate(final String authHeader) {
    return this.authenticateSession(authHeader).user();
  }

  /**
   * Authenticates like {@link #authenticate} and also returns the session start of the token, for
   * endpoints that mint new tokens. The token is verified and decoded once.
   */
  public PanelSession authenticateSession(final String authHeader) {
    final var claims = this.jwtUtils.extractPanelClaims(authHeader);
    final var user = this.userTxService.getAuthenticatedUserByUsername(claims.username());

    // The token version and the user id (RPS-1604), as ProtocolAuthService#authenticatePanelBearer
    // does for the @RepoOperation routes.
    if (!claims.issuedTo(user)) {
      throw new UnAuthorizedException(ErrorConstants.SESSION_EXPIRED);
    }

    return new PanelSession(user, claims.sessionStart());
  }

  /**
   * Authenticates the caller of the repo list: a login, or a personal access token that may read
   * ({@code repo:read}).
   */
  public UserInfo authenticateRepoReader(final String authHeader) {
    return this.authenticateOrPersonalAccessToken(authHeader, Permission.READ);
  }

  /**
   * Authenticates the caller of the repo creation: a login, or a personal access token with {@code
   * repo:manage}. The caller still has to be an ADMIN ({@link #requireAdmin}), which a scope cannot
   * give.
   */
  public UserInfo authenticateRepoCreator(final String authHeader) {
    return this.authenticateOrPersonalAccessToken(authHeader, Permission.MANAGE);
  }

  /**
   * Authenticates the caller of {@code GET /api/profile/access-tokens/current}, the one route that
   * is for a personal access token: it answers what the token is. A login is a valid caller and is
   * answered {@code notAnAccessToken} (400), as it has no token to describe.
   */
  public PersonalAccessTokenInfo authenticateAccessToken(final String authHeader) {

    final var secret = AuthUtils.personalAccessTokenSecretOf(authHeader);

    if (secret == null) {
      this.authenticate(authHeader);

      throw new BadRequestException(ErrorConstants.NOT_AN_ACCESS_TOKEN);
    }

    final var token = this.liveToken(secret);

    this.touch(token);

    return token;
  }

  private UserInfo authenticateOrPersonalAccessToken(
      final String authHeader, final Permission required) {

    final var secret = AuthUtils.personalAccessTokenSecretOf(authHeader);

    if (secret == null) {
      return this.authenticate(authHeader);
    }

    final var token = this.liveToken(secret);

    // Signed in but not allowed is a 403 on the panel API, not a 401 (RPS-1284).
    if (!TokenScope.permits(token.scopes(), required)) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.ACCESS_DENIED);
    }

    final var user = this.userTxService.getAuthenticatedUserById(token.userId());

    this.touch(token);

    return user;
  }

  private PersonalAccessTokenInfo liveToken(final String secret) {

    final var service = this.personalAccessTokens;
    final var found = service == null ? null : service.findByToken(secret).orElse(null);

    if (found == null || found.isExpired()) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    return found;
  }

  private void touch(final PersonalAccessTokenInfo token) {

    final var service = this.personalAccessTokens;

    if (service != null) {
      service.updateLastUsedTime(token.id());
    }
  }

  public void requireAdmin(final UserInfo userInfo) {
    if (userInfo.getRole() != UserRole.ADMIN) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.ACCESS_DENIED);
    }
  }
}
