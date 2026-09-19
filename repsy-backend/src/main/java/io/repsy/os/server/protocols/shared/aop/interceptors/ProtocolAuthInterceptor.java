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
package io.repsy.os.server.protocols.shared.aop.interceptors;

import static io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils.REPO_INFO;
import static io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils.REPO_PERMISSION_INFO;
import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@NullMarked
@RequiredArgsConstructor
public class ProtocolAuthInterceptor implements HandlerInterceptor {

  private final Map<RepoType, ProtocolAuthService> authComponents;
  private final RepoTxService repoTxService;

  @Override
  public boolean preHandle(
      final HttpServletRequest request, final HttpServletResponse response, final Object handler) {

    if (!(handler instanceof HandlerMethod methodHandler)) {
      return true;
    }

    if (methodHandler.getMethodAnnotation(RepoOperation.class) == null) {
      return true;
    }

    final var repoName = ResolverUtils.extractRepoInfo(this.getUriVariables(request));

    if (repoName == null) {
      this.authenticateUser(request, this.getPermission(methodHandler));
      return true;
    }

    final var repoInfoOpt = this.repoTxService.findRepoByName(repoName);

    if (repoInfoOpt.isEmpty()) {
      throw this.authorizeUnknownRepo(methodHandler, request);
    }

    final var repoInfo = repoInfoOpt.get();
    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    final var authComponent = this.authComponents.get(repoInfo.getType());

    request.setAttribute(REPO_INFO, repoInfo);

    // Authorize before checking the scope, so a caller who may not access the repo cannot learn
    // its type from a scope mismatch either.
    this.putRepoPermission(authComponent, methodHandler, repoInfo, request, authHeader);
    this.checkRepoScope(methodHandler, repoInfo);

    return true;
  }

  /**
   * Handles a request for a repo that does not exist. The caller is authenticated and authorized as
   * if the repo were a private one, so anyone who could not use an existing private repo gets the
   * same response as for one that is missing, and only a caller allowed to see it learns that it
   * does not exist.
   *
   * @return the exception to throw once the caller has passed the checks
   */
  private ItemNotFoundException authorizeUnknownRepo(
      final HandlerMethod methodHandler, final HttpServletRequest request) {

    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var authComponent = this.getUnknownRepoAuthComponent(methodHandler);
    final var userInfo = authComponent.authenticateUser(authHeader);

    authComponent.authorizeUser(userInfo, this.getPermission(methodHandler));

    return new ItemNotFoundException("repoNotFound");
  }

  private ProtocolAuthService getUnknownRepoAuthComponent(final HandlerMethod methodHandler) {

    // Only Docker authenticates differently. Use the endpoint's own type where it has one, and any
    // other protocol's generic authentication otherwise.
    final var scopeType = RepoType.fromString(this.getRepoScope(methodHandler).name());

    return this.authComponents.get(scopeType.orElse(RepoType.MAVEN));
  }

  private void authenticateUser(final HttpServletRequest request, final Permission permission) {

    final var uriVariables = this.getUriVariables(request);
    final var repoTypeOpt = ResolverUtils.extractRepoType(uriVariables);

    if (repoTypeOpt.isEmpty()) {
      throw new ItemNotFoundException("repoTypeNotFound");
    }

    final var authComponent = this.authComponents.get(repoTypeOpt.get());
    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

    if (authHeader == null) {
      authComponent.authorizeUser(null, permission);
      return;
    }

    final var userInfo = authComponent.authenticateUser(authHeader);
    authComponent.authorizeUser(userInfo, permission);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> getUriVariables(final HttpServletRequest request) {

    final var uriVariables =
        (Map<String, String>) request.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);

    if (uriVariables == null) {
      throw new IllegalArgumentException("repoName should not be empty!");
    }

    return uriVariables;
  }

  private void putRepoPermission(
      final ProtocolAuthService authComponent,
      final HandlerMethod handler,
      final RepoInfo repoInfo,
      final HttpServletRequest request,
      final String authHeader) {

    final var permission = this.getPermission(handler);
    final var permissionInfo = authComponent.authorizeUserRequest(repoInfo, authHeader, permission);

    request.setAttribute(REPO_PERMISSION_INFO, permissionInfo);
  }

  private void checkRepoScope(final HandlerMethod methodHandler, final RepoInfo repoInfo) {

    final var scope = this.getRepoScope(methodHandler);

    if (scope == RepoScope.ALL) {
      return;
    }

    final var typeOpt = RepoType.fromString(scope.name());

    if (typeOpt.isEmpty()) {
      throw new ItemNotFoundException("repoTypeNotFound");
    }

    if (repoInfo.getType() == typeOpt.get()) {
      return;
    }

    throw new BadRequestException("repoScopeNotMatched");
  }

  private RepoScope getRepoScope(final HandlerMethod methodHandler) {

    final var annotation = methodHandler.getMethodAnnotation(RepoOperation.class);

    if (annotation == null) {
      return RepoScope.ALL;
    }

    return annotation.scope();
  }

  private Permission getPermission(final HandlerMethod methodHandler) {

    final var annotation = methodHandler.getMethodAnnotation(RepoOperation.class);

    if (annotation == null) {
      return Permission.READ;
    }

    return annotation.permission();
  }
}
