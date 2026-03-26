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
package io.repsy.os.server.shared.auth;

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;
import static io.repsy.os.shared.auth.utils.AuthUtils.checkPassword;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromAuthHeader;
import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.isBearerToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;
import static io.repsy.os.shared.repo.dtos.RepoPermissionInfo.buildPublicReadOnlyPermissions;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.PermissionInfo;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Credentials;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProtocolAuthService {

  protected final @NonNull UserTxService userTxService;
  protected final @NonNull JwtUtils jwtUtils;
  protected final @NonNull DeployTokenService deployTokenService;

  public @Nullable String emulateAuthHeader(final @NonNull HttpServletRequest request) {

    final var tokenParameter = request.getParameter("token");

    if (tokenParameter != null) {
      return AUTH_BEARER + tokenParameter;
    }

    return request.getHeader(HttpHeaders.AUTHORIZATION);
  }

  public void handleBearerAuth(
      final @NonNull String authHeader,
      final @NonNull UUID repoId,
      final @NonNull Permission permission) {

    final var bearerToken = authHeader.substring(AUTH_BEARER.length());

    if (this.tryAuthorizeWithDeployToken(repoId, bearerToken, permission)) {
      return;
    }

    this.authorizeJWTRequest(authHeader, permission);
  }

  public void handleBasicAuth(
      final @NonNull String authHeader,
      final @NonNull Permission permission,
      final @NonNull UUID repoId) {

    final var basicToken = removeBasicPrefix(authHeader);
    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (this.tryAuthorizeWithDeployToken(repoId, credentials.getPassword(), permission)) {
      return;
    }

    this.handleUsernamePasswordAuthentication(credentials, permission);
  }

  public @NonNull PermissionInfo authorizeUser(
      final @Nullable UserInfo userInfo, final @NonNull Permission permission) {

    if (userInfo == null) {
      return PermissionInfo.builder().canRead(true).canWrite(false).canManage(false).build();
    }

    this.checkPermission(userInfo, permission);

    final var isAdmin = userInfo.getRole() == UserRole.ADMIN;

    return PermissionInfo.builder().canRead(true).canWrite(true).canManage(isAdmin).build();
  }

  public @NonNull RepoPermissionInfo authorizeUserRequest(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String authHeader,
      final @NonNull Permission permission) {

    if (authHeader != null) {
      return this.authorizeRepoUser(repoInfo, authHeader, permission);
    }

    // Auth header is null
    if (this.isPublicReadAccess(repoInfo, permission)) {
      return buildPublicReadOnlyPermissions(repoInfo);
    }

    throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
  }

  public @NonNull UserInfo authenticateUser(final @NonNull String authHeader) {

    return switch (authHeader) {
      case final String header when isBasicToken(header) -> this.authenticateWithBasic(header);
      case final String header when isBearerToken(header) -> this.authenticateWithBearer(header);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    };
  }

  protected boolean isPublicReadAccess(
      final @NonNull RepoInfo repoInfo, final @NonNull Permission permission) {

    return !repoInfo.isPrivateRepo() && Permission.READ.equals(permission);
  }

  protected boolean tryAuthorizeWithDeployToken(
      final @NonNull UUID repoId,
      final @NonNull String token,
      final @NonNull Permission permission) {

    final var deployTokenInfoOpt = this.deployTokenService.findByRepoIdAndToken(repoId, token);

    if (deployTokenInfoOpt.isEmpty()) {
      return false;
    }

    this.authorizeDeployTokenRequest(deployTokenInfoOpt.get(), permission);

    return true;
  }

  public void authorizeTokenRequestTokenId(
      final @NonNull UUID repoId,
      final @NonNull UUID tokenId,
      final @NonNull Permission permission) {

    final var deployTokenInfo = this.deployTokenService.findByRepoIdAndTokenId(repoId, tokenId);

    if (deployTokenInfo.isEmpty()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var deployToken = deployTokenInfo.get();

    if (deployToken.isExpired()) {
      throw new UnAuthorizedException("deployTokenExpired");
    }

    if (this.isWritePermissionRequired(permission) && deployToken.isReadOnly()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.deployTokenService.updateLastUsedTime(deployToken.getId());
  }

  protected boolean isWritePermissionRequired(final @NonNull Permission permission) {

    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }

  private void authorizeDeployTokenRequest(
      final @NonNull DeployTokenInfo deployTokenInfo, final @NonNull Permission permission) {

    if (deployTokenInfo.isExpired()) {
      throw new UnAuthorizedException("deployTokenExpired");
    }

    this.authorizeDeployToken(deployTokenInfo, permission);
    this.deployTokenService.updateLastUsedTime(deployTokenInfo.getId());
  }

  private void authorizeDeployToken(
      final @NonNull DeployTokenInfo deployTokenInfo, final @NonNull Permission permission) {

    if (this.isWritePermissionRequired(permission) && deployTokenInfo.isReadOnly()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void authorizeJWTRequest(
      final @NonNull String authHeader, final @NonNull Permission permission) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);
    final var userInfo = this.userTxService.getUserByUsername(username);

    this.authorizeUser(userInfo, permission);
  }

  protected void handleUsernamePasswordAuthentication(
      final @NonNull Credentials credentials, final @NonNull Permission permission) {

    final var username = credentials.getUsername();

    if (username == null || !this.userTxService.existsByUsername(username)) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var userInfo = this.userTxService.getUserByUsername(username);

    if (!checkPassword(userInfo.getHash(), userInfo.getSalt(), credentials.getPassword())) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authorizeUser(userInfo, permission);
  }

  private @NonNull RepoPermissionInfo authorizeRepoUser(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String authHeader,
      final @NonNull Permission permission) {

    final var userInfo = this.authenticateUser(authHeader);
    final var permissionInfo = this.authorizeUser(userInfo, permission);

    return RepoPermissionInfo.buildRepoPermissionInfo(repoInfo, permissionInfo);
  }

  private @NonNull UserInfo authenticateWithBasic(final @NonNull String authHeader) {

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

  private @NonNull UserInfo authenticateWithBearer(final @NonNull String authHeader) {

    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);

    return this.userTxService.getUserByUsername(username);
  }

  private void checkPermission(
      final @NonNull UserInfo userInfo, final @NonNull Permission permission) {

    switch (permission) {
      case READ, WRITE -> {
        /* All authenticated users have read/write permission */
      }
      case MANAGE -> this.checkManage(userInfo);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void checkManage(final @NonNull UserInfo userInfo) {

    if (userInfo.getRole() != UserRole.ADMIN) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }
}
