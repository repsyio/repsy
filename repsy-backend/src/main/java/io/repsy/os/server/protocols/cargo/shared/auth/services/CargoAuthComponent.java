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
import static io.repsy.os.shared.auth.utils.AuthUtils.checkPassword;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromAuthHeader;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;
import static io.repsy.protocols.shared.repo.dtos.RepoType.CARGO;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
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
public class CargoAuthComponent extends ProtocolAuthService {

  public CargoAuthComponent(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService) {

    super(userTxService, jwtUtils, deployTokenService);
  }

  public void authorizeRequest(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String authHeader,
      final Permission permission) {

    final var authRequired = repoInfo.isPrivateRepo() || this.isWritePermissionRequired(permission);

    if (!authRequired) {
      if (authHeader != null) {
        this.processAuthRequest(repoInfo, authHeader, permission);
      }
      return;
    }

    if (authHeader == null) {
      if (repoInfo.isPrivateRepo()) {
        throw new ItemNotFoundException("repoNotFound");
      }
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.processAuthRequest(repoInfo, authHeader, permission);
  }

  public String authenticateAndCreateToken(final String authHeader) {

    if (isBasicToken(authHeader)) {
      return this.authenticateBasicAndCreateToken(authHeader);
    }

    if (isBearerToken(authHeader)) {
      final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);
      final var userInfo = this.userTxService.getUserByUsername(username);
      return this.jwtUtils.createTokenWithDuration(
          userInfo.getId(), userInfo.getUsername(), TIMEOUT_ACCESS_TOKEN);
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  private void processAuthRequest(
      final BaseRepoInfo<UUID> repoInfo, final String authHeader, final Permission permission) {

    // Cargo CLI sends tokens without a prefix — normalise to Bearer for uniform handling.
    final var normalized =
        (isBasicToken(authHeader) || isBearerToken(authHeader))
            ? authHeader
            : "Bearer " + authHeader;

    final UserInfo userInfo;

    if (isBasicToken(normalized)) {
      userInfo = this.resolveBasicAuthUser(normalized);
    } else {
      userInfo = this.resolveBearerAuthUser(repoInfo, normalized);
    }

    this.authorizeUser(userInfo, permission);
  }

  private UserInfo resolveBasicAuthUser(final String authHeader) {

    final var credentials = extractCredentialsFromAuthHeader(authHeader);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var userInfo = this.userTxService.getUserByUsername(credentials.getUsername());

    if (!checkPassword(userInfo.getHash(), userInfo.getSalt(), credentials.getPassword())) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return userInfo;
  }

  private @Nullable UserInfo resolveBearerAuthUser(
      final BaseRepoInfo<UUID> repoInfo, final String authHeader) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);
    final var userInfoOpt = this.userTxService.getUserByUsernameOptional(username);

    if (userInfoOpt.isEmpty() && repoInfo.isPrivateRepo()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return userInfoOpt.orElse(null);
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
        this.jwtUtils.createTokenWithDuration(
            deployTokenOpt.get().getId(),
            credentials.getUsername(),
            TIMEOUT_ACCESS_TOKEN,
            AuthenticationType.DEPLOY_TOKEN);

    return Optional.of(token);
  }

  private Optional<String> authenticateWithUsernamePassword(final Credentials credentials) {

    final var userInfo = this.userTxService.getUserByUsername(credentials.getUsername());

    if (!checkPassword(userInfo.getHash(), userInfo.getSalt(), credentials.getPassword())) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var token =
        this.jwtUtils.createTokenWithDuration(
            userInfo.getId(), userInfo.getUsername(), TIMEOUT_ACCESS_TOKEN);

    return Optional.of(token);
  }
}
