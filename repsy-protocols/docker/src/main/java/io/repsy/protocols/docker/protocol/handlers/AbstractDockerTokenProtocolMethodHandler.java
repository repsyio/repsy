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
package io.repsy.protocols.docker.protocol.handlers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.auth.dtos.LoginResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractDockerTokenProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  public static final TemporalAmount DEFAULT_TIMEOUT_ACCESS_TOKEN =
      Duration.of(30, ChronoUnit.MINUTES);

  private static final String WWW_AUTHENTICATE_VALUE = "Basic realm=\"Repsy Managed Repository\"";

  private final DockerScopeParser<ID> scopeParser;
  private final DockerAuthService<ID> authService;

  public AbstractDockerTokenProtocolMethodHandler(
      final DockerAuthService<ID> authService,
      final DockerScopeParser<ID> scopeParser,
      final DockerProtocolProvider provider) {
    this.authService = authService;
    this.scopeParser = scopeParser;

    provider.registerMethodHandler(this);
  }

  protected abstract Optional<ProtocolContext> getProtocolContext(RelativePath relativePath);

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET, HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.NONE,
        "skipHeaderPreProcessor", true,
        "skipUsagePostProcessor", true,
        "skipPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var method = HttpMethod.valueOf(request.getMethod());
      if (!HttpMethod.GET.equals(method) && !HttpMethod.POST.equals(method)) {
        return Optional.empty();
      }

      final var path = request.getServletPath();
      if (!"/v2/token".equals(path)) {
        return Optional.empty();
      }

      final var relativePath = new RelativePath("/token");

      return this.getProtocolContext(relativePath);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    try {
      final var authHeader = request.getHeader(AUTHORIZATION);
      final var scope = request.getParameter("scope");

      if (authHeader == null) {
        return this.handleUnauthenticatedRequest(scope);
      }

      final var sessionToken = this.authService.authenticateUserDockerCli(authHeader);
      final var loginResponse = this.createLoginResponse(sessionToken);

      return ResponseEntity.ok(loginResponse);
    } catch (final Exception _) {
      return this.buildUnauthorizedResponse();
    }
  }

  private ResponseEntity<Object> handleUnauthenticatedRequest(final @Nullable String scope) {

    if (scope == null || scope.trim().isEmpty()) {
      return this.buildUnauthorizedResponse();
    }

    if (this.requiresAuthentication(scope)) {
      return this.buildUnauthorizedResponse();
    }

    return this.handlePublicReadRequest(scope);
  }

  private ResponseEntity<Object> handlePublicReadRequest(final String scope) {
    final var repoInfoOpt = this.scopeParser.getRepoInfoByScope(scope);

    if (repoInfoOpt.isEmpty()) {

      if (this.isWildcardScope(scope)) {
        final var sessionToken = this.authService.createAnonymousUser();
        return ResponseEntity.ok(this.createLoginResponse(sessionToken));
      }

      throw new UnAuthorizedException("unAuthorized");
    }

    final var repoInfo = repoInfoOpt.get();

    this.authService.authorizeRequest(repoInfo, null, Permission.READ, false);

    final var sessionToken = this.authService.createAnonymousUser();

    return ResponseEntity.ok(this.createLoginResponse(sessionToken));
  }

  private boolean requiresAuthentication(final String scope) {
    final var lowerScope = scope.toLowerCase(Locale.getDefault());
    return lowerScope.contains("push") || lowerScope.contains(",*") || lowerScope.endsWith(":*");
  }

  private ResponseEntity<Object> buildUnauthorizedResponse() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE)
        .build();
  }

  private boolean isWildcardScope(final String scope) {
    return scope.contains(":*:");
  }

  private LoginResponse createLoginResponse(final String sessionToken) {
    return LoginResponse.builder()
        .token(sessionToken)
        .accessToken(sessionToken)
        .expiresIn(DEFAULT_TIMEOUT_ACCESS_TOKEN.get(ChronoUnit.SECONDS))
        .issuedAt(Instant.now())
        .build();
  }
}
