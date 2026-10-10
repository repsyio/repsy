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

import static org.springframework.http.MediaType.APPLICATION_JSON;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetPackageVersionsProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  static final Pattern VERSIONS_PATTERN =
      Pattern.compile("^.*/v3/package/[^/]+/index\\.json$", Pattern.CASE_INSENSITIVE);

  public AbstractNuGetPackageVersionsProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider) {
    super(HandlerRoute.read(HttpMethod.GET).head(), basePathParser, facade, provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return VERSIONS_PATTERN.matcher(request.getServletPath()).matches();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var versions = this.facade.getPackageVersions(context);
      return ResponseEntity.ok().contentType(APPLICATION_JSON).body(Map.of("versions", versions));

    } catch (final ItemNotFoundException e) {
      log.debug("NuGet package versions not found: {}", e.getMessage());
      return ResponseEntity.notFound().build();
    } catch (final Exception e) {
      log.error("NuGet package versions failed", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Resolves what the {@code GET} would answer, so that a missing package is a 404. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    try {
      this.facade.getPackageVersions(context);

      return ResponseEntity.ok().contentType(APPLICATION_JSON).build();
    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      log.debug("NuGet HEAD not found: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
