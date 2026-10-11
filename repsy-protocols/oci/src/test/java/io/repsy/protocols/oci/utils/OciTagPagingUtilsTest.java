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
package io.repsy.protocols.oci.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OciTagPagingUtils")
class OciTagPagingUtilsTest {

  private static final List<String> TAGS = List.of("v2", "_x", "latest", "v10", "alpha", "v1.0");

  @Test
  @DisplayName("lists every tag in byte order when neither n nor last is given")
  void listsEveryTagSorted() {
    final var page = OciTagPagingUtils.page(TAGS, null, null);

    assertThat(page.tags()).containsExactly("_x", "alpha", "latest", "v1.0", "v10", "v2");
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("cuts a page of n tags and reports that more follow")
  void cutsAPage() {
    final var page = OciTagPagingUtils.page(TAGS, 2, null);

    assertThat(page.tags()).containsExactly("_x", "alpha");
    assertThat(page.hasMore()).isTrue();
  }

  @Test
  @DisplayName("starts after last, exclusive, whether or not last is a tag")
  void startsAfterLast() {
    assertThat(OciTagPagingUtils.page(TAGS, 2, "alpha").tags()).containsExactly("latest", "v1.0");
    assertThat(OciTagPagingUtils.page(TAGS, 2, "b").tags()).containsExactly("latest", "v1.0");
  }

  @Test
  @DisplayName("the last page holds the rest and has no next page, also when it is exactly n")
  void theLastPageHasNoNext() {
    assertThat(OciTagPagingUtils.page(TAGS, 5, "alpha").hasMore()).isFalse();
    assertThat(OciTagPagingUtils.page(TAGS, 3, "latest").hasMore()).isFalse();
    assertThat(OciTagPagingUtils.page(TAGS, 3, "latest").tags())
        .containsExactly("v1.0", "v10", "v2");
  }

  @Test
  @DisplayName("walking with n and last visits every tag once")
  void walkingVisitsEveryTagOnce() {
    final var seen = new ArrayList<String>();
    String last = null;

    while (true) {
      final var page = OciTagPagingUtils.page(TAGS, 4, last);
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
    final var page = OciTagPagingUtils.page(TAGS, 0, null);

    assertThat(page.tags()).isEmpty();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("an image without tags, or a last beyond the end, has an empty page")
  void emptyPages() {
    assertThat(OciTagPagingUtils.page(List.of(), 5, null).tags()).isEmpty();
    assertThat(OciTagPagingUtils.page(TAGS, 5, "zzz").tags()).isEmpty();
    assertThat(OciTagPagingUtils.page(TAGS, 5, "zzz").hasMore()).isFalse();
  }

  @Test
  @DisplayName("parseLimit reads a non-negative integer and refuses anything else")
  void parseLimit() {
    assertThat(OciTagPagingUtils.parseLimit(null)).isNull();
    assertThat(OciTagPagingUtils.parseLimit("0")).isZero();
    assertThat(OciTagPagingUtils.parseLimit("5")).isEqualTo(5);
    assertThatThrownBy(() -> OciTagPagingUtils.parseLimit("-1"))
        .hasMessage("paginationNumberInvalid");
    assertThatThrownBy(() -> OciTagPagingUtils.parseLimit("x"))
        .hasMessage("paginationNumberInvalid");
  }

  @Test
  @DisplayName("nextLink is a relative reference with the encoded last tag")
  void nextLink() {
    assertThat(OciTagPagingUtils.nextLink("/v2/r/app/tags/list", 2, "a b"))
        .isEqualTo("</v2/r/app/tags/list?n=2&last=a+b>; rel=\"next\"");
  }
}
