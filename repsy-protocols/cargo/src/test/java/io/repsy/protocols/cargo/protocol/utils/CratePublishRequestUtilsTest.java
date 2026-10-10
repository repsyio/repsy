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
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("CratePublishRequestUtils")
class CratePublishRequestUtilsTest {

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
      assertThatCode(() -> CratePublishRequestUtils.validatePublishRequest(request))
          .doesNotThrowAnyException();
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

      assertThatThrownBy(() -> CratePublishRequestUtils.validatePublishRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("version must be at most 64 characters");
    }

    @Test
    @DisplayName("rejects a rust-version one character over the limit")
    void rejectsOverLongRustVersion() {
      final var request =
          this.request("1.0.0", of(CrateUtils.MAX_RUST_VERSION_LENGTH + 1), null, null);

      assertThatThrownBy(() -> CratePublishRequestUtils.validatePublishRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("rust-version must be at most 20 characters");
    }

    @Test
    @DisplayName("rejects links one character over the limit")
    void rejectsOverLongLinks() {
      final var request = this.request("1.0.0", null, of(CrateUtils.MAX_LINKS_LENGTH + 1), null);

      assertThatThrownBy(() -> CratePublishRequestUtils.validatePublishRequest(request))
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

      assertThat(CratePublishRequestUtils.dropOverLongMetadata(atLimit)).isEqualTo(atLimit);
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

      final var dropped = CratePublishRequestUtils.dropOverLongMetadata(request);

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

      final var dropped = CratePublishRequestUtils.dropOverLongMetadata(withLongEntries);

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

      final var dropped = CratePublishRequestUtils.dropOverLongMetadata(withoutLists);

      assertThat(dropped.authors()).isNull();
      assertThat(dropped.categories()).isNull();
    }
  }

  @Nested
  @DisplayName("validatePublishRequest() rules besides the column limits")
  class PublishValidation {

    private CratePublishRequest request(
        final @Nullable String name,
        final @Nullable String vers,
        final @Nullable List<String> keywords) {

      return new CratePublishRequest(
          name, vers, null, null, null, null, null, null, null, null, null, keywords, null, null,
          null, null, null, null, null, null);
    }

