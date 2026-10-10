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
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@NullMarked
public abstract class AbstractNuGetPublishProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  public AbstractNuGetPublishProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider) {
    super(HandlerRoute.write(HttpMethod.PUT), basePathParser, facade, provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    if (!super.accepts(method, request)) {
      return false;
    }

    final var path = request.getServletPath();
    final var normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

    return normalizedPath.endsWith("/v3/package");
  }

  /**
   * Publishes the package. A validation failure is a 400 and an existing version a 409, in NuGet's
   * error body. Any other failure (a storage outage, a database failure) is left to propagate to
   * {@code ProtocolErrorAdvice}, which answers 500, or 503 with {@code Retry-After} for a storage
   * outage, the same as on every other format (RPS-2104, RPS-2060).
   */
  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException, ServletException {

    try {
      this.validateRequest(request);

      final var parts = request.getParts();

      if (parts == null || parts.isEmpty()) {
        return this.createErrorResponse(HttpStatus.BAD_REQUEST, "Missing package content.");
      }

      this.processPublishing(context, findPackagePart(parts));
      return ResponseEntity.status(HttpStatus.CREATED).build();

    } catch (final IllegalArgumentException e) {
      return this.handleException(e.getMessage());
    } catch (final ResponseStatusException e) {
      return this.handleConflict(e);
    }
  }

  private void validateRequest(final HttpServletRequest request) {

    final var contentType = request.getContentType();

    if (contentType == null
        || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
      throw new IllegalArgumentException("Content-Type must be multipart/form-data");
    }
  }

  private static Part findPackagePart(final Collection<Part> parts) {

    return parts.stream()
        .filter(p -> "package".equalsIgnoreCase(p.getName()))
        .findFirst()
        .orElseGet(() -> parts.iterator().next());
  }

  private void processPublishing(final ProtocolContext context, final Part nupkgPart)
      throws IOException {

    try (final var inputStream = nupkgPart.getInputStream()) {
      this.facade.publish(context, inputStream);
    }
  }

  private ResponseEntity<Object> handleException(final String errorMsg) {

    log.debug("NuGet validation error: {}", errorMsg);

    return this.createErrorResponse(HttpStatus.BAD_REQUEST, errorMsg);
  }

  private ResponseEntity<Object> handleConflict(
      final org.springframework.web.server.ResponseStatusException e) {

    log.debug("NuGet publish conflict: {}", e.getMessage());

    final var reason = e.getReason() != null ? e.getReason() : "Conflict";

    return this.createErrorResponse((HttpStatus) e.getStatusCode(), reason);
  }

  private ResponseEntity<Object> createErrorResponse(
      final HttpStatus status, final String message) {

    return ResponseEntity.status(status).body(ProtocolErrorBody.withMessage(message));
  }
}
