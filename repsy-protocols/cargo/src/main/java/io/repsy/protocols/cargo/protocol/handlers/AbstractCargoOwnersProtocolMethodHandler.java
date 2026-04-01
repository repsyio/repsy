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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.cargo.protocol.facade.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

@NullMarked
public abstract class AbstractCargoOwnersProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern OWNERS_PATTERN = Pattern.compile(".*/api/v1/crates/[^/]+/owners$");

  private static final String MSG_OWNERS_UPDATED =
      "Ownership is managed at the repository level in this registry";

  private final PathParser basePathParser;
  private final CargoProtocolFacade<ID> facade;
  private final ObjectMapper objectMapper;

  public AbstractCargoOwnersProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade<ID> facade,
      final ObjectMapper objectMapper,
      final CargoProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    this.objectMapper = objectMapper;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var method = HttpMethod.valueOf(request.getMethod());
      if (!this.getSupportedMethods().contains(method)) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!OWNERS_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      //      final var method = HttpMethod.valueOf(request.getMethod());
      //
      //      if (HttpMethod.GET.equals(method)) {
      //        final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
      //        final var owners = this.facade.listOwners(context, authHeader);
      //        return ResponseEntity.ok()
      //            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      //            .body(Map.of("users", owners));
      //      }

      //      final var body =
      //          this.objectMapper.readValue(request.getInputStream(), CargoOwnersRequest.class);
      //      final var logins =
      // body.users().stream().map(CargoOwnersRequest.UserLogin::login).toList();
      //
      //      if (HttpMethod.PUT.equals(method)) {
      //        this.facade.addOwners(context, logins);
      //      } else {
      //        this.facade.removeOwners(context, logins);
      //      }

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(Map.of("ok", true, "msg", MSG_OWNERS_UPDATED));

    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(CargoErrorResponse.of(e.getMessage()));
    }
  }
}
