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
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@NullMarked
public abstract class AbstractCargoSparseIndexProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<CargoProtocolFacade> {

  private final ObjectMapper objectMapper;

  static final Pattern INDEX_PATTERN =
      Pattern.compile(
          "^(?:/1/(?<n1>[^/]+)"
              + "|/2/(?<n2>[^/]+)"
              + "|/3/[^/]+/(?<n3>[^/]+)"
              + "|/[^/]{2}/[^/]{2}/(?<n4>[^/]+))$");

  static final Pattern EXCLUDED_PATTERN = Pattern.compile("^/(?:api/|config\\.json|me).*");

  protected AbstractCargoSparseIndexProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final ObjectMapper objectMapper,
      final CargoProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET)
            .skipPreProcessor(false)
            .skipHeaderPreProcessor(true)
            .path(
                path ->
                    !EXCLUDED_PATTERN.matcher(path).matches()
                        && INDEX_PATTERN.matcher(path).matches()),
        basePathParser,
        facade,
        provider);

    this.objectMapper = objectMapper;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var entries = this.facade.getIndexEntries(context);

      if (entries.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      final var body = this.buildIndexBody(entries);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
          .body(body);

    } catch (final Exception e) {
      log.debug(
          "Cargo sparse index lookup failed for {}: {}", request.getServletPath(), e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  private String buildIndexBody(final List<CrateIndexEntry> entries) {
    final var sb = new StringBuilder();
    for (final var entry : entries) {
      try {
        sb.append(this.objectMapper.writeValueAsString(entry));
        sb.append('\n');
      } catch (final Exception e) {
        log.warn("Failed to serialize index entry: {}", e.getMessage());
      }
    }
    return sb.toString();
  }
}
