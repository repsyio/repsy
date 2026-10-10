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
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Answers a {@code HEAD} on the service index ({@code /v3/index.json}) like its {@code GET} would,
 * without the body (RPS-1465). Like the {@code GET} it needs no credentials: a client reads it
 * before it knows whether the feed wants any. The {@code Content-Length} is not sent, the body is
 * built from the request's own URL.
 */
@NullMarked
public abstract class AbstractNuGetServiceIndexHeadProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  protected AbstractNuGetServiceIndexHeadProtocolMethodHandler(
      final PathParser basePathParser, final NuGetProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.HEAD).skipUsagePostProcessor(true).skipPreProcessor(true),
        basePathParser,
        provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return request.getRequestURI().toLowerCase(Locale.ROOT).endsWith("/v3/index.json");
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).build();
  }
}
