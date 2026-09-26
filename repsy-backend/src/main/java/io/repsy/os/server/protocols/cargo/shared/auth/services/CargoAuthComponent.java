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
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBearerHeader;
import static io.repsy.protocols.shared.repo.dtos.RepoType.CARGO;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoAuthComponent extends ProtocolAuthService {

  public CargoAuthComponent(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService,
      final VerifiedPasswordCache verifiedPasswordCache,
      final AuthFailureThrottle authFailureThrottle) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);
  }

  public String authenticateAndCreateToken(final String authHeader) {

    if (isBasicToken(authHeader)) {
      return this.authenticateBasicAndCreateToken(authHeader);
    }

    if (isBearerToken(authHeader)) {
      // Only a token issued to a user is renewed. A deploy-token JWT carries a username the client
      // chose, so exchanging it would hand out the token of the user of that name (RPS-979).
      if (this.jwtUtils.extractAuthenticationType(authHeader, TokenRealm.PROTOCOL)
          != AuthenticationType.USERNAME_PASSWORD) {
        throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
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

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  private String authenticateBasicAndCreateToken(final String authHeader) {

    final var credentials = extractCredentialsFromBasicToken(removeBasicPrefix(authHeader));

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return this.authenticateWithDeployToken(credentials)
        .or(() -> this.authenticateWithUsernamePassword(credentials))
        .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
  }

  private Optional<String> authenticateWithDeployToken(final Credentials credentials) {

    final var deployTokenOpt =
        this.deployTokenService.findByTokenAndRepoType(credentials.getPassword(), CARGO);

    if (deployTokenOpt.isEmpty()) {
      return Optional.empty();
    }

    if (deployTokenOpt.get().isExpired()) {
      throw new UnAuthorizedException("deployTokenExpired");
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
