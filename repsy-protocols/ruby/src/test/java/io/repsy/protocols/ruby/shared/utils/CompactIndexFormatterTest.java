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
package io.repsy.protocols.ruby.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemVersionsEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CompactIndexFormatter")
class CompactIndexFormatterTest {

  private static GemCompactEntry entry(
      final String version,
      final String platform,
      final boolean yanked,
      final Instant createdAt,
      final List<GemDependency> deps) {
    return GemCompactEntry.builder()
        .gemName("demo")
        .version(version)
        .platform(platform)
        .checksum("abc123")
        .yanked(yanked)
        .createdAt(createdAt)
        .runtimeDependencies(deps)
        .build();
  }

  @Test
  @DisplayName("formatGemInfo omits a yanked version entirely, never with a '-' prefix")
  void omitsYankedVersionFromInfo() {
    final var dep = GemDependency.builder().name("rack").requirements(">= 3.0.0").build();
    final var nonYanked =
        entry("2.0.0", "ruby", false, Instant.parse("2026-01-02T00:00:00Z"), List.of(dep));
    final var yankedEntry =
        GemCompactEntry.builder()
            .gemName("demo")
            .version("1.0.0")
            .platform("ruby")
            .checksum("def456")
            .yanked(true)
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .runtimeDependencies(List.of())
            .build();

    final var body = CompactIndexFormatter.formatGemInfo(List.of(yankedEntry, nonYanked));

    assertThat(body).doesNotContain("1.0.0");
    assertThat(body).contains("2.0.0 rack:>= 3.0.0|checksum:abc123");
  }

  @Test
  @DisplayName("formatGemInfo of an all-yanked gem is just the preamble, with a real created_at")
  void allYankedGemIsJustThePreamble() {
    final var createdAt = Instant.parse("2026-03-04T05:06:07Z");
    final var yanked =
        GemCompactEntry.builder()
            .gemName("demo")
            .version("1.0.0")
            .platform("ruby")
            .checksum("def456")
            .yanked(true)
            .createdAt(createdAt)
            .runtimeDependencies(List.of())
            .build();

    final var body = CompactIndexFormatter.formatGemInfo(List.of(yanked));

    assertThat(body).isEqualTo("created_at: " + createdAt + "\n---\n");
    assertThat(body).doesNotContain("1970-01-01");
  }

  @Test
  @DisplayName("formatGemInfo renders a non-default platform as <version>-<platform>")
  void rendersNonDefaultPlatform() {
    final var javaEntry =
        entry("1.0.0", "java", false, Instant.parse("2026-01-01T00:00:00Z"), List.of());

    final var body = CompactIndexFormatter.formatGemInfo(List.of(javaEntry));

    assertThat(body).contains("1.0.0-java |checksum:abc123");
  }

  @Test
  @DisplayName("formatVersionEntry still marks a yanked version with a '-' prefix (/versions)")
  void formatVersionEntryStillMarksYanked() {
    assertThat(CompactIndexFormatter.formatVersionEntry("1.0.0", "ruby", true)).isEqualTo("-1.0.0");
    assertThat(CompactIndexFormatter.formatVersionEntry("1.0.0", "ruby", false)).isEqualTo("1.0.0");
    assertThat(CompactIndexFormatter.formatVersionEntry("1.0.0", "java", false))
        .isEqualTo("1.0.0-java");
  }

  @Test
  @DisplayName("formatVersionsIndex renders one sorted line per gem")
  void formatsVersionsIndex() {
    final var body =
        CompactIndexFormatter.formatVersionsIndex(
            Map.of(
                "zeta", new GemVersionsEntry("1.0.0", "csum1"),
                "alpha", new GemVersionsEntry("2.0.0", "csum2")));

    final var lines = body.lines().skip(2).toList();
    assertThat(lines).containsExactly("alpha 2.0.0 csum2", "zeta 1.0.0 csum1");
  }
}
