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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CrateInspectionUtils")
class CrateInspectionUtilsTest {

  /** Packs the given entries into a gzipped tar, the way {@code cargo package} builds a .crate. */
  private static byte[] crate(final Map<String, String> files) throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      for (final var file : files.entrySet()) {
        final var content = file.getValue().getBytes(StandardCharsets.UTF_8);
        final var entry = new TarArchiveEntry(file.getKey());
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static boolean hasLib(final byte[] crateBytes) throws IOException {
    return CrateInspectionUtils.inspectCrate(new ByteArrayInputStream(crateBytes)).hasLib();
  }

  private static @Nullable String edition(final byte[] crateBytes) throws IOException {
    return CrateInspectionUtils.inspectCrate(new ByteArrayInputStream(crateBytes)).edition();
  }

  @Test
  @DisplayName("inspectCrate() reports hasLib=true when the crate contains src/lib.rs")
  void detectsLibRs() throws IOException {
    final var crate =
        crate(
            Map.of(
                "demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n",
                "demo-1.0.0/src/lib.rs", "pub fn demo() {}\n"));

    assertThat(hasLib(crate)).isTrue();
  }

  @Test
  @DisplayName(
      "inspectCrate() reports hasLib=true when Cargo.toml declares a [lib] target, even with"
          + " non-ASCII text")
  void detectsLibTargetInCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put(
        "demo-1.0.0/Cargo.toml",
        "[package]\nname = \"demo\"\nauthors = [\"Zoë Müller\"]\n\n  [lib]  \npath = \"lib/main.rs\"\n");
    files.put("demo-1.0.0/lib/main.rs", "pub fn demo() {}\n");

    assertThat(hasLib(crate(files))).isTrue();
  }

  @Test
  @DisplayName("inspectCrate() reports hasLib=false for a binary-only crate")
  void rejectsBinaryOnlyCrate() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n\n[[bin]]\nname = \"demo\"\n");
    files.put("demo-1.0.0/src/main.rs", "fn main() {}\n");

    assertThat(hasLib(crate(files))).isFalse();
  }

  @Test
  @DisplayName("inspectCrate() reports hasLib=false for an empty crate")
  void rejectsEmptyCrate() throws IOException {
    assertThat(hasLib(crate(Map.of()))).isFalse();
  }

  @Test
  @DisplayName("inspectCrate() reads a Cargo.toml of exactly the size limit")
  void readsCargoTomlAtLimit() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", cargoTomlOfSize(CrateInspectionUtils.MAX_CARGO_TOML_BYTES));

    assertThat(hasLib(crate(files))).isTrue();
  }

  @Test
  @DisplayName("inspectCrate() refuses a Cargo.toml larger than the size limit, naming the limit")
  void refusesOversizedCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", cargoTomlOfSize(CrateInspectionUtils.MAX_CARGO_TOML_BYTES + 1));

    final var crate = crate(files);

    assertThatThrownBy(() -> hasLib(crate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cargo.toml in the crate must be at most 10 MiB");
  }

  @Test
  @DisplayName("inspectCrate() refuses an oversized Cargo.toml of a nested package too")
  void refusesOversizedNestedCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n");
    files.put("demo-1.0.0/sub/Cargo.toml", cargoTomlOfSize(CrateInspectionUtils.MAX_CARGO_TOML_BYTES + 1));

    final var crate = crate(files);

    assertThatThrownBy(() -> hasLib(crate)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("inspectCrate() does not inflate an oversized Cargo.toml it has no need to read")
  void skipsOversizedEntryThatIsNotCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n");
    files.put("demo-1.0.0/README.md", "#".repeat((int) CrateInspectionUtils.MAX_CARGO_TOML_BYTES + 1));

    assertThat(hasLib(crate(files))).isFalse();
  }

  @Nested
  @DisplayName("inspectCrate() edition (RPS-1141)")
  class EditionTests {

    @Test
    @DisplayName("reads the edition declared in the [package] table")
    void readsDeclaredEdition() throws IOException {
      final var files = new LinkedHashMap<String, String>();
      files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\nedition = \"2021\"\n");

      assertThat(edition(crate(files))).isEqualTo("2021");
    }

    @Test
    @DisplayName("returns null when Cargo.toml declares no edition")
    void returnsNullWhenNoEdition() throws IOException {
      final var files = new LinkedHashMap<String, String>();
      files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n");

      assertThat(edition(crate(files))).isNull();
    }

    @Test
    @DisplayName("ignores an edition key outside the [package] table")
    void ignoresEditionOutsidePackageTable() throws IOException {
      final var files = new LinkedHashMap<String, String>();
      files.put(
          "demo-1.0.0/Cargo.toml",
          "[package]\nname = \"demo\"\n\n[workspace.package]\nedition = \"2018\"\n");

      assertThat(edition(crate(files))).isNull();
    }

    @Test
    @DisplayName("drops an edition longer than the 10-character column width")
    void dropsOverLongEdition() throws IOException {
      final var files = new LinkedHashMap<String, String>();
      files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\nedition = \"12345678901\"\n");

      assertThat(edition(crate(files))).isNull();
    }

    @Test
    @DisplayName("keeps an edition of exactly the 10-character column width")
    void keepsEditionAtTheLimit() throws IOException {
      final var files = new LinkedHashMap<String, String>();
      files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\nedition = \"1234567890\"\n");

      assertThat(edition(crate(files))).isEqualTo("1234567890");
    }
  }

  private static String cargoTomlOfSize(final long size) {
    final var head = "[package]\nname = \"demo\"\n\n[lib]\n#";

    return head + "#".repeat((int) size - head.length());
  }
}
