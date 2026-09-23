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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GemspecParser")
class GemspecParserTest {

  private static final String HEADER =
      """
      --- !ruby/object:Gem::Specification
      name: demo
      version: !ruby/object:Gem::Version
        version: 1.2.3
      platform: ruby
      authors:
      - Ada
      - Grace
      """;

  /** Wraps a gemspec YAML document into the outer tar of a .gem (metadata.gz only). */
  private static byte[] gem(final String yaml) throws IOException {
    final var gzipped = new ByteArrayOutputStream();
    try (final var out = new GZIPOutputStream(gzipped)) {
      out.write(yaml.getBytes(StandardCharsets.UTF_8));
    }

    final var tarBytes = new ByteArrayOutputStream();
    try (final var tar = new TarArchiveOutputStream(tarBytes)) {
      final var entry = new TarArchiveEntry("metadata.gz");
      entry.setSize(gzipped.size());
      tar.putArchiveEntry(entry);
      tar.write(gzipped.toByteArray());
      tar.closeArchiveEntry();
    }
    return tarBytes.toByteArray();
  }

  private static GemMetadata parse(final byte[] gem) {
    return GemspecParser.parse(new ByteArrayInputStream(gem));
  }

  private static byte[] tarOf(final String entryName, final byte[] content) throws IOException {
    final var tarBytes = new ByteArrayOutputStream();
    try (final var tar = new TarArchiveOutputStream(tarBytes)) {
      final var entry = new TarArchiveEntry(entryName);
      entry.setSize(content.length);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
    }
    return tarBytes.toByteArray();
  }

  private static GemDependency dependency(
      final String name, final String requirements, final String type) {
    return GemDependency.builder().name(name).requirements(requirements).type(type).build();
  }

  @Test
  @DisplayName("parse() splits the dependencies into runtime and development ones")
  void splitsRuntimeAndDevelopmentDependencies() throws IOException {
    final var yaml =
        HEADER
            + """
            dependencies:
            - !ruby/object:Gem::Dependency
              name: rack
              requirement: !ruby/object:Gem::Requirement
                requirements:
                - - "~>"
                  - !ruby/object:Gem::Version
                    version: '2.0'
              type: :runtime
            - !ruby/object:Gem::Dependency
              name: rspec
              requirement: !ruby/object:Gem::Requirement
                requirements:
                - - ">="
                  - !ruby/object:Gem::Version
                    version: '3.0'
                - - "<"
                  - !ruby/object:Gem::Version
                    version: '4'
              type: :development
            """;

    final var metadata = parse(gem(yaml));

    assertThat(metadata.getName()).isEqualTo("demo");
    assertThat(metadata.getVersion()).isEqualTo("1.2.3");
    assertThat(metadata.getAuthors()).isEqualTo("Ada, Grace");
    assertThat(metadata.getRuntimeDependencies())
        .containsExactly(dependency("rack", "~> 2.0", "runtime"));
    assertThat(metadata.getDevelopmentDependencies())
        .containsExactly(dependency("rspec", ">= 3.0, < 4", "development"));
  }

  @Test
  @DisplayName("parse() skips dependencies that are not maps or have no string name")
  void skipsMalformedDependencies() throws IOException {
    final var yaml =
        HEADER
            + """
            dependencies:
            - just-a-string
            - name: 42
              type: :runtime
            - type: :runtime
            - name: thor
            """;

    final var metadata = parse(gem(yaml));

    assertThat(metadata.getRuntimeDependencies())
        .containsExactly(dependency("thor", ">= 0", "runtime"));
    assertThat(metadata.getDevelopmentDependencies()).isEmpty();
  }

  @Test
  @DisplayName("parse() returns no dependencies when the gemspec has none")
  void handlesMissingDependencies() throws IOException {
    final var metadata = parse(gem(HEADER));

    assertThat(metadata.getRuntimeDependencies()).isEmpty();
    assertThat(metadata.getDevelopmentDependencies()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "extensions: !!javax.script.ScriptEngineManager\n  key: value\n",
        "extensions: !!java.net.URL [\"http://localhost/\"]\n",
        "extensions: !<tag:yaml.org,2002:javax.script.ScriptEngineManager>\n  key: value\n"
      })
  @DisplayName("parse() rejects a global tag that would instantiate an arbitrary class")
  void rejectsGlobalTags(final String extensions) throws IOException {
    final var gem = gem(HEADER + extensions);

    assertThatThrownBy(() -> parse(gem))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("invalidGemFile");
  }

