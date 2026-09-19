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

    final var invalid = new ArrayList<String>(2);

    if (!isWithin(request.getParameter(PAGE_PARAMETER), 0, Integer.MAX_VALUE)) {
      invalid.add(PAGE_PARAMETER);
    }

    if (!isWithin(request.getParameter(SIZE_PARAMETER), 1, MAX_PAGE_SIZE)) {
      invalid.add(SIZE_PARAMETER);
    }

    if (!invalid.isEmpty()) {
      throw new InvalidPagingParameterException(String.join(",", invalid));
    }

    return true;
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
}
