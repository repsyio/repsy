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
package io.repsy.protocols.cargo.protocol.handlers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoMeProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  private final CargoAuthenticator authenticator;

  public AbstractCargoMeProtocolMethodHandler(
      final CargoAuthenticator authenticator, final CargoProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.NONE, HttpMethod.GET, HttpMethod.HEAD)
            .skipHeaderPreProcessor(true)
            .skipUsagePostProcessor(true)
            .skipPreProcessor(true),
        provider);
    this.authenticator = authenticator;
  }

  protected abstract Optional<ProtocolContext> findProtocolContext(RelativePath relativePath);

  @FunctionalInterface
  public interface CargoAuthenticator {
    String authenticateAndCreateToken(String authHeader);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request) && request.getServletPath().endsWith("/me");
  }

  @Override
  protected Optional<ProtocolContext> parse(final HttpServletRequest request) {
    return this.findProtocolContext(new RelativePath("/me"));
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var authHeader = request.getHeader(AUTHORIZATION);

    if (authHeader == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .build();
    }

    try {
      final var token = this.authenticator.authenticateAndCreateToken(authHeader);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(Map.of("token", token));

    } catch (final TooManyRequestsException e) {
      // 429 with Retry-After is the answer, not a 401 that makes the client log in again.
      throw e;
    } catch (final UnAuthorizedException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(ProtocolErrorBody.withDetail(e.getMessage()));
    }
  }
}
