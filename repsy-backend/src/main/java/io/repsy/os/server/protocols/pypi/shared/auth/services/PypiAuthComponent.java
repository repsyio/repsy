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
package io.repsy.os.server.protocols.pypi.shared.auth.services;

import static io.repsy.os.shared.auth.utils.AuthUtils.checkPassword;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromAuthHeader;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;
import static io.repsy.os.shared.repo.dtos.RepoPermissionInfo.buildPublicReadOnlyPermissions;
import static io.repsy.os.shared.repo.dtos.RepoPermissionInfo.buildRepoPermissionInfo;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class PypiAuthComponent extends ProtocolAuthService {

  public PypiAuthComponent(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService) {

    super(userTxService, jwtUtils, deployTokenService);
  }

  @Override
  public RepoPermissionInfo authorizeUserRequest(
      final RepoInfo repoInfo, final @Nullable String authHeader, final Permission permission) {

    if (authHeader != null) {
      return this.authorizeUser(repoInfo, authHeader, permission);
    }

    // Auth header is null
    if (super.isPublicReadAccess(repoInfo, permission)) {
      return buildPublicReadOnlyPermissions(repoInfo);
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  public void handleBasicAuthWithToken(
      final String authHeader, final Permission permission, final UUID repoId) {

    final var basicToken = removeBasicPrefix(authHeader);

    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (super.tryAuthorizeWithDeployToken(repoId, credentials.getPassword(), permission)) {
      return;
    }

    super.handleUsernamePasswordAuthentication(credentials, permission);
  }

  private RepoPermissionInfo authorizeUser(
      final RepoInfo repoInfo, final String authHeader, final Permission permission) {

    final var pair = this.authenticateRepoUser(authHeader, repoInfo);

    final var userInfo = pair.getFirst().orElse(null);

    final var permissionInfo = super.authorizeUser(userInfo, permission);

    return buildRepoPermissionInfo(repoInfo, permissionInfo);
  }

  private Pair<Optional<UserInfo>, Boolean> authenticateRepoUser(
      final String authHeader, final RepoInfo repoInfo) {

    return switch (authHeader) {
      case final String header when isBasicToken(header) ->
          this.authenticateWithBasicAuth(header, repoInfo);
      case final String header when isBearerToken(header) ->
          this.authenticateWithBearerAuth(authHeader, repoInfo);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    };
  }

  private Pair<Optional<UserInfo>, Boolean> authenticateWithBasicAuth(
      final String authHeader, final RepoInfo repoInfo) {

    final var creds = extractCredentialsFromAuthHeader(authHeader);

    if (creds == null) {
      return this.handleNullCredentials(repoInfo);
    }

    final var userInfo = this.userTxService.getUserByUsername(creds.getUsername());

    if (!checkPassword(userInfo.getHash(), userInfo.getSalt(), creds.getPassword())) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return Pair.of(Optional.of(userInfo), false);
  }

  private Pair<Optional<UserInfo>, Boolean> authenticateWithBearerAuth(
      final String authHeader, final RepoInfo repoInfo) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);

    final var userInfoOpt = this.userTxService.getUserByUsernameOptional(username);

    if (userInfoOpt.isEmpty() && repoInfo.isPrivateRepo()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return Pair.of(userInfoOpt, false);
  }

  private Pair<Optional<UserInfo>, Boolean> handleNullCredentials(final RepoInfo repoInfo) {

    if (repoInfo.isPrivateRepo()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    return Pair.of(Optional.empty(), false);
  }
}
