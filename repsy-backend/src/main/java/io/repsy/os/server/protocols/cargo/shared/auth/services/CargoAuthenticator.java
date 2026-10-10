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

import static io.repsy.os.shared.auth.utils.AuthUtils.TIMEOUT_ACCESS_TOKEN;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.normalizeToBearer;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBearerHeader;
import static io.repsy.protocols.shared.repo.dtos.RepoType.CARGO;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.token.dtos.TokenType;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.auth.AuthFailureThrottle;
import io.repsy.protocols.shared.auth.VerifiedPasswordCache;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoAuthenticator extends ProtocolAuthService {

  public CargoAuthenticator(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService,
      final VerifiedPasswordCache verifiedPasswordCache,
      final AuthFailureThrottle authFailureThrottle) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);
  }

  public String authenticateAndCreateToken(final String rawAuthHeader) {

    // The Cargo CLI's cargo:token provider sends a renewed bearer token with no scheme prefix
    // (CargoAuthPreProcessor.normalizeAuthHeader does the same for the index/publish/download
    // endpoints); without this, such a token matched neither branch below and this method — the
    // only one implementing the RPS-979/RPS-1552 bearer-renewal checks — was unreachable for it.
    final var authHeader = normalizeToBearer(rawAuthHeader);

    if (isBasicToken(authHeader)) {
      return this.authenticateBasicAndCreateToken(authHeader);
    }

    if (isBearerToken(authHeader)) {
      final var patSecret = AuthUtils.personalAccessTokenSecretOf(authHeader);

      if (patSecret != null) {
        return this.authenticateWithPersonalAccessToken(patSecret);
      }

      // Only a token issued to a user is renewed. A deploy-token JWT carries a username the client
      // chose, so exchanging it would hand out the token of the user of that name (RPS-979).
      if (this.jwtUtils.extractAuthenticationType(authHeader, TokenRealm.PROTOCOL)
          != AuthenticationType.USERNAME_PASSWORD) {
        throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
      }

      this.rejectRevokedToken(removeBearerHeader(authHeader));

      // A token that a password change ended is not renewed: the renewal would hand out a fresh one
      // (RPS-1552).
      final var userInfo = this.authenticateJwtUser(authHeader);
      return this.jwtUtils.createProtocolToken(
          userInfo.getId(),
          userInfo.getUsername(),
          TIMEOUT_ACCESS_TOKEN,
          userInfo.getTokenVersion());
    }

    throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  private String authenticateBasicAndCreateToken(final String authHeader) {

    final var credentials = extractCredentialsFromBasicToken(removeBasicPrefix(authHeader));

    if (credentials == null) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    // A secret with the personal access token prefix is that and nothing else (RPS-1903).
    if (TokenType.REPSY_USER_TOKEN.matches(credentials.getPassword())) {
      return this.authenticateWithPersonalAccessToken(credentials.getPassword());
    }

    return this.authenticateWithDeployToken(credentials)
        .or(() -> this.authenticateWithUsernamePassword(credentials))
        .orElseThrow(() -> new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED));
  }

  /**
   * Answers a personal access token with a {@link AuthenticationType#PERSONAL_ACCESS_TOKEN} JWT,
   * which {@code handleBearerAuth} authorizes as that token, read again on every request. The
   * secret sent as the Bearer value, with or without the scheme, is the same credential.
   */
  private String authenticateWithPersonalAccessToken(final String secret) {

    final var token = super.authenticateWithPat(secret);

    super.touchPersonalAccessToken(token);

    return super.createPersonalAccessTokenJwt(token, TIMEOUT_ACCESS_TOKEN);
  }

  private Optional<String> authenticateWithDeployToken(final Credentials credentials) {

    final var deployTokenOpt =
        this.deployTokenService.findByTokenAndRepoType(credentials.getPassword(), CARGO);

    if (deployTokenOpt.isEmpty()) {
      return Optional.empty();
    }

    if (deployTokenOpt.get().isExpired()) {
      throw new UnAuthorizedException(ProtocolErrorCodes.DEPLOY_TOKEN_EXPIRED);
    }

    this.deployTokenService.updateLastUsedTime(deployTokenOpt.get().getId());

    final var token =
        this.jwtUtils.createProtocolToken(
            deployTokenOpt.get().getId(),
            credentials.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            AuthenticationType.DEPLOY_TOKEN);

    return Optional.of(token);
  }

  private Optional<String> authenticateWithUsernamePassword(final Credentials credentials) {

    final var userInfo = this.authenticateWithPassword(credentials);

    final var token =
        this.jwtUtils.createProtocolToken(
            userInfo.getId(),
            userInfo.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            userInfo.getTokenVersion());

    return Optional.of(token);
  }
}
