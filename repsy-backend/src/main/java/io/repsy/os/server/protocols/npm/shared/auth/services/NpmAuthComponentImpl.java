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
package io.repsy.os.server.protocols.npm.shared.auth.services;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthComponent;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.time.Period;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
public class NpmAuthComponentImpl extends ProtocolAuthService implements NpmAuthComponent<UUID> {

  private static final int TOKEN_EXPIRATION_DAYS = 90;

  public NpmAuthComponentImpl(
      final @NonNull UserTxService userTxService,
      final @NonNull JwtUtils jwtUtils,
      final @NonNull DeployTokenService deployTokenService,
      final @NonNull VerifiedPasswordCache verifiedPasswordCache,
      final @NonNull AuthFailureThrottle authFailureThrottle) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);
  }

  @Override
  public @NonNull String authenticateRepoUser(
      final @NonNull BaseRepoInfo<UUID> repoInfo,
      final @NonNull String username,
      final @NonNull String password) {

    final var deployTokenInfoOpt =
        this.deployTokenService.findByRepoIdAndToken(repoInfo.getStorageKey(), password);

    return deployTokenInfoOpt
        .map(deployTokenInfo -> this.authenticateWithDeployToken(deployTokenInfo, username))
        .orElseGet(() -> this.authenticateWithUserCredentials(username, password));
  }

  private @NonNull String authenticateWithUserCredentials(
      final @NonNull String username, final @NonNull String password) {

    final var userInfo =
        this.authenticateWithPassword(
            Credentials.builder().username(username).password(password).build());

    return super.jwtUtils.createProtocolToken(
        userInfo.getId(), username, Period.ofDays(TOKEN_EXPIRATION_DAYS));
  }

  /**
   * Answers a deploy-token login with a {@link AuthenticationType#DEPLOY_TOKEN} JWT, which {@code
   * ProtocolAuthService.handleBearerAuth} authorizes as that deploy token: bound to its repo,
   * read-only and expiry checked on every request, and gone once the token is revoked. The stored
   * SHA-256 hash of the secret must never be returned (RPS-1045): it is not a credential, so the
   * registry rejects it, and it should not leave the server. The {@code username} claim is whatever
   * the client typed, so it never identifies a user (RPS-979).
   */
  private @NonNull String authenticateWithDeployToken(
      final @NonNull DeployTokenInfo deployTokenInfo, final @NonNull String username) {

    if (deployTokenInfo.isExpired()) {
      throw new UnAuthorizedException("unAuthorized");
    }

    this.deployTokenService.updateLastUsedTime(deployTokenInfo.getId());

    return super.jwtUtils.createProtocolToken(
        deployTokenInfo.getId(),
        username,
        Period.ofDays(TOKEN_EXPIRATION_DAYS),
        AuthenticationType.DEPLOY_TOKEN);
  }
}