  @Test
  @DisplayName("parse() finds metadata.gz after an entry it has to skip over, as in a real gem")
  void skipsEntriesBeforeMetadata() throws IOException {
    final var gzipped = new ByteArrayOutputStream();
    try (final var out = new GZIPOutputStream(gzipped)) {
      out.write(HEADER.getBytes(StandardCharsets.UTF_8));
    }

    final var tarBytes = new ByteArrayOutputStream();
    try (final var tar = new TarArchiveOutputStream(tarBytes)) {
      final var data = new byte[100_000];
      final var dataEntry = new TarArchiveEntry("data.tar.gz");
      dataEntry.setSize(data.length);
      tar.putArchiveEntry(dataEntry);
      tar.write(data);
      tar.closeArchiveEntry();

      final var metadataEntry = new TarArchiveEntry("metadata.gz");
      metadataEntry.setSize(gzipped.size());
      tar.putArchiveEntry(metadataEntry);
      tar.write(gzipped.toByteArray());
      tar.closeArchiveEntry();
    }

    assertThat(parse(tarBytes.toByteArray()).getName()).isEqualTo("demo");
  }

  @Test
  @DisplayName("parse() refuses a metadata.gz larger than the cap without reading it")
  void rejectsOversizedMetadata() throws IOException {
    final var gem = tarOf("metadata.gz", new byte[(int) GemspecParser.MAX_METADATA_GZ_BYTES + 1]);

    assertThatThrownBy(() -> parse(gem))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gemMetadataTooLarge");
  }

  @Test
  @DisplayName("parse() rejects a gem without a metadata.gz entry")
  void rejectsGemWithoutMetadata() throws IOException {
    final var gem = tarOf("data.tar.gz", new byte[10]);

    assertThatThrownBy(() -> parse(gem))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("invalidGemFile");
  }

  @Test
  @DisplayName("parse() rejects something that is not a tar archive")
  void rejectsNonTar() {
    final var notAGem = new byte[2048];
    Arrays.fill(notAGem, (byte) 'x');

    assertThatThrownBy(() -> parse(notAGem))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("invalidGemFile");
  }

  /**
   * RPS-1071: a value longer than its {@code ruby_gem_version} column used to fail the row insert
   * with a {@code DataIntegrityViolationException} that named no field. Each column now has a
   * policy that is applied while the metadata is read, before anything is written.
   */
  @Nested
  @DisplayName("over-long gemspec metadata (RPS-1071)")
  class OverLongMetadata {

    private static final List<String> AUTHORS = List.of("Ada", "Grace");

    /** A gemspec whose length-limited fields are the given ones; the rest are ordinary values. */
    private static byte[] gemWith(
        final String name,
        final String version,
        final String platform,
        final List<String> authors,
        final String homepage,
        final String rubyRequirement)
        throws IOException {

      final var authorLines =
          authors.stream().map(a -> "- \"" + a + "\"").reduce((a, b) -> a + "\n" + b).orElseThrow();
      final var yaml =
          """
          name: "%s"
          version:
            version: "%s"
          platform: "%s"
          authors:
          %s
          homepage: "%s"
          required_ruby_version:
            requirements:
            - - ">="
              - version: "%s"
          """
              .formatted(name, version, platform, authorLines, homepage, rubyRequirement);

      return gem(yaml);
    }

    private static byte[] gemWithAuthors(final List<String> authors) throws IOException {
      return gemWith("demo", "1.0.0", "ruby", authors, "https://example.test", "3.1.0");
    }

    private static byte[] gemWithHomepage(final String homepage) throws IOException {
      return gemWith("demo", "1.0.0", "ruby", AUTHORS, homepage, "3.1.0");
    }

    /** The required Ruby version is stored as {@code ">= <version>"}, so it is 3 longer. */
    private static String rubyRequirementOfLength(final int storedLength) {
      return "1".repeat(storedLength - ">= ".length());
    }

    static Stream<Arguments> rejectedColumns() throws IOException {
      final var over = "a".repeat(GemspecParser.MAX_NAME_LENGTH + 1);
      return Stream.of(
          Arguments.of(
              "name",
              gemWith(over, "1.0.0", "ruby", AUTHORS, "https://example.test", "3.1.0"),
              "gemNameTooLong"),
          Arguments.of(
              "version",
              gemWith(
                  "demo",
                  "1." + "0".repeat(GemspecParser.MAX_VERSION_LENGTH - 1),
                  "ruby",
                  AUTHORS,
                  "https://example.test",
                  "3.1.0"),
              "gemVersionTooLong"),
          Arguments.of(
              "platform",
              gemWith(
                  "demo",
                  "1.0.0",
                  "p".repeat(GemspecParser.MAX_PLATFORM_LENGTH + 1),
                  AUTHORS,
                  "https://example.test",
                  "3.1.0"),
              "gemPlatformTooLong"),
          Arguments.of(
              "required_ruby_version",
              gemWith(
                  "demo",
                  "1.0.0",
                  "ruby",
                  AUTHORS,
                  "https://example.test",
                  rubyRequirementOfLength(GemspecParser.MAX_REQUIRED_RUBY_VERSION_LENGTH + 1)),
              "gemRequiredRubyVersionTooLong"));
    }