    @Test
    @DisplayName("accepts a plain crate")
    void accepts() {
      assertThatCode(
              () ->
                  CratePublishRequestUtils.validatePublishRequest(
                      this.request("serde_json-2", "1.0.0-alpha.1+build", List.of("a", "b"))))
          .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "|crate name cannot be empty",
          "' '|crate name cannot be empty",
          "-bad|crate name `-bad` must start with an alphanumeric character and contain only"
              + " alphanumerics, `-`, or `_`",
          "bad name|crate name `bad name` must start with an alphanumeric character and contain"
              + " only alphanumerics, `-`, or `_`",
        })
    @DisplayName("refuses a name that is blank or has characters Cargo does not allow")
    void refusesName(final @Nullable String name, final String message) {
      assertThatThrownBy(
              () ->
                  CratePublishRequestUtils.validatePublishRequest(
                      this.request(name, "1.0.0", null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(message);
    }

    @Test
    @DisplayName("refuses a name longer than 64 characters")
    void refusesLongName() {
      final var name = "a".repeat(65);

      assertThatThrownBy(
              () ->
                  CratePublishRequestUtils.validatePublishRequest(
                      this.request(name, "1.0.0", null)))
          .hasMessage("crate name `%s` must be at most 64 characters".formatted(name));
    }

    @Test
    @DisplayName("refuses a blank version and one that is not semver")
    void refusesVersion() {
      assertThatThrownBy(
              () -> CratePublishRequestUtils.validatePublishRequest(this.request("a", " ", null)))
          .hasMessage("version cannot be empty");
      assertThatThrownBy(
              () -> CratePublishRequestUtils.validatePublishRequest(this.request("a", "1.x", null)))
          .hasMessage("version `1.x` is not a valid semver format (expected MAJOR.MINOR.PATCH)");
    }

    @Test
    @DisplayName("refuses more than five keywords and a keyword over 20 characters")
    void refusesKeywords() {
      final var six = List.of("a", "b", "c", "d", "e", "f");

      assertThatThrownBy(
              () ->
                  CratePublishRequestUtils.validatePublishRequest(this.request("a", "1.0.0", six)))
          .hasMessage("a crate may have at most 5 keywords, got 6");
      assertThatThrownBy(
              () ->
                  CratePublishRequestUtils.validatePublishRequest(
                      this.request("a", "1.0.0", List.of("k".repeat(21)))))
          .hasMessage("keyword `%s` must be at most 20 characters".formatted("k".repeat(21)));
    }
  }

  private final tools.jackson.databind.ObjectMapper mapper =
      new tools.jackson.databind.ObjectMapper();

  private CratePublishRequest request(final @Nullable Map<String, List<String>> features2) {
    final var plain =
        new io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep(
            "serde", "^1", List.of("derive"), true, false, "cfg(unix)", "dev", null, null);
    final var renamed =
        new io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep(
            "real-name", "=2", List.of(), false, true, null, "normal", "https://r", "alias");

    return new CratePublishRequest(
        "demo",
        "1.2.3",
        true,
        List.of(plain, renamed),
        Map.of("f", List.of("serde")),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "demo-sys",
        "1.70",
        "abc123",
        features2);
  }

  @Test
  @DisplayName("getIndexJsonLine maps deps, renames and the index version")
  void indexLine() {
    final var json =
        this.mapper.readTree(
            CratePublishRequestUtils.getIndexJsonLine(this.request(null), this.mapper));

    assertThat(json.get("name").asString()).isEqualTo("demo");
    assertThat(json.get("vers").asString()).isEqualTo("1.2.3");
    assertThat(json.get("cksum").asString()).isEqualTo("abc123");
    assertThat(json.get("yanked").asBoolean()).isFalse();
    assertThat(json.get("v").asInt()).isEqualTo(1);
    assertThat(json.get("links").asString()).isEqualTo("demo-sys");
    assertThat(json.get("rust_version").asString()).isEqualTo("1.70");
    assertThat(json.has("features2")).isFalse();
    assertThat(json.get("features").get("f").get(0).asString()).isEqualTo("serde");

    final var plain = json.get("deps").get(0);
    assertThat(plain.get("name").asString()).isEqualTo("serde");
    assertThat(plain.get("req").asString()).isEqualTo("^1");
    assertThat(plain.get("optional").asBoolean()).isTrue();
    assertThat(plain.get("default_features").asBoolean()).isFalse();
    assertThat(plain.get("target").asString()).isEqualTo("cfg(unix)");
    assertThat(plain.get("kind").asString()).isEqualTo("dev");
    assertThat(plain.has("package")).isFalse();

    final var renamed = json.get("deps").get(1);
    assertThat(renamed.get("name").asString()).isEqualTo("alias");
    assertThat(renamed.get("package").asString()).isEqualTo("real-name");
    assertThat(renamed.get("registry").asString()).isEqualTo("https://r");
  }

  @Test
  @DisplayName("getIndexJsonLine is version 2 when features2 is present, with empty defaults")
  void indexLineVersion2() {
    final var withFeatures2 = this.request(Map.of("g", List.of("dep:x")));
    final var json =
        this.mapper.readTree(CratePublishRequestUtils.getIndexJsonLine(withFeatures2, this.mapper));

    assertThat(json.get("v").asInt()).isEqualTo(2);
    assertThat(json.get("features2").get("g").get(0).asString()).isEqualTo("dep:x");

    final var bare =
        new CratePublishRequest(
            "demo", "1.0.0", null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, "c", null);
    final var bareJson =
        this.mapper.readTree(CratePublishRequestUtils.getIndexJsonLine(bare, this.mapper));
    assertThat(bareJson.get("deps")).isEmpty();
    assertThat(bareJson.get("features")).isEmpty();
  }

  @Test
  @DisplayName("createCratePublishRequestWithChecksum sets the checksum and lib flag only")
  void withChecksum() {
    final var original = this.request(null);

    final var copy =
        CratePublishRequestUtils.createCratePublishRequestWithChecksum(original, "deadbeef", false);

    assertThat(copy.cksum()).isEqualTo("deadbeef");
    assertThat(copy.hasLib()).isFalse();
    assertThat(copy)
        .usingRecursiveComparison()
        .ignoringFields("cksum", "hasLib")
        .isEqualTo(original);
  }
}
