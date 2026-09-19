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
