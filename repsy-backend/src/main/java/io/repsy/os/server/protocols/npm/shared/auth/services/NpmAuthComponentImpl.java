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

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.dtos.ProtocolTokenClaims;
import io.repsy.os.shared.auth.services.RevokedProtocolTokenService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthComponent;
import io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver;
import io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import java.time.Period;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class NpmAuthComponentImpl extends ProtocolAuthService
    implements NpmAuthComponent<UUID>, NpmIdentityResolver<UUID>, NpmTokenRevoker<UUID> {

  private static final int TOKEN_EXPIRATION_DAYS = 90;

  private final @NonNull RevokedProtocolTokenService revokedTokenService;

  public NpmAuthComponentImpl(
      final @NonNull UserTxService userTxService,
      final @NonNull JwtUtils jwtUtils,
      final @NonNull DeployTokenService deployTokenService,
      final @NonNull VerifiedPasswordCache verifiedPasswordCache,
      final @NonNull AuthFailureThrottle authFailureThrottle,
      final @NonNull RevokedProtocolTokenService revokedTokenService) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);

    this.revokedTokenService = revokedTokenService;
    this.setRevokedTokens(revokedTokenService);
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

    return this.resolveCaller(repoInfo.getStorageKey(), authHeader).username();
  }

  /**
   * Answers {@code DELETE /-/user/token/<token>} (RPS-1361). The protocol token of a login is a
   * stateless JWT, so it is revoked by remembering it in {@link RevokedProtocolTokenService}, which
   * every protocol bearer authentication asks. The caller is verified again here, and may only
   * revoke a token whose subject is the caller itself: the user, or the deploy token a deploy-token
   * login was made with. That is the same token it presents, or another one issued to it, never one
   * of somebody else's. The secret of a deploy token is refused: it is managed in the panel, and
   * its revocation would take the deploy token away from every CI job that uses it.
   */
  @Override
  public void revokeToken(
      final @NonNull BaseRepoInfo<UUID> repoInfo,
      final @Nullable String authHeader,
      final @NonNull String token) {

    final var caller = this.resolveCaller(repoInfo.getStorageKey(), authHeader);

    if (this.deployTokenService.findByRepoIdAndToken(repoInfo.getStorageKey(), token).isPresent()) {
      throw new AccessNotAllowedException("deployTokenNotRevocable");
    }

    final var claims = this.issuedTokenClaims(token);

    if (!claims.subject().equals(caller.id())) {
      throw new AccessNotAllowedException("loginTokenNotYours");
    }

    try {
      this.revokedTokenService.revoke(token, claims.expiresAt());
    } catch (final DataIntegrityViolationException _) {
      // Somebody revoked the same token at the same time: it is revoked, which is what was asked.
    }
  }

  /**
   * The claims of a token this registry issued, or {@code loginTokenNotFound} for anything else.
   */
  private @NonNull ProtocolTokenClaims issuedTokenClaims(final @NonNull String token) {
    try {
      final var claims = this.jwtUtils.verifyProtocolToken(token);

      if (claims.authenticationType() == AuthenticationType.ANONYMOUS
          || claims.authenticationType() == AuthenticationType.DOCKER_SCAN) {
        throw new ItemNotFoundException("loginTokenNotFound");
      }

      return claims;
    } catch (final UnAuthorizedException | BadRequestException _) {
      throw new ItemNotFoundException("loginTokenNotFound");
    }
  }

  /** Who a request's credentials belong to: the id of the user or deploy token, and its name. */
  private record Caller(@NonNull UUID id, @NonNull String username) {}

  private @NonNull Caller resolveCaller(
      final @NonNull UUID repoId, final @Nullable String authHeader) {

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (AuthUtils.isBasicToken(authHeader)) {
      return this.basicCaller(repoId, authHeader);
    }

    if (AuthUtils.isBearerToken(authHeader)) {
      return this.bearerCaller(repoId, authHeader);
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  private @NonNull Caller basicCaller(final @NonNull UUID repoId, final @NonNull String header) {

    final var credentials =
        AuthUtils.extractCredentialsFromBasicToken(AuthUtils.removeBasicPrefix(header));

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var deployToken =
        this.deployTokenService.findByRepoIdAndToken(repoId, credentials.getPassword());

    if (deployToken.isPresent()) {
      return callerOf(deployToken.get());
    }

    final var user = this.authenticateWithPassword(credentials);

    return new Caller(user.getId(), user.getUsername());
  }

  private @NonNull Caller bearerCaller(final @NonNull UUID repoId, final @NonNull String header) {

    final var deployToken =
        this.deployTokenService.findByRepoIdAndToken(repoId, AuthUtils.removeBearerHeader(header));

    if (deployToken.isPresent()) {
      return callerOf(deployToken.get());
    }

    final var authenticationType = this.protocolAuthenticationType(header);

    this.rejectRevokedToken(AuthUtils.removeBearerHeader(header));

    return switch (authenticationType) {
      case DEPLOY_TOKEN -> {
        final var tokenId = this.jwtUtils.extractUserId(header, TokenRealm.PROTOCOL);

        yield this.deployTokenService
            .findByRepoIdAndTokenId(repoId, tokenId)
            .map(NpmAuthComponentImpl::callerOf)
            .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
      }
      case ANONYMOUS, DOCKER_SCAN -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
      default -> {
        final var username = this.jwtUtils.verifyAndExtractUsername(header, TokenRealm.PROTOCOL);
        final var user = this.userTxService.getAuthenticatedUserByUsername(username);

        yield new Caller(user.getId(), user.getUsername());
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

  /** A live deploy token; an expired one identifies nobody. */
  private static @NonNull Caller callerOf(final @NonNull DeployTokenInfo deployToken) {

    if (deployToken.isExpired() || deployToken.getUsername() == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return new Caller(deployToken.getId(), deployToken.getUsername());
  }
}
