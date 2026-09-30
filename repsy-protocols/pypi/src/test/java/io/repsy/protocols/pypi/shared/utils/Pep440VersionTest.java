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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pep440Version (RPS-1688)")
class Pep440VersionTest {

  private static int compare(final String a, final String b) {
    return Pep440Version.parse(a).compareTo(Pep440Version.parse(b));
  }

  @Test
  @DisplayName("orders 10.0 after 9.0, unlike a plain string sort")
  void ordersByNumericPrecedenceNotString() {

    assertThat(compare("10.0", "9.0")).isPositive();
    assertThat(compare("9.0", "10.0")).isNegative();

    // A plain string sort gets this backwards: "10.0" < "9.0" lexicographically.
    assertThat("10.0".compareTo("9.0")).isNegative();
  }

  @Test
  @DisplayName("the official PEP 440 example chain sorts in the documented order")
  void officialOrderingExample() {
    // https://packaging.python.org/en/latest/specifications/version-specifiers (Appendix summary)
    final var chain =
        List.of(
            "1.0.dev456",
            "1.0a1",
            "1.0a2.dev456",
            "1.0a12.dev456",
            "1.0a12",
            "1.0b1.dev456",
            "1.0b2",
            "1.0rc1.dev456",
            "1.0rc1",
            "1.0",
            "1.0.post456.dev34",
            "1.0.post456",
            "1.1.dev1");

    final var shuffled = new ArrayList<>(chain);
    Collections.shuffle(shuffled, new Random(42));

    final var sorted =
        shuffled.stream().sorted(Comparator.comparing(Pep440Version::parse)).toList();

    assertThat(sorted).containsExactlyElementsOf(chain);
  }

  @Test
  @DisplayName("trailing-zero release segments do not change ordering (1.0 == 1.0.0)")
  void trailingZeroReleaseSegmentsAreInsignificant() {
    assertThat(compare("1.0", "1.0.0")).isZero();
    assertThat(compare("1.0.0", "1.0.1")).isNegative();
  }

  @Test
  @DisplayName("an epoch takes precedence over the release segment")
  void epochTakesPrecedence() {
    assertThat(compare("1!1.0", "2.0")).isPositive();
    assertThat(compare("1!1.0", "1!2.0")).isNegative();
  }

  @Test
  @DisplayName("equal versions compare as equal")
  void equalVersionsCompareEqual() {
    assertThat(compare("1.2.3", "1.2.3")).isZero();
  }

  @Test
  @DisplayName("a version with a local segment sorts after the same version without one (RPS-1662)")
  void localVersionSortsAfterThePublicVersion() {
    assertThat(compare("1.0+cu118", "1.0")).isPositive();
    assertThat(compare("1.0", "1.0+cu118")).isNegative();
    assertThat(compare("1.0+cu118", "1.0+cu118")).isZero();
  }

  @Test
  @DisplayName("the local segment never outranks a newer public version")
  void localSegmentDoesNotOutrankANewerVersion() {
    assertThat(compare("1.0+zzz.99", "1.0.post1")).isNegative();
    assertThat(compare("1.0+zzz.99", "1.0.1")).isNegative();
    assertThat(compare("1.0rc1+cu118", "1.0")).isNegative();
    assertThat(compare("1.0.dev1+local", "1.0a1")).isNegative();
  }

  @Test
  @DisplayName("local segments compare position by position: numbers numerically, text lexically")
  void localSegmentsCompareByPosition() {
    assertThat(compare("1.0+cu121", "1.0+cu118")).isPositive();
    assertThat(compare("1.0+abc", "1.0+abd")).isNegative();
    assertThat(compare("1.0+local.10", "1.0+local.9")).isPositive();
    assertThat(compare("1.0+local.1.1", "1.0+local.1")).isPositive();
    assertThat(compare("1.0+local.1", "1.0+local.1.0")).isNegative();
  }

  @Test
  @DisplayName("a numeric local segment outranks an alphanumeric one, as PEP 440 says")
  void numericLocalSegmentOutranksText() {
    assertThat(compare("1.0+1", "1.0+abc")).isPositive();
    assertThat(compare("1.0+abc", "1.0+1")).isNegative();
    assertThat(compare("1.0+local.1", "1.0+local.zzz")).isPositive();
  }

  @Test
  @DisplayName("local separators are interchangeable and the case does not matter")
  void localSeparatorsAndCaseAreNormalized() {
    assertThat(compare("1.0+Ubuntu-1_2", "1.0+ubuntu.1.2")).isZero();
  }

  @Test
  @DisplayName("a mixed list of public and local versions sorts in PEP 440 order")
  void mixedListSortsInPep440Order() {
    final var chain =
        List.of("1.0rc1", "1.0", "1.0+cu118", "1.0+cu121", "1.0+cu121.1", "1.0.post1", "1.1");

    final var shuffled = new ArrayList<>(chain);
    Collections.shuffle(shuffled, new Random(7));

    assertThat(shuffled.stream().sorted(Comparator.comparing(Pep440Version::parse)).toList())
        .containsExactlyElementsOf(chain);
  }
}
