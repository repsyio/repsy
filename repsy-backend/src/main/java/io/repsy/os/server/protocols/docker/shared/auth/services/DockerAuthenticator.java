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
import static io.repsy.os.shared.auth.utils.AuthUtils.personalAccessTokenSecretOf;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.token.dtos.TokenType;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.docker.protocol.parser.DockerScopes;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.auth.AuthFailureThrottle;
import io.repsy.protocols.shared.auth.VerifiedPasswordCache;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class DockerAuthenticator extends ProtocolAuthService implements DockerAuthService<UUID> {

  private static final String ANONYMOUS_USER = "anonymous";

  private final RepoTxService repoTxService;

  public DockerAuthenticator(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService,
      final VerifiedPasswordCache verifiedPasswordCache,
      final AuthFailureThrottle authFailureThrottle,
      final RepoTxService repoTxService) {

    super(userTxService, jwtUtils, deployTokenService, verifiedPasswordCache, authFailureThrottle);
    this.repoTxService = repoTxService;
  }

  /**
   * The token of a caller without credentials. It is granted {@code pull} of what was asked for and
   * nothing more (RPS-2216): a push or a delete is never anonymous, whatever the client asked for.
   */
  @Override
  public String createAnonymousUser(final List<String> grants) {

    final var pullOnly = new PatDockerGrants.Rights(true, false, false);

    // The username is only a label. The token type keeps it from being looked up as a real user,
    // so a user who happens to be named "anonymous" is not reachable through it (RPS-986).
    return this.jwtUtils.createProtocolToken(
        UUID.randomUUID(),
        ANONYMOUS_USER,
        TIMEOUT_ACCESS_TOKEN,
        AuthenticationType.ANONYMOUS,
        PatDockerGrants.narrow(grants, _ -> pullOnly));
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
        .or(() -> this.authenticateWithDeployToken(credentials, grants))
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
   * owner ({@link PatDockerGrants}); a request for repositories that leaves nothing is answered
   * with a token whose access list is empty, as the Docker token specification has it (RPS-2216),
   * so that {@code docker login} keeps working and the registry refuses the operation. A token that
   * is unknown or expired is refused with {@code unAuthorized}, which also counts as a failed
   * credential. The JWT is read against the token again on every {@code /v2} request.
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

    // Every user may read and write every repo (OS has no per-repo roles); only an ADMIN manages.
    final var rights = new PatDockerGrants.Rights(true, true, userInfo.getRole() == UserRole.ADMIN);

    final var token =
        this.jwtUtils.createProtocolToken(
            userInfo.getId(),
            userInfo.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            userInfo.getTokenVersion(),
            PatDockerGrants.narrow(grants, _ -> rights));

    return Optional.of(token);
  }

  /**
   * Exchanges a deploy token for a JWT whose access list is what the token may do in the images
   * asked for: it is bound to one repo, reads there, writes unless it is read-only, and never
   * manages (RPS-2216). An image of another repo, or of none, is left out.
   */
  private Optional<String> authenticateWithDeployToken(
      final Credentials credentials, final List<String> grants) {

    final var deployTokenOpt = this.deployTokenService.findByToken(credentials.getPassword());

    if (deployTokenOpt.isEmpty()) {
      return Optional.empty();
    }

    if (deployTokenOpt.get().isExpired()) {
      throw new UnAuthorizedException(ProtocolErrorCodes.DEPLOY_TOKEN_EXPIRED);
    }

    this.deployTokenService.updateLastUsedTime(deployTokenOpt.get().getId());

    final var deployToken = deployTokenOpt.get();

    final var token =
        this.jwtUtils.createProtocolToken(
            deployToken.getId(),
            credentials.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            AuthenticationType.DEPLOY_TOKEN,
            PatDockerGrants.narrow(grants, name -> this.deployTokenRights(deployToken, name)));

    return Optional.of(token);
  }

  private PatDockerGrants.Rights deployTokenRights(
      final DeployTokenInfo deployToken, final String name) {

    final var separator = name.indexOf('/');

    if (separator <= 0) {
      return PatDockerGrants.Rights.NONE;
    }

    final var repo =
        this.repoTxService.findRepoByNameAndType(name.substring(0, separator), RepoType.DOCKER);

    if (repo.isEmpty()
        || this.deployTokenService
            .findByRepoIdAndTokenId(repo.get().getStorageKey(), deployToken.getId())
            .isEmpty()) {
      return PatDockerGrants.Rights.NONE;
    }

    return new PatDockerGrants.Rights(true, !deployToken.isReadOnly(), false);
  }

  /**
   * Checks that a token from {@code /v2/token} was issued for the operation it is used for, on top
   * of the role check {@link #handleBearerAuth} did (RPS-1434, RPS-2216): {@code pull} for a read,
   * {@code push} for a write and {@code delete} for a delete, in exactly the image the request
   * addresses. A token that records no grants (a scanner token, or one minted before the grants
   * were recorded for every credential) keeps the role-based decision.
   *
   * @param name The image, as {@code <repo>/<image>}
   * @throws UnAuthorizedException if the token was not issued for the action in that image
   */
  public void authorizeGrantedAccess(
      final String authHeader, final String name, final Permission permission) {

    // A personal access token presented as the Bearer value itself is no JWT and carries no
    // grants: it is authorized as the token, by its scopes. Only a delete reads it as a JWT.
    if (permission != Permission.MANAGE && personalAccessTokenSecretOf(authHeader) != null) {
      return;
    }

    final var grants = this.jwtUtils.extractAccess(authHeader, TokenRealm.PROTOCOL);

    if (grants == null) {
      return;
    }

    if (!DockerScopes.allowsPermission(grants, name, permission)) {
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
