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
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Handles {@code HEAD /{repo}/config.json}, which is served without authentication even on private
 * repos (RPS-1465, RPS-2109). Cargo fetches {@code config.json} before it knows whether the
 * registry requires authentication (RFC 3139, {@code auth-required: true}), so the route is
 * deliberately kept open to all clients. Like the {@code GET}, it exposes only the {@code dl} and
 * {@code api} base URLs and the {@code auth-required} flag, confirming that the registry exists.
 * The {@code Content-Length} is not sent since the body is empty.
 */
@NullMarked
public abstract class AbstractCargoConfigHeadProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  protected AbstractCargoConfigHeadProtocolMethodHandler(
      final PathParser basePathParser, final CargoProtocolProvider provider) {

    super(
        HandlerRoute.of(Permission.READ, HttpMethod.HEAD)
            .skipUsagePostProcessor(true)
            .skipPreProcessor(true),
        basePathParser,
        provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request) && request.getServletPath().endsWith("/config.json");
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }
}
