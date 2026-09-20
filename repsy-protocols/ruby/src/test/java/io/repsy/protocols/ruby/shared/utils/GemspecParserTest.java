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
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
