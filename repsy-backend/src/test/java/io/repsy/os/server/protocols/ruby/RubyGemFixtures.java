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
package io.repsy.os.server.protocols.ruby;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** What the Ruby integration tests share to build and push gems. */
public final class RubyGemFixtures {

  /** {@code gem push} endpoint of a repo. */
  public static final String PUBLISH_PATH = "/{repo}/api/v1/gems";

  private RubyGemFixtures() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** A pure-Ruby gem with the default description. */
  public static byte[] gem(final String name, final String version) throws IOException {
    return gem(name, version, "ruby", "fixture");
  }

  /**
   * Builds the minimal outer tar {@code GemspecParser} needs ({@code metadata.gz} + {@code
   * data.tar.gz}), with representative metadata: two authors, a homepage, a required Ruby version
   * and one runtime and one development dependency.
   */
  public static byte[] gem(
      final String name, final String version, final String platform, final String description)
      throws IOException {
    return gem(
        name,
        version,
        platform,
        description,
        List.of("Alice", "Bob"),
        "https://example.test/" + name,
        "3.1.0");
  }

  /**
   * Like {@link #gem(String, String, String, String)}, with the length-limited metadata given: the
   * authors, the homepage and the version of the {@code >= x} required Ruby version (RPS-1071).
   * String values are quoted in the YAML, so a value such as {@code 1.0000} stays a string.
   */
  public static byte[] gem(
      final String name,
      final String version,
      final String platform,
      final String description,
      final List<String> authors,
      final String homepage,
      final String requiredRubyVersion)
      throws IOException {
    final var authorLines =
        authors.stream().map(author -> "- \"" + author + "\"").collect(Collectors.joining("\n"));
    final var metadata =
        """
        name: "%s"
        version:
          version: "%s"
        platform: "%s"
        description: %s
        authors:
        %s
        homepage: "%s"
        required_ruby_version:
          requirements:
          - - ">="
            - version: "%s"
        dependencies:
        - name: rack
          type: runtime
          requirement:
            requirements:
            - - ">="
              - version: 3.0.0
        - name: rake
          type: development
          requirement:
            requirements:
            - - ">="
              - version: 13.0.0
        """
            .formatted(
                name, version, platform, description, authorLines, homepage, requiredRubyVersion);
    final var output = new ByteArrayOutputStream();

    try (var tar = new TarArchiveOutputStream(output)) {
      add(tar, "metadata.gz", gzip(metadata.getBytes(StandardCharsets.UTF_8)));
      add(tar, "data.tar.gz", gzip(new byte[0]));
      tar.finish();
    }

    return output.toByteArray();
  }

  private static byte[] gzip(final byte[] bytes) throws IOException {
    final var output = new ByteArrayOutputStream();

    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(bytes);
    }

    return output.toByteArray();
  }

  private static void add(final TarArchiveOutputStream tar, final String name, final byte[] bytes)
      throws IOException {
    final var entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }
}
