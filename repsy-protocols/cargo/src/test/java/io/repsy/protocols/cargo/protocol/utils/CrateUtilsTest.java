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
package io.repsy.protocols.cargo.protocol.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DisplayName("CrateUtils")
class CrateUtilsTest {

  @ParameterizedTest
  @CsvSource({"serde-json, serde_json", "Serde-JSON, serde_json", "TITLE, title", "a_b, a_b"})
  @DisplayName("normalizeCrateName() lower-cases and replaces dashes with underscores")
  void normalizesCrateName(final String name, final String expected) {
    assertThat(CrateUtils.normalizeCrateName(name)).isEqualTo(expected);
  }

  @Nested
  @DisplayName("resolveVersionSort()")
  class ResolveVersionSort {

    private final Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");

    private List<CrateVersionListItem> tied() {
      return List.of(
          new CrateVersionListItem("1.0.2", false, this.sameInstant),
          new CrateVersionListItem("1.0.0", false, this.sameInstant),
          new CrateVersionListItem("1.0.10", false, this.sameInstant),
          new CrateVersionListItem("1.0.1", false, this.sameInstant));
    }

    private List<String> sorted(final PageRequest pageable, final boolean shuffled) {
      final var input = new java.util.ArrayList<>(this.tied());
      if (shuffled) {
        Collections.reverse(input);
      }

      return input.stream()
          .sorted(CrateUtils.resolveVersionSort(pageable))
          .map(CrateVersionListItem::version)
          .toList();
    }

    @Test
    @DisplayName(
        "versions tied on createdAt come out in the same order whatever order they came in")
    void tiedOnCreatedAtAreStable() {
      final var pageable = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));

      assertThat(this.sorted(pageable, false))
          .containsExactly("1.0.0", "1.0.1", "1.0.10", "1.0.2")
          .isEqualTo(this.sorted(pageable, true));
    }

    @Test
    @DisplayName("the default order is stable for versions tied on createdAt")
    void defaultOrderIsStable() {
      final var pageable = PageRequest.of(0, 2);

      assertThat(this.sorted(pageable, false)).isEqualTo(this.sorted(pageable, true));
    }
  }

  private static io.repsy.libs.protocol.router.ProtocolContext context(final String relativePath) {
    final var repoInfo =
        io.repsy.protocols.shared.repo.dtos.BaseRepoInfo.<java.util.UUID>builder()
            .id(java.util.UUID.randomUUID())
            .storageKey(java.util.UUID.randomUUID())
            .name("cargo")
            .build();
    final var urlProps =
        io.repsy.protocols.shared.utils.BaseUrlParserProperties
            .<java.util.UUID, io.repsy.protocols.shared.repo.dtos.BaseRepoInfo<java.util.UUID>>
                builder()
            .repoName("cargo")
            .relativePath(new io.repsy.libs.storage.core.dtos.RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();
    final var ctx = new io.repsy.libs.protocol.router.ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

  @Nested
  @DisplayName("request paths")
  class RequestPaths {

    @Test
    @DisplayName("extractCrateNameAndVersion normalizes the crate name and keeps the version")
    void nameAndVersion() {
      final var pair =
          CrateUtils.extractCrateNameAndVersion(
              context("/api/v1/crates/Serde-Json/1.0.0-Beta.1/download"));

      assertThat(pair.getFirst()).isEqualTo("serde_json");
      assertThat(pair.getSecond()).isEqualTo("1.0.0-Beta.1");
    }

    @Test
    @DisplayName("extractLastSegment returns the last path segment")
    void lastSegment() {
      assertThat(CrateUtils.extractLastSegment(context("/api/v1/crates/Serde-Json/1.0.0/yank")))
          .isEqualTo("yank");
    }
  }
}
