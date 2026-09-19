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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.os.shared.error_handling.exceptions.InvalidPagingParameterException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortValidatorTest {

  private static final Set<String> ALLOWED = Set.of("createdAt", "severity");

  @Test
  @DisplayName("accepts an unsorted request")
  void unsorted() {
    assertThatCode(() -> SortValidator.requireSortableBy(Pageable.unpaged(), ALLOWED))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("accepts every allowed property, in either direction")
  void allowedProperties() {
    final var pageable =
        PageRequest.of(0, 10, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("severity")));

    assertThatCode(() -> SortValidator.requireSortableBy(pageable, ALLOWED))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rejects an unknown property, naming sort")
  void unknownProperty() {
    final var pageable = PageRequest.of(0, 10, Sort.by("bogus"));

    assertThatThrownBy(() -> SortValidator.requireSortableBy(pageable, ALLOWED))
        .isInstanceOf(InvalidPagingParameterException.class)
        .extracting(e -> ((InvalidPagingParameterException) e).getParameterNames())
        .isEqualTo("sort");
  }

  @Test
  @DisplayName("rejects the request when only one of several properties is unknown")
  void oneUnknownAmongSeveral() {
    final var pageable = PageRequest.of(0, 10, Sort.by("createdAt", "bogus"));

    assertThatThrownBy(() -> SortValidator.requireSortableBy(pageable, ALLOWED))
        .isInstanceOf(InvalidPagingParameterException.class);
  }

  @Test
  @DisplayName("maps each sort key to its path, keeping direction, page and size")
  void resolvesSortPaths() {
    final var pageable =
        PageRequest.of(2, 15, Sort.by(Sort.Order.desc("max_version"), Sort.Order.asc("name")));

    final var resolved =
        SortValidator.resolveSortPaths(
            pageable, Map.of("max_version", "maxVersion", "name", "name"));

    assertThat(resolved.getPageNumber()).isEqualTo(2);
    assertThat(resolved.getPageSize()).isEqualTo(15);
    assertThat(resolved.getSort())
        .containsExactly(Sort.Order.desc("maxVersion"), Sort.Order.asc("name"));
  }

  @Test
  @DisplayName("keeps an unsorted request unsorted when mapping sort paths")
  void resolvesUnsorted() {
    final var resolved =
        SortValidator.resolveSortPaths(PageRequest.of(0, 10), Map.of("max_version", "maxVersion"));

    assertThat(resolved.getSort().isSorted()).isFalse();
  }

  @Test
  @DisplayName("rejects a path the mapping does not list as a key, naming sort")
  void resolveRejectsUnmappedKey() {
    final var pageable = PageRequest.of(0, 10, Sort.by("maxVersion"));

    assertThatThrownBy(
            () -> SortValidator.resolveSortPaths(pageable, Map.of("max_version", "maxVersion")))
        .isInstanceOf(InvalidPagingParameterException.class)
        .extracting(e -> ((InvalidPagingParameterException) e).getParameterNames())
        .isEqualTo("sort");
  }

  @Test
  @DisplayName("matches property names exactly, including case and nesting")
  void exactMatch() {
    assertThat(ALLOWED).doesNotContain("Severity", "createdAt.year");

    for (final var property : new String[] {"Severity", "createdAt.year"}) {
      final var pageable = PageRequest.of(0, 10, Sort.by(property));

      assertThatThrownBy(() -> SortValidator.requireSortableBy(pageable, ALLOWED))
          .isInstanceOf(InvalidPagingParameterException.class);
    }
  }
}
