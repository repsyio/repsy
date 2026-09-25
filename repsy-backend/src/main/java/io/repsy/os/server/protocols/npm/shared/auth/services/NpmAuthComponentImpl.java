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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthComponent;
import io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.time.Period;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class NpmAuthComponentImpl extends ProtocolAuthService
    implements NpmAuthComponent<UUID>, NpmIdentityResolver<UUID> {

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

  /**
   * Answers {@code GET /-/whoami}. Everything is verified again here, so the answer does not depend
   * on the pre-processor having run. A deploy token is answered with its own generated username
   * whichever way it is presented, because the name a client types with one is its own choice
   * (RPS-979).
   */
  @Override
  public @NonNull String resolveUsername(
      final @NonNull BaseRepoInfo<UUID> repoInfo, final @Nullable String authHeader) {

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (AuthUtils.isBasicToken(authHeader)) {
      return this.basicUsername(repoInfo.getStorageKey(), authHeader);
    }

    if (AuthUtils.isBearerToken(authHeader)) {
      return this.bearerUsername(repoInfo.getStorageKey(), authHeader);
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  private @NonNull String basicUsername(final @NonNull UUID repoId, final @NonNull String header) {

    final var credentials =
        AuthUtils.extractCredentialsFromBasicToken(AuthUtils.removeBasicPrefix(header));

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var deployToken =
        this.deployTokenService.findByRepoIdAndToken(repoId, credentials.getPassword());

    if (deployToken.isPresent()) {
      return usernameOf(deployToken.get());
    }

    return this.authenticateWithPassword(credentials).getUsername();
  }

  private @NonNull String bearerUsername(final @NonNull UUID repoId, final @NonNull String header) {

    final var deployToken =
        this.deployTokenService.findByRepoIdAndToken(repoId, AuthUtils.removeBearerHeader(header));

    if (deployToken.isPresent()) {
      return usernameOf(deployToken.get());
    }

    final var authenticationType = this.protocolAuthenticationType(header);

    return switch (authenticationType) {
      case DEPLOY_TOKEN -> {
        final var tokenId = this.jwtUtils.extractUserId(header, TokenRealm.PROTOCOL);

        yield this.deployTokenService
            .findByRepoIdAndTokenId(repoId, tokenId)
            .map(NpmAuthComponentImpl::usernameOf)
            .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
      }
      case ANONYMOUS, DOCKER_SCAN -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
      default -> {
        final var username = this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PROTOCOL);

        yield this.userTxService.getAuthenticatedUserByUsername(username).getUsername();
      }
    };
  }

  private @NonNull AuthenticationType protocolAuthenticationType(final @NonNull String header) {
    try {
      return this.jwtUtils.extractAuthenticationType(header, TokenRealm.PROTOCOL);
    } catch (final BadRequestException _) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  /** The username of a live deploy token; an expired one identifies nobody. */
  private static @NonNull String usernameOf(final @NonNull DeployTokenInfo deployToken) {

    if (deployToken.isExpired() || deployToken.getUsername() == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return deployToken.getUsername();
  }
}
