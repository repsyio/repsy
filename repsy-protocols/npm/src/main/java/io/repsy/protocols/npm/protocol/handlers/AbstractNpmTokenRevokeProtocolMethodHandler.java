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
package io.repsy.protocols.npm.protocol.handlers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code DELETE /{repo}/-/user/token/{token}}, which {@code npm logout} and {@code pnpm logout}
 * call to revoke the token of the login. It answers {@code {"ok": true}} as the public registry
 * does, and the token is refused from then on (RPS-1361). The request has to carry credentials on a
 * public repository too, so the handler asks the pre-processor to authenticate ({@code
 * requireAuthentication}) and still verifies everything itself, like {@code whoami}.
 */
@NullMarked
public abstract class AbstractNpmTokenRevokeProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final String CHALLENGE = BasicAuthChallenge.REPSY;
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"" + BasicAuthChallenge.REALM + "\", " + CHALLENGE;
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String TOKEN_PATH_REGEX = "/-/user/token/[^/]+";
  private static final String TOKEN_PATH_PREFIX = "/-/user/token/";

  private final PathParser pathParser;
  private final NpmTokenRevoker<ID> tokenRevoker;

  public AbstractNpmTokenRevokeProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmTokenRevoker<ID> tokenRevoker,
      final NpmProtocolProvider provider) {
    this.pathParser = new NpmExactPathParser(basePathParser, HttpMethod.DELETE, TOKEN_PATH_REGEX);
    this.tokenRevoker = tokenRevoker;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.DELETE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "writeOperation", false,
        "requireAuthentication", true,
        "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return this.pathParser;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var authHeader = request.getHeader(AUTHORIZATION);
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var token = relativePath.substring(TOKEN_PATH_PREFIX.length());

    try {
      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

      this.tokenRevoker.revokeToken(repoInfo, authHeader, token);

      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("ok", true));

    } catch (final UnAuthorizedException e) {
      // A TooManyRequestsException is not caught: 429 is the answer, not another login.
      final var challenge =
          authHeader != null && authHeader.startsWith(BEARER_PREFIX) ? BEARER_CHALLENGE : CHALLENGE;

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, challenge)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("error", "unAuthorized"));
    }
  }
}
