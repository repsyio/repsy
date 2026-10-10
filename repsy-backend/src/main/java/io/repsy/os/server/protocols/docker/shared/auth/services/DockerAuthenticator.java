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

import static io.repsy.os.shared.auth.utils.AuthUtils.TIMEOUT_ACCESS_TOKEN;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.token.dtos.TokenType;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.docker.protocol.parser.DockerScopes;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class DockerAuthenticator extends ProtocolAuthService implements DockerAuthService<UUID> {

  private static final String ANONYMOUS_USER = "anonymous";

  public DockerAuthenticator(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService,
      final VerifiedPasswordCache verifiedPasswordCache,
      final AuthFailureThrottle authFailureThrottle) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);
  }

  @Override
  public String createAnonymousUser() {

    // The username is only a label. The token type keeps it from being looked up as a real user,
    // so a user who happens to be named "anonymous" is not reachable through it (RPS-986).
    return this.jwtUtils.createProtocolToken(
        UUID.randomUUID(), ANONYMOUS_USER, TIMEOUT_ACCESS_TOKEN, AuthenticationType.ANONYMOUS);
  }

  @Override
  public UserInfo authenticateUser(final @Nullable String authHeader) {

    if (authHeader == null || !isBearerToken(authHeader)) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    return this.authenticatePanelBearer(authHeader);
  }

  @Override
  public String authenticateUserDockerCli(final String authHeader, final List<String> grants) {

    if (!isBasicToken(authHeader)) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    final var credentials = this.getBasicAuthCredentials(removeBasicPrefix(authHeader));

    return this.authenticateWithPersonalAccessToken(credentials, grants)
        .or(() -> this.authenticateWithDeployToken(credentials))
        .or(() -> this.authenticateWithUsernamePassword(credentials, grants))
        .orElseThrow(() -> new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED));
  }

  /**
   * The only public read a caller without credentials gets is on a public repo. A private repo is
   * answered as if it did not exist, so the token endpoint neither hands out an anonymous token for
   * it nor reveals that it is there.
   */
  @Override
  public void authorizePublicRead(final BaseRepoInfo<UUID> repoInfo) {

    if (repoInfo.isPrivateRepo()) {
      throw new ItemNotFoundException(ProtocolErrorCodes.REPO_NOT_FOUND);
    }
  }

  private Credentials getBasicAuthCredentials(final String basicToken) {

    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    return credentials;
  }

  /**
   * Exchanges a personal access token for a {@link AuthenticationType#PERSONAL_ACCESS_TOKEN} JWT of
   * the protocol realm (RPS-1903). The username is ignored, as it is for a deploy token. The grants
   * the JWT carries are what was asked for, narrowed to the scopes of the token and the role of its
   * owner ({@link PatDockerGrants}); a request for repositories that leaves nothing is refused with
   * {@code unAuthorized}, and so is the request of a token that is unknown or expired, which also
   * counts as a failed credential. A request that asks for nothing (a {@code docker login}) is
   * answered. The JWT is read against the token again on every {@code /v2} request.
   *
   * @return empty only when the password is not a personal access token
   */
  private Optional<String> authenticateWithPersonalAccessToken(
      final Credentials credentials, final List<String> grants) {

    if (!TokenType.REPSY_USER_TOKEN.matches(credentials.getPassword())) {
      return Optional.empty();
    }

    final var token = this.authenticateWithPat(credentials.getPassword());
    final var admin =
        this.userTxService.getAuthenticatedUserById(token.userId()).getRole() == UserRole.ADMIN;
    final var narrowed = PatDockerGrants.narrow(grants, token.scopes(), admin);

    if (!grants.isEmpty() && narrowed.isEmpty()) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    this.touchPersonalAccessToken(token);

    final var untilExpiry = Duration.between(Instant.now(), token.expirationDate());
    final var timeout =
        untilExpiry.compareTo(TIMEOUT_ACCESS_TOKEN) < 0 ? untilExpiry : TIMEOUT_ACCESS_TOKEN;

    return Optional.of(
        this.jwtUtils.createProtocolToken(
            token.id(),
            token.username(),
            timeout,
            AuthenticationType.PERSONAL_ACCESS_TOKEN,
            narrowed));
  }

  private Optional<String> authenticateWithUsernamePassword(
      final Credentials credentials, final List<String> grants) {

    final var userInfo = this.authenticateWithPassword(credentials);

    final var token =
        this.jwtUtils.createProtocolToken(
            userInfo.getId(),
            userInfo.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            userInfo.getTokenVersion(),
            grants);

    return Optional.of(token);
  }

  private Optional<String> authenticateWithDeployToken(final Credentials credentials) {

    final var deployTokenOpt = this.deployTokenService.findByToken(credentials.getPassword());

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

  /**
   * Checks that a token from {@code /v2/token} was issued for the operation it is used for, on top
   * of the role check {@link #handleBearerAuth} did (RPS-1434). Only a delete needs it: a token
   * asked for {@code pull} or {@code push,pull} does not delete, even for an administrator. A token
   * that records no grants (a deploy-token or scanner token, or one minted by another protocol's
   * login) keeps the role-based decision.
   *
   * @param name The image, as {@code <repo>/<image>}
   * @throws UnAuthorizedException if the token was not issued for the {@code delete} action of it
   */
  public void authorizeGrantedAccess(
      final String authHeader, final String name, final Permission permission) {

    if (permission != Permission.MANAGE) {
      return;
    }

    final var grants = this.jwtUtils.extractAccess(authHeader, TokenRealm.PROTOCOL);

    if (grants != null && !DockerScopes.allowsDelete(grants, name)) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }
  }

  /**
   * Docker clients exchange Basic credentials for a JWT at {@code /v2/token} first, so a raw
   * deploy-token secret handed to {@code /v2} directly as a Bearer value must be refused, not
   * accepted the way every other protocol accepts it (RPS-1171).
   */
  @Override
  protected boolean acceptsRawDeployTokenBearer() {
    return false;
  }

  /**
   * A deploy token reads and writes, and never manages: a Docker request that needs {@link
   * Permission#MANAGE} (deleting a manifest, RPS-1216) is refused for it, whether or not the token
   * is read-only, so a CI's credential cannot delete what it pushed. The panel offers the same
   * operations to users who manage the repo only. {@code ProtocolAuthService} now refuses MANAGE
   * for every protocol (RPS-1424); this override stays as defence in depth for the delete route.
   */
  @Override
  public void authorizeTokenRequestTokenId(
      final UUID repoId, final UUID tokenId, final Permission permission) {

    if (permission == Permission.MANAGE) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    super.authorizeTokenRequestTokenId(repoId, tokenId, permission);
  }

  /**
   * A scanner token is repo-scoped and carries no user; only Docker issues it, for its
   * vulnerability scanner to re-pull the image it just scanned.
   */
  @Override
  protected void authorizeScannerBearer(
      final String authHeader, final UUID repoId, final Permission permission) {

    if (permission != Permission.READ) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    final var tokenRepoId = this.jwtUtils.extractUserId(authHeader, TokenRealm.PROTOCOL);

    if (!tokenRepoId.equals(repoId)) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }
  }
}
