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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The expected {@code h1:} values below are not taken from this class's own implementation: they
 * were computed independently with Python's {@code hashlib} (sha256 + base64), following {@code
 * golang.org/x/mod/sumdb/dirhash}'s {@code Hash1} algorithm by hand (sort entries by name, each
 * line {@code hex(sha256(content)) + " " + name + "\n"}, outer hash {@code "h1:" +
 * base64(sha256(joined lines))}), and cross-checked against the same algorithm's TypeScript
 * reimplementation in {@code e2e/src/clients/golang-raw.ts}'s {@code dirhashHash1} (built for
 * RPS-294 and itself independently confirmed live against a real {@code go mod download -json}). A
 * regression to the pre-RPS-1231 format (colon-separated, name first) would fail every test in this
 * class.
 */
@DisplayName("GoModuleHashCalculator")
class GoModuleHashCalculatorTest {

  private static final String MOD_CONTENT = "module example.com/mod\n\ngo 1.21\n";

  /**
   * {@code h1:} of a single line {@code sha256hex(MOD_CONTENT) + " go.mod\n"}, i.e. {@code
   * dirhash.HashGoMod(MOD_CONTENT)}.
   */
  private static final String EXPECTED_H1_MOD = "h1:6Olo59fmoKf9DlNu5tKoc/bk5pj1sXqF+kzl7w/es4E=";

  /**
   * {@code h1:} of two entries, {@code .../go.mod} (= MOD_CONTENT) and {@code .../hello.go} (=
   * {@code "package mod\n"}), sorted by name (go.mod before hello.go) before being joined.
   */
  private static final String EXPECTED_H1_ZIP = "h1:zDM7FWRTVC2I9HVwUb0GbzZhaoKSkjFwg+lIvej2LYs=";

  /** Well-known SHA-256 test vector: the digest of the empty string. */
  private static final String SHA256_OF_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private static byte[] zip(final Map<String, String> entries) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      for (final var e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  @Test
  @DisplayName("computeSha256Hex matches the well-known SHA-256 of the empty string")
  void computeSha256HexMatchesKnownVector() {
    assertThat(GoModuleHashCalculator.computeSha256Hex(new byte[0])).isEqualTo(SHA256_OF_EMPTY);
  }

  @Test
  @DisplayName("hashMod matches a known-correct dirhash.HashGoMod value (RPS-1231)")
  void hashModMatchesKnownValue() {
    final var hash = GoModuleHashCalculator.hashMod(MOD_CONTENT.getBytes(StandardCharsets.UTF_8));

    assertThat(hash).isEqualTo(EXPECTED_H1_MOD);
  }

  @Test
  @DisplayName(
      "hashZip matches a known-correct dirhash.Hash1 value, entries sorted by name regardless of "
          + "their order in the zip (RPS-1231)")
  void hashZipMatchesKnownValueRegardlessOfEntryOrder() {
    // hello.go is added before go.mod: a correct implementation sorts by name before hashing, so
    // the result is the same as if go.mod had come first.
    final var entries = new LinkedHashMap<String, String>();
    entries.put("example.com/mod@v1.0.0/hello.go", "package mod\n");
    entries.put("example.com/mod@v1.0.0/go.mod", MOD_CONTENT);

    final var hash = GoModuleHashCalculator.hashZip(new ByteArrayInputStream(zip(entries)));

    assertThat(hash).isEqualTo(EXPECTED_H1_ZIP);
  }

  @Test
  @DisplayName("skips directory entries")
  void skipsDirectoryEntries() {
    final var withoutDir =
        GoModuleHashCalculator.hashZip(
            new ByteArrayInputStream(zip(Map.of("example.com/mod@v1.0.0/go.mod", MOD_CONTENT))));

    final var out = new ByteArrayOutputStream();
    try (final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("example.com/mod@v1.0.0/"));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("example.com/mod@v1.0.0/go.mod"));
      zip.write(MOD_CONTENT.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    final var withDir = GoModuleHashCalculator.hashZip(new ByteArrayInputStream(out.toByteArray()));

    assertThat(withDir).isEqualTo(withoutDir);
  }

  @Test
  @DisplayName("refuses an entry name containing a newline (RPS-1231)")
  void refusesNewlineInEntryName() {
    final var zip = zip(Map.of("example.com/mod@v1.0.0/weird\nname.txt", "content"));

    assertThatThrownBy(() -> GoModuleHashCalculator.hashZip(new ByteArrayInputStream(zip)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("moduleZipEntryNameInvalid");
  }

  @Test
  @DisplayName("refuses a zip whose entries inflate past the configured limit (RPS-1118)")
  void refusesOversizedInflation() {
    final var zip = zip(Map.of("example.com/mod@v1.0.0/big.txt", "a".repeat(1000)));

    assertThatThrownBy(
            () ->
                GoModuleHashCalculator.hashZip(
                    new ByteArrayInputStream(zip), 999L, GoModuleHashCalculator.MAX_ENTRY_COUNT))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("moduleZipTooLarge");
  }

  @Test
  @DisplayName("accepts a zip whose entries inflate to exactly the configured limit")
  void acceptsInflationAtLimit() {
    final var zip = zip(Map.of("example.com/mod@v1.0.0/big.txt", "a".repeat(1000)));

    assertThatCode(
            () ->
                GoModuleHashCalculator.hashZip(
                    new ByteArrayInputStream(zip), 1000L, GoModuleHashCalculator.MAX_ENTRY_COUNT))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a zip with more entries than the configured limit (RPS-1118)")
  void refusesTooManyEntries() {
    final var entries = new LinkedHashMap<String, String>();
    entries.put("example.com/mod@v1.0.0/a.txt", "a");
    entries.put("example.com/mod@v1.0.0/b.txt", "b");
    final var zip = zip(entries);

    assertThatThrownBy(
            () ->
                GoModuleHashCalculator.hashZip(
                    new ByteArrayInputStream(zip), GoModuleHashCalculator.MAX_INFLATED_BYTES, 1))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("moduleZipTooManyFiles");
  }

  @Test
  @DisplayName("accepts a zip with exactly the configured number of entries")
  void acceptsEntryCountAtLimit() {
    final var entries = new LinkedHashMap<String, String>();
    entries.put("example.com/mod@v1.0.0/a.txt", "a");
    entries.put("example.com/mod@v1.0.0/b.txt", "b");
    final var zip = zip(entries);

    assertThatCode(
            () ->
                GoModuleHashCalculator.hashZip(
                    new ByteArrayInputStream(zip), GoModuleHashCalculator.MAX_INFLATED_BYTES, 2))
        .doesNotThrowAnyException();
  }
}