    static Stream<Arguments> keptColumns() throws IOException {
      final var name = "n".repeat(GemspecParser.MAX_NAME_LENGTH);
      final var version = "1." + "0".repeat(GemspecParser.MAX_VERSION_LENGTH - 2);
      final var platform = "p".repeat(GemspecParser.MAX_PLATFORM_LENGTH);
      final var ruby = rubyRequirementOfLength(GemspecParser.MAX_REQUIRED_RUBY_VERSION_LENGTH);
      final var gem = gemWith(name, version, platform, AUTHORS, "https://example.test", ruby);

      return Stream.of(Arguments.of(gem, name, version, platform, ">= " + ruby));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedColumns")
    @DisplayName("parse() rejects a value one over the column limit and names the field")
    void rejectsOverLongIdentifiers(
        final String column, final byte[] gem, final String expectedMsgId) {

      assertThatThrownBy(() -> parse(gem))
          .as(column)
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining(expectedMsgId);
    }

    @ParameterizedTest
    @MethodSource("keptColumns")
    @DisplayName("parse() keeps name, version, platform and required Ruby version at the limit")
    void keepsIdentifiersAtTheLimit(
        final byte[] gem,
        final String name,
        final String version,
        final String platform,
        final String requiredRubyVersion) {

      final var metadata = parse(gem);

      assertThat(metadata.getName()).isEqualTo(name);
      assertThat(metadata.getVersion()).isEqualTo(version);
      assertThat(metadata.getPlatform()).isEqualTo(platform);
      assertThat(metadata.getRequiredRubyVersion()).isEqualTo(requiredRubyVersion);
    }

    @Test
    @DisplayName("parse() keeps a homepage at the limit and drops one that is a character over")
    void dropsOverLongHomepage() throws IOException {
      final var atLimit = "h".repeat(GemspecParser.MAX_HOMEPAGE_LENGTH);

      assertThat(parse(gemWithHomepage(atLimit)).getHomepage()).isEqualTo(atLimit);
      assertThat(parse(gemWithHomepage(atLimit + "h")).getHomepage()).isNull();
    }

    @Test
    @DisplayName("parse() keeps authors that fill the column exactly")
    void keepsAuthorsAtTheLimit() throws IOException {
      // 40 authors of 10 characters and 39 separators, then one that brings it to the limit.
      final var authors = manyAuthors(40, 10);
      final var joined = String.join(", ", authors);
      final var last = "z".repeat(GemspecParser.MAX_AUTHORS_LENGTH - joined.length() - 2);
      final var all = Stream.concat(authors.stream(), Stream.of(last)).toList();

      final var stored = parse(gemWithAuthors(all)).getAuthors();

      assertThat(stored).isEqualTo(String.join(", ", all));
      assertThat(stored).hasSize(GemspecParser.MAX_AUTHORS_LENGTH);
    }

    @Test
    @DisplayName("parse() cuts over-long authors after the last author that fits")
    void cutsAuthorsAtAnAuthorBoundary() throws IOException {
      final var authors = manyAuthors(40, 10);
      final var joined = String.join(", ", authors);
      // One character over the limit once the last author is added.
      final var last = "z".repeat(GemspecParser.MAX_AUTHORS_LENGTH - joined.length() - 2 + 1);
      final var all = Stream.concat(authors.stream(), Stream.of(last)).toList();

      final var stored = parse(gemWithAuthors(all)).getAuthors();

      assertThat(stored).isEqualTo(joined);
    }

    @Test
    @DisplayName("parse() keeps an author whose separator starts right at the limit")
    void keepsTheAuthorThatEndsAtTheLimit() throws IOException {
      final var first = "a".repeat(GemspecParser.MAX_AUTHORS_LENGTH);

      final var stored = parse(gemWithAuthors(List.of(first, "b"))).getAuthors();

      assertThat(stored).isEqualTo(first);
    }

    @Test
    @DisplayName("parse() cuts a single author that alone is over-long")
    void cutsASingleOverLongAuthor() throws IOException {
      final var stored =
          parse(gemWithAuthors(List.of("x".repeat(GemspecParser.MAX_AUTHORS_LENGTH + 88))))
              .getAuthors();

      assertThat(stored).isEqualTo("x".repeat(GemspecParser.MAX_AUTHORS_LENGTH));
    }

    @Test
    @DisplayName("parse() does not split a character that is two UTF-16 units when it cuts")
    void doesNotSplitASurrogatePair() throws IOException {
      // The emoji starts at unit 511, so a cut at 512 would keep only its high surrogate.
      final var author =
          "a".repeat(GemspecParser.MAX_AUTHORS_LENGTH - 1) + "\uD83D\uDE00" + "b".repeat(10);

      final var stored = parse(gemWithAuthors(List.of(author))).getAuthors();

      assertThat(stored).isEqualTo("a".repeat(GemspecParser.MAX_AUTHORS_LENGTH - 1));
    }

    @Test
    @DisplayName("parse() keeps multi-byte authors that fit the column in characters")
    void countsCharactersNotBytes() throws IOException {
      final var author = "\u00e9".repeat(GemspecParser.MAX_AUTHORS_LENGTH);

      assertThat(parse(gemWithAuthors(List.of(author))).getAuthors()).isEqualTo(author);
    }

    private static List<String> manyAuthors(final int count, final int length) {
      return java.util.stream.IntStream.range(0, count).mapToObj(i -> "a".repeat(length)).toList();
    }
  }

