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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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

  private static boolean hasLib(final byte[] crateBytes) throws IOException {
    return CrateUtils.inspectCrate(new ByteArrayInputStream(crateBytes)).hasLib();
  }

  private static @Nullable String edition(final byte[] crateBytes) throws IOException {
    return CrateUtils.inspectCrate(new ByteArrayInputStream(crateBytes)).edition();
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
    files.put("demo-1.0.0/Cargo.toml", cargoTomlOfSize(CrateUtils.MAX_CARGO_TOML_BYTES));

    assertThat(hasLib(crate(files))).isTrue();
  }

  @Test
  @DisplayName("inspectCrate() refuses a Cargo.toml larger than the size limit, naming the limit")
  void refusesOversizedCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", cargoTomlOfSize(CrateUtils.MAX_CARGO_TOML_BYTES + 1));

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
    files.put("demo-1.0.0/sub/Cargo.toml", cargoTomlOfSize(CrateUtils.MAX_CARGO_TOML_BYTES + 1));

    final var crate = crate(files);

    assertThatThrownBy(() -> hasLib(crate)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("inspectCrate() does not inflate an oversized Cargo.toml it has no need to read")
  void skipsOversizedEntryThatIsNotCargoToml() throws IOException {
    final var files = new LinkedHashMap<String, String>();
    files.put("demo-1.0.0/Cargo.toml", "[package]\nname = \"demo\"\n");
    files.put("demo-1.0.0/README.md", "#".repeat((int) CrateUtils.MAX_CARGO_TOML_BYTES + 1));

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

  @ParameterizedTest
  @CsvSource({"serde-json, serde_json", "Serde-JSON, serde_json", "TITLE, title", "a_b, a_b"})
  @DisplayName("normalizeCrateName() lower-cases and replaces dashes with underscores")
  void normalizesCrateName(final String name, final String expected) {
    assertThat(CrateUtils.normalizeCrateName(name)).isEqualTo(expected);
  }

  /**
   * RPS-1072: the publish metadata that is stored in a length-limited column is held to the limit
   * before anything is written. A value that identifies the crate or says where it can be used is
   * rejected, a descriptive one is dropped.
   */
  @Nested
  @DisplayName("metadata length limits (RPS-1072)")
  class MetadataLimits {

    private CratePublishRequest request(
        final String vers,
        final @Nullable String rustVersion,
        final @Nullable String links,
        final @Nullable String homepage) {

      return new CratePublishRequest(
          "demo",
          vers,
          null,
          List.of(),
          Map.of(),
          List.of("Alice"),
          "a description",
          "https://docs.example.test",
          homepage,
          "the readme",
          "README.md",
          List.of("demo"),
          List.of("development-tools"),
          "MIT",
          "LICENSE",
          "https://example.test/demo.git",
          links,
          rustVersion,
          null,
          null);
    }

    private CratePublishRequest request() {
      return this.request("1.0.0", "1.70", "demo-sys", "https://example.test");
    }

    private static String of(final int length) {
      return "x".repeat(length);
    }

    @Test
    @DisplayName("accepts a version, rust-version and links of exactly the limit")
    void acceptsValuesAtTheLimit() {
      final var version = "1.0.0-" + of(CrateUtils.MAX_VERSION_LENGTH - "1.0.0-".length());
      final var request =
          this.request(
              version,
              of(CrateUtils.MAX_RUST_VERSION_LENGTH),
              of(CrateUtils.MAX_LINKS_LENGTH),
              null);

      assertThat(version).hasSize(CrateUtils.MAX_VERSION_LENGTH);
      assertThatCode(() -> CrateUtils.validatePublishRequest(request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a version one character over the limit, naming the field and limit")
    void rejectsOverLongVersion() {
      final var request =
          this.request(
              "1.0.0-" + of(CrateUtils.MAX_VERSION_LENGTH - "1.0.0-".length() + 1),
              null,
              null,
              null);

      assertThatThrownBy(() -> CrateUtils.validatePublishRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("version must be at most 64 characters");
    }

    @Test
    @DisplayName("rejects a rust-version one character over the limit")
    void rejectsOverLongRustVersion() {
      final var request =
          this.request("1.0.0", of(CrateUtils.MAX_RUST_VERSION_LENGTH + 1), null, null);

      assertThatThrownBy(() -> CrateUtils.validatePublishRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("rust-version must be at most 20 characters");
    }

    @Test
    @DisplayName("rejects links one character over the limit")
    void rejectsOverLongLinks() {
      final var request = this.request("1.0.0", null, of(CrateUtils.MAX_LINKS_LENGTH + 1), null);

      assertThatThrownBy(() -> CrateUtils.validatePublishRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("links must be at most 255 characters");
    }

    @Test
    @DisplayName("keeps every descriptive value that fits, whatever the limit")
    void keepsValuesAtTheLimit() {
      final var atLimit =
          new CratePublishRequest(
              "demo",
              "1.0.0",
              true,
              List.of(),
              Map.of(),
              List.of(of(CrateUtils.MAX_AUTHOR_LENGTH), "Alice"),
              "a description",
              of(CrateUtils.MAX_DOCUMENTATION_LENGTH),
              of(CrateUtils.MAX_HOMEPAGE_LENGTH),
              "the readme",
              "README.md",
              List.of("demo"),
              List.of(of(CrateUtils.MAX_CATEGORY_LENGTH)),
              of(CrateUtils.MAX_LICENSE_LENGTH),
              of(CrateUtils.MAX_LICENSE_FILE_LENGTH),
              of(CrateUtils.MAX_REPOSITORY_LENGTH),
              null,
              null,
              "abc",
              null);

      assertThat(CrateUtils.dropOverLongMetadata(atLimit)).isEqualTo(atLimit);
    }

    @Test
    @DisplayName("drops a homepage, repository, documentation, license and license file over 255")
    void dropsOverLongDescriptiveValues() {
      final var request =
          new CratePublishRequest(
              "demo",
              "1.0.0",
              true,
              List.of(),
              Map.of(),
              List.of("Alice"),
              "a description",
              of(CrateUtils.MAX_DOCUMENTATION_LENGTH + 1),
              of(CrateUtils.MAX_HOMEPAGE_LENGTH + 1),
              "the readme",
              "README.md",
              List.of("demo"),
              List.of("development-tools"),
              of(CrateUtils.MAX_LICENSE_LENGTH + 1),
              of(CrateUtils.MAX_LICENSE_FILE_LENGTH + 1),
              of(CrateUtils.MAX_REPOSITORY_LENGTH + 1),
              "demo-sys",
              "1.70",
              "abc",
              null);

      final var dropped = CrateUtils.dropOverLongMetadata(request);

      assertThat(dropped.documentation()).isNull();
      assertThat(dropped.homepage()).isNull();
      assertThat(dropped.license()).isNull();
      assertThat(dropped.licenseFile()).isNull();
      assertThat(dropped.repository()).isNull();
      assertThat(dropped)
          .as("nothing else changes")
          .isEqualTo(
              new CratePublishRequest(
                  "demo",
                  "1.0.0",
                  true,
                  List.of(),
                  Map.of(),
                  List.of("Alice"),
                  "a description",
                  null,
                  null,
                  "the readme",
                  "README.md",
                  List.of("demo"),
                  List.of("development-tools"),
                  null,
                  null,
                  null,
                  "demo-sys",
                  "1.70",
                  "abc",
                  null));
    }

    @Test
    @DisplayName("drops only the authors and categories over 255 and keeps the others in order")
    void dropsOverLongAuthorsAndCategories() {
      final var request = this.request();
      final var withLongEntries =
          new CratePublishRequest(
              request.name(),
              request.vers(),
              request.hasLib(),
              request.deps(),
              request.features(),
              List.of("Alice", of(CrateUtils.MAX_AUTHOR_LENGTH + 1), "Bob"),
              request.description(),
              request.documentation(),
              request.homepage(),
              request.readme(),
              request.readmeFile(),
              request.keywords(),
              List.of(of(CrateUtils.MAX_CATEGORY_LENGTH + 1), "development-tools"),
              request.license(),
              request.licenseFile(),
              request.repository(),
              request.links(),
              request.rustVersion(),
              request.cksum(),
              request.features2());

      final var dropped = CrateUtils.dropOverLongMetadata(withLongEntries);

      assertThat(dropped.authors()).containsExactly("Alice", "Bob");
      assertThat(dropped.categories()).containsExactly("development-tools");
    }

    @Test
    @DisplayName("leaves absent lists absent")
    void keepsAbsentLists() {
      final var request = this.request();
      final var withoutLists =
          new CratePublishRequest(
              request.name(),
              request.vers(),
              request.hasLib(),
              request.deps(),
              request.features(),
              null,
              request.description(),
              null,
              null,
              request.readme(),
              request.readmeFile(),
              request.keywords(),
              null,
              null,
              null,
              null,
              request.links(),
              request.rustVersion(),
              request.cksum(),
              request.features2());

      final var dropped = CrateUtils.dropOverLongMetadata(withoutLists);

      assertThat(dropped.authors()).isNull();
      assertThat(dropped.categories()).isNull();
    }
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
}
