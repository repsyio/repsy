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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

@DisplayName("OffsetPageRequest")
class OffsetPageRequestTest {

  private static final Sort SORT = Sort.by(Sort.Direction.DESC, "packageId");

  @Test
  @DisplayName("keeps an offset that is not a multiple of the page size")
  void keepsTheExactOffset() {
    final var pageable = new OffsetPageRequest(5, 10, SORT);

    assertThat(pageable.getOffset()).isEqualTo(5);
    assertThat(pageable.getPageSize()).isEqualTo(10);
    assertThat(pageable.getSort()).isEqualTo(SORT);
    assertThat(pageable.isPaged()).isTrue();
  }

  @Test
  @DisplayName("reports the page the offset falls in")
  void pageNumberIsTheContainingPage() {
    assertThat(OffsetPageRequest.of(0, 10).getPageNumber()).isZero();
    assertThat(OffsetPageRequest.of(9, 10).getPageNumber()).isZero();
    assertThat(OffsetPageRequest.of(15, 10).getPageNumber()).isEqualTo(1);
    assertThat(OffsetPageRequest.of(20, 10).getPageNumber()).isEqualTo(2);
  }

  @Test
  @DisplayName("of() is unsorted")
  void ofIsUnsorted() {
    assertThat(OffsetPageRequest.of(3, 4).getSort()).isEqualTo(Sort.unsorted());
  }

  @Test
  @DisplayName("next() moves the window on by one page size")
  void next() {
    assertThat(new OffsetPageRequest(5, 10, SORT).next())
        .isEqualTo(new OffsetPageRequest(15, 10, SORT));
  }

  @Test
  @DisplayName("previousOrFirst() moves back by one page size, and stops at the first row")
  void previousOrFirst() {
    assertThat(new OffsetPageRequest(15, 10, SORT).previousOrFirst())
        .isEqualTo(new OffsetPageRequest(5, 10, SORT));
    assertThat(new OffsetPageRequest(5, 10, SORT).previousOrFirst())
        .isEqualTo(new OffsetPageRequest(0, 10, SORT));
    assertThat(new OffsetPageRequest(0, 10, SORT).previousOrFirst())
        .isEqualTo(new OffsetPageRequest(0, 10, SORT));
  }

  @Test
  @DisplayName("first() starts at the first row and keeps the size and sort")
  void first() {
    assertThat(new OffsetPageRequest(15, 10, SORT).first())
        .isEqualTo(new OffsetPageRequest(0, 10, SORT));
  }

  @Test
  @DisplayName("withPage() starts at a multiple of the page size")
  void withPage() {
    assertThat(new OffsetPageRequest(5, 10, SORT).withPage(3))
        .isEqualTo(new OffsetPageRequest(30, 10, SORT));
  }

  @Test
  @DisplayName("hasPrevious() is true only after the first row")
  void hasPrevious() {
    assertThat(OffsetPageRequest.of(0, 10).hasPrevious()).isFalse();
    assertThat(OffsetPageRequest.of(1, 10).hasPrevious()).isTrue();
  }

  @Test
  @DisplayName("rejects a negative offset")
  void rejectsNegativeOffset() {
    assertThatThrownBy(() -> OffsetPageRequest.of(-1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Offset");
  }

  @Test
  @DisplayName("rejects a page size below 1")
  void rejectsNonPositivePageSize() {
    assertThatThrownBy(() -> OffsetPageRequest.of(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Page size");
  }
}
