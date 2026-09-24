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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.utils.OffsetPageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("StablePaging")
class StablePagingTest {

  @Test
  @DisplayName("adds the id after the requested sort, keeping page and size")
  void appendsIdToRequestedSort() {
    final var pageable = PageRequest.of(3, 7, Sort.by(Sort.Direction.DESC, "createdAt"));

    final var stable = StablePaging.withTieBreaker(pageable);

    assertThat(stable.getPageNumber()).isEqualTo(3);
    assertThat(stable.getPageSize()).isEqualTo(7);
    assertThat(stable.getSort())
        .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
  }

  @Test
  @DisplayName("sorts an unsorted request by id")
  void unsortedBecomesIdOrder() {
    final var stable = StablePaging.withTieBreaker(PageRequest.of(0, 10));

    assertThat(stable.getSort()).containsExactly(Sort.Order.asc("id"));
  }

  @Test
  @DisplayName("keeps every requested key and direction, in order")
  void keepsSeveralKeys() {
    final var pageable =
        PageRequest.of(0, 10, Sort.by(Sort.Order.desc("name"), Sort.Order.asc("createdAt")));

    assertThat(StablePaging.withTieBreaker(pageable).getSort())
        .containsExactly(
            Sort.Order.desc("name"), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
  }

  @Test
  @DisplayName("leaves a request that already sorts by id as it is, in either direction")
  void leavesIdSortAlone() {
    final var ascending = PageRequest.of(1, 5, Sort.by("id"));
    final var descending = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "id"));

    assertThat(StablePaging.withTieBreaker(ascending)).isSameAs(ascending);
    assertThat(StablePaging.withTieBreaker(descending)).isSameAs(descending);
  }

  @Test
  @DisplayName("leaves an unpaged request as it is")
  void leavesUnpagedAlone() {
    assertThat(StablePaging.withTieBreaker(Pageable.unpaged())).isSameAs(Pageable.unpaged());
  }

  @Test
  @DisplayName("keeps the exact offset of an OffsetPageRequest")
  void keepsOffset() {
    final var pageable = new OffsetPageRequest(15, 10, Sort.by("packageId"));

    final var stable = StablePaging.withTieBreaker(pageable);

    assertThat(stable).isInstanceOf(OffsetPageRequest.class);
    assertThat(stable.getOffset()).isEqualTo(15);
    assertThat(stable.getPageSize()).isEqualTo(10);
    assertThat(stable.getSort()).containsExactly(Sort.Order.asc("packageId"), Sort.Order.asc("id"));
  }

  @Test
  @DisplayName("leaves a Pageable of a type it cannot rebuild as it is")
  void leavesUnknownTypeAlone() {
    final var custom = Mockito.mock(Pageable.class);
    Mockito.when(custom.isUnpaged()).thenReturn(false);
    Mockito.when(custom.getSort()).thenReturn(Sort.by("name"));

    assertThat(StablePaging.withTieBreaker(custom)).isSameAs(custom);
  }
}
