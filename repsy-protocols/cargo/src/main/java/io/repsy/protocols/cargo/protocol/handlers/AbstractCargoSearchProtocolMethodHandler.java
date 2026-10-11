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
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public abstract class AbstractCargoSearchProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<CargoProtocolFacade> {

  private static final int DEFAULT_PER_PAGE = 10;
  private static final int MAX_PER_PAGE = 100;

  static final String PER_PAGE_NOT_A_NUMBER = "per_page must be a whole number";
  static final String PAGE_NOT_A_NUMBER = "page must be a whole number";

  /**
   * Crates are unique per (repo, name), so the name alone is a total order and the pages of one
   * search never repeat or skip a crate. It is the order of the database collation (PostgreSQL and
   * H2 differ on {@code _} and {@code -}), which is fine here: the client pages by offset and every
   * page comes from the same database, so it never compares names itself (unlike the Docker tag
   * list, which is cut by a {@code last} name and so sorts in Java).
   */
  private static final Sort SEARCH_ORDER = Sort.by("name");

  public AbstractCargoSearchProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET).path(path -> path.endsWith("/api/v1/crates")),
        basePathParser,
        facade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    // Only a malformed paging parameter is the client's fault. A database failure is left to
    // ProtocolErrorAdvice (500) and, with this mark, to Cargo's error body (RPS-2060); it used to
    // be answered 400 "Search failed".
    request.setAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE, true);

    final var query = Optional.ofNullable(request.getParameter("q")).orElse("");
    final var perPage = parseWholeNumber(request.getParameter("per_page"), DEFAULT_PER_PAGE);
    final var page = parseWholeNumber(request.getParameter("page"), 1);

    if (perPage.isEmpty()) {
      return badRequest(PER_PAGE_NOT_A_NUMBER);
    }

    if (page.isEmpty()) {
      return badRequest(PAGE_NOT_A_NUMBER);
    }

    // cargo sends per_page=0 for `cargo search --limit 0`: no crates, but the true total, which
    // cargo prints as "... and N crates more". A page size of 0 is not a valid Pageable, so ask
    // for one row to learn the total and drop it.
    final var wanted = perPage.getAsInt();
    final var pageable =
        wanted == 0
            ? PageRequest.of(0, 1, SEARCH_ORDER)
            : PageRequest.of(
                Math.max(page.getAsInt() - 1, 0),
                Math.clamp(wanted, 1, MAX_PER_PAGE),
                SEARCH_ORDER);
    final var result = this.facade.search(context, query, pageable);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(
            Map.of(
                "crates",
                wanted == 0 ? List.of() : result.getContent(),
                "meta",
                Map.of("total", result.getTotalElements())));
  }

  /**
   * Reads a whole-number query parameter.
   *
   * @return the value, the default when the parameter is absent, or empty when it is not a number
   */
  private static OptionalInt parseWholeNumber(
      final @Nullable String value, final int defaultValue) {

    if (value == null) {
      return OptionalInt.of(defaultValue);
    }

    try {
      return OptionalInt.of(Integer.parseInt(value));
    } catch (final NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  private static ResponseEntity<Object> badRequest(final String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(ProtocolErrorBody.withDetail(detail));
  }
}
