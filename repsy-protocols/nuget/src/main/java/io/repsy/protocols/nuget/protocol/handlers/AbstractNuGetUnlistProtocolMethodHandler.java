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

import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractNuGetUnlistProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  private static final Pattern UNLIST_PATTERN =
      Pattern.compile("^.*/v3/package/[^/]+/[^/]+$", Pattern.CASE_INSENSITIVE);

  protected AbstractNuGetUnlistProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider) {
    super(HandlerRoute.write(HttpMethod.DELETE), basePathParser, facade, provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request)
        && UNLIST_PATTERN.matcher(request.getServletPath()).matches();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    try {
      this.facade.unlistVersion(context);
      return ResponseEntity.noContent().build();

    } catch (final ItemNotFoundException e) {
      return ResponseEntity.status(NOT_FOUND).body(ProtocolErrorBody.withMessage(e.getMessage()));
    }
  }
}