  /**
   * RPS-1135: a dependency's name and its formatted requirement list, stored in {@code
   * ruby_gem_dependency}, used to fail the row insert the same way the {@code ruby_gem_version}
   * columns did before RPS-1071. Both are now rejected with a 400 that names the field, before
   * anything is written.
   */
  @Nested
  @DisplayName("over-long dependency metadata (RPS-1135)")
  class OverLongDependencies {

    /** A gemspec whose one dependency has the given name and {@code >= <version>} requirement. */
    private static byte[] gemWithDependency(final String name, final String version)
        throws IOException {
      final var yaml =
          HEADER
              + """
              dependencies:
              - !ruby/object:Gem::Dependency
                name: "%s"
                requirement: !ruby/object:Gem::Requirement
                  requirements:
                  - - ">="
                    - !ruby/object:Gem::Version
                      version: "%s"
                type: :runtime
              """
                  .formatted(name, version);

      return gem(yaml);
    }

    @Test
    @DisplayName("parse() rejects a dependency name one over the column limit and names the field")
    void rejectsOverLongDependencyName() throws IOException {
      final var name = "d".repeat(GemspecParser.MAX_DEPENDENCY_NAME_LENGTH + 1);

      assertThatThrownBy(() -> parse(gemWithDependency(name, "1.0")))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("gemDependencyNameTooLong");
    }

    @Test
    @DisplayName("parse() keeps a dependency name that fills the column exactly")
    void keepsDependencyNameAtTheLimit() throws IOException {
      final var name = "d".repeat(GemspecParser.MAX_DEPENDENCY_NAME_LENGTH);

      final var metadata = parse(gemWithDependency(name, "1.0"));

      assertThat(metadata.getRuntimeDependencies())
          .containsExactly(dependency(name, ">= 1.0", "runtime"));
    }

    @Test
    @DisplayName(
        "parse() rejects a dependency requirement one over the column limit and names the field")
    void rejectsOverLongDependencyRequirements() throws IOException {
      // Stored as ">= <version>", so 3 characters longer than the version alone.
      final var version = "1".repeat(GemspecParser.MAX_DEPENDENCY_REQUIREMENTS_LENGTH - 2);

      assertThatThrownBy(() -> parse(gemWithDependency("demo-dep", version)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("gemDependencyRequirementsTooLong");
    }

    @Test
    @DisplayName("parse() keeps a dependency requirement that fills the column exactly")
    void keepsDependencyRequirementsAtTheLimit() throws IOException {
      final var version = "1".repeat(GemspecParser.MAX_DEPENDENCY_REQUIREMENTS_LENGTH - 3);

      final var metadata = parse(gemWithDependency("demo-dep", version));

      assertThat(metadata.getRuntimeDependencies())
          .containsExactly(dependency("demo-dep", ">= " + version, "runtime"));
      assertThat(metadata.getRuntimeDependencies().get(0).getRequirements())
          .hasSize(GemspecParser.MAX_DEPENDENCY_REQUIREMENTS_LENGTH);
    }
  }
}
