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

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ManifestNameGenerator")
class ManifestNameGeneratorTest {

  private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  @DisplayName("a manifest without a storage name is stored at its digest")
  void digestIsTheFileName() {
    final var digest = "sha256:" + "a".repeat(64);

    assertThat(ManifestNameGenerator.fileName(REPO, "app", digest, null)).isEqualTo(digest);
  }

  @Test
  @DisplayName("a manifest with a storage name keeps the name generated from that reference")
  void storageNameKeepsTheLegacyName() {
    final var digest = "sha256:" + "a".repeat(64);

    assertThat(ManifestNameGenerator.fileName(REPO, "app", digest, "latest"))
        .isEqualTo(ManifestNameGenerator.generate(REPO, "app", "latest"))
        .startsWith("manifest_")
        .endsWith("_app_latest");
  }

  @Test
  @DisplayName("the legacy name differs by repo, image and reference")
  void legacyNameIsScoped() {
    final var base = ManifestNameGenerator.generate(REPO, "app", "latest");

    assertThat(ManifestNameGenerator.generate(UUID.randomUUID(), "app", "latest"))
        .isNotEqualTo(base);
    assertThat(ManifestNameGenerator.generate(REPO, "other", "latest")).isNotEqualTo(base);
    assertThat(ManifestNameGenerator.generate(REPO, "app", "stable")).isNotEqualTo(base);
  }
}
