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
package io.repsy.protocols.nuget.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.nuget.shared.dtos.NuGetCatalogEntry;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NuGetRegistrationPageUtilsTest {

  @Test
  @DisplayName("bounds a registration page by its lowest and highest four-part versions")
  void boundsRegistrationPageByFourPartVersions() {
    final var leaves =
        Stream.of("1.0.0.10", "1.0.0.5", "1.0.0.6", "1.0.0")
            .map(NuGetRegistrationPageUtilsTest::leafItem)
            .toList();

    final var pages =
        NuGetRegistrationPageUtils.buildRegistrationPages(leaves, "https://x/index.json");

    assertThat(pages)
        .singleElement()
        .satisfies(
            page -> {
              assertThat(page.lower()).isEqualTo("1.0.0");
              assertThat(page.upper()).isEqualTo("1.0.0.10");
            });
  }

  @Nested
  @DisplayName("registration pages")
  class RegistrationPages {

    private List<io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem> leaves(
        final int count) {
      return java.util.stream.IntStream.range(0, count)
          .mapToObj(i -> leafItem("1.0." + i))
          .toList();
    }

    @Test
    @DisplayName("up to 64 leaves are one page that keeps the index url")
    void onePageKeepsIndexUrl() {
      final var pages =
          NuGetRegistrationPageUtils.buildRegistrationPages(
              this.leaves(64), "https://x/a/index.json");

      assertThat(pages)
          .singleElement()
          .satisfies(
              page -> {
                assertThat(page.id()).isEqualTo("https://x/a/index.json");
                assertThat(page.type()).isEqualTo("catalog:CatalogPage");
                assertThat(page.count()).isEqualTo(64);
                assertThat(page.items()).hasSize(64);
                assertThat(page.lower()).isEqualTo("1.0.0");
                assertThat(page.upper()).isEqualTo("1.0.63");
              });
    }

    @Test
    @DisplayName("no leaves is one empty page with blank bounds")
    void emptyIsOnePage() {
      final var pages =
          NuGetRegistrationPageUtils.buildRegistrationPages(List.of(), "https://x/a/index.json");

      assertThat(pages)
          .singleElement()
          .satisfies(
              page -> {
                assertThat(page.count()).isZero();
                assertThat(page.lower()).isEmpty();
                assertThat(page.upper()).isEmpty();
              });
    }

    @Test
    @DisplayName("more than 64 leaves are split into pages of 64 with numbered page urls")
    void splitsIntoPagesOf64() {
      final var pages =
          NuGetRegistrationPageUtils.buildRegistrationPages(
              this.leaves(130), "https://x/a/index.json");

      assertThat(pages)
          .extracting(page -> page.id())
          .containsExactly(
              "https://x/a/page/0.json", "https://x/a/page/1.json", "https://x/a/page/2.json");
      assertThat(pages).extracting(page -> page.count()).containsExactly(64, 64, 2);
      assertThat(pages)
          .extracting(page -> page.lower())
          .containsExactly("1.0.0", "1.0.64", "1.0.128");
      assertThat(pages)
          .extracting(page -> page.upper())
          .containsExactly("1.0.63", "1.0.127", "1.0.129");
    }

    @Test
    @DisplayName("the JSON-LD context and the json suffix are the ones the clients expect")
    void contextAndSuffix() {
      assertThat(NuGetRegistrationPageUtils.FORMAT_JSON).isEqualTo(".json");
      assertThat(NuGetRegistrationPageUtils.NUGET_CONTEXT)
          .containsEntry("@vocab", "http://schema.nuget.org/schema#")
          .containsEntry("comment", "http://www.w3.org/2000/01/rdf-schema#comment")
          .hasSize(2);
    }
  }

  private static NuGetRegistrationLeafItem leafItem(final String version) {
    final var entry =
        new NuGetCatalogEntry(
            "id",
            "type",
            "Some.Package",
            version,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            Instant.EPOCH,
            null);
    return new NuGetRegistrationLeafItem(
        "id", "type", entry, true, "content", Instant.EPOCH, "registration");
  }
}
