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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("CrateUtils")
class CrateUtilsTest {

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

  @Test
  @DisplayName("isLib() is true when the crate contains src/lib.rs")
  void detectsLibRs() throws IOException {
    final var crate =
        crate(
            Map.of(
                "demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n",
                "demo-1.0.0/src/lib.rs", "pub fn demo() {}\n"));

    assertThat(CrateUtils.isLib(crate)).isTrue();
  }

  @Test
  @DisplayName("isLib() is true when Cargo.toml declares a [lib] target, even with non-ASCII text")
  void detectsLibTargetInCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put(
        "demo-1.0.0/Cargo.toml",
        "[package]\nname = \"demo\"\nauthors = [\"Zoë Müller\"]\n\n  [lib]  \npath = \"lib/main.rs\"\n");
    files.put("demo-1.0.0/lib/main.rs", "pub fn demo() {}\n");

    assertThat(CrateUtils.isLib(crate(files))).isTrue();
  }

  @Test
  @DisplayName("isLib() is false for a binary-only crate")
  void rejectsBinaryOnlyCrate() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n\n[[bin]]\nname = \"demo\"\n");
    files.put("demo-1.0.0/src/main.rs", "fn main() {}\n");

    assertThat(CrateUtils.isLib(crate(files))).isFalse();
  }

  @Test
  @DisplayName("isLib() is false for an empty crate")
  void rejectsEmptyCrate() throws IOException {
    assertThat(CrateUtils.isLib(crate(Map.of()))).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"serde-json, serde_json", "Serde-JSON, serde_json", "TITLE, title", "a_b, a_b"})
  @DisplayName("normalizeCrateName() lower-cases and replaces dashes with underscores")
  void normalizesCrateName(final String name, final String expected) {
    assertThat(CrateUtils.normalizeCrateName(name)).isEqualTo(expected);
  }
}
