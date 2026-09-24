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
package io.repsy.os.shared.paging;

import io.repsy.os.shared.utils.OffsetPageRequest;
import java.util.ArrayList;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Makes the order of a paged query total.
 *
 * <p>A page is a window into an ordered result, so the windows only tile the result when the order
 * is the same on every request. A sort on a column that can hold equal values (a timestamp, a name,
 * a version) leaves the order of the tied rows to the database, which may return them differently
 * for two executions of the same query: a row then shows on two pages, or on none. Ending the sort
 * on the primary key breaks every tie.
 *
 * <p>{@link StablePagingConfiguration} applies this to every repository method that takes a {@link
 * Pageable}, so no query or controller has to remember it.
 */
public final class StablePaging {

  /** The property every entity of the application keeps its primary key in. */
  static final @NonNull String TIE_BREAKER_PROPERTY = "id";

  private StablePaging() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Answers {@code pageable} with the primary key added as the last sort key, in ascending order.
   *
   * <p>A request that is unpaged, that already sorts by the key, or of a type this class cannot
   * rebuild is answered unchanged.
   *
   * @param pageable The paging a query was asked for
   * @return The same page and size, with an order that no two rows share a position in
   */
  public static @NonNull Pageable withTieBreaker(final @NonNull Pageable pageable) {
    if (pageable.isUnpaged() || sortsByTieBreaker(pageable.getSort())) {
      return pageable;
    }

    final var orders = new ArrayList<Sort.Order>();
    pageable.getSort().forEach(orders::add);
    orders.add(Sort.Order.asc(TIE_BREAKER_PROPERTY));
    final var sort = Sort.by(orders);

    return switch (pageable) {
      case final OffsetPageRequest offsetPageRequest ->
          new OffsetPageRequest(offsetPageRequest.offset(), offsetPageRequest.pageSize(), sort);
      case final PageRequest pageRequest -> pageRequest.withSort(sort);
      case null, default -> pageable;
    };
  }

  private static boolean sortsByTieBreaker(final @NonNull Sort sort) {
    return sort.stream().anyMatch(order -> TIE_BREAKER_PROPERTY.equals(order.getProperty()));
  }
}
