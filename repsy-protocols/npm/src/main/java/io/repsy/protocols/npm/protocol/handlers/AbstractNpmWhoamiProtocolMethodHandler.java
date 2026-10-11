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
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /{repo}/-/whoami}: answers {@code {"username": "..."}} for the credentials of the
 * request. It needs credentials on a public repository too, so the handler asks the pre-processor
 * to authenticate ({@code requireAuthentication}) and still verifies everything itself.
 */
public abstract class AbstractNpmWhoamiProtocolMethodHandler<ID>
    extends AbstractRoutedProtocolMethodHandler {

  private static final String CHALLENGE = BasicAuthChallenge.REPSY;
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"" + BasicAuthChallenge.REALM + "\", " + CHALLENGE;
  private static final String BEARER_PREFIX = "Bearer ";

  private final NpmIdentityResolver<ID> identityResolver;

  public AbstractNpmWhoamiProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmIdentityResolver<ID> identityResolver,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET).requireAuthentication(true).skipUsagePostProcessor(true),
        new NpmExactPathParser(basePathParser, HttpMethod.GET, "/-/whoami"),
        provider);
    this.identityResolver = identityResolver;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var authHeader = request.getHeader(AUTHORIZATION);

    try {
      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
      final var username = this.identityResolver.resolveUsername(repoInfo, authHeader);

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("username", username));

    } catch (final UnAuthorizedException e) {
      // A TooManyRequestsException is not caught: 429 is the answer, not another login.
      final var challenge =
          authHeader != null && authHeader.startsWith(BEARER_PREFIX) ? BEARER_CHALLENGE : CHALLENGE;

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, challenge)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("error", ProtocolErrorCodes.UN_AUTHORIZED));
    }
  }
}
