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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoYankProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<CargoProtocolFacade> {

  private static final Pattern YANK_PATTERN =
      Pattern.compile(".*/api/v1/crates/[^/]+/[^/]+/(yank|unyank)$");

  public AbstractCargoYankProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {

    super(
        HandlerRoute.write(HttpMethod.DELETE, HttpMethod.PUT).path(YANK_PATTERN.asMatchPredicate()),
        basePathParser,
        facade,
        provider);
  }

  @Override
  protected boolean matches(final HttpMethod method, final String relativePath) {

    if (!super.matches(method, relativePath)) {
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

    // A version that is not there (or a malformed request) is the client's 400; any other failure
    // is
    // left to ProtocolErrorAdvice and, with this mark, to Cargo's error body (RPS-2060).
    request.setAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE, true);

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

    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(ProtocolErrorBody.withDetail(e.getMessage()));
    }
  }
}
