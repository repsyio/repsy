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
package io.repsy.protocols.pypi.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ReleaseVersion (RPS-1155)")
class ReleaseVersionTest {

  @Test
  @DisplayName("normalizes a PEP 440 version")
  void normalizesVersion() {
    assertThat(ReleaseVersion.of("1.0.RC1").getVersion()).isEqualTo("1.0rc1");
  }

  @Test
  @DisplayName("answers 400 pypiVersionTooLong for a 2 KB dotted version, not a StackOverflowError")
  void rejectsPathologicalDottedVersion() {
    final var version = "1" + ".1".repeat(1000);

    assertThatThrownBy(() -> ReleaseVersion.of(version))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pypiVersionTooLong");
  }

  @Test
  @DisplayName("a dotted version at the 255 character limit parses without overflow")
  void parsesTheLongestAllowedDottedVersion() {
    final var version = "1" + ".1".repeat(127);

    assertThat(version).hasSize(PypiPublishLimits.MAX_VERSION_LENGTH);
    assertThat(ReleaseVersion.of(version).getVersion()).isEqualTo(version);
  }

  @Test
  @DisplayName("a non-normalized dotted version at the limit parses without overflow")
  void parsesTheLongestAllowedUnnormalizedVersion() {
    final var version = "1" + ".01".repeat(83) + "rc1";

    assertThat(version.length()).isLessThanOrEqualTo(PypiPublishLimits.MAX_VERSION_LENGTH);
    assertThat(ReleaseVersion.of(version).isPreRelease()).isTrue();
  }

  @Test
  @DisplayName("keeps a normalized local version segment, on the already-normalized fast path")
  void keepsNormalizedLocalVersion() {
    final var rv = ReleaseVersion.of("2.1.0+cu118");

    assertThat(rv.getVersion()).isEqualTo("2.1.0+cu118");
    assertThat(rv.isFinalRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0.post1+local.1.a2").getVersion())
        .isEqualTo("1.0.post1+local.1.a2");
  }

  @Test
  @DisplayName("normalizes a local version: lower case, dots for separators, no leading zeros")
  void normalizesLocalVersion() {
    assertThat(ReleaseVersion.of("1.0+Ubuntu-1_2").getVersion()).isEqualTo("1.0+ubuntu.1.2");
    assertThat(ReleaseVersion.of("1.0.RC1+CU118").getVersion()).isEqualTo("1.0rc1+cu118");
    assertThat(ReleaseVersion.of("1.0+007.0a").getVersion()).isEqualTo("1.0+7.0a");
    assertThat(ReleaseVersion.of("1.0+000").getVersion()).isEqualTo("1.0+0");
    assertThat(ReleaseVersion.of("v1.0-1+abc").getVersion()).isEqualTo("1.0.post1+abc");
  }

  @Test
  @DisplayName("two local builds of one release stay two versions, and differ from the release")
  void localBuildsStayDistinct() {
    assertThat(ReleaseVersion.of("1.0+cu118").getVersion())
        .isNotEqualTo(ReleaseVersion.of("1.0+cu121").getVersion())
        .isNotEqualTo(ReleaseVersion.of("1.0").getVersion());
  }

  @Test
  @DisplayName("a malformed local segment is a badVersionString")
  void refusesMalformedLocalVersion() {
    for (final var version : new String[] {"1.0+", "1.0+a..b", "1.0+a+b", "1.0+-a", "1.0+a b"}) {
      assertThatThrownBy(() -> ReleaseVersion.of(version))
          .as(version)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("badVersionString");
    }
  }

  @Test
  @DisplayName("a dotted local version at the 255 character limit parses without overflow")
  void parsesTheLongestAllowedLocalVersion() {
    final var version = "1+" + "a.".repeat(126) + "a";

    assertThat(version).hasSize(PypiPublishLimits.MAX_VERSION_LENGTH);
    assertThat(ReleaseVersion.of(version).getVersion()).isEqualTo(version);
    assertThat(ReleaseVersion.of(version.toUpperCase(java.util.Locale.ROOT)).getVersion())
        .isEqualTo(version);
  }

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "1!2.0, 1!2.0",
    "1.0ALPHA, 1.0a0",
    "1.0-alpha2, 1.0a2",
    "1.0Beta, 1.0b0",
    "1.0b3, 1.0b3",
    "1.0c1, 1.0rc1",
    "1.0preview3, 1.0rc3",
    "1.0pre, 1.0rc0",
    "1.0.post, 1.0.post0",
    "1.0-1, 1.0.post1",
    "1.0.post2, 1.0.post2",
    "1.0.dev, 1.0.dev0",
    "1.0.dev4, 1.0.dev4",
    "1.0rc1.post2.dev3, 1.0rc1.post2.dev3",
    "1!1.0a.post.dev, 1!1.0a0.post0.dev0",
    "1.0+Ubuntu_1-2.007, 1.0+ubuntu.1.2.7",
    "1.0+000, 1.0+0"
  })
  @DisplayName("normalizes epochs, pre, post, dev and local parts")
  void normalizesEveryPart(final String given, final String expected) {
    assertThat(ReleaseVersion.of(given).getVersion()).isEqualTo(expected);
  }

  @Test
  @DisplayName("tells the kind of release")
  void tellsTheKindOfRelease() {
    assertThat(ReleaseVersion.of("1.0").isFinalRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0+local").isFinalRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0rc1").isPreRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0rc1").isFinalRelease()).isFalse();
    assertThat(ReleaseVersion.of("1.0.post1").isPostRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0.post1").isFinalRelease()).isFalse();
    assertThat(ReleaseVersion.of("1.0.dev1").isDevelopmentRelease()).isTrue();
    assertThat(ReleaseVersion.of("1.0.dev1").isFinalRelease()).isFalse();
  }

  @ParameterizedTest(name = "\"{0}\" is refused")
  @CsvSource(
      value = {"not-a-version", "1.0.0.", "''", "1.0 rc"},
      quoteCharacter = '"')
  @DisplayName("refuses text that is not a PEP 440 version")
  void refusesGarbage(final String given) {
    assertThatThrownBy(() -> ReleaseVersion.of(given)).isInstanceOf(BadRequestException.class);
  }
}
