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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code PUT}/{@code DELETE} {@code /api/v1/crates/{name}/owners}: {@code cargo owner --add}/{@code
 * --remove}. Repsy has no ownership model finer than the repository itself, so these are no-ops
 * that report success without changing anything — but they are still {@code writeOperation}s, so
 * {@code CargoAuthPreProcessor} authenticates them even on a public repo instead of letting a
 * mutating request through unauthenticated.
 */
@NullMarked
public abstract class AbstractCargoOwnersModifyProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  private static final Pattern OWNERS_PATTERN = Pattern.compile(".*/api/v1/crates/[^/]+/owners$");

  protected AbstractCargoOwnersModifyProtocolMethodHandler(
      final PathParser basePathParser, final CargoProtocolProvider provider) {

    super(
        HandlerRoute.write(HttpMethod.PUT, HttpMethod.DELETE)
            .path(OWNERS_PATTERN.asMatchPredicate()),
        basePathParser,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(
            Map.of(
                "ok",
                true,
                "msg",
                "Ownership is managed at the repository level in this registry"));
  }
}
