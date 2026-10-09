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

import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pages a version list sorted by a real version comparator instead of a database {@code ORDER BY}
 * (RPS-1665, RPS-1688). A package format's version string (Maven, npm, NuGet, PyPI, RubyGems...)
 * does not sort correctly as plain text: {@code "10.0.0"} would sit above {@code "9.0.0"}. The
 * database can still sort a query by any other property (id, publish time); only the {@code
 * version} property needs this class, and only when a caller asks to sort by it.
 *
 * <p>Because the comparator's order is not a database order, the whole matching set has to be
 * fetched unpaged, sorted here, and sliced, the way {@code ArtifactService.sortByVersionAndPage}
 * does for Maven and {@code CrateUtils.resolveVersionSort} does for Cargo.
 */
public final class VersionSortPaging {

  private VersionSortPaging() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * The direction {@code pageable} asks to sort {@code property} by, or {@code null} when the
   * request does not sort by it (so it stays a plain database {@code ORDER BY} on whatever it does
   * sort by).
   */
  public static Sort.@Nullable Direction directionFor(
      final @NonNull Pageable pageable, final @NonNull String property) {

    final var order = pageable.getSort().getOrderFor(property);

    return order == null ? null : order.getDirection();
  }

  /**
   * Orders the whole unpaged {@code items} by {@code comparator} and slices out the page {@code
   * pageable} asked for.
   */
  public static <T> @NonNull Page<T> sortAndPage(
      final @NonNull List<T> items,
      final @NonNull Pageable pageable,
      final Sort.@NonNull Direction direction,
      final @NonNull Comparator<T> comparator) {

    final var ordered = direction.isDescending() ? comparator.reversed() : comparator;

    final var sorted = items.stream().sorted(ordered).toList();

    final var start = (int) Math.min(pageable.getOffset(), sorted.size());
    final var end = Math.min(start + pageable.getPageSize(), sorted.size());

    return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
  }
}
