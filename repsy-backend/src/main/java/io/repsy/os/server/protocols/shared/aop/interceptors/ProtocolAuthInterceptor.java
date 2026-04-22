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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.protocols.shared.aop.utils.ResolverUtils;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
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

    this.checkRepoOperationAnnotationExists(methodHandler);

    final var repoInfoOpt = this.getRepoInfo(request);

    if (repoInfoOpt.isEmpty()) {
      this.authenticateUser(request);
      return true;
    }

    final var repoInfo = repoInfoOpt.get();
    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    final var authComponent = this.authComponents.get(repoInfo.getType());

    this.checkRepoScope(methodHandler, repoInfo);
    this.putRepoPermission(authComponent, methodHandler, repoInfo, request, authHeader);

    return true;
  }

  private void authenticateUser(final HttpServletRequest request) {

    final var uriVariables = this.getUriVariables(request);
    final var repoTypeOpt = ResolverUtils.extractRepoType(uriVariables);

    if (repoTypeOpt.isEmpty()) {
      throw new ItemNotFoundException("repoTypeNotFound");
    }

    final var authComponent = this.authComponents.get(repoTypeOpt.get());
    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

    authComponent.authenticateUser(authHeader);
  }

  private Optional<RepoInfo> getRepoInfo(final HttpServletRequest request) {

    final var uriVariables = this.getUriVariables(request);
    final var repoName = ResolverUtils.extractRepoInfo(uriVariables);

    if (repoName == null) {
      return Optional.empty();
    }

    final var repoInfo = this.repoTxService.getRepoByName(repoName);

    request.setAttribute(REPO_INFO, repoInfo);

    return Optional.of(repoInfo);
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

  private void checkRepoOperationAnnotationExists(final HandlerMethod handler) {

    final var annotation = handler.getMethodAnnotation(RepoOperation.class);

    if (annotation == null) {
      throw new IllegalArgumentException("RepoOperation Annotations is missing.");
    }
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

    throw new IllegalArgumentException("repoScopeNotMatched");
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
