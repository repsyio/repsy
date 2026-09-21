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
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class DockerAuthComponent extends ProtocolAuthService implements DockerAuthService<UUID> {

  private static final String ANONYMOUS_USER = "anonymous";

  public DockerAuthComponent(
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
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader, TokenRealm.PANEL);

    return this.userTxService.getAuthenticatedUserByUsername(username);
  }

  @Override
  public String authenticateUserDockerCli(final String authHeader) {

    if (!isBasicToken(authHeader)) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var credentials = this.getBasicAuthCredentials(removeBasicPrefix(authHeader));

    return this.authenticateWithDeployToken(credentials)
        .or(() -> this.authenticateWithUsernamePassword(credentials))
        .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
  }

  /**
   * The only public read a caller without credentials gets is on a public repo. A private repo is
   * answered as if it did not exist, so the token endpoint neither hands out an anonymous token for
   * it nor reveals that it is there.
   */
  @Override
  public void authorizePublicRead(final BaseRepoInfo<UUID> repoInfo) {

    if (repoInfo.isPrivateRepo()) {
      throw new ItemNotFoundException("repoNotFound");
    }
  }

  private @Nullable AuthenticationType extractAuthenticationTypeSafely(final String authHeader) {

    try {
      return this.jwtUtils.extractAuthenticationType(authHeader, TokenRealm.PROTOCOL);
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  private Credentials getBasicAuthCredentials(final String basicToken) {

    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return credentials;
  }

  private Optional<String> authenticateWithUsernamePassword(final Credentials credentials) {

    final var userInfo = this.authenticateWithPassword(credentials);

    final var token =
        this.jwtUtils.createProtocolToken(
            userInfo.getId(), userInfo.getUsername(), TIMEOUT_ACCESS_TOKEN);

    return Optional.of(token);
  }

  private Optional<String> authenticateWithDeployToken(final Credentials credentials) {

    final var deployTokenOpt = this.deployTokenService.findByToken(credentials.getPassword());

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

  @Override
  public void handleBearerAuth(
      final String authHeader, final UUID repoId, final Permission permission) {

    final var authType = this.extractAuthenticationTypeSafely(authHeader);

    if (authType == AuthenticationType.DEPLOY_TOKEN) {
      this.authorizeTokenRequestTokenId(
          repoId, this.jwtUtils.extractUserId(authHeader, TokenRealm.PROTOCOL), permission);
      return;
    }

    if (authType == AuthenticationType.DOCKER_SCAN) {
      this.authorizeScannerToken(authHeader, repoId, permission);
      return;
    }

    // Public reads never reach this method, they skip authentication. Anything that does (a write
    // or a private repo) needs a real credential, which an anonymous token is not.
    if (authType == AuthenticationType.ANONYMOUS) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader, TokenRealm.PROTOCOL);
    final var userInfo = this.userTxService.getUserByUsernameOptional(username).orElse(null);

    // A valid token of a user who no longer exists is an authentication failure, never a downgrade
    // to an anonymous caller (RPS-962, RPS-1027).
    if (userInfo == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authorizeUser(userInfo, permission);
  }

  private void authorizeScannerToken(
      final String authHeader, final UUID repoId, final Permission permission) {

    if (permission != Permission.READ) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var tokenRepoId = this.jwtUtils.extractUserId(authHeader, TokenRealm.PROTOCOL);

    if (!tokenRepoId.equals(repoId)) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }
}
