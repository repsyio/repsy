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
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
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

@NullMarked
public abstract class AbstractCargoYankProtocolMethodHandler implements ProtocolMethodHandler {

  private static final Pattern YANK_PATTERN =
      Pattern.compile(".*/api/v1/crates/[^/]+/[^/]+/(yank|unyank)$");

  private final PathParser basePathParser;
  private final CargoProtocolFacade facade;

  public AbstractCargoYankProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.DELETE, HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {

    return this::getPathParserCallback;
  }

  private Optional<ProtocolContext> getPathParserCallback(final HttpServletRequest request) {

    final var method = HttpMethod.valueOf(request.getMethod());

    if (!this.getSupportedMethods().contains(method)) {
      return Optional.empty();
    }

    final var parsedPathOpt = this.basePathParser.parse(request);
    if (parsedPathOpt.isEmpty()) {
      return Optional.empty();
    }

    final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

    if (!this.checkEndpointAndMethods(method, relativePath)) {
      return Optional.empty();
    }

    return parsedPathOpt;
  }

  private boolean checkEndpointAndMethods(final HttpMethod method, final String relativePath) {

    if (!YANK_PATTERN.matcher(relativePath).matches()) {
      return false;
    }

    final var isYankPath = relativePath.endsWith("/yank");
    if (isYankPath && !HttpMethod.DELETE.equals(method)) {
      return false;
    }

    return isYankPath || HttpMethod.PUT.equals(method);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
      final var isYank = relativePath.endsWith("/yank");

      if (isYank) {
        this.facade.yank(context);
      } else {
        this.facade.unyank(context);
      }

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(Map.of("ok", true));

    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(CargoErrorResponse.of(e.getMessage()));
    }
  }
}
