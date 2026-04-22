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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoMeProtocolMethodHandler implements ProtocolMethodHandler {

  private static final String WWW_AUTHENTICATE_VALUE = "Basic realm=\"Repsy Managed Repository\"";

  private final CargoAuthenticator authenticator;

  public AbstractCargoMeProtocolMethodHandler(
      final CargoAuthenticator authenticator, final CargoProtocolProvider provider) {
    this.authenticator = authenticator;
    provider.registerMethodHandler(this);
  }

  protected abstract Optional<ProtocolContext> getProtocolContext(RelativePath relativePath);

  @FunctionalInterface
  public interface CargoAuthenticator {
    String authenticateAndCreateToken(String authHeader);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET, HttpMethod.HEAD);
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

      if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
        return Optional.empty();
      }

      if (!request.getServletPath().endsWith("/me")) {
        return Optional.empty();
      }

      return this.getProtocolContext(new RelativePath("/me"));
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var authHeader = request.getHeader(AUTHORIZATION);

    if (authHeader == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE)
          .build();
    }

    try {
      final var token = this.authenticator.authenticateAndCreateToken(authHeader);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(Map.of("token", token));

    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(CargoErrorResponse.of(e.getMessage()));
    }
  }
}
