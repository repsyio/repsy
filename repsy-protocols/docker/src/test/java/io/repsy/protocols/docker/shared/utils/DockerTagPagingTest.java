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
package io.repsy.protocols.docker.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DockerTagPaging")
class DockerTagPagingTest {

  private static final List<String> TAGS = List.of("v2", "_x", "latest", "v10", "alpha", "v1.0");

  @Test
  @DisplayName("lists every tag in byte order when neither n nor last is given")
  void listsEveryTagSorted() {
    final var page = DockerTagPaging.page(TAGS, null, null);

    assertThat(page.tags()).containsExactly("_x", "alpha", "latest", "v1.0", "v10", "v2");
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("cuts a page of n tags and reports that more follow")
  void cutsAPage() {
    final var page = DockerTagPaging.page(TAGS, 2, null);

    assertThat(page.tags()).containsExactly("_x", "alpha");
    assertThat(page.hasMore()).isTrue();
  }

  @Test
  @DisplayName("starts after last, exclusive, whether or not last is a tag")
  void startsAfterLast() {
    assertThat(DockerTagPaging.page(TAGS, 2, "alpha").tags()).containsExactly("latest", "v1.0");
    assertThat(DockerTagPaging.page(TAGS, 2, "b").tags()).containsExactly("latest", "v1.0");
  }

  @Test
  @DisplayName("the last page holds the rest and has no next page, also when it is exactly n")
  void theLastPageHasNoNext() {
    assertThat(DockerTagPaging.page(TAGS, 5, "alpha").hasMore()).isFalse();
    assertThat(DockerTagPaging.page(TAGS, 3, "latest").hasMore()).isFalse();
    assertThat(DockerTagPaging.page(TAGS, 3, "latest").tags()).containsExactly("v1.0", "v10", "v2");
  }

  @Test
  @DisplayName("walking with n and last visits every tag once")
  void walkingVisitsEveryTagOnce() {
    final var seen = new ArrayList<String>();
    String last = null;

    while (true) {
      final var page = DockerTagPaging.page(TAGS, 4, last);
      seen.addAll(page.tags());

      if (!page.hasMore()) {
        break;
      }

      last = page.tags().get(page.tags().size() - 1);
    }

    assertThat(seen).containsExactly("_x", "alpha", "latest", "v1.0", "v10", "v2");
  }

  @Test
  @DisplayName("n=0 answers an empty page without a next one")
  void zeroAnswersAnEmptyPage() {
    final var page = DockerTagPaging.page(TAGS, 0, null);

    assertThat(page.tags()).isEmpty();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("an image without tags, or a last beyond the end, has an empty page")
  void emptyPages() {
    assertThat(DockerTagPaging.page(List.of(), 5, null).tags()).isEmpty();
    assertThat(DockerTagPaging.page(TAGS, 5, "zzz").tags()).isEmpty();
    assertThat(DockerTagPaging.page(TAGS, 5, "zzz").hasMore()).isFalse();
  }
}
