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
package io.repsy.os.shared.configs;

import io.repsy.os.shared.error_handling.exceptions.InvalidPagingParameterException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects an invalid {@code page} or {@code size} on every handler that takes a Spring Data {@link
 * Pageable}.
 *
 * <p>Spring Data's argument resolver never fails: it swaps a non-numeric or out-of-range value for
 * the default and honours any size up to its own, much larger, limit. That is inconsistent with the
 * endpoints that declare explicit {@code page}/{@code size} parameters, which answer 400. Checking
 * here gives every {@code Pageable} endpoint the same answer without rewriting each of them.
 *
 * <p>An absent or blank value is left alone, so the resolver still applies the endpoint's default.
 *
 * <p>Once {@code page} and {@code size} are each individually valid, their product is also checked:
 * Spring Data computes the query offset as {@code page * size} and, further down the JPA/Hibernate
 * stack, narrows it to an {@code int}, so a combination that overflows {@link Integer#MAX_VALUE}
 * reaches the database layer as an unhandled exception instead of a validation error. Rejecting it
 * here keeps that failure a 400, like every other invalid paging value.
 */
public final class PagingParameterInterceptor implements HandlerInterceptor {

  static final int MAX_PAGE_SIZE = 100;

  private static final @NonNull String PAGE_PARAMETER = "page";
  private static final @NonNull String SIZE_PARAMETER = "size";

  @Override
  public boolean preHandle(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull Object handler) {

    if (!(handler instanceof final HandlerMethod handlerMethod) || !takesPageable(handlerMethod)) {
      return true;
    }

    final var invalid = invalidParameters(request);
    if (!invalid.isEmpty()) {
      throw new InvalidPagingParameterException(String.join(",", invalid));
    }

    return true;
  }

  private static @NonNull List<String> invalidParameters(
      final @NonNull HttpServletRequest request) {

    final var invalid = new ArrayList<String>(2);
    final var rawPage = request.getParameter(PAGE_PARAMETER);
    final var rawSize = request.getParameter(SIZE_PARAMETER);

    final var pageValid = isWithin(rawPage, 0, Integer.MAX_VALUE);
    if (!pageValid) {
      invalid.add(PAGE_PARAMETER);
    }

    final var sizeValid = isWithin(rawSize, 1, MAX_PAGE_SIZE);
    if (!sizeValid) {
      invalid.add(SIZE_PARAMETER);
    }

    if (pageValid && sizeValid && overflowsOffset(rawPage, rawSize)) {
      invalid.add(PAGE_PARAMETER);
    }

    return invalid;
  }

  private static boolean takesPageable(final @NonNull HandlerMethod handlerMethod) {
    return Arrays.stream(handlerMethod.getMethodParameters())
        .anyMatch(parameter -> Pageable.class.isAssignableFrom(parameter.getParameterType()));
  }

  private static boolean isWithin(final String rawValue, final int min, final int max) {
    if (rawValue == null || rawValue.isBlank()) {
      return true;
    }

    try {
      // Parsed exactly like Spring Data does, so nothing is accepted here that it would then
      // quietly replace with the default.
      final var value = Integer.parseInt(rawValue);

      return value >= min && value <= max;
    } catch (final NumberFormatException e) {
      return false;
    }
  }

  /**
   * Whether {@code page * size} exceeds {@link Integer#MAX_VALUE}, widened to {@code long} so the
   * multiplication itself cannot overflow. Only called once both raw values already parsed as
   * individually valid ints, so {@link Integer#parseInt(String)} cannot throw here. A blank value
   * (page or size absent) is left to the resolver's default, as elsewhere in this class, so it
   * never reaches this check.
   */
  private static boolean overflowsOffset(final String rawPage, final String rawSize) {
    if (rawPage == null || rawPage.isBlank() || rawSize == null || rawSize.isBlank()) {
      return false;
    }

    return (long) Integer.parseInt(rawPage) * Integer.parseInt(rawSize) > Integer.MAX_VALUE;
  }
}
