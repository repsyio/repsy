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
package io.repsy.protocols.nuget.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.dtos.NuGetErrorResponse;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetPublishProtocolMethodHandler implements ProtocolMethodHandler {

  private final PathParser basePathParser;
  private final NuGetProtocolFacade facade;

  public AbstractNuGetPublishProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider) {

    this.basePathParser = basePathParser;
    this.facade = facade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.name().equals(request.getMethod())) {
        return Optional.empty();
      }

      final var path = request.getServletPath();
      final var normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      if (!normalizedPath.endsWith("/v3/package")) {
        return Optional.empty();
      }

      return this.basePathParser.parse(request);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {

      final var contentType = request.getContentType();

      if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(NuGetErrorResponse.of("Content-Type must be multipart/form-data"));
      }

      final var parts = request.getParts();

      if (parts == null || parts.isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(NuGetErrorResponse.of("Missing package content."));
      }

      final var nupkgPart = parts.iterator().next();

      try (final var inputStream = nupkgPart.getInputStream()) {
        this.facade.publish(context, inputStream);
      }

      return ResponseEntity.status(HttpStatus.CREATED).build();

    } catch (final IllegalArgumentException e) {
      log.debug("NuGet validation error: {}", e.getMessage());
      return ResponseEntity.badRequest().body(NuGetErrorResponse.of(e.getMessage()));
    } catch (final Exception e) {
      log.error("NuGet publish failed: ", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(NuGetErrorResponse.of("Publish failed"));
    }
  }
}
