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
package io.repsy.protocols.golang.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GoModuleZipReader")
class GoModuleZipReaderTest {

  private static final String MODULE = "example.com/mod";
  private static final String VERSION = "v1.0.0";
  private static final String GO_MOD_ENTRY = MODULE + "@" + VERSION + "/go.mod";
  private static final String HEAD = "module " + MODULE + "\n\ngo 1.23\n//";

  /** A go.mod of exactly {@code size} bytes: a valid head padded with a comment. */
  private static String goModOfSize(final long size) {
    return HEAD + "#".repeat((int) size - HEAD.length());
  }

  /**
   * A zip with the given entries, deflated: the entry headers then carry no size (a descriptor).
   */
  private static byte[] zip(final Map<String, String> entries) {
    return build(entries, false);
  }

  /** A zip with the given entries, stored: the entry headers then carry their size. */
  private static byte[] storedZip(final Map<String, String> entries) {
    return build(entries, true);
  }

  private static byte[] build(final Map<String, String> entries, final boolean stored) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      for (final var e : entries.entrySet()) {
        final var content = e.getValue().getBytes(StandardCharsets.UTF_8);
        final var entry = new ZipEntry(e.getKey());

        if (stored) {
          final var crc = new CRC32();

          crc.update(content);
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(content.length);
          entry.setCrc(crc.getValue());
        }

        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static Map<String, String> files(final String goMod) {
    final var files = new LinkedHashMap<String, String>();

    files.put(MODULE + "@" + VERSION + "/hello.go", "package hello\n");
    files.put(GO_MOD_ENTRY, goMod);

    return files;
  }

  @Test
  @DisplayName("returns the go.mod of the module")
  void returnsGoMod() {
    final var goMod = "module " + MODULE + "\n\ngo 1.23\n";

    final var content = GoModuleZipReader.extractGoMod(zip(files(goMod)), MODULE, VERSION);

    assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo(goMod);
  }

  @Test
  @DisplayName("returns an empty go.mod as empty content")
  void returnsEmptyGoMod() {
    assertThat(GoModuleZipReader.extractGoMod(zip(files("")), MODULE, VERSION)).isEmpty();
  }

  @Test
  @DisplayName("accepts a go.mod of exactly the limit, whether or not the header carries its size")
  void acceptsGoModAtLimit() {
    final var goMod = goModOfSize(GoModuleZipReader.MAX_GO_MOD_BYTES);

    assertThat(GoModuleZipReader.extractGoMod(zip(files(goMod)), MODULE, VERSION))
        .hasSize((int) GoModuleZipReader.MAX_GO_MOD_BYTES);
    assertThat(GoModuleZipReader.extractGoMod(storedZip(files(goMod)), MODULE, VERSION))
        .hasSize((int) GoModuleZipReader.MAX_GO_MOD_BYTES);
  }

  @Test
  @DisplayName("refuses a deflated go.mod over the limit, whose header carries no size")
  void refusesOversizedDeflatedGoMod() {
    final var bomb = zip(files(goModOfSize(GoModuleZipReader.MAX_GO_MOD_BYTES + 1)));

    assertThatThrownBy(() -> GoModuleZipReader.extractGoMod(bomb, MODULE, VERSION))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModTooLarge");
  }

  @Test
  @DisplayName("refuses a stored go.mod over the limit from its header")
  void refusesOversizedStoredGoMod() {
    final var bomb = storedZip(files(goModOfSize(GoModuleZipReader.MAX_GO_MOD_BYTES + 1)));

    assertThatThrownBy(() -> GoModuleZipReader.extractGoMod(bomb, MODULE, VERSION))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModTooLarge");
  }

  @Test
  @DisplayName("does not read a go.mod of another module or version, however large")
  void ignoresOtherGoMods() {
    final var files = new LinkedHashMap<String, String>();

    files.put(MODULE + "@" + VERSION + "/sub/go.mod", goModOfSize(1L << 25));
    files.put(MODULE + "@v9.9.9/go.mod", goModOfSize(1L << 25));
    files.put(GO_MOD_ENTRY, "module " + MODULE + "\n");

    final var content = GoModuleZipReader.extractGoMod(zip(files), MODULE, VERSION);

    assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("module " + MODULE + "\n");
  }

  @Test
  @DisplayName("reports a zip without the go.mod")
  void reportsMissingGoMod() {
    final var files = Map.of(MODULE + "@" + VERSION + "/hello.go", "package hello\n");

    assertThatThrownBy(() -> GoModuleZipReader.extractGoMod(zip(files), MODULE, VERSION))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("goModNotFoundInZip");
  }
}
