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
package io.repsy.os.server.protocols.helm.protocol.pre_processors;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.helm.shared.auth.HelmAuthComponent;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
public class HelmAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String AUTH_BASIC = "Basic ";
  private static final String AUTH_BEARER = "Bearer ";
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";

  private final HelmAuthComponent authComponent;
  private final DeployTokenService deployTokenService;
  private final JwtUtils jwtUtils;
  private final UserTxService userTxService;
  private final HelmProtocolProvider provider;

  @PostConstruct
  public void register() {
    this.provider.registerPreProcessor(this);
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var authHeader = this.authComponent.emulateAuthHeader(request);

    log.debug(
        "[HelmAuth] method={} path={} authHeader={}",
        request.getMethod(),
        request.getRequestURI(),
        authHeader == null ? "null" : authHeader.split(" ")[0]);

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authenticateRequest(authHeader, repoInfo.getId(), properties);

    return ProcessorResult.next();
  }

  private void authenticateRequest(
      final String authHeader, final UUID repoId, final Map<String, Object> properties) {

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    switch (authHeader) {
      case final String h when h.startsWith(AUTH_BASIC) ->
          this.authComponent.handleBasicAuth(h, permission, repoId);
      case final String h when h.startsWith(AUTH_BEARER) ->
          this.handleBearerAuth(h, repoId, permission);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void handleBearerAuth(
      final String authHeader, final UUID repoId, final Permission permission) {

    final var authType = this.jwtUtils.extractAuthenticationType(authHeader);

    if (authType == AuthenticationType.DEPLOY_TOKEN) {
      final var tokenId = this.jwtUtils.extractUserId(authHeader);
      this.authorizeDeployTokenRequest(repoId, tokenId, permission);
      return;
    }

    this.authorizeJWTRequest(authHeader, permission);
  }

  private void authorizeJWTRequest(final String authHeader, final Permission permission) {
    final var userInfo = this.getUserInfoForBearerAuth(authHeader);

    if (userInfo == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authComponent.authorizeUser(userInfo, permission);
  }

  private void authorizeDeployTokenRequest(
      final UUID repoId, final UUID tokenId, final Permission permission) {

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

  private boolean isWritePermissionRequired(final Permission permission) {
    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }

  private @Nullable UserInfo getUserInfoForBearerAuth(final String authHeader) {
    final var username = this.jwtUtils.verifyAndExtractUsername(authHeader);
    return this.userTxService.getUserByUsernameOptional(username).orElse(null);
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor = (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false);

    if (skipPreProcessor) {
      return true;
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    if (this.isWritePermissionRequired(permission)) {
      return false;
    }

    return !repoInfo.isPrivateRepo();
  }
}
