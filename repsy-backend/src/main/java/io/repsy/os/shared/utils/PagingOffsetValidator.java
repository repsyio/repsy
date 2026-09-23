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
package io.repsy.os.shared.utils;

import io.repsy.os.shared.error_handling.exceptions.InvalidPagingParameterException;
import org.jspecify.annotations.NonNull;

/**
 * Validates that an explicit {@code page}/{@code size} pair, each already individually bounded by
 * the endpoint's own {@code @Min}/{@code @Max} constraints, does not overflow {@code int} once
 * multiplied.
 *
 * <p>{@code PagingParameterInterceptor} gives this same protection to every handler that takes a
 * Spring Data {@link org.springframework.data.domain.Pageable}, but an endpoint that instead binds
 * {@code page} and {@code size} as plain {@code @RequestParam} ints and builds its own {@link
 * org.springframework.data.domain.PageRequest} (for example {@code UserController} and {@code
 * SecurityScanController}) is not a handler the interceptor inspects, so it stays exposed unless it
 * checks this itself. Spring Data computes the query offset as {@code page * size}, and further
 * down the JPA/Hibernate stack that offset is narrowed to an {@code int}; an overflowing
 * combination would otherwise reach the database layer as an unhandled exception instead of a
 * validation error (RPS-1150).
 */
public final class PagingOffsetValidator {

  private static final @NonNull String PAGE_PARAMETER = "page";

  private PagingOffsetValidator() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Rejects a {@code page}/{@code size} combination whose product exceeds {@link
   * Integer#MAX_VALUE}.
   *
   * @param page Requested page number, already validated to be non-negative
   * @param size Requested page size, already validated to be within the endpoint's bounds
   * @throws InvalidPagingParameterException naming {@code page} when {@code page * size} overflows
   */
  public static void requireNoOffsetOverflow(final int page, final int size) {
    if ((long) page * size > Integer.MAX_VALUE) {
      throw new InvalidPagingParameterException(PAGE_PARAMETER);
    }
  }
}
