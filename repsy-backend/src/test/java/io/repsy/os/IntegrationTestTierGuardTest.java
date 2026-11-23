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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the opt-out tiering of the integration tests (RPS-2729).
 *
 * <p>A class tagged {@code extended} is left out of a run that passes {@code
 * -Dit.excluded.groups=extended}; untagged classes run everywhere. A mistyped tag would silently
 * stay in every run, and an over-eager tagging could leave a protocol with nothing on the pull
 * request, so this test scans the sources (no Docker, runs in {@code mvn test}) and fails when:
 *
 * <ul>
 *   <li>a {@code @Tag} value is not in {@link #KNOWN_TAGS}, or
 *   <li>a protocol package under {@code server/protocols/} (except {@code shared}) has no {@code
 *       *IT} without an {@code extended} tag, so that the protocol would not be exercised when the
 *       tier is excluded.
 * </ul>
 *
 * <p>Only {@code @Tag("...")} with a string literal is seen. Comments are ignored.
 */
@DisplayName("integration test tier tags are known and every protocol keeps an untagged test")
class IntegrationTestTierGuardTest {

  private static final Path TEST_SOURCES = Path.of("src", "test", "java");

  private static final Path PROTOCOLS = Path.of("io", "repsy", "os", "server", "protocols");

  /** Directories under the protocols package that are not a protocol. */
  private static final Set<String> NOT_PROTOCOLS = Set.of("shared");

  /** This file holds sample sources with bogus tags in text blocks. */
  private static final String SELF = "IntegrationTestTierGuardTest.java";

  private static final Set<String> KNOWN_TAGS = Set.of("extended");

  private static final Pattern TAG = Pattern.compile("@Tag\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

  private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

  @Test
  @DisplayName("every @Tag in the test sources is a known tier tag")
  void tagsAreKnown() throws IOException {
    final var violations = new ArrayList<String>();

    try (final Stream<Path> files = Files.walk(TEST_SOURCES)) {
      for (final var file :
          files
              .filter(f -> f.toString().endsWith(".java"))
              .filter(f -> !f.getFileName().toString().equals(SELF))
              .toList()) {
        violations.addAll(unknownTags(file.getFileName().toString(), read(file)));
      }
    }

    assertThat(violations).as(String.join("\n", violations)).isEmpty();
  }

  @Test
  @DisplayName("every protocol package keeps at least one integration test without a tier tag")
  void everyProtocolKeepsAnUntaggedTest() throws IOException {
    final var violations = new ArrayList<String>();
    final var root = TEST_SOURCES.resolve(PROTOCOLS);

    try (final Stream<Path> protocols = Files.list(root)) {
      for (final var dir : protocols.filter(Files::isDirectory).sorted().toList()) {
        final var name = dir.getFileName().toString();

        if (NOT_PROTOCOLS.contains(name)) {
          continue;
        }

        try (final Stream<Path> files = Files.walk(dir)) {
          final var untagged =
              files
                  .filter(f -> f.getFileName().toString().endsWith("IT.java"))
                  .filter(f -> !f.getFileName().toString().startsWith("Abstract"))
                  .filter(f -> !TAG.matcher(stripComments(read(f))).find())
                  .count();

          if (untagged == 0) {
            violations.add(
                "protocols/"
                    + name
                    + " has no *IT without a @Tag: leave at least one (its publish-and-read flow)"
                    + " untagged so the protocol still runs when the extended tier is excluded");
          }
        }
      }
    }

    assertThat(violations).as(String.join("\n", violations)).isEmpty();
  }

  @Test
  @DisplayName("an unknown tag is reported with the file and the known tags")
  void reportsAnUnknownTag() {
    final var source =
        """
        @Tag("extnded")
        class SomeIT {}
        """;

    assertThat(unknownTags("SomeIT.java", source))
        .singleElement()
        .asString()
        .startsWith("SomeIT.java:")
        .contains("extnded")
        .contains("extended");
  }

  @Test
  @DisplayName("a known tag and a tag in a comment are accepted")
  void acceptsKnownAndCommentedTags() {
    final var source =
        """
        // @Tag("bogus")
        @Tag("extended")
        class SomeIT {}
        """;

    assertThat(unknownTags("SomeIT.java", source)).isEmpty();
  }

  private static List<String> unknownTags(final String file, final String source) {
    final var violations = new ArrayList<String>();
    final var matcher = TAG.matcher(stripComments(source));

    while (matcher.find()) {
      final var tag = matcher.group(1);

      if (!KNOWN_TAGS.contains(tag)) {
        violations.add(
            file
                + ": unknown @Tag(\""
                + tag
                + "\"). Known tier tags: "
                + KNOWN_TAGS
                + ". Fix the spelling, or add the tag to KNOWN_TAGS and \"Testing\" in AGENTS.md");
      }
    }

    return violations;
  }

  /** Blanks comments but keeps newlines, so positions stay meaningful. */
  private static String stripComments(final String source) {
    return COMMENT.matcher(source).replaceAll(" ");
  }

  private static String read(final Path file) {
    try {
      return Files.readString(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
