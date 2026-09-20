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
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Validates the {@code sort} of a Spring Data {@link Pageable}.
 *
 * <p>Spring Data's resolver accepts any property name, so an unknown one only fails later, inside
 * the query, as a {@code PropertyReferenceException} that surfaces as a 500. Checking the resolved
 * {@link Sort} against the properties an endpoint supports answers 400 {@code validationError}
 * naming {@code sort} instead, like an invalid {@code page} or {@code size} does.
 */
public final class SortValidator {

  private static final @NonNull String SORT_PARAMETER = "sort";

  private SortValidator() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Rejects the request when {@code pageable} sorts by a property outside {@code allowed}.
   *
   * @param pageable Resolved paging of the request
   * @param allowed Properties the endpoint can sort by
   * @throws InvalidPagingParameterException when any sort property is not allowed
   */
  public static void requireSortableBy(
      final @NonNull Pageable pageable, final @NonNull Set<String> allowed) {

    final var supported =
        pageable.getSort().stream().map(Sort.Order::getProperty).allMatch(allowed::contains);

    if (!supported) {
      throw new InvalidPagingParameterException(SORT_PARAMETER);
    }
  }

  /**
   * Rejects the request when {@code pageable} sorts by a key outside {@code sortPaths}, and
   * otherwise returns it sorted by the paths those keys stand for.
   *
   * <p>Lets an endpoint accept the field names its response carries as sort keys while the query
   * sorts by the entity or projection path behind each one.
   *
   * @param pageable Resolved paging of the request
   * @param sortPaths Sort keys the endpoint accepts, mapped to the paths the query sorts by
   * @return The same page and size, sorted by the mapped paths in the requested directions
   * @throws InvalidPagingParameterException when any sort key is not a key of {@code sortPaths}
   */
  public static @NonNull Pageable resolveSortPaths(
      final @NonNull Pageable pageable, final @NonNull Map<String, String> sortPaths) {

    requireSortableBy(pageable, sortPaths.keySet());

    final var sort =
        Sort.by(
            pageable.getSort().stream()
                .map(order -> order.withProperty(sortPaths.get(order.getProperty())))
                .toList());

    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
  }
}
